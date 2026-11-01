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
    private val reserve: Long = RecordingStore.FREE_RESERVE
) {
    private var active: File? = null
    private var openedAt = 0L
    init { store.recoverLogs() }
    @Synchronized fun append(line: String, now: Long) {
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        check(store.bytesUsed() + bytes.size <= budget && store.directory().usableSpace >= reserve + bytes.size) {
            "Diagnostic log limit reached; existing logs retained"
        }
        if (active != null && (active!!.length() + bytes.size > chunkSize || now - openedAt >= 5 * 60_000)) seal()
        if (active == null) {
            val date = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS_Z", Locale.ROOT).format(Date(now))
            active = File(store.directory(), "RaveAI_log_${date}_${UUID.randomUUID()}.log.part")
            openedAt = now
        }
        FileOutputStream(active!!, true).use { it.write(bytes); it.fd.sync() }
    }
    @Synchronized fun seal() { active?.let { store.sealLog(it) }; active = null }
    companion object {
        fun redact(text: String): String = text
            .replace(Regex("(?i)Bearer\\s+[^\\s,;]+"), "Bearer [redacted]")
            .replace(Regex("GOCSPX-[A-Za-z0-9_-]+"), "[redacted]")
            .replace(Regex("(?i)(access_token|refresh_token|client_secret|device_code|user_code|authorization)\\s*[:=]\\s*[^\\s,;]+"), "$1=[redacted]")
    }
}
