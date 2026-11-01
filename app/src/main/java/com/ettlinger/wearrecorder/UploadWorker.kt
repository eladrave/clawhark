package com.ettlinger.wearrecorder

import android.content.Context
import android.os.SystemClock
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        const val WORK_NAME = "upload_recordings"
        const val PREF_STATUS = "upload_status"
        private const val IMMEDIATE = "upload_immediate"
        private fun constraints(c: Context) = Constraints.Builder().setRequiredNetworkType(
            if (AppSettings.unmetered(c)) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(AppSettings.uploadTiming(c) == 2).build()

        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            wm.cancelUniqueWork("upload_fallback") // migrate the legacy second upload schedule
            wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<UploadWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(constraints(context)).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        }
        fun reconfigure(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(IMMEDIATE)
            schedule(context)
            if (AppSettings.uploadTiming(context) != 1) kick(context, true)
        }
        fun kick(context: Context, replace: Boolean = false) {
            if (!replace && AppSettings.uploadTiming(context) == 1) return
            // Coalesce offline wakes instead of building thousands of jobs. The periodic pass covers snapshot races.
            WorkManager.getInstance(context).enqueueUniqueWork(IMMEDIATE, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<UploadWorker>().setConstraints(constraints(context))
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        AppLog.init(applicationContext)
        AuthManager.init(applicationContext)
        val prefs = applicationContext.getSharedPreferences(RecordingService.PREF_FILE, Context.MODE_PRIVATE)
        fun status(message: String) {
            prefs.edit().putString(PREF_STATUS, message).apply()
            AppLog.i("Upload", message)
            RecorderStatus.refreshTile(applicationContext)
        }
        val store = RecordingFiles.open(applicationContext)
        val lock = store.tryLock("upload") ?: return@withContext Result.retry()
        lock.use {
            try {
                store.recoverLegacyUploads()
                if (!AuthManager.isAuthenticated()) {
                    status("Link Drive to upload. Audio retained.")
                    return@withContext Result.success()
                }
                val startedAt = SystemClock.elapsedRealtime()
                val pending = store.pending()
                val uploader = DriveUploader(store, idBatchSize = pending.size)
                var failed = false
                var consecutiveFailures = 0
                var verified = 0
                var verifiedBytes = 0L
                var logRequests = 0
                AppLog.i("Upload", "Batch start; audio=${pending.size}; timing=${AppSettings.uploadTiming(applicationContext)}; unmetered=${AppSettings.unmetered(applicationContext)}")
                for ((index, file) in pending.withIndex()) {
                    if (isStopped) return@withContext Result.retry()
                    status("Uploading batch ${index + 1}/${pending.size}")
                    val copy = uploader.uploadFile(file)
                    if (copy == null) {
                        status("Upload pending. Audio retained; will retry.")
                        failed = true
                        consecutiveFailures++
                        if (consecutiveFailures >= 3) break
                        continue // A single bad object must not strand otherwise healthy recordings.
                    }
                    consecutiveFailures = 0
                    store.acknowledge(file, copy.id, copy.size, copy.md5)
                    verified++; verifiedBytes += copy.size
                }
                if (AppSettings.logging(applicationContext)) {
                    AppLog.flush()
                    val logs = RecordingFiles.logs(applicationContext)
                    val pendingLogs = logs.pendingLogs()
                    val logUploader = DriveUploader(logs, ownerStore = store, idBatchSize = pendingLogs.size)
                    // A finite snapshot: logging this upload cannot grow the same upload pass forever.
                    for (file in pendingLogs) {
                        if (isStopped) return@withContext Result.retry()
                        if (!AppSettings.logging(applicationContext)) break
                        val copy = logUploader.uploadFile(file, logFile = true)
                        if (copy == null) { failed = true; break }
                        logs.acknowledge(file, copy.id, copy.size, copy.md5)
                    }
                    logRequests = logUploader.requestCount
                }
                AppLog.i("Upload", "Batch result; verified_audio=$verified; bytes=$verifiedBytes; elapsed_ms=${SystemClock.elapsedRealtime() - startedAt}; http=${uploader.requestCount + logRequests}; remaining=${store.pending().size}; retry=$failed")
                if (failed) {
                    status("Uploads pending. Local files retained; will retry.")
                    Result.retry()
                } else {
                    status("Saved files verified in Drive")
                    Result.success()
                }
            } catch (e: Exception) {
                AppLog.e("Upload", "Sync interrupted; local audio retained", e)
                status("Sync needs retry. Audio retained.")
                Result.retry()
            }
        }
    }
}
