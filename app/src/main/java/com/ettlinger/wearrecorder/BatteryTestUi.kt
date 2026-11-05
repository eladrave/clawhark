package com.ettlinger.wearrecorder

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/** Read-only test display. A test marker never starts/stops capture or schedules an upload. */
object BatteryTestUi {
    const val TARGET_MS = 60 * 60_000L

    fun display(active: Boolean, startedWall: Long, startedElapsed: Long?, startedBoot: Int?,
        nowWall: Long, nowElapsed: Long, nowBoot: Int?, zone: ZoneId = ZoneId.systemDefault()): String {
        if (!active) return "No battery test marked"
        fun clock(wall: Long) = DateTimeFormatter.ofPattern("HH:mm:ss z", Locale.ROOT)
            .withZone(zone).format(Instant.ofEpochMilli(wall))
        val started = if (startedWall > 0) clock(startedWall) else "unavailable"
        val reason = when {
            startedElapsed == null -> "older test marker"
            startedBoot == null || nowBoot == null -> "boot could not be verified"
            startedBoot != nowBoot -> "watch restarted"
            startedElapsed < 0 || nowElapsed < startedElapsed -> "clock could not be verified"
            else -> null
        }
        if (reason != null) return "Battery test active\nStarted $started\nElapsed unavailable ($reason)\n60-min target unavailable"
        val elapsed = nowElapsed - checkNotNull(startedElapsed)
        val target = nowWall + (TARGET_MS - elapsed)
        val clockChanged = startedWall > 0 && abs((nowWall - startedWall) - elapsed) > 2_000
        return buildString {
            append("Battery test active\nStarted $started\nElapsed ${duration(elapsed)}\n60-min target ${clock(target)}")
            if (elapsed >= TARGET_MS) append("\n60-minute target reached")
            if (clockChanged) append("\nClock adjusted; target follows elapsed time")
        }
    }

    private fun duration(millis: Long): String {
        val seconds = millis / 1000
        return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, (seconds / 60) % 60, seconds % 60)
    }
}
