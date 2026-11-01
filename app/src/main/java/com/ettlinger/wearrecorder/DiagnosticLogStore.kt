package com.ettlinger.wearrecorder

import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/** Immutable upload batches; a mutable .part file is never offered to the uploader. */
class DiagnosticLogStore(
    private val store: RecordingStore,
    private val budget: Long = 20L * 1024 * 1024,
    private val chunkSize: Long = 256 * 1024,
    private val reserve: Long = RecordingStore.FREE_RESERVE,
    private val syncOutput: (FileOutputStream) -> Unit = { it.fd.sync() }
) {
    private var active: File? = null
    private var output: FileOutputStream? = null
    private var openedAt = 0L
    private var syncedAt = 0L
    private var countedAt = 0L
    private var bytesUsed = 0L
    init { store.recoverLogs(); bytesUsed = store.bytesUsed() }
    @Synchronized fun append(line: String, now: Long, important: Boolean = false) {
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        // Reconcile deletions by the uploader, without scanning the directory for every line.
        if (now - countedAt >= 30_000 || now < countedAt || bytesUsed + bytes.size > budget) {
            bytesUsed = store.bytesUsed(); countedAt = now
        }
        check(bytesUsed + bytes.size <= budget && store.directory().usableSpace >= reserve + bytes.size) {
            "Diagnostic log limit reached; existing logs retained"
        }
        if (active != null && (active!!.length() + bytes.size > chunkSize || now - openedAt >= 5 * 60_000)) seal()
        if (active == null) {
            val date = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS_Z", Locale.ROOT).format(Date(now))
            active = File(store.directory(), "RaveAI_log_${date}_${UUID.randomUUID()}.log.part")
            openedAt = now
            syncedAt = now
            output = FileOutputStream(active!!, true)
        }
        // Writes go to the OS immediately. Only diagnostics relax hard-power-loss durability to 30s.
        checkNotNull(output).write(bytes)
        bytesUsed += bytes.size
        if (important || now - syncedAt >= 30_000 || now < syncedAt) checkpoint(now)
    }
    @Synchronized fun checkpoint(now: Long) {
        output?.let { syncOutput(it); syncedAt = now }
    }
    @Synchronized fun seal() {
        val part = active ?: return
        output?.close(); output = null
        store.sealLog(part) // sync data, rename, sync directory before exposing to uploads
        active = null
    }
    companion object {
        fun redact(text: String): String = text
            .replace(Regex("(?i)Bearer\\s+[^\\s,;]+"), "Bearer [redacted]")
            .replace(Regex("GOCSPX-[A-Za-z0-9_-]+"), "[redacted]")
            .replace(Regex("(?i)(access_token|refresh_token|client_secret|device_code|user_code|authorization)\\s*[:=]\\s*[^\\s,;]+"), "$1=[redacted]")
    }
}
