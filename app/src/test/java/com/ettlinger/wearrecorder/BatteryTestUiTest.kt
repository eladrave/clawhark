package com.ettlinger.wearrecorder

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class BatteryTestUiTest {
    private val zone = ZoneId.of("America/New_York")
    private val start = Instant.parse("2026-11-02T13:27:01Z").toEpochMilli()
    private fun display(elapsed: Long, wallShift: Long = 0, boot: Int? = 3) = BatteryTestUi.display(
        true, start, 123_000, 3, start + elapsed + wallShift, 123_000 + elapsed, boot, zone)

    @Test fun anInactiveMarkerDoesNotDisplayAnOldStartOrElapsed() {
        assertEquals("No battery test marked", BatteryTestUi.display(false, start, 123_000, 3,
            start + 7_200_000, 7_323_000, 3, zone))
    }

    @Test fun sameBootDisplaysPersistedStartElapsedAndOneHourTarget() {
        val text = display(30 * 60_000L)
        assertTrue(text.contains("Started 08:27:01 EST"))
        assertTrue(text.contains("Elapsed 00:30:00"))
        assertTrue(text.contains("60-min target 09:27:01 EST"))
        assertFalse(text.contains("unavailable"))
    }

    @Test fun aWallClockChangeDoesNotFalsifyElapsedAndMovesTheDisplayedTarget() {
        val text = display(30 * 60_000L, wallShift = 3_600_000)
        assertTrue(text.contains("Elapsed 00:30:00"))
        assertTrue(text.contains("60-min target 10:27:01 EST"))
        assertTrue(text.contains("Clock adjusted"))
    }

    @Test fun reachingTheTargetDoesNotResetElapsedOrHideTheOriginalStart() {
        val text = display(69 * 60_000L + 18_000)
        assertTrue(text.contains("Started 08:27:01 EST"))
        assertTrue(text.contains("Elapsed 01:09:18"))
        assertTrue(text.contains("60-min target 09:27:01 EST"))
        assertTrue(text.contains("60-minute target reached"))
    }

    @Test fun aRebootMakesDurationAndTargetUncertainInsteadOfClaimingContinuity() {
        val text = display(30 * 60_000L, boot = 4)
        assertTrue(text.contains("Started 08:27:01 EST"))
        assertTrue(text.contains("Elapsed unavailable (watch restarted)"))
        assertTrue(text.contains("60-min target unavailable"))
    }

    @Test fun legacyMarkersAndUnknownBootRemainExplicitlyUncertain() {
        val legacy = BatteryTestUi.display(true, start, null, null, start + 1_800_000, 1_923_000, 3, zone)
        assertTrue(legacy.contains("Started 08:27:01 EST"))
        assertTrue(legacy.contains("Elapsed unavailable (older test marker)"))
        assertTrue(display(1_800_000, boot = null).contains("Elapsed unavailable (boot could not be verified)"))
    }

    @Test fun anInvalidMonotonicAnchorCannotCreateANegativeTimer() {
        val text = BatteryTestUi.display(true, start, 500_000, 3, start + 1_000, 100_000, 3, zone)
        assertTrue(text.contains("Elapsed unavailable (clock could not be verified)"))
        assertTrue(text.contains("60-min target unavailable"))
        assertFalse(text.contains("Elapsed -"))
    }
}
