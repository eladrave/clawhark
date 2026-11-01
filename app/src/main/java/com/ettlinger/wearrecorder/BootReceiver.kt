package com.ettlinger.wearrecorder

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build

/** Modern Android requires a visible user action to resume microphone capture after reboot. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        UploadWorker.schedule(context)
        UploadWorker.kick(context)
        if (!context.getSharedPreferences(RecordingService.PREF_FILE, Context.MODE_PRIVATE)
                .getBoolean(RecordingService.PREF_SHOULD_RECORD, false)) return
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(RecordingService.CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        manager.notify(RecordingService.NOTIFICATION_ID, Notification.Builder(context, RecordingService.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("Resume Rave AI recording")
            .setContentText("Watch restarted. Tap to resume; saved audio is retained.").setContentIntent(open).setAutoCancel(true).build())
    }
}
