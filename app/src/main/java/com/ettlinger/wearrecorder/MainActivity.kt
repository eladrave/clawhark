package com.ettlinger.wearrecorder

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.view.View
import android.widget.*
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.*

/** Recording is available offline, independently of cloud authentication. */
class MainActivity : Activity() {
    private var service: RecordingService? = null
    private var bound = false
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var authJob: Job? = null
    private var uiJob: Job? = null
    private var visible = false
    private var startAfterPermission = false
    private lateinit var toggleBtn: Button
    private lateinit var statusText: TextView
    private lateinit var attentionText: TextView
    private lateinit var batteryText: TextView
    private lateinit var testText: TextView
    private lateinit var syncSummaryText: TextView
    private lateinit var infoText: TextView
    private lateinit var recordGroup: LinearLayout
    private lateinit var authGroup: LinearLayout
    private lateinit var authStatus: TextView
    private lateinit var authCode: TextView
    private lateinit var authBtn: Button
    private lateinit var linkBtn: Button
    private val prefs get() = getSharedPreferences(RecordingService.PREF_FILE, MODE_PRIVATE)
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as RecordingService.LocalBinder).getService()
            updateUI()
        }
        override fun onServiceDisconnected(name: ComponentName) { service = null; updateUI() }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_main)
        AppLog.init(this)
        AuthManager.init(this)
        toggleBtn = findViewById(R.id.toggleBtn)
        statusText = findViewById(R.id.statusText)
        attentionText = findViewById(R.id.attentionText)
        infoText = findViewById(R.id.infoText)
        batteryText = findViewById(R.id.batteryText)
        testText = findViewById(R.id.testText)
        syncSummaryText = findViewById(R.id.syncSummaryText)
        findViewById<Button>(R.id.settingsBtn).setOnClickListener {
            AppLog.i("UI", "Settings opened")
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        recordGroup = findViewById(R.id.recordGroup)
        authGroup = findViewById(R.id.authGroup)
        authStatus = findViewById(R.id.authStatus)
        authCode = findViewById(R.id.authCode)
        authBtn = findViewById(R.id.authBtn)
        linkBtn = findViewById(R.id.linkBtn)
        toggleBtn.setOnClickListener {
            if (service?.isFinalizing() == true) return@setOnClickListener
            if (service?.isBusy() == true || prefs.getBoolean(RecordingService.PREF_SHOULD_RECORD, false)) {
                prefs.edit().putBoolean(RecordingService.PREF_SHOULD_RECORD, false)
                    .remove(RecordingService.PREF_RECOVERY_REASON).commit()
                startService(Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_STOP))
                updateUI()
            } else startCapture()
        }
        linkBtn.setOnClickListener {
            recordGroup.visibility = View.GONE
            authGroup.visibility = View.VISIBLE
            authStatus.text = "Connect your Google Drive"
            if (AuthManager.pendingDeviceLink() != null) linkDrive(false)
        }
        authBtn.setOnClickListener { linkDrive() }
        findViewById<Button>(R.id.offlineBtn).setOnClickListener {
            // Cloud linking can finish while the visible user uses offline recording controls.
            showRecorder()
        }
        UploadWorker.schedule(this)
        UploadWorker.kick(this)
        showRecorder()
        if (AuthManager.pendingDeviceLink() != null && !prefs.getBoolean(RecordingService.PREF_SHOULD_RECORD, false)) {
            authGroup.visibility = View.VISIBLE
            recordGroup.visibility = View.GONE
        }
    }

    override fun onStart() {
        super.onStart()
        visible = true
        uiJob?.cancel()
        uiJob = scope.launch {
            while (isActive) {
                updateUI()
                if (authJob?.isActive != true && AuthManager.pendingDeviceLink() != null) linkDrive(false)
                delay(5000)
            }
        }
        AppLog.i("UI", "Recorder screen opened")
        bound = bindService(Intent(this, RecordingService::class.java), connection, BIND_AUTO_CREATE)
        if (AuthManager.pendingDeviceLink() != null) linkDrive(false)
        else if (AuthManager.isAuthenticated()) { showRecorder(); UploadWorker.kick(this) }
        // Only resume previously requested capture from a visible Activity with microphone permission.
        if (prefs.getBoolean(RecordingService.PREF_SHOULD_RECORD, false) && hasMic()) startCapture()
    }
    override fun onStop() {
        visible = false
        uiJob?.cancel(); uiJob = null
        AppLog.i("UI", "Recorder screen hidden")
        AppLog.checkpoint()
        // Pause network polling, but retain the encrypted grant for onStart/process recreation.
        authJob?.cancel()
        if (bound) { unbindService(connection); bound = false; service = null }
        super.onStop()
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private fun hasMic() = ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startCapture() {
        if (!visible || service?.isFinalizing() == true) return
        val needed = mutableListOf<String>()
        if (!hasMic()) needed.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 && ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        if (needed.isNotEmpty()) {
            startAfterPermission = true
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1)
            return
        }
        // Reopening the Activity may re-send START to an already recovering service.
        // Only the service can clear that reason after new capture or actual recovery.
        prefs.edit().putBoolean(RecordingService.PREF_SHOULD_RECORD, true).commit()
        try { startForegroundService(Intent(this, RecordingService::class.java)) }
        catch (e: Exception) {
            prefs.edit().putBoolean(RecordingService.PREF_SHOULD_RECORD, false)
                .putString(RecordingService.PREF_ERROR, "Cannot start recorder. Open the app and retry.").commit()
            AppLog.e("UI", "Start rejected", e)
        }
        updateUI()
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code != 1 || !startAfterPermission) return
        startAfterPermission = false
        // Notification refusal does not revoke microphone permission or justify a request loop.
        if (visible && hasMic()) {
            prefs.edit().putBoolean(RecordingService.PREF_SHOULD_RECORD, true).commit()
            try { startForegroundService(Intent(this, RecordingService::class.java)) }
            catch (e: Exception) { Toast.makeText(this, "Open recorder and retry", Toast.LENGTH_LONG).show() }
        } else Toast.makeText(this, "Microphone permission required", Toast.LENGTH_LONG).show()
    }

    private fun showRecorder() { authGroup.visibility = View.GONE; recordGroup.visibility = View.VISIBLE; updateUI() }
    private fun updateUI() {
        batteryText.text = BatteryMonitor.label
        testText.text = AppSettings.testDisplay(this)
        testText.setTextColor(if (AppSettings.testId(this) != "none") 0xff71daca.toInt() else 0xffbbbbbb.toInt())
        syncSummaryText.text = prefs.getString(SyncMeasurements.PREF_STATUS, "Final sync summary: none yet")
        val liveState = when {
            service?.isFinalizing() == true -> "SAVING AUDIO"
            service?.isCurrentlyRecording() == true -> "RECORDING"
            service?.isListening() == true -> "LISTENING"
            service?.isBusy() == true -> "RECOVERING MIC"
            service != null -> "STOPPED"
            else -> null
        }
        val display = RecorderAttentionUi.resolve(
            prefs.getBoolean(RecordingService.PREF_SHOULD_RECORD, false), RecorderStatus.state(this), liveState,
            prefs.getString(RecordingService.PREF_ERROR, null), prefs.getString(RecordingService.PREF_RECOVERY_REASON, null))
        toggleBtn.isEnabled = service?.isFinalizing() != true
        toggleBtn.text = if (display.stopAction) "STOP" else "START"
        toggleBtn.contentDescription = if (display.stopAction) "Stop recording" else "Start recording"
        toggleBtn.setBackgroundResource(if (display.recordingActive) R.drawable.circle_button_recording else R.drawable.circle_button)
        statusText.text = display.label
        statusText.setTextColor(RecorderAttentionUi.statusColor(display.label))
        attentionText.text = display.reason.orEmpty()
        attentionText.visibility = if (display.reason == null) View.GONE else View.VISIBLE
        attentionText.setTextColor(RecorderAttentionUi.statusColor(display.label))
        val store = RecordingFiles.open(this)
        val megabytes = String.format("%.1f", store.bytesUsed() / 1024.0 / 1024.0)
        val detail = prefs.getString(UploadWorker.PREF_STATUS, "Audio saved on watch")
        val logPending = RecordingFiles.logs(this).pendingLogs().size
        val lastLog = prefs.getLong(UploadWorker.PREF_LOG_SUCCESS_AT, 0)
        val lastLogLabel = if (lastLog > 0) java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.ROOT).format(java.util.Date(lastLog)) else "none yet"
        infoText.text = "${store.pending().size} audio pending | $megabytes MB\n${RecorderStatus.mode(this)}\nProcessing: ${AppSettings.powerOptions(this).label}\nFormat: ${AppSettings.powerOptions(this).formatLabel}\nPreset: ${AppSettings.presetLabel(this)}\n${AppSettings.uploadLabel(this)}\n$detail\nAudio: ${prefs.getString(UploadWorker.PREF_AUDIO_STATUS, "Not synced yet")}\nLogs: $logPending sealed batches pending\n${prefs.getString(UploadWorker.PREF_LOG_STATUS, "No log sync yet")}\nLast log sync: $lastLogLabel"
        linkBtn.text = if (AuthManager.isAuthenticated()) "RELINK DRIVE" else "LINK DRIVE"
    }

    private fun linkDrive(requestCode: Boolean = true) {
        if (!visible || authJob?.isActive == true) return
        authBtn.isEnabled = false
        authBtn.text = "WAITING"
        authJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                if (requestCode && AuthManager.requestDeviceCode() == null) {
                    authCode.visibility = View.GONE
                    authStatus.text = "Cloud setup unavailable.\nRecording works offline."
                    return@launch
                }
                val linked = AuthManager.deviceLinkPoller().resume { session, message ->
                    if (visible && authGroup.visibility == View.VISIBLE) {
                        authCode.text = session.userCode
                        authCode.visibility = if (AuthManager.pendingDeviceLink() != null) View.VISIBLE else View.GONE
                        authStatus.text = message
                    }
                }
                if (linked || AuthManager.isAuthenticated()) {
                    UploadWorker.kick(this@MainActivity)
                    if (visible) {
                        showRecorder()
                        Toast.makeText(this@MainActivity, "Google Drive linked", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e("UI", "Drive linking interrupted", e)
                if (visible) authStatus.text = "Linking interrupted. Reopen the app to resume."
            } finally {
                // A stopped Activity's old request cannot reset a newer request's controls.
                if (currentCoroutineContext()[Job] === authJob) {
                    authJob = null
                    authBtn.isEnabled = true
                    authBtn.text = if (AuthManager.pendingDeviceLink() != null) "CHECK LINK" else "LINK"
                }
            }
        }
        authJob?.start()
    }
}
