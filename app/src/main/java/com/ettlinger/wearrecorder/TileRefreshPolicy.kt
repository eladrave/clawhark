package com.ettlinger.wearrecorder

/** Routine state changes never wake a hidden tile. Safety transitions bypass coalescing. */
class TileRefreshPolicy {
    var visible = false
        private set
    private var lastRequest: Long? = null
    fun visibility(value: Boolean) { visible = value }
    fun shouldRequest(now: Long, force: Boolean = false): Boolean {
        val last = lastRequest
        if (!force && (!visible || (last != null && now >= last && now - last < 60_000))) return false
        lastRequest = now
        return true
    }
}
