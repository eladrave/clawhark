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
        private const val MANUAL = "upload_manual"
        private const val INPUT_MANUAL = "manual_sync"
        private const val INPUT_EPOCH = "manual_epoch"
        private fun constraints(c: Context) = Constraints.Builder().setRequiredNetworkType(
            if (AppSettings.unmetered(c)) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(AppSettings.uploadTiming(c) == UploadPolicy.CHARGING).build()

        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            wm.cancelUniqueWork("upload_fallback") // migrate the legacy second upload schedule
            val timing = AppSettings.uploadTiming(context)
            if (timing == UploadPolicy.MANUAL) {
                wm.cancelUniqueWork(WORK_NAME)
                wm.cancelUniqueWork(IMMEDIATE)
                return
            }
            wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<UploadWorker>(UploadPolicy.intervalMinutes(timing), TimeUnit.MINUTES)
                    .setConstraints(constraints(context)).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        }
        fun reconfigure(context: Context) {
            AppSettings.invalidateManualSync(context)
            WorkManager.getInstance(context).cancelUniqueWork(MANUAL)
            WorkManager.getInstance(context).cancelUniqueWork(IMMEDIATE)
            schedule(context)
            if (UploadPolicy.automaticKick(AppSettings.uploadTiming(context))) kick(context, true)
        }
        fun kick(context: Context, replace: Boolean = false) {
            if (!UploadPolicy.automaticKick(AppSettings.uploadTiming(context))) return
            // Coalesce offline wakes instead of building thousands of jobs. The periodic pass covers snapshot races.
            WorkManager.getInstance(context).enqueueUniqueWork(IMMEDIATE, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<UploadWorker>().setConstraints(constraints(context))
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        }
        fun syncNow(context: Context) {
            // Queue a finite follow-up when an earlier manual pass already took its log snapshot.
            // KEEP could silently omit the newly sealed END marker in Manual only mode.
            WorkManager.getInstance(context).enqueueUniqueWork(MANUAL, ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<UploadWorker>().setInputData(workDataOf(INPUT_MANUAL to true, INPUT_EPOCH to AppSettings.syncEpoch(context)))
                    .setConstraints(constraints(context))
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        }
        fun testStarted(context: Context) { WorkManager.getInstance(context).cancelUniqueWork(MANUAL) }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        AppLog.init(applicationContext)
        val manual = inputData.getBoolean(INPUT_MANUAL, false)
        fun currentGrant() = !manual || inputData.getLong(INPUT_EPOCH, -1) == AppSettings.syncEpoch(applicationContext)
        fun allowed(logFile: Boolean) = !isStopped && UploadPolicy.mayUpload(
            AppSettings.uploadTiming(applicationContext), manual, AppSettings.uploadContent(applicationContext), logFile, currentGrant())
            && (!logFile || AppSettings.logging(applicationContext))
        // A previously queued automatic job must not create even a token-refresh request in Manual only.
        if (!UploadPolicy.mayRun(AppSettings.uploadTiming(applicationContext), manual, currentGrant())) return@withContext Result.success()
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
                val pending = if (allowed(false)) store.pending() else emptyList()
                val uploader = DriveUploader(store, idBatchSize = pending.size, requestAllowed = { allowed(false) })
                var failed = false
                var consecutiveFailures = 0
                var verified = 0
                var verifiedBytes = 0L
                var logRequests = 0
                AppLog.i("Profile", "Batch start; audio=${pending.size}; manual=$manual; ${AppSettings.profile(applicationContext)}")
                for ((index, file) in pending.withIndex()) {
                    if (isStopped) return@withContext Result.retry()
                    if (!allowed(false)) break
                    status("Uploading batch ${index + 1}/${pending.size}")
                    val copy = uploader.uploadFile(file)
                    if (copy == null) {
                        if (!allowed(false)) break
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
                if (allowed(true)) {
                    AppLog.flush()
                    val logs = RecordingFiles.logs(applicationContext)
                    val pendingLogs = logs.pendingLogs()
                    val logUploader = DriveUploader(logs, ownerStore = store, idBatchSize = pendingLogs.size, requestAllowed = { allowed(true) })
                    // A finite snapshot: logging this upload cannot grow the same upload pass forever.
                    for (file in pendingLogs) {
                        if (isStopped) return@withContext Result.retry()
                        if (!allowed(true)) break
                        val copy = logUploader.uploadFile(file, logFile = true)
                        if (copy == null) { if (allowed(true)) failed = true; break }
                        logs.acknowledge(file, copy.id, copy.size, copy.md5)
                    }
                    logRequests = logUploader.requestCount
                }
                val remaining = store.pending().size
                AppLog.i("Profile", "Batch result; verified_audio=$verified; bytes=$verifiedBytes; elapsed_ms=${SystemClock.elapsedRealtime() - startedAt}; http=${uploader.requestCount + logRequests}; remaining=$remaining; retry=$failed; manual=$manual; ${AppSettings.profile(applicationContext)}")
                if (failed) {
                    status("Uploads pending. Local files retained; will retry.")
                    Result.retry()
                } else {
                    status(if (remaining == 0) "Selected uploads verified in Drive" else "Selected uploads finished; $remaining recordings retained on watch")
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
