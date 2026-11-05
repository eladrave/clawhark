package com.ettlinger.wearrecorder

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.Closeable

/** Owns the microphone only. Storage callbacks stay outside device-error catches. */
class RecoveringMicrophone(
    private val open: () -> Input,
    private val wanted: () -> Boolean,
    private val clock: () -> Long,
    private val waiting: (Waiting) -> Unit,
    private val pause: suspend (Long) -> Unit = { delay(it) }
) : Closeable {
    interface Input : Closeable {
        fun read(samples: ShortArray): Int
        fun silenced(): Boolean
        fun stop()
    }
    data class Waiting(val reason: String, val elapsedMs: Long, val first: Boolean)
    data class Read(val samples: Int, val gapMs: Long?)
    @Volatile private var input: Input? = null
    @Volatile private var forcedRetry = false
    @Volatile private var lastInputAt = 0L
    @Volatile private var lastResponseAt = 0L
    @Volatile private var waitingSince: Long? = null
    private var publishedAt: Long? = null
    private var attempt = 0

    /** A watchdog can unblock a stuck read; only the acquisition owner releases it. */
    fun retryStalledRead(now: Long): Boolean {
        val active = input ?: return false
        if (!wanted() || now - lastResponseAt <= STALL_MS || forcedRetry) return false
        forcedRetry = true
        try { active.stop() } catch (_: Exception) { }
        return true
    }
    fun unblock() { try { input?.stop() } catch (_: Exception) { } }

    suspend fun next(samples: ShortArray): Read? {
        require(samples.isNotEmpty())
        while (wanted()) {
            currentCoroutineContext().ensureActive()
            if (input == null) {
                try {
                    val created = open()
                    if (!wanted()) { created.close(); return null }
                    input = created
                    forcedRetry = false
                    lastInputAt = clock()
                    lastResponseAt = lastInputAt
                } catch (e: CancellationException) { throw e }
                catch (e: SecurityException) { throw e }
                catch (e: IllegalArgumentException) { throw e }
                catch (e: Exception) {
                    if (!wanted()) return null
                    interrupted("Microphone unavailable (${e.javaClass.simpleName}). Retrying automatically.")
                    pause(backoff())
                    continue
                }
            }
            val active = input ?: continue
            var deviceError: Exception? = null
            var silenced = false
            val read = try {
                val n = active.read(samples)
                silenced = active.silenced()
                n
            } catch (e: CancellationException) { throw e }
            catch (e: SecurityException) { throw e }
            catch (e: IllegalArgumentException) { throw e }
            catch (e: Exception) { deviceError = e; -1 }
            currentCoroutineContext().ensureActive()
            if (!wanted()) return null
            val now = clock()
            lastResponseAt = now // Muted reads are still physical progress; waiting alone is not.
            if (forcedRetry || read < 0 || (read == 0 && now - lastInputAt > STALL_MS)) {
                val reason = if (forcedRetry || read == 0) "Microphone stopped supplying audio. Retrying automatically."
                    else "Microphone read interrupted (${deviceError?.javaClass?.simpleName ?: read}). Retrying automatically."
                releaseCurrent()
                interrupted(reason)
                pause(backoff())
                continue
            }
            if (silenced) {
                // Keep the foreground capture session; never store or classify muted input.
                interrupted("Android muted the microphone. Waiting to resume automatically.")
                pause(1_000)
                continue
            }
            if (read == 0) { pause(20); continue }
            require(read <= samples.size) { "Microphone returned an invalid sample count" }
            lastInputAt = now
            attempt = 0
            val gap = waitingSince?.let { (now - it).coerceAtLeast(0) }
            waitingSince = null; publishedAt = null
            return Read(read, gap)
        }
        return null
    }
    private fun interrupted(reason: String) {
        val now = clock()
        val first = waitingSince == null
        if (first) waitingSince = now
        val last = publishedAt
        if (first || last == null || now < last || now - last >= 30_000) {
            publishedAt = now
            waiting(Waiting(reason, (now - checkNotNull(waitingSince)).coerceAtLeast(0), first))
        }
    }
    private fun backoff(): Long = minOf(30_000L, 1_000L shl minOf(attempt++, 5))
    private fun releaseCurrent() {
        val active = input; input = null
        forcedRetry = false
        try { active?.stop() } catch (_: Exception) { }
        try { active?.close() } catch (_: Exception) { }
    }
    override fun close() { releaseCurrent(); waitingSince = null; publishedAt = null }
    companion object { private const val STALL_MS = 10_000L }
}
