package com.ettlinger.wearrecorder

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.*
import kotlinx.coroutines.*
import java.io.File

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
        private const val SEGMENT_BYTES = SAMPLE_RATE * 2L * CHUNK_DURATION_MS / 1000
        private const val BUFFER_SAMPLES = 4096
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null
    @Volatile private var requested = false
    @Volatile private var capturing = false
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
            fail("Open ClawHark to resume recording", e)
            stopSelf()
            return START_NOT_STICKY
        }
        requested = true
        prefs.edit().remove(PREF_ERROR).commit()
        job = scope.launch { capture() }
        scope.launch {
            while (requested) {
                delay(5000)
                if (capturing && SystemClock.elapsedRealtime() - lastAudioAt > 10_000) {
                    fail("Microphone stopped supplying audio. Open ClawHark to resume.", IllegalStateException("Capture stalled"))
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

    fun isBusy() = requested
    fun isCurrentlyRecording() = capturing
    fun isRecovering() = requested && !capturing
    fun getRecordings() = store.pending()
    fun getStorageUsed() = store.bytesUsed()

    private suspend fun capture() {
        var lock: RecordingStore.Lock? = null
        var wakeLock: PowerManager.WakeLock? = null
        var segment: RecordingStore.Segment? = null
        try {
            lock = store.tryLock("capture") ?: throw IllegalStateException("Recorder already active")
            store.recover()
            store.checkSpace(SAMPLE_RATE * 2L)
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
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
            var recoveryAttempt = 0

            while (requested && currentCoroutineContext().isActive) {
                if (recorder == null) {
                    try {
                        recorder = openMicrophone()
                        lastReadAt = SystemClock.elapsedRealtime()
                        notifyState("Waiting for microphone audio")
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
                    throw IllegalStateException("Microphone silenced by another app. Open ClawHark to resume.")
                }
                if (read < 0 || (read == 0 && now - lastReadAt > 10_000)) {
                    capturing = false
                    segment?.seal()?.also { totalChunks++ }
                    segment = null
                    UploadWorker.kick(this@RecordingService)
                    try { audio.stop() } catch (_: Exception) { }
                    audio.release()
                    recorder = null
                    notifyState("Microphone interrupted. Retrying. Saved audio retained.")
                    delay(1000)
                    continue
                }
                if (read == 0) { delay(20); continue }
                lastReadAt = now
                lastAudioAt = now
                recoveryAttempt = 0
                if (!capturing) notifyState("Recording. Audio saved on watch.")
                capturing = true
                if (segment == null) {
                    segment = store.begin(System.currentTimeMillis() - read * 1000L / SAMPLE_RATE)
                    checkpointAt = now
                }
                for (i in 0 until read) {
                    pcm[2 * i] = samples[i].toByte()
                    pcm[2 * i + 1] = (samples[i].toInt() shr 8).toByte()
                }
                val currentSegment = checkNotNull(segment)
                currentSegment.append(pcm, read * 2)
                if (now - checkpointAt >= 1000) {
                    currentSegment.checkpoint()
                    store.checkSpace(SAMPLE_RATE * 2L)
                    checkpointAt = now
                }
                if (currentSegment.payloadBytes() >= SEGMENT_BYTES) {
                    currentSegment.seal()
                    segment = null
                    totalChunks++
                    UploadWorker.kick(this@RecordingService)
                }
                if (now - lastStatusAt >= 30_000) {
                    notifyState("Recording. ${store.pending().size} files awaiting upload")
                    lastStatusAt = now
                    AppLog.i("Service", "Capture active; pending=${store.pending().size}; bytes=${store.bytesUsed()}")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(e.message ?: "Recording stopped. Saved audio retained.", e)
        } finally {
            withContext(NonCancellable) {
                capturing = false
                requested = false
                try { recorder?.stop() } catch (_: Exception) { }
                try { recorder?.release() } catch (_: Exception) { }
                recorder = null
                try { segment?.seal()?.also { totalChunks++ } }
                catch (e: Exception) {
                    try { segment?.close() } catch (_: Exception) { }
                    fail("Recording stopped. Partial audio retained for recovery.", e)
                }
                try { lock?.close() } catch (_: Exception) { }
                wakeLock?.let { if (it.isHeld) it.release() }
                UploadWorker.kick(this@RecordingService)
                stopForeground(STOP_FOREGROUND_REMOVE)
                if (prefs.contains(PREF_ERROR)) notifyState(prefs.getString(PREF_ERROR, "Recorder stopped")!!)
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
        return Notification.Builder(this, CHANNEL_ID).setContentTitle("ClawHark")
            .setContentText(message).setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open).setOngoing(requested).build()
    }
    private fun notifyState(message: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message))
    }
}
