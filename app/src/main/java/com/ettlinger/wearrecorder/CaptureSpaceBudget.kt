package com.ettlinger.wearrecorder

/** Reserve a bounded amount before writes; reconcile disk accounting once per 10s/budget. */
class CaptureSpaceBudget(private val checkSpace: (Long) -> Unit) {
    private val windowBytes = 16_000L * 2 * 10
    private var checkedAt: Long? = null
    private var spent = 0L
    fun beforeAppend(now: Long, bytes: Int) {
        require(bytes in 0..8192)
        val at = checkedAt
        if (at == null || now < at || now - at >= 10_000 || spent + bytes > windowBytes) {
            checkSpace(windowBytes + 8192 + 44)
            checkedAt = now
            spent = 0
        }
        spent += bytes
    }
}
