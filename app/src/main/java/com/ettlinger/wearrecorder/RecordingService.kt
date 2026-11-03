package com.ettlinger.wearrecorder

import android.app.*
import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.*
import kotlinx.coroutines.*
import com.konovalov.vad.webrtc.VadWebRTC

/** Capture owns local durability. Network work never runs on this loop. */
class RecordingService : Service() {
    companion object {
        const val PREF_FILE = "clawhark"
        const val PREF_SHOULD_RECORD = "should_record"
        const val PREF_ERROR = "recording_error"
        const val ACTION_STOP = "STOP"
        const val CHANNEL_ID = "clawhark_channel"
        const val NOTIFICATION_ID = 1
        const val SAMPLE_RATE = RecordingStore.SAMPLE_RATE
        const val CHUNK_DURATION_MS = 60_000L
        private const val SEGMENT_SAMPLES = SAMPLE_RATE * CHUNK_DURATION_MS / 1000
        private const val BUFFER_SAMPLES = 4096
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null
    private var watchdog: Job? = null
    @Volatile private var requested = false
    @Volatile private var capturing = false
    @Volatile private var speechOnly = false
    @Volatile private var speechActive = false
    @Volatile private var vadFallback = false
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var lastAudioAt = 0L
    @Volatile var recordingStartTime = 0L
        private set
    @Volatile var totalChunks = 0
        private set
    private lateinit var store: RecordingStore
    private val prefs get() = getSharedPreferences(PREF_FILE, MODE_PRIVATE)

    inner class LocalBinder : Binder() { fun getService() = this@RecordingService }
    override fun onBind(intent: Intent): IBinder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        store = RecordingFiles.open(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW)
        )
        UploadWorker.schedule(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            AppLog.i("Recorder", "Stop requested")
            prefs.edit().putBoolean(PREF_SHOULD_RECORD, false).commit()
            requested = false
            capturing = false
            // Unblock a blocking read. The capture finally block seals before scheduling upload.
            try { recorder?.stop() } catch (_: Exception) { }
            if (job?.isActive != true) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            return START_NOT_STICKY
        }
        if (!prefs.getBoolean(PREF_SHOULD_RECORD, false)) { stopSelf(); return START_NOT_STICKY }
        if (job?.isActive == true) return START_STICKY
        try {
            startForeground(NOTIFICATION_ID, notification("Starting recorder"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            fail("Open Rave AI to resume recording", e)
            stopSelf()
            return START_NOT_STICKY
        }
        requested = true
        speechOnly = AppSettings.speechOnly(this)
        speechActive = false
        vadFallback = false
        AppLog.i("Recorder", "Start requested; mode=${modeLabel()}")
        RecorderStatus.publish(this, "STARTING", modeLabel())
        prefs.edit().remove(PREF_ERROR).commit()
        job = scope.launch { capture() }
        watchdog?.cancel()
        watchdog = scope.launch {
            while (requested) {
                delay(5000)
                if (capturing && SystemClock.elapsedRealtime() - lastAudioAt > 10_000) {
                    fail("Microphone stopped supplying audio. Open Rave AI to resume.", IllegalStateException("Capture stalled"))
                    try { recorder?.stop() } catch (_: Exception) { }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        requested = false
        capturing = false
        try { recorder?.stop() } catch (_: Exception) { }
        scope.cancel() // NonCancellable finalization retains the last segment.
        super.onDestroy()
    }

    fun isFinalizing() = !requested && job?.isActive == true
    fun isBusy() = requested
    fun isCurrentlyRecording() = capturing && (!speechOnly || speechActive)
    fun isListening() = capturing && speechOnly && !speechActive
    fun modeLabel() = if (vadFallback) "VAD fallback: continuous" else if (speechOnly) "Speech only" else "Continuous"
    fun isRecovering() = requested && !capturing
    fun getRecordings() = store.pending()
    fun getStorageUsed() = store.bytesUsed()

    private suspend fun capture() {
        var lock: RecordingStore.Lock? = null
        var wakeLock: PowerManager.WakeLock? = null
        var segment: RecordingStore.Segment? = null
        var encodedSegment: AacSegment? = null
        var segmentSamples = 0L
        val power = AppSettings.powerOptions(this)
        var vad: VadWebRTC? = null
        var gate: SpeechGate? = null
        fun seal() {
            // Detach before finalization: finish/recovery/finally may converge on this path.
            val wav = segment; segment = null
            val aac = encodedSegment; encodedSegment = null
            segmentSamples = 0
            val began = SystemClock.elapsedRealtime()
            val sealed = try {
                if (aac != null) aac.seal() else wav?.seal()
            } finally {
                try { wav?.close() } finally { aac?.close() }
            }
            val elapsed = SystemClock.elapsedRealtime() - began
            if (aac != null) AppLog.i("Profile", "AAC segment sealed; elapsed_ms=$elapsed")
            if (elapsed > 500) AppLog.w("Service", "Segment finalization delayed capture; elapsed_ms=$elapsed; continuity requires review")
            if (sealed != null) { totalChunks++; UploadWorker.kick(this@RecordingService) }
        }
        try {
            power.checkCapture(speechOnly)
            lock = store.tryLock("capture") ?: throw IllegalStateException("Recorder already active")
            store.recover()
            store.checkSpace(SAMPLE_RATE * 2L)
            if (power.captureWakeLock) wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ClawHark:Capture").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            recordingStartTime = System.currentTimeMillis()
            val samples = ShortArray(BUFFER_SAMPLES)
            val pcm = ByteArray(BUFFER_SAMPLES * 2)
            var checkpointAt = SystemClock.elapsedRealtime()
            var lastReadAt = checkpointAt
            var lastStatusAt = checkpointAt
            val captureAt = checkpointAt
            val cpuAt = Process.getElapsedCpuTime()
            var inputSamples = 0L
            var storedSamples = 0L
            val space = CaptureSpaceBudget { store.checkSpace(it) }
            var recoveryAttempt = 0
            var deliveryGaps = 0
            var maxDeliveryMs = 0L
            // Qualify the initial encoder before opening the microphone. Unsupported codecs fail visibly.
            if (power.audioFormat == PowerOptions.AAC) encodedSegment = AacSegment(store, recordingStartTime)
            fun append(input: ShortArray, length: Int, at: Long) {
                val now = SystemClock.elapsedRealtime()
                space.beforeAppend(now, length * 2)
                if (power.audioFormat == PowerOptions.AAC) {
                    if (encodedSegment == null) { encodedSegment = AacSegment(store, at); checkpointAt = SystemClock.elapsedRealtime() }
                    checkNotNull(encodedSegment).append(input, length)
                } else {
                    if (segment == null) { segment = store.begin(at); checkpointAt = SystemClock.elapsedRealtime() }
                    for (i in 0 until length) {
                        pcm[2 * i] = input[i].toByte()
                        pcm[2 * i + 1] = (input[i].toInt() shr 8).toByte()
                    }
                    checkNotNull(segment).append(pcm, length * 2)
                }
                storedSamples += length
                segmentSamples += length
                if (now - checkpointAt >= 1000) {
                    if (power.audioFormat == PowerOptions.AAC) encodedSegment?.checkpoint() else segment?.checkpoint()
                    checkpointAt = now
                }
                if (segmentSamples >= SEGMENT_SAMPLES) seal()
            }
            if (speechOnly) {
                try {
                    vad = VadWebRTC(AppSettings.sensitivity(this@RecordingService))
                    gate = SpeechGate({ checkNotNull(vad).isSpeech(it) }, ::append, ::seal, { active, fallback ->
                        speechActive = active; vadFallback = fallback
                        if (fallback) AppLog.w("VAD", "Detector unavailable; retaining all audio")
                        else AppLog.i("VAD", if (active) "Speech storage started" else "Waiting for speech")
                        notifyState(if (fallback) "VAD unavailable. Saving all audio." else if (active) "Saving speech on watch" else "Listening for speech")
                    }, reuseBuffers = power.reuseSpeechBuffers)
                } catch (_: Exception) { vadFallback = true; speechOnly = false; AppLog.w("VAD", "Cannot initialize; saving all audio") }
                  catch (_: LinkageError) { vadFallback = true; speechOnly = false; AppLog.w("VAD", "Native detector unavailable; saving all audio") }
            }
            AppLog.i("Profile", "Capture start; mode=${modeLabel()}; ${AppSettings.profile(this@RecordingService)}; effective_power=${power.diagnostic}; pcm_hz=$SAMPLE_RATE; read_samples=$BUFFER_SAMPLES")

            while (requested && currentCoroutineContext().isActive) {
                if (recorder == null) {
                    try {
                        recorder = openMicrophone()
                        lastReadAt = SystemClock.elapsedRealtime()
                        notifyState("Waiting for microphone audio")
                    } catch (e: SecurityException) {
                        throw e
                    } catch (e: Exception) {
                        capturing = false
                        notifyState("Microphone unavailable. Retrying. Saved audio retained.")
                        delay(minOf(60_000L, 1000L shl minOf(recoveryAttempt++, 6)))
                        continue
                    }
                }
                val audio = recorder ?: continue
                val read = audio.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                if (!requested) break
                val now = SystemClock.elapsedRealtime()
                if (audio.activeRecordingConfiguration?.isClientSilenced == true) {
                    throw IllegalStateException("Microphone silenced by another app. Open Rave AI to resume.")
                }
                if (read < 0 || (read == 0 && now - lastReadAt > 10_000)) {
                    capturing = false
                    gate?.finish()
                    speechActive = false
                    seal()
                    try { audio.stop() } catch (_: Exception) { }
                    audio.release()
                    recorder = null
                    notifyState("Microphone interrupted. Retrying. Saved audio retained.")
                    delay(1000)
                    continue
                }
                if (read == 0) { delay(20); continue }
                val deliveryMs = now - lastReadAt
                maxDeliveryMs = maxOf(maxDeliveryMs, deliveryMs)
                if (deliveryMs > 2000) {
                    deliveryGaps++
                    AppLog.w("Service", "Late microphone delivery; delay_ms=$deliveryMs; samples=$read; possible_gap=true; ${power.diagnostic}")
                }
                lastReadAt = now
                lastAudioAt = now
                recoveryAttempt = 0
                val resumed = !capturing
                capturing = true
                if (resumed) notifyState(if (speechOnly) "Listening for speech" else "Recording. Audio saved on watch.")
                val at = System.currentTimeMillis() - read * 1000L / SAMPLE_RATE
                inputSamples += read
                if (gate != null) gate.accept(samples, read, at) else append(samples, read, at)
                if (now - lastStatusAt >= 60_000) {
                    notifyState(if (isListening()) "Listening for speech. ${store.pending().size} files pending"
                        else "Recording. ${store.pending().size} files awaiting upload")
                    lastStatusAt = now
                    AppLog.i("Service", "Capture active; mode=${modeLabel()}; elapsed_ms=${now - captureAt}; input_ms=${inputSamples * 1000 / SAMPLE_RATE}; stored_ms=${storedSamples * 1000 / SAMPLE_RATE}; process_cpu_ms=${Process.getElapsedCpuTime() - cpuAt}; delivery_gaps=$deliveryGaps; max_delivery_ms=$maxDeliveryMs; effective_power=${power.diagnostic}; pending=${store.pending().size}; bytes=${store.bytesUsed()}")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(e.message ?: "Recording stopped. Saved audio retained.", e)
        } finally {
            withContext(NonCancellable) {
                watchdog?.cancel()
                watchdog = null
                capturing = false
                requested = false
                try { recorder?.stop() } catch (_: Exception) { }
                try { recorder?.release() } catch (_: Exception) { }
                recorder = null
                try { gate?.finish() } catch (e: Exception) { AppLog.e("VAD", "Final speech buffer could not be written", e) }
                try { vad?.close() } catch (_: Exception) { }
                try { seal() }
                catch (e: Exception) { fail("Recording stopped. Partial audio retained for recovery.", e) }
                try { lock?.close() } catch (_: Exception) { }
                wakeLock?.let { if (it.isHeld) it.release() }
                UploadWorker.kick(this@RecordingService)
                stopForeground(STOP_FOREGROUND_REMOVE)
                if (prefs.contains(PREF_ERROR)) notifyState(prefs.getString(PREF_ERROR, "Recorder stopped")!!)
                RecorderStatus.publish(this@RecordingService, if (prefs.contains(PREF_ERROR)) "NEEDS ATTENTION" else "STOPPED", modeLabel())
                AppLog.i("Recorder", "Capture stopped; pending=${store.pending().size}")
                AppLog.flush()
                stopSelf()
            }
        }
    }

    @Suppress("MissingPermission")
    private fun openMicrophone(): AudioRecord {
        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        require(minimum > 0) { "Unsupported microphone format" }
        val audio = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, BUFFER_SAMPLES * 2) * 2)
        try {
            check(audio.state == AudioRecord.STATE_INITIALIZED) { "Microphone initialization failed" }
            audio.startRecording()
            check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone did not start" }
            return audio
        } catch (e: Exception) { audio.release(); throw e }
    }

    private fun fail(message: String, error: Exception) {
        capturing = false
        requested = false
        prefs.edit().putString(PREF_ERROR, message).putBoolean(PREF_SHOULD_RECORD, false).commit()
        AppLog.e("Service", message, error)
    }

    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID).setContentTitle(getString(R.string.app_name))
            .setContentText(message).setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open).setOngoing(requested).build()
    }
    private fun notifyState(message: String) {
        val state = when {
            prefs.contains(PREF_ERROR) -> "NEEDS ATTENTION"
            !requested -> "STOPPED"
            isListening() -> "LISTENING"
            capturing -> "RECORDING"
            else -> "RECOVERING MIC"
        }
        RecorderStatus.publish(this, state, modeLabel())
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message))
    }
}
