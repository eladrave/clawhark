package com.ettlinger.wearrecorder

import org.junit.Assert.*
import org.junit.Test

class PowerPolicyTest {
    @Test fun hiddenTileReceivesOnlyExplicitSafetyUpdates() {
        val policy = TileRefreshPolicy()
        assertFalse(policy.shouldRequest(1000))
        assertTrue(policy.shouldRequest(1000, force = true))
        assertFalse(policy.shouldRequest(120_000))
        policy.visibility(true)
        assertTrue(policy.shouldRequest(120_000, force = true))
        assertFalse(policy.shouldRequest(120_001))
        assertTrue(policy.shouldRequest(180_000))
        policy.visibility(false)
        assertFalse(policy.shouldRequest(240_000))
    }
    @Test fun errorsBypassTileCoalescingAndClockResetCannotStrandVisibleTile() {
        val policy = TileRefreshPolicy(); policy.visibility(true)
        assertTrue(policy.shouldRequest(100_000))
        assertTrue(policy.shouldRequest(100_001, force = true))
        assertTrue(policy.shouldRequest(1))
    }
    @Test fun diskBudgetChecksBeforeWritesAndReservesFullWindow() {
        val reservations = mutableListOf<Long>()
        val budget = CaptureSpaceBudget { reservations.add(it) }
        repeat(50) { budget.beforeAppend(1000 + it * 20L, 640) }
        assertEquals(listOf(328_236L), reservations)
        budget.beforeAppend(11_000, 640)
        assertEquals(2, reservations.size)
    }
    @Test fun prerollBurstsCannotOverrunTheReservedDiskBudget() {
        var checks = 0
        val budget = CaptureSpaceBudget { checks++ }
        repeat(500) { budget.beforeAppend(1, 640) }
        assertEquals(1, checks)
        budget.beforeAppend(1, 640)
        assertEquals(2, checks)
    }
    @Test fun failedSpaceReservationDoesNotPermitAnAppend() {
        val budget = CaptureSpaceBudget { throw java.io.IOException("full") }
        try { budget.beforeAppend(1, 640); fail("Must reject before writing") } catch (_: java.io.IOException) { }
    }
}
