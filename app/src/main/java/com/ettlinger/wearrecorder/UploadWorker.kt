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
import org.json.JSONObject
import java.util.UUID

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
                    .addTag(SyncMeasurements.TAG).setConstraints(constraints(context)).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        }
        fun reconfigure(context: Context) {
            WorkManager.getInstance(context).cancelAllWorkByTag(SyncMeasurements.TAG)
            WorkManager.getInstance(context).cancelAllWorkByTag(SyncMeasurements.RECEIPT_TAG)
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
                OneTimeWorkRequestBuilder<UploadWorker>().addTag(SyncMeasurements.TAG).setConstraints(constraints(context))
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        }
        fun syncNow(context: Context, sourceTestId: String = AppSettings.testId(context)) {
            SyncMeasurements.recover(context, explicit = true)
            val workId = UUID.randomUUID()
            val epoch = AppSettings.syncEpoch(context)
            val session = SyncMeasurements.create(context, workId.toString(), true, epoch, sourceTestId)
            WorkManager.getInstance(context).enqueueUniqueWork(MANUAL, ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<UploadWorker>().setId(workId)
                    .setInputData(workDataOf(INPUT_MANUAL to true, INPUT_EPOCH to epoch,
                        INPUT_CUTOFF to System.currentTimeMillis(), SyncMeasurements.INPUT_SESSION to session))
                    .addTag(SyncMeasurements.TAG).setConstraints(constraints(context))
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        }
        fun testStarted(context: Context) {
            WorkManager.getInstance(context).cancelAllWorkByTag(SyncMeasurements.CONTINUATION_TAG)
            WorkManager.getInstance(context).cancelAllWorkByTag(SyncMeasurements.RECEIPT_TAG)
            WorkManager.getInstance(context).cancelUniqueWork(MANUAL)
            WorkManager.getInstance(context).cancelUniqueWork(CONTINUATION)
        }
        fun pauseForCleanup(context: Context) {
            AppSettings.invalidateManualSync(context)
            WorkManager.getInstance(context).cancelAllWorkByTag(SyncMeasurements.TAG)
            WorkManager.getInstance(context).cancelAllWorkByTag(SyncMeasurements.RECEIPT_TAG)
            val wm = WorkManager.getInstance(context)
            for (name in listOf(MANUAL, CONTINUATION, IMMEDIATE, WORK_NAME)) wm.cancelUniqueWork(name)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        AppLog.init(applicationContext)
        val manual = inputData.getBoolean(INPUT_MANUAL, false)
        val epoch = inputData.getLong(INPUT_EPOCH, -1)
        var cutoff = inputData.getLong(INPUT_CUTOFF, System.currentTimeMillis())
        fun currentGrant() = !manual || epoch == AppSettings.syncEpoch(applicationContext)
        fun allowed(logFile: Boolean) = !isStopped && UploadPolicy.mayUpload(
            AppSettings.uploadTiming(applicationContext), manual, AppSettings.uploadContent(applicationContext), logFile, currentGrant())
            && (!logFile || AppSettings.logging(applicationContext))
        if (!UploadPolicy.mayRun(AppSettings.uploadTiming(applicationContext), manual, currentGrant())) return@withContext Result.success()
        inputData.getString(SyncMeasurements.INPUT_RECEIPT)?.let { return@withContext publishReceipt(it, manual, inputData.getLong(INPUT_EPOCH, -1)) }
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
            val attempt = "$id:$runAttemptCount"
            var session: String? = null
            var measured = false
            var requestCount = 0
            try {
                try { if (AppSettings.logging(applicationContext)) {
                    session = try { inputData.getString(SyncMeasurements.INPUT_SESSION)
                        ?: SyncMeasurements.store(applicationContext).findOpen(id.toString())
                        ?: SyncMeasurements.create(applicationContext, id.toString(), manual, epoch, AppSettings.testId(applicationContext)) }
                        catch (_: Exception) { null }
                    session?.let { sid ->
                        val s = SyncMeasurements.store(applicationContext).snapshot(sid)
                        if (s.has("report")) { SyncMeasurements.queueReceipt(applicationContext, sid, manual, epoch); return@withContext Result.success() }
                        val intent = s.optJSONObject("continuation")
                        if (intent != null && intent.getString("work_id") != id.toString()) {
                            SyncMeasurements.enqueueContinuation(applicationContext, sid, intent)
                            return@withContext Result.success()
                        }
                        cutoff = SyncMeasurements.store(applicationContext).cutoff(sid, cutoff)
                        measured = SyncMeasurements.begin(applicationContext, sid, attempt)
                        if (!measured) {
                            if (s.optString("last_pass_outcome") == "completed") {
                                SyncMeasurements.store(applicationContext).finish(sid, "completed_recovered")
                                SyncMeasurements.queueReceipt(applicationContext, sid, manual, epoch)
                                return@withContext Result.success()
                            }
                            return@withContext Result.retry()
                        }
                    }
                } } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    AppLog.w("Profile", "Measurement unavailable; payload sync continues [${e.javaClass.simpleName}]")
                    session = null; measured = false
                }
                currentCoroutineContext().ensureActive()
                store.recoverLegacyUploads()
                if (!AuthManager.isAuthenticated()) {
                    SyncMeasurements.end(applicationContext, session, attempt, 0, "not_authenticated")
                    SyncMeasurements.finish(applicationContext, session, "not_authenticated", manual, epoch)
                    status("Link Drive to upload. Files retained.")
                    return@withContext Result.success()
                }
                val startedAt = SystemClock.elapsedRealtime()
                val batch = UploadBatch(SystemClock::elapsedRealtime)
                val logs = RecordingFiles.logs(applicationContext)
                // Seal the current diagnostic batch, but keep a stable cutoff for every continuation.
                AppLog.flush()
                val pendingLogs = if (allowed(true)) UploadBatch.snapshot(logs.pendingLogs().filterNot(SyncSessionStore::isReceipt), cutoff) else emptyList()
                val pendingAudio = if (allowed(false)) UploadBatch.snapshot(store.pending(), cutoff) else emptyList()
                AppLog.i("Profile", "Batch start; audio=${pendingAudio.size}; logs=${pendingLogs.size}; manual=$manual; cutoff_ms=$cutoff; ${AppSettings.profile(applicationContext)}")
                status("Syncing selected files; diagnostics first")
                var http = 0
                val logUploader = DriveUploader(logs, ownerStore = store, idBatchSize = pendingLogs.size, requestAllowed = {
                    SyncMeasurements.observe(applicationContext); allowed(true)
                }, onRequest = { requestCount++ })
                // Diagnostic errors and test markers cannot be stranded behind hundreds of audio files.
                val logResult = batch.run(pendingLogs, { allowed(true) }, { logUploader.uploadFile(it, logFile = true) },
                    { file, copy ->
                        logs.acknowledge(file, copy.id, copy.size, copy.md5)
                        if (measured) SyncMeasurements.verified(applicationContext, session, true, copy.size)
                    },
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
                val audioUploader = DriveUploader(store, idBatchSize = pendingAudio.size, requestAllowed = {
                    SyncMeasurements.observe(applicationContext); allowed(false)
                }, onRequest = { requestCount++ })
                val audioResult = batch.run(pendingAudio, { allowed(false) }, { audioUploader.uploadFile(it) },
                    { file, copy ->
                        store.acknowledge(file, copy.id, copy.size, copy.md5)
                        if (measured) SyncMeasurements.verified(applicationContext, session, false, copy.size)
                    },
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
                val outcome = when { paused -> "aborted_policy"; failed -> "retry"; remaining > 0 -> "continuing"; else -> "completed" }
                val intent = if (remaining > 0 && !failed && !paused && session != null)
                    SyncMeasurements.continuation(session, attempt, manual, epoch, cutoff) else null
                SyncMeasurements.end(applicationContext, session, attempt, requestCount, outcome, intent)
                when {
                    paused -> {
                        SyncMeasurements.finish(applicationContext, session, "aborted_policy", manual, epoch)
                        status("Sync paused. Pending files retained."); Result.success()
                    }
                    failed -> { status("Sync will retry. Pending files retained."); Result.retry() }
                    remaining > 0 -> {
                        if (session != null && intent != null) SyncMeasurements.enqueueContinuation(applicationContext, session, intent)
                        else {
                            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                                .setInputData(workDataOf(INPUT_MANUAL to manual, INPUT_EPOCH to epoch, INPUT_CUTOFF to cutoff))
                                .addTag(SyncMeasurements.TAG).addTag(SyncMeasurements.CONTINUATION_TAG).setConstraints(constraints(applicationContext))
                                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
                            WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                                if (manual) MANUAL else CONTINUATION, ExistingWorkPolicy.APPEND_OR_REPLACE, request).result.get()
                        }
                        status("Batch verified; continuing remaining files"); Result.success()
                    }
                    else -> {
                        SyncMeasurements.finish(applicationContext, session, "completed", manual, epoch)
                        status("Selected sync finished; summary delivery shown separately."); Result.success()
                    }
                }
            } catch (e: CancellationException) {
                // WorkManager owns rescheduling. Never convert coroutine cancellation into a Drive error/success.
                val reason = if (android.os.Build.VERSION.SDK_INT >= 31) stopReason.toString() else "unavailable"
                SyncMeasurements.end(applicationContext, session, attempt, requestCount, "cancel_reason=$reason")
                AppLog.w("Profile", "Sync paused by worker cancellation; stop_reason=$reason; files retained")
                detail(activeStatusKey, "Paused; pending files retained")
                status("Sync paused by Android or settings. Files retained.")
                throw e
            } catch (e: Exception) {
                SyncMeasurements.end(applicationContext, session, attempt, requestCount, "retry")
                AppLog.e("Upload", "Sync interrupted; local files retained", e)
                detail(activeStatusKey, "Needs retry; pending files retained")
                status("Sync needs retry. Files retained.")
                Result.retry()
            }
        }
    }
    private suspend fun publishReceipt(sessionId: String, manual: Boolean, epoch: Long): Result {
        fun allowed() = !isStopped && SyncMeasurements.grant(applicationContext, manual, epoch, true)
        if (!allowed()) return Result.success()
        AuthManager.init(applicationContext)
        val owner = RecordingFiles.open(applicationContext)
        val lock = owner.tryLock("upload") ?: return Result.retry()
        lock.use {
            try {
                if (!AuthManager.isAuthenticated()) return Result.success()
                val sessions = SyncMeasurements.store(applicationContext)
                val state = sessions.snapshot(sessionId)
                if (state.optString("status") == "DELIVERED") { sessions.delivered(sessionId); return Result.success() }
                if (!state.has("report")) return Result.success()
                val logs = RecordingFiles.logs(applicationContext)
                val file = logs.publishSyncReceipt(SyncSessionStore.receiptName(sessionId), state.getString("report"))
                if (state.has("receipt_drive_id")) logs.saveDriveId(file, state.getString("receipt_drive_id"))
                val uploader = DriveUploader(logs, ownerStore = owner, requestAllowed = ::allowed)
                val copy = uploader.uploadFile(file, logFile = true)
                    ?: return if (allowed()) Result.retry() else Result.success()
                // The uploader already persists a generated ID before POST. Retain it in the outbox before ACK can delete sidecars.
                sessions.receiptId(sessionId, copy.id)
                logs.acknowledge(file, copy.id, copy.size, copy.md5)
                sessions.delivered(sessionId)
                SyncMeasurements.status(applicationContext, "Final sync summary verified in Drive")
                applicationContext.getSharedPreferences(RecordingService.PREF_FILE, Context.MODE_PRIVATE).edit()
                    .putLong(PREF_LOG_SUCCESS_AT, System.currentTimeMillis()).apply()
                AppLog.i("Profile", "Final sync summary verified; session=$sessionId; delivery excluded from measured session")
                return Result.success()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                SyncMeasurements.status(applicationContext, "Final sync summary pending; local receipt retained")
                AppLog.w("Profile", "Summary delivery deferred [${e.javaClass.simpleName}]")
                return Result.retry()
            }
        }
    }

}
