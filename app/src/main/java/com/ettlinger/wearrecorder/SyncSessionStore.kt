package com.ettlinger.wearrecorder

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** Bounded opt-in measurement metadata. Frozen receipts are independent from delivery state. */
class SyncSessionStore(private val dir: File, private val persist: ((String, String) -> Unit)? = null,
    private val capacity: Int = 32, private val deleteState: ((String) -> Unit)? = null) {
    data class Sample(val wall: Long, val mono: Long, val boot: String, val battery: Int,
        val charging: Boolean?, val plugged: Boolean?, val recording: Boolean) {
        fun json() = JSONObject().put("at", iso(wall)).put("wall_ms", wall).put("mono_ms", mono)
            .put("boot", boot).put("battery_pct", battery).put("charging", charging ?: JSONObject.NULL)
            .put("plugged", plugged ?: JSONObject.NULL).put("recording_observed", recording)
    }
    private val states = mutableMapOf<String, JSONObject>()
    init { if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create measurements") }
    private fun file(id: String): File { require(id.matches(Regex("[a-f0-9-]{36}"))); return File(dir, "sync_$id.json") }
    @Synchronized fun read(id: String): JSONObject = states.getOrPut(id) { JSONObject(file(id).readText()) }
    @Synchronized fun snapshot(id: String) = JSONObject(read(id).toString())
    @Synchronized fun all(): List<String> = dir.listFiles { _, n -> n.matches(Regex("sync_[a-f0-9-]{36}\\.json")) }
        ?.map { it.name.removePrefix("sync_").removeSuffix(".json") } ?: emptyList()
    private fun save(id: String) {
        val content = read(id).toString()
        check(content.toByteArray().size <= 64 * 1024) { "Measurement metadata limit" }
        if (persist != null) { persist.invoke(file(id).name, content); return }
        val target = file(id); val tmp = File(target.path + ".new")
        FileOutputStream(tmp).use { it.write(content.toByteArray()); it.fd.sync() }
        check(tmp.renameTo(target)) { "Cannot commit measurement" }
        FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }
    @Synchronized fun create(root: String, queued: Sample, testId: String, profile: String,
        manual: Boolean, epoch: Long, version: String, firmware: String): String {
        all().filter { read(it).optString("status") == "DELIVERED" }.forEach { delivered(it) }
        check(all().size < capacity) { "Measurement outbox full; existing sessions retained" }
        val id = UUID.randomUUID().toString()
        states[id] = JSONObject().put("id", id).put("root", root).put("queued", queued.json())
            .put("test_id", testId.take(80)).put("profile", profile.take(1500)).put("manual", manual)
            .put("epoch", epoch).put("version", version).put("firmware", firmware.take(200))
            .put("status", "QUEUED").put("outcome", "pending").put("samples", JSONArray())
            .put("audio_files", 0).put("audio_bytes", 0L).put("log_files", 0).put("log_bytes", 0L)
            .put("passes", 0).put("retries", 0).put("running_ms", 0L).put("http_requests", 0)
        save(id); return id
    }
    @Synchronized fun cutoff(id: String, fallback: Long): Long {
        val s = read(id)
        if (!s.has("cutoff_ms")) { s.put("cutoff_ms", fallback); save(id) }
        return s.getLong("cutoff_ms")
    }
    @Synchronized fun findOpen(root: String) = all().firstOrNull {
        val s = read(it); s.optString("root") == root && !s.has("report")
    }
    @Synchronized fun begin(id: String, attempt: String, sample: Sample, process: String): Boolean {
        val s = read(id)
        if (s.has("report") || s.optString("last_closed_attempt") == attempt) return false
        if (s.has("open")) { s.put("running_time_incomplete", true).put("counts_incomplete", true) }
        if (s.has("started") && s.optString("process") != process) s.put("observation_gap", true)
        if (!s.has("started")) s.put("started", sample.json())
        s.put("open", sample.json().put("attempt", attempt)).put("process", process).put("status", "ACTIVE")
        val intent = s.optJSONObject("continuation")
        if (intent != null && attempt.startsWith(intent.getString("work_id") + ":")) s.remove("continuation")
        observe(id, sample, true); save(id); return true
    }
    @Synchronized fun observe(id: String, sample: Sample, force: Boolean = false) {
        val s = read(id)
        if (!s.has("started") || s.has("report")) return
        if (sample.charging == true || sample.plugged == true) s.put("external_power_observed", true)
        if (sample.charging == null || sample.plugged == null || sample.battery < 0) s.put("unknown_power_observed", true)
        if (sample.recording) s.put("recording_overlap_observed", true)
        val j = sample.json(); val samples = s.getJSONArray("samples")
        val last = if (samples.length() > 0) samples.getJSONObject(samples.length() - 1) else null
        if (force || last == null || listOf("battery_pct", "charging", "plugged", "recording_observed").any { last.opt(it) != j.opt(it) }) {
            if (samples.length() >= 64) { samples.remove(1); s.put("samples_truncated", true) }
            samples.put(j)
        }
        s.put("last_sample", j)
    }
    @Synchronized fun verified(id: String, log: Boolean, bytes: Long) {
        val s = read(id); val p = if (log) "log" else "audio"
        s.put(p + "_files", s.getInt(p + "_files") + 1).put(p + "_bytes", s.getLong(p + "_bytes") + bytes)
        // Persist at pass end, not per audio file. An unmatched attempt explicitly marks counters incomplete.
    }
    @Synchronized fun endPass(id: String, attempt: String, sample: Sample, http: Int,
        outcome: String, continuation: JSONObject? = null) {
        val s = read(id); val open = s.optJSONObject("open") ?: return
        if (open.getString("attempt") != attempt || s.has("report")) return
        observe(id, sample, true)
        val delta = sample.mono - open.getLong("mono_ms")
        if (sample.boot == "unknown" || sample.boot != open.getString("boot") || delta < 0)
            s.put("running_time_incomplete", true)
        else s.put("running_ms", s.getLong("running_ms") + delta)
        s.put("passes", s.getInt("passes") + 1).put("http_requests", s.getInt("http_requests") + http)
            .put("last_closed_attempt", attempt).put("last_pass_outcome", outcome).put("status", "QUEUED")
        if (outcome == "retry" || outcome.startsWith("cancel")) s.put("retries", s.getInt("retries") + 1)
        s.remove("open")
        if (continuation != null) s.put("continuation", continuation)
        save(id)
    }
    @Synchronized fun finish(id: String, outcome: String, sample: Sample? = null) {
        val s = read(id); if (s.has("report")) return
        if (sample != null) observe(id, sample, true)
        if (s.has("open")) s.put("running_time_incomplete", true).put("counts_incomplete", true)
        s.put("outcome", outcome).put("status", "RECEIPT_PENDING")
        val start = s.optJSONObject("started"); val end = s.optJSONObject("last_sample")
        val sameBoot = start != null && end != null && start.getString("boot") != "unknown" && start.getString("boot") == end.getString("boot")
        val elapsed = if (sameBoot) end!!.getLong("mono_ms") - start!!.getLong("mono_ms") else -1L
        val running = s.getLong("running_ms")
        val waiting = if (elapsed >= running && !s.optBoolean("running_time_incomplete")) elapsed - running else -1L
        val queued = s.getJSONObject("queued")
        val initialQueue = if (s.getBoolean("manual") && start != null && queued.getString("boot") != "unknown"
            && queued.getString("boot") == start.getString("boot") && start.getLong("mono_ms") >= queued.getLong("mono_ms"))
            start.getLong("mono_ms") - queued.getLong("mono_ms") else -1L
        s.put("elapsed_ms", elapsed).put("non_worker_ms", waiting).put("initial_queue_ms", initialQueue)
        // Between-pass activity is not continuously proven. Never allocate battery loss to running/waiting time.
        if (waiting > 0 || s.optBoolean("observation_gap")) s.put("between_pass_observation_unproven", true)
        val report = buildString {
            append("RAVE_AI_SYNC_SUMMARY schema=1\n")
            for (key in listOf("id", "version", "firmware", "test_id", "profile", "manual", "outcome", "passes", "retries",
                "audio_files", "audio_bytes", "log_files", "log_bytes", "http_requests", "running_ms", "elapsed_ms", "non_worker_ms", "initial_queue_ms"))
                append("$key=${s.opt(key)}\n")
            append("queued=${s.getJSONObject("queued")}\nstarted=${start ?: JSONObject.NULL}\nend_last_observed=${end ?: JSONObject.NULL}\n")
            val drop = if (start != null && end != null && start.getInt("battery_pct") >= 0 && end.getInt("battery_pct") >= 0)
                start.getInt("battery_pct") - end.getInt("battery_pct") else "unknown"
            append("whole_watch_battery_drop_points=$drop\n")
            for (key in listOf("external_power_observed", "unknown_power_observed", "recording_overlap_observed", "observation_gap",
                "running_time_incomplete", "counts_incomplete", "samples_truncated", "between_pass_observation_unproven")) append("$key=${s.optBoolean(key)}\n")
            append("battery_samples=${s.getJSONArray("samples")}\n")
            append("Scope: whole-watch battery endpoints. Worker-running time includes CPU/network waits; non-worker time includes OS/retry delays. Unknown durations are -1. Aborted outcomes end at the last observed sample, not an inferred cancellation timestamp. Between-pass charging/recording cannot be ruled out by endpoints alone. No hourly rate or energy allocation is calculated. Counts may be lower bounds when incomplete.\n")
            append("HTTP count covers Drive API calls, not OAuth refresh requests. File bytes are verified payload bytes, not total radio traffic. Automatic-job pre-start queue time is unavailable.\n")
            append("Receipt delivery and its retries are excluded from these measurements.\n")
        }
        s.put("report", report); s.remove("continuation"); save(id)
    }
    @Synchronized fun receiptId(id: String, value: String) { read(id).put("receipt_drive_id", value); save(id) }
    @Synchronized fun delivered(id: String) {
        read(id).put("status", "DELIVERED"); save(id)
        if (deleteState != null) { deleteState.invoke(file(id).name); states.remove(id) }
        else if (file(id).delete()) { states.remove(id); FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) } }
    }
    companion object {
        /** Missing work may be a live create/enqueue race; wait ten minutes before reconciling. */
        fun orphanGraceElapsed(state: JSONObject, now: Sample): Boolean {
            val queued = state.getJSONObject("queued")
            if (now.boot != "unknown" && queued.getString("boot") != "unknown") {
                if (now.boot != queued.getString("boot")) return true
                return now.mono - queued.getLong("mono_ms") >= 10 * 60_000L
            }
            return now.wall - queued.getLong("wall_ms") >= 10 * 60_000L
        }
        fun iso(wall: Long): String = Instant.ofEpochMilli(wall).atZone(ZoneId.systemDefault()).toOffsetDateTime().toString()
        fun receiptName(id: String) = "RaveAI_sync_$id.log"
        fun isReceipt(file: File) = file.name.matches(Regex("RaveAI_sync_[a-f0-9-]{36}\\.log"))
    }
}
