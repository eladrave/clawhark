package com.ettlinger.wearrecorder

import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

/** Kept in encrypted preferences, so pausing or recreating the Activity cannot lose the grant. */
data class DeviceLinkSession(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val expiresAt: Long,
    val intervalMs: Long,
    val nextPollAt: Long
) {
    fun encode(): String = JSONObject().put("device_code", deviceCode).put("user_code", userCode)
        .put("verification_url", verificationUrl).put("expires_at", expiresAt)
        .put("interval_ms", intervalMs).put("next_poll_at", nextPollAt).toString()

    companion object {
        fun decode(value: String): DeviceLinkSession = JSONObject(value).let {
            DeviceLinkSession(it.getString("device_code"), it.getString("user_code"),
                it.getString("verification_url"), it.getLong("expires_at"),
                maxOf(5000L, it.getLong("interval_ms")), it.getLong("next_poll_at"))
        }
    }
}

sealed class DeviceLinkResult {
    object Success : DeviceLinkResult()
    object Pending : DeviceLinkResult()
    object SlowDown : DeviceLinkResult()
    object Expired : DeviceLinkResult()
    object Retryable : DeviceLinkResult()
    data class Error(val message: String) : DeviceLinkResult()
}

/** A fresh poller resumes the same persisted session, including server pacing and backoff. */
class DeviceLinkPoller(
    private val load: () -> DeviceLinkSession?,
    private val save: (DeviceLinkSession) -> Boolean,
    private val clear: (String) -> Unit,
    private val poll: suspend (String) -> DeviceLinkResult,
    private val now: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) }
) {
    suspend fun resume(update: (DeviceLinkSession, String) -> Unit): Boolean {
        while (true) {
            currentCoroutineContext().ensureActive()
            val session = load() ?: return false
            if (now() >= session.expiresAt) {
                clear(session.deviceCode)
                update(session, "Code expired. Tap LINK for a new code.")
                return false
            }
            update(session, "Waiting for Google approval.\nEnter code at google.com/device")
            val wait = minOf(session.nextPollAt, session.expiresAt) - now()
            if (wait > 0) pause(wait)
            currentCoroutineContext().ensureActive()
            if (now() >= session.expiresAt) continue
            // Persist pacing BEFORE the request, including if the Activity stops during HTTP.
            if (!save(session.copy(nextPollAt = now() + session.intervalMs))) return false
            when (val result = poll(session.deviceCode)) {
                DeviceLinkResult.Success -> { clear(session.deviceCode); return true }
                DeviceLinkResult.Pending -> Unit
                DeviceLinkResult.SlowDown, DeviceLinkResult.Retryable -> {
                    val interval = if (result == DeviceLinkResult.SlowDown) session.intervalMs + 5000
                        else maxOf(session.intervalMs, 30_000L)
                    if (!save(session.copy(intervalMs = interval, nextPollAt = now() + interval))) return false
                    if (result == DeviceLinkResult.Retryable)
                        update(session, "Connection interrupted. Retrying.\nYour code is still valid.")
                }
                DeviceLinkResult.Expired -> {
                    clear(session.deviceCode)
                    update(session, "Code expired. Tap LINK for a new code.")
                    return false
                }
                is DeviceLinkResult.Error -> {
                    clear(session.deviceCode)
                    update(session, result.message)
                    return false
                }
            }
        }
    }
}
