package com.ettlinger.wearrecorder

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.work.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Opt-in measurement/outbox bridge. No microphone or new wake lock is started here. */
object SyncMeasurements {
    const val INPUT_SESSION = "sync_session"
    const val INPUT_RECEIPT = "sync_receipt"
    const val TAG = "rave_measured_sync"
    const val CONTINUATION_TAG = "rave_sync_continuation"
    const val RECEIPT_TAG = "rave_sync_receipt"
    const val PREF_STATUS = "sync_measurement_status"
    val process = UUID.randomUUID().toString()
    private var storage: SyncSessionStore? = null
    @Volatile var activeId: String? = null
        private set
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Synchronized fun store(c: Context): SyncSessionStore {
        storage?.let { return it }
        val backing = RecordingFiles.measurements(c)
        return SyncSessionStore(backing.directory(), { name, text -> backing.writeMeasurementState(name, text) },
            deleteState = { name -> backing.deleteMeasurementState(name) }).also { storage = it }
    }
    fun sample(c: Context): SyncSessionStore.Sample {
        val boot = try { Settings.Global.getInt(c.contentResolver, Settings.Global.BOOT_COUNT, -1).let { if (it < 0) "unknown" else it.toString() } }
            catch (_: Exception) { "unknown" }
        val recording = c.getSharedPreferences(RecordingService.PREF_FILE, Context.MODE_PRIVATE)
            .getBoolean(RecordingService.PREF_SHOULD_RECORD, false)
        return SyncSessionStore.Sample(System.currentTimeMillis(), SystemClock.elapsedRealtime(), boot,
            BatteryMonitor.percent, BatteryMonitor.charging, BatteryMonitor.plugged, recording)
    }
    fun create(c: Context, root: String, manual: Boolean, epoch: Long, test: String): String? {
        if (!AppSettings.logging(c)) return null
        return try { store(c).create(root, sample(c), test, AppSettings.profile(c), manual, epoch,
            BuildConfig.VERSION_NAME, android.os.Build.DISPLAY) }
        catch (e: Exception) { status(c, "Measurement unavailable; sync can continue"); AppLog.w("Profile", "Sync measurement unavailable; existing outbox retained [${e.javaClass.simpleName}]"); null }
    }
    fun begin(c: Context, id: String?, attempt: String): Boolean {
        if (id == null || !AppSettings.logging(c)) return false
        return store(c).begin(id, attempt, sample(c), process).also { if (it) activeId = id }
    }
    fun observe(c: Context) {
        if (!AppSettings.logging(c)) return
        val id = activeId ?: return
        try { store(c).observe(id, sample(c)) } catch (_: Exception) { }
    }
    fun end(c: Context, id: String?, attempt: String, http: Int, outcome: String, continuation: JSONObject? = null) {
        if (id == null) return
        try {
            store(c).endPass(id, attempt, sample(c), http, outcome, continuation)
        } catch (e: Exception) { AppLog.w("Profile", "Measurement checkpoint failed [${e.javaClass.simpleName}]") }
        finally { if (activeId == id) activeId = null }
    }
    fun finish(c: Context, id: String?, outcome: String, manual: Boolean, epoch: Long) {
        if (id == null) return
        try {
            store(c).finish(id, outcome, if (AppSettings.logging(c)) sample(c) else null)
            status(c, "Sync $outcome; final summary pending Drive")
            queueReceipt(c, id, manual, epoch)
        } catch (e: Exception) { AppLog.w("Profile", "Final sync summary retained for retry [${e.javaClass.simpleName}]") }
    }
    fun verified(c: Context, id: String?, log: Boolean, bytes: Long) {
        if (id == null) return
        try { store(c).verified(id, log, bytes) }
        catch (e: Exception) { AppLog.w("Profile", "Measurement count unavailable [${e.javaClass.simpleName}]") }
    }
    fun status(c: Context, message: String) { c.getSharedPreferences(RecordingService.PREF_FILE, Context.MODE_PRIVATE).edit().putString(PREF_STATUS, message).apply() }
    fun grant(c: Context, manual: Boolean, epoch: Long, log: Boolean) = AppSettings.logging(c) &&
        UploadPolicy.mayUpload(AppSettings.uploadTiming(c), manual, AppSettings.uploadContent(c), log,
            !manual || epoch == AppSettings.syncEpoch(c))
    private fun constraints(c: Context) = Constraints.Builder().setRequiredNetworkType(
        if (AppSettings.unmetered(c)) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresCharging(AppSettings.uploadTiming(c) == UploadPolicy.CHARGING).build()
    fun queueReceipt(c: Context, id: String, manual: Boolean, epoch: Long) {
        if (!grant(c, manual, epoch, true)) return
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(workDataOf(INPUT_RECEIPT to id, "manual_sync" to manual, "manual_epoch" to epoch))
            .addTag(RECEIPT_TAG).setConstraints(constraints(c))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(c).enqueueUniqueWork("rave_receipt_$id", ExistingWorkPolicy.KEEP, request)
    }
    fun continuation(id: String, attempt: String, manual: Boolean, epoch: Long, cutoff: Long): JSONObject =
        JSONObject().put("work_id", UUID.nameUUIDFromBytes("$id/$attempt".toByteArray()).toString())
            .put("manual", manual).put("epoch", epoch).put("cutoff", cutoff)
    fun enqueueContinuation(c: Context, id: String, intent: JSONObject) {
        val request = OneTimeWorkRequestBuilder<UploadWorker>().setId(UUID.fromString(intent.getString("work_id")))
            .setInputData(workDataOf(INPUT_SESSION to id, "manual_sync" to intent.getBoolean("manual"),
                "manual_epoch" to intent.getLong("epoch"), "sync_cutoff_ms" to intent.getLong("cutoff")))
            .addTag(TAG).addTag(CONTINUATION_TAG).setConstraints(constraints(c)).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        val wm = WorkManager.getInstance(c)
        val existing = wm.getWorkInfoById(request.id).get()
        if (existing == null) wm.enqueueUniqueWork("rave_continuation_${request.id}", ExistingWorkPolicy.KEEP, request).result.get()
    }
    /** Reconcile only work we own. Never turn an uncertain running/queued session into completed. */
    fun recover(c: Context, explicit: Boolean = false) {
        if (!AppSettings.logging(c)) return
        scope.launch {
            try {
                val states = store(c)
                for (id in states.all()) try {
                    val s = states.snapshot(id)
                    if (s.optString("status") == "DELIVERED") { states.delivered(id); continue }
                    if (s.has("report")) { queueReceipt(c, id, explicit, AppSettings.syncEpoch(c)); continue }
                    val intent = s.optJSONObject("continuation")
                    val manual = if (intent != null) intent.getBoolean("manual") else s.getBoolean("manual")
                    val epoch = if (intent != null) intent.getLong("epoch") else s.getLong("epoch")
                    val policyValid = UploadPolicy.mayRun(AppSettings.uploadTiming(c), manual, !manual || epoch == AppSettings.syncEpoch(c))
                    val workId = intent?.getString("work_id") ?: s.optJSONObject("open")?.getString("attempt")?.substringBefore(":")
                        ?: s.optString("last_closed_attempt").substringBefore(":").ifEmpty { s.getString("root") }
                    val info = if (workId.isNotEmpty()) WorkManager.getInstance(c).getWorkInfoById(UUID.fromString(workId)).get() else null
                    val runnable = info != null && !info.state.isFinished
                    if (!runnable && (!policyValid || info?.state == WorkInfo.State.CANCELLED || info?.state == WorkInfo.State.FAILED)) {
                        states.finish(id, "aborted_reconciled")
                        queueReceipt(c, id, explicit, AppSettings.syncEpoch(c))
                    } else if (policyValid && intent != null && info == null) enqueueContinuation(c, id, intent)
                    else if (!runnable && intent == null && s.optString("last_pass_outcome") == "completed") {
                        states.finish(id, "completed_recovered")
                        queueReceipt(c, id, explicit, AppSettings.syncEpoch(c))
                    } else if (!runnable && intent == null && (info?.state == WorkInfo.State.SUCCEEDED ||
                            (info == null && SyncSessionStore.orphanGraceElapsed(s, sample(c))))) {
                        // No surviving runnable work owns this record. Keep its last observed endpoint.
                        states.finish(id, "aborted_orphan_reconciled")
                        queueReceipt(c, id, explicit, AppSettings.syncEpoch(c))
                    }
                } catch (e: Exception) { AppLog.w("Profile", "Session reconciliation deferred [${e.javaClass.simpleName}]") }
            } catch (e: Exception) { AppLog.w("Profile", "Measurement reconciliation deferred [${e.javaClass.simpleName}]") }
        }
    }
}
