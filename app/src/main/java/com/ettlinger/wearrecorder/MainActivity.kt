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
import java.io.File

/** Recording is available offline, independently of cloud authentication. */
class MainActivity : Activity() {
    private var service: RecordingService? = null
    private var bound = false
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var authJob: Job? = null
    private var visible = false
    private var startAfterPermission = false
    private lateinit var toggleBtn: Button
    private lateinit var statusText: TextView
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
        infoText = findViewById(R.id.infoText)
        recordGroup = findViewById(R.id.recordGroup)
        authGroup = findViewById(R.id.authGroup)
        authStatus = findViewById(R.id.authStatus)
        authCode = findViewById(R.id.authCode)
        authBtn = findViewById(R.id.authBtn)
        linkBtn = findViewById(R.id.linkBtn)
        toggleBtn.setOnClickListener {
            if (service?.isBusy() == true) {
                prefs.edit().putBoolean(RecordingService.PREF_SHOULD_RECORD, false).commit()
                startService(Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_STOP))
            } else startCapture()
        }
        linkBtn.setOnClickListener {
            recordGroup.visibility = View.GONE
            authGroup.visibility = View.VISIBLE
            authStatus.text = "Connect your Google Drive"
        }
        authBtn.setOnClickListener { linkDrive() }
        findViewById<Button>(R.id.offlineBtn).setOnClickListener {
            authJob?.cancel()
            authBtn.isEnabled = true
            showRecorder()
        }
        UploadWorker.schedule(this)
        UploadWorker.kick(this)
        showRecorder()
        scope.launch {
            while (isActive) { if (visible) updateUI(); delay(1000) }
        }
    }

    override fun onStart() {
        super.onStart()
        visible = true
        bound = bindService(Intent(this, RecordingService::class.java), connection, BIND_AUTO_CREATE)
        // Only resume previously requested capture from a visible Activity with microphone permission.
        if (prefs.getBoolean(RecordingService.PREF_SHOULD_RECORD, false) && hasMic()) startCapture()
    }
    override fun onStop() {
        visible = false
        authJob?.cancel() // A delayed authorization response must never start a microphone.
        authBtn.isEnabled = true
        if (bound) { unbindService(connection); bound = false; service = null }
        super.onStop()
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private fun hasMic() = ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startCapture() {
        if (!visible) return
        val needed = mutableListOf<String>()
        if (!hasMic()) needed.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 && ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        if (needed.isNotEmpty()) {
            startAfterPermission = true
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1)
            return
        }
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
        val active = service?.isCurrentlyRecording() == true
        val busy = service?.isBusy() == true
        toggleBtn.text = if (busy) "STOP" else "START"
        toggleBtn.contentDescription = if (busy) "Stop recording" else "Start recording"
        toggleBtn.setBackgroundResource(if (active) R.drawable.circle_button_recording else R.drawable.circle_button)
        statusText.text = when {
            active -> "RECORDING"
            busy -> "RECOVERING MIC"
            prefs.contains(RecordingService.PREF_ERROR) -> "NEEDS ATTENTION"
            else -> "STOPPED"
        }
        val store = RecordingFiles.open(this)
        val megabytes = String.format("%.1f", store.bytesUsed() / 1024.0 / 1024.0)
        val detail = prefs.getString(RecordingService.PREF_ERROR, null)
            ?: prefs.getString(UploadWorker.PREF_STATUS, "Audio saved on watch")
        infoText.text = "${store.pending().size} pending | $megabytes MB\n$detail"
        linkBtn.text = if (AuthManager.isAuthenticated()) "RELINK DRIVE" else "LINK DRIVE"
    }

    private fun linkDrive() {
        authJob?.cancel()
        authBtn.isEnabled = false
        authJob = scope.launch {
            try {
                val response = AuthManager.requestDeviceCode()
                if (response == null) { authStatus.text = "Cloud setup unavailable.\nRecording works offline."; return@launch }
                authCode.text = response.userCode
                authCode.visibility = View.VISIBLE
                authStatus.text = "Enter code at\ngoogle.com/device"
                var interval = maxOf(response.interval, 5) * 1000L
                val deadline = SystemClock.elapsedRealtime() + response.expiresIn * 1000L
                while (isActive && SystemClock.elapsedRealtime() < deadline) {
                    delay(interval)
                    when (val result = AuthManager.pollForAuthorization(response.deviceCode)) {
                        is AuthManager.PollResult.Success -> { UploadWorker.kick(this@MainActivity); showRecorder(); return@launch }
                        is AuthManager.PollResult.Pending -> Unit
                        is AuthManager.PollResult.SlowDown -> interval += 5000
                        else -> { authStatus.text = "Authorization not completed. Retry or record offline."; return@launch }
                    }
                }
                authStatus.text = "Code expired. Retry or record offline."
            } finally { authBtn.isEnabled = true }
        }
    }
}
