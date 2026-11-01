package com.ettlinger.wearrecorder

import android.content.*
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*

object BatteryMonitor {
    @Volatile var label = "Battery --"
        private set
    private var initialized = false
    @Volatile var appVisible = false
    @Synchronized fun init(context: Context) {
        if (initialized) return
        initialized = true
        val app = context.applicationContext
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        fun update(intent: Intent?) {
            if (intent == null) return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val percent = if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else -1
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            val next = if (percent < 0) "Battery --" else "Battery $percent%" + if (charging) " (charging)" else ""
            if (next != label) {
                label = next
                AppLog.i("Battery", "Battery changed")
                RecorderStatus.refreshTile(app)
            }
        }
        update(app.registerReceiver(null, filter))
        ContextCompat.registerReceiver(app, object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = update(i)
        }, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        CoroutineScope(Dispatchers.Default + SupervisorJob()).launch {
            while (isActive) {
                delay(5 * 60_000L)
                if (appVisible || app.getSharedPreferences(RecordingService.PREF_FILE, Context.MODE_PRIVATE)
                        .getBoolean(RecordingService.PREF_SHOULD_RECORD, false)) {
                    AppLog.i("Battery", "Periodic battery sample")
                    AppLog.flush()
                    if (AppSettings.logging(app)) UploadWorker.kick(app)
                }
            }
        }
    }
}
