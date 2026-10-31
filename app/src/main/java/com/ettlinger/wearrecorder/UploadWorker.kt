package com.ettlinger.wearrecorder

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        const val WORK_NAME = "upload_recordings"
        const val PREF_STATUS = "upload_status"
        private const val IMMEDIATE = "upload_immediate"
        private fun constraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            wm.cancelUniqueWork("upload_fallback") // migrate the legacy second upload schedule
            wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<UploadWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(constraints()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        }
        fun kick(context: Context) {
            // Coalesce offline wakes instead of building thousands of jobs. The periodic pass covers snapshot races.
            WorkManager.getInstance(context).enqueueUniqueWork(IMMEDIATE, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<UploadWorker>().setConstraints(constraints())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        AppLog.init(applicationContext)
        AuthManager.init(applicationContext)
        val prefs = applicationContext.getSharedPreferences(RecordingService.PREF_FILE, Context.MODE_PRIVATE)
        fun status(message: String) { prefs.edit().putString(PREF_STATUS, message).apply() }
        val store = RecordingFiles.open(applicationContext)
        val lock = store.tryLock("upload") ?: return@withContext Result.retry()
        lock.use {
            try {
                store.recoverLegacyUploads()
                if (!AuthManager.isAuthenticated()) {
                    status("Link Drive to upload. Audio retained.")
                    return@withContext Result.success()
                }
                val uploader = DriveUploader(store)
                var failed = false
                var consecutiveFailures = 0
                for (file in store.pending()) {
                    if (isStopped) return@withContext Result.retry()
                    status("Uploading ${store.pending().size} saved files")
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
                }
                if (failed) {
                    status("Some uploads pending. Audio retained; will retry.")
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
