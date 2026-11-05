package com.ettlinger.wearrecorder

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/** One worker spends a shared object/time budget, with diagnostics offered first. */
class UploadBatch(private val clock: () -> Long, private val maxObjects: Int = 20, private val budgetMs: Long = 90_000) {
    private val started = clock()
    private var attempts = 0
    data class Outcome(val verified: Int, val bytes: Long, val remaining: Int, val failed: Boolean, val paused: Boolean)
    suspend fun run(files: List<File>, allowed: () -> Boolean,
        upload: suspend (File) -> DriveUploader.VerifiedCopy?,
        acknowledge: (File, DriveUploader.VerifiedCopy) -> Unit,
        progress: (Int, Int) -> Unit = { _, _ -> }): Outcome {
        var verified = 0
        var bytes = 0L
        for ((index, file) in files.withIndex()) {
            currentCoroutineContext().ensureActive()
            if (!allowed()) return Outcome(verified, bytes, files.size - verified, false, true)
            if (attempts >= maxObjects || clock() - started >= budgetMs) break
            attempts++
            progress(index + 1, files.size)
            val copy = upload(file)
            currentCoroutineContext().ensureActive()
            if (copy == null) {
                val stillAllowed = allowed()
                return Outcome(verified, bytes, files.size - verified, stillAllowed, !stillAllowed)
            }
            // Finish verified cleanup even if preferences changed during the last HTTP request.
            acknowledge(file, copy)
            verified++; bytes += copy.size
        }
        return Outcome(verified, bytes, files.size - verified, false, false)
    }
    companion object {
        // Continuations keep the original cutoff, excluding recordings/logs produced by the sync itself.
        fun snapshot(files: List<File>, cutoff: Long) = files.filter { it.lastModified() <= cutoff }
    }
}
