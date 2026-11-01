package com.ettlinger.wearrecorder

import android.content.Context
import android.os.SystemClock
import androidx.wear.tiles.TileService

object RecorderStatus {
    private const val FILE = "rave_ai_status"
    fun publish(c: Context, state: String, mode: String) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString("state", state)
            .putString("mode", mode).putLong("heartbeat", SystemClock.elapsedRealtime())
            .putLong("updated", System.currentTimeMillis()).apply()
        refreshTile(c)
    }
    fun state(c: Context): String {
        val p = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val s = p.getString("state", "STOPPED")!!
        val age = SystemClock.elapsedRealtime() - p.getLong("heartbeat", 0)
        return displayState(s, age)
    }
    fun displayState(state: String, age: Long) =
        if (state in listOf("RECORDING", "LISTENING", "RECOVERING MIC", "STARTING") && (age < 0 || age > 60_000)) "CHECK RECORDER" else state
    fun processStarted(c: Context) {
        val p = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (p.getString("state", "STOPPED") in listOf("RECORDING", "LISTENING", "RECOVERING MIC", "STARTING"))
            publish(c, "CHECK RECORDER", mode(c))
    }
    fun mode(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("mode",
        if (AppSettings.speechOnly(c)) "Speech only" else "Continuous")!!
    fun updated(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getLong("updated", 0)
    fun refreshTile(c: Context) {
        try { TileService.getUpdater(c).requestUpdate(StatusTileService::class.java) } catch (_: Exception) { }
    }
}
