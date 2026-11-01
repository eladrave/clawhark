package com.ettlinger.wearrecorder

import android.content.Context
import android.os.SystemClock
import androidx.wear.tiles.TileService

object RecorderStatus {
    private const val FILE = "rave_ai_status"
    private data class Snapshot(val state: String, val mode: String, val heartbeat: Long, val updated: Long)
    @Volatile private var live: Snapshot? = null
    private var persistedAt: Long? = null
    private val refreshPolicy = TileRefreshPolicy()
    @Synchronized fun publish(c: Context, state: String, mode: String) {
        val now = SystemClock.elapsedRealtime()
        val previous = live
        live = Snapshot(state, mode, now, System.currentTimeMillis())
        val critical = previous?.state != state && state !in listOf("RECORDING", "LISTENING")
        val last = persistedAt
        if (previous == null || critical || previous.mode != mode || last == null || now < last || now - last >= 60_000) {
            val snapshot = checkNotNull(live)
            c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString("state", state)
                .putString("mode", mode).putLong("heartbeat", now)
                .putLong("updated", snapshot.updated).apply()
            persistedAt = now
        }
        refreshTile(c, force = critical)
    }
    fun state(c: Context): String {
        live?.let { return displayState(it.state, SystemClock.elapsedRealtime() - it.heartbeat) }
        val p = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val s = p.getString("state", "STOPPED")!!
        val age = SystemClock.elapsedRealtime() - p.getLong("heartbeat", 0)
        return displayState(s, age)
    }
    fun displayState(state: String, age: Long) =
        if (state in listOf("RECORDING", "LISTENING", "RECOVERING MIC", "STARTING") && (age < 0 || age > 120_000)) "CHECK RECORDER" else state
    fun processStarted(c: Context) {
        val p = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (p.getString("state", "STOPPED") in listOf("RECORDING", "LISTENING", "RECOVERING MIC", "STARTING"))
            publish(c, "CHECK RECORDER", mode(c))
    }
    fun mode(c: Context) = live?.mode ?: c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("mode",
        if (AppSettings.speechOnly(c)) "Speech only" else "Continuous")!!
    fun updated(c: Context) = live?.updated ?: c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getLong("updated", 0)
    @Synchronized fun tileVisible(c: Context, visible: Boolean) {
        refreshPolicy.visibility(visible)
        if (visible) refreshTile(c, force = true)
    }
    @Synchronized fun refreshTile(c: Context, force: Boolean = false) {
        if (!refreshPolicy.shouldRequest(SystemClock.elapsedRealtime(), force)) return
        try { TileService.getUpdater(c).requestUpdate(StatusTileService::class.java) } catch (_: Exception) { }
    }
}
