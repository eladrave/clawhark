package com.ettlinger.wearrecorder

import android.content.Context
import android.os.SystemClock
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        const val WORK_NAME = "upload_recordings"
        const val PREF_STATUS = "upload_status"
        const val PREF_AUDIO_STATUS = "audio_sync_status"
        const val PREF_LOG_STATUS = "log_sync_status"
        const val PREF_LOG_SUCCESS_AT = "log_sync_success_at"
        private const val CONTINUATION = "upload_batch_continuation"
        private const val INPUT_CUTOFF = "sync_cutoff_ms"
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
                wm.cancelUniqueWork(CONTINUATION)
                return
            }
            wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<UploadWorker>(UploadPolicy.intervalMinutes(timing), TimeUnit.MINUTES)
                    .setConstraints(constraints(context)).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        }
        fun reconfigure(context: Context) {
            AppSettings.invalidateManualSync(context)
            WorkManager.getInstance(context).cancelUniqueWork(MANUAL)
            WorkManager.getInstance(context).cancelUniqueWork(CONTINUATION)
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
                OneTimeWorkRequestBuilder<UploadWorker>().setInputData(workDataOf(INPUT_MANUAL to true, INPUT_EPOCH to AppSettings.syncEpoch(context), INPUT_CUTOFF to System.currentTimeMillis()))
                    .setConstraints(constraints(context))
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        }
        fun testStarted(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(MANUAL)
            WorkManager.getInstance(context).cancelUniqueWork(CONTINUATION)
        }
        fun pauseForCleanup(context: Context) {
            AppSettings.invalidateManualSync(context)
            val wm = WorkManager.getInstance(context)
            for (name in listOf(MANUAL, CONTINUATION, IMMEDIATE, WORK_NAME)) wm.cancelUniqueWork(name)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        AppLog.init(applicationContext)
        val manual = inputData.getBoolean(INPUT_MANUAL, false)
        val epoch = inputData.getLong(INPUT_EPOCH, -1)
        val cutoff = inputData.getLong(INPUT_CUTOFF, System.currentTimeMillis())
        fun currentGrant() = !manual || epoch == AppSettings.syncEpoch(applicationContext)
        fun allowed(logFile: Boolean) = !isStopped && UploadPolicy.mayUpload(
            AppSettings.uploadTiming(applicationContext), manual, AppSettings.uploadContent(applicationContext), logFile, currentGrant())
            && (!logFile || AppSettings.logging(applicationContext))
        if (!UploadPolicy.mayRun(AppSettings.uploadTiming(applicationContext), manual, currentGrant())) return@withContext Result.success()
        AuthManager.init(applicationContext)
        val prefs = applicationContext.getSharedPreferences(RecordingService.PREF_FILE, Context.MODE_PRIVATE)
        fun status(message: String) {
            prefs.edit().putString(PREF_STATUS, message).apply()
            AppLog.i("Upload", message)
            RecorderStatus.refreshTile(applicationContext)
        }
        fun detail(key: String, message: String) { prefs.edit().putString(key, message).apply() }
        val store = RecordingFiles.open(applicationContext)
        val lock = store.tryLock("upload") ?: return@withContext Result.retry()
        lock.use {
            var activeStatusKey = PREF_LOG_STATUS
            try {
                currentCoroutineContext().ensureActive()
                store.recoverLegacyUploads()
                if (!AuthManager.isAuthenticated()) {
                    status("Link Drive to upload. Files retained.")
                    return@withContext Result.success()
                }
                val startedAt = SystemClock.elapsedRealtime()
                val batch = UploadBatch(SystemClock::elapsedRealtime)
                val logs = RecordingFiles.logs(applicationContext)
                // Seal the current diagnostic batch, but keep a stable cutoff for every continuation.
                AppLog.flush()
                val pendingLogs = if (allowed(true)) UploadBatch.snapshot(logs.pendingLogs(), cutoff) else emptyList()
                val pendingAudio = if (allowed(false)) UploadBatch.snapshot(store.pending(), cutoff) else emptyList()
                AppLog.i("Profile", "Batch start; audio=${pendingAudio.size}; logs=${pendingLogs.size}; manual=$manual; cutoff_ms=$cutoff; ${AppSettings.profile(applicationContext)}")
                status("Syncing selected files; diagnostics first")
                var http = 0
                val logUploader = DriveUploader(logs, ownerStore = store, idBatchSize = pendingLogs.size, requestAllowed = { allowed(true) })
                // Diagnostic errors and test markers cannot be stranded behind hundreds of audio files.
                val logResult = batch.run(pendingLogs, { allowed(true) }, { logUploader.uploadFile(it, logFile = true) },
                    { file, copy -> logs.acknowledge(file, copy.id, copy.size, copy.md5) },
                    { index, total ->
                        detail(PREF_LOG_STATUS, "Uploading logs $index/$total")
                        status("Uploading logs $index/$total")
                    })
                http += logUploader.requestCount
                val logMessage = when {
                    !allowed(true) -> "Logs deferred by settings or worker stop"
                    logResult.failed -> "Logs need retry; local batches retained"
                    logResult.remaining > 0 -> "Logs: ${logResult.verified} verified; ${logResult.remaining} waiting in this sync"
                    else -> "Logs verified for this sync"
                }
                detail(PREF_LOG_STATUS, logMessage)
                if (pendingLogs.isNotEmpty() && logResult.remaining == 0 && !logResult.failed && !logResult.paused)
                    prefs.edit().putLong(PREF_LOG_SUCCESS_AT, System.currentTimeMillis()).apply()
                activeStatusKey = PREF_AUDIO_STATUS
                val audioUploader = DriveUploader(store, idBatchSize = pendingAudio.size, requestAllowed = { allowed(false) })
                val audioResult = batch.run(pendingAudio, { allowed(false) }, { audioUploader.uploadFile(it) },
                    { file, copy -> store.acknowledge(file, copy.id, copy.size, copy.md5) },
                    { index, total ->
                        detail(PREF_AUDIO_STATUS, "Uploading audio $index/$total")
                        status("Uploading audio $index/$total; logs processed first")
                    })
                http += audioUploader.requestCount
                detail(PREF_AUDIO_STATUS, when {
                    !allowed(false) -> "Audio deferred by settings or worker stop"
                    audioResult.failed -> "Audio needs retry; recordings retained"
                    audioResult.remaining > 0 -> "Audio: ${audioResult.verified} verified; ${audioResult.remaining} waiting in this sync"
                    else -> "Audio verified for this sync"
                })
                currentCoroutineContext().ensureActive()
                val failed = logResult.failed || audioResult.failed
                val paused = logResult.paused || audioResult.paused
                val remaining = logResult.remaining + audioResult.remaining
                AppLog.i("Profile", "Batch result; verified_audio=${audioResult.verified}; verified_logs=${logResult.verified}; bytes=${audioResult.bytes + logResult.bytes}; elapsed_ms=${SystemClock.elapsedRealtime() - startedAt}; http=$http; remaining_audio=${store.pending().size}; remaining_logs=${logs.pendingLogs().size}; retry=$failed; paused=$paused; manual=$manual; ${AppSettings.profile(applicationContext)}")
                when {
                    paused -> { status("Sync paused. Pending files retained."); Result.success() }
                    failed -> { status("Sync will retry. Pending files retained."); Result.retry() }
                    remaining > 0 -> {
                        val request = OneTimeWorkRequestBuilder<UploadWorker>()
                            .setInputData(workDataOf(INPUT_MANUAL to manual, INPUT_EPOCH to epoch, INPUT_CUTOFF to cutoff))
                            .setConstraints(constraints(applicationContext))
                            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
                        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                            if (manual) MANUAL else CONTINUATION, ExistingWorkPolicy.APPEND_OR_REPLACE, request).result.get()
                        status("Batch verified; continuing remaining files")
                        Result.success()
                    }
                    else -> { status("Selected sync finished. See audio/log status."); Result.success() }
                }
            } catch (e: CancellationException) {
                // WorkManager owns rescheduling. Never convert coroutine cancellation into a Drive error/success.
                val reason = if (android.os.Build.VERSION.SDK_INT >= 31) stopReason.toString() else "unavailable"
                AppLog.w("Profile", "Sync paused by worker cancellation; stop_reason=$reason; files retained")
                detail(activeStatusKey, "Paused; pending files retained")
                status("Sync paused by Android or settings. Files retained.")
                throw e
            } catch (e: Exception) {
                AppLog.e("Upload", "Sync interrupted; local files retained", e)
                detail(activeStatusKey, "Needs retry; pending files retained")
                status("Sync needs retry. Files retained.")
                Result.retry()
            }
        }
    }
}
