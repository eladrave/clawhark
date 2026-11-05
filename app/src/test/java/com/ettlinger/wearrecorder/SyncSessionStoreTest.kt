package com.ettlinger.wearrecorder

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class SyncSessionStoreTest {
    private fun sample(mono: Long, battery: Int, wall: Long = mono + 1_790_000_000_000, boot: String = "7",
        charging: Boolean? = false, plugged: Boolean? = false, recording: Boolean = false) =
        SyncSessionStore.Sample(wall, mono, boot, battery, charging, plugged, recording)
    private fun store() = SyncSessionStore(Files.createTempDirectory("sync-session").toFile())
    private fun create(s: SyncSessionStore, manual: Boolean = true) = s.create("root", sample(1000, 95), "test-original", "manual/speech", manual, 4, "1.5.0", "firmware")
    @Test fun fullSessionSeparatesWorkerTimeFromSchedulingGapsAndLinksSourceTest() {
        val s = store(); val id = create(s)
        s.begin(id, "pass1:0", sample(5000, 95), "p")
        s.verified(id, false, 200); s.verified(id, true, 100)
        s.endPass(id, "pass1:0", sample(65000, 94), 6, "continuing", JSONObject().put("work_id", "pass2"))
        s.begin(id, "pass2:0", sample(125000, 94), "p")
        s.verified(id, false, 300)
        s.endPass(id, "pass2:0", sample(185000, 93), 4, "completed")
        s.finish(id, "completed")
        val j = s.snapshot(id)
        assertEquals(180000, j.getLong("elapsed_ms")); assertEquals(120000, j.getLong("running_ms"))
        assertEquals(60000, j.getLong("non_worker_ms")); assertEquals(4000, j.getLong("initial_queue_ms"))
        assertEquals(2, j.getInt("audio_files")); assertEquals(500, j.getLong("audio_bytes"))
        assertEquals("test-original", j.getString("test_id")); assertTrue(j.getBoolean("between_pass_observation_unproven"))
        assertTrue(j.getString("report").contains("whole_watch_battery_drop_points=2"))
    }
    @Test fun receiptIsFrozenAndDeliveryIdentitySurvivesReloadBeforeCleanup() {
        val dir = Files.createTempDirectory("receipt-reload").toFile(); val s = SyncSessionStore(dir); val id = create(s)
        s.begin(id, "pass:0", sample(2000, 95), "p"); s.endPass(id, "pass:0", sample(4000, 94), 2, "completed"); s.finish(id, "completed")
        val original = s.snapshot(id).getString("report"); s.receiptId(id, "stable_remote_id")
        val fresh = SyncSessionStore(dir); fresh.finish(id, "aborted", sample(100000, 50))
        assertEquals(original, fresh.snapshot(id).getString("report"))
        assertEquals("stable_remote_id", fresh.snapshot(id).getString("receipt_drive_id"))
        assertFalse(fresh.begin(id, "later:0", sample(100000, 50), "other"))
        fresh.delivered(id); assertTrue(fresh.all().isEmpty())
    }
    @Test fun processDeathDoesNotCountUnknownRunningTimeAsWaitingOrPretendCountsComplete() {
        val dir = Files.createTempDirectory("interrupted-sync").toFile(); val s = SyncSessionStore(dir); val id = create(s)
        s.begin(id, "pass:0", sample(2000, 95), "first"); s.verified(id, false, 100)
        val fresh = SyncSessionStore(dir); fresh.begin(id, "pass:1", sample(50000, 94), "second")
        fresh.endPass(id, "pass:1", sample(55000, 94), 1, "completed"); fresh.finish(id, "completed")
        val j = fresh.snapshot(id)
        assertTrue(j.getBoolean("running_time_incomplete")); assertTrue(j.getBoolean("counts_incomplete"))
        assertTrue(j.getBoolean("observation_gap")); assertEquals(-1, j.getLong("non_worker_ms"))
        assertEquals(0, j.getInt("audio_files"))
    }
    @Test fun duplicateClosedAttemptCannotDoubleCountTiming() {
        val s = store(); val id = create(s); s.begin(id, "pass:0", sample(2000, 95), "p")
        s.endPass(id, "pass:0", sample(7000, 94), 2, "retry")
        s.endPass(id, "pass:0", sample(12000, 94), 2, "retry")
        assertFalse(s.begin(id, "pass:0", sample(14000, 94), "p"))
        assertEquals(5000, s.snapshot(id).getLong("running_ms")); assertEquals(1, s.snapshot(id).getInt("retries"))
    }
    @Test fun wallClockChangeDoesNotChangeMonotonicSessionDuration() {
        val s = store(); val id = create(s); s.begin(id, "pass:0", sample(2000, 95), "p")
        s.endPass(id, "pass:0", sample(12000, 94, wall = 1_790_100_000_000), 2, "completed"); s.finish(id, "completed")
        assertEquals(10000, s.snapshot(id).getLong("elapsed_ms"))
    }
    @Test fun rebootMakesCrossBootDurationUnknown() {
        val s = store(); val id = create(s); s.begin(id, "pass:0", sample(2000, 95), "p")
        s.endPass(id, "pass:0", sample(1000, 94, boot = "8"), 1, "completed"); s.finish(id, "completed")
        assertEquals(-1, s.snapshot(id).getLong("elapsed_ms")); assertTrue(s.snapshot(id).getBoolean("running_time_incomplete"))
    }
    @Test fun powerAndRecordingContaminationSurviveSampleTruncation() {
        val s = store(); val id = create(s); s.begin(id, "pass:0", sample(2000, 95), "p")
        s.observe(id, sample(3000, 95, charging = true, plugged = true, recording = true), true)
        for (i in 1..100) s.observe(id, sample(4000L + i, 94), true)
        s.endPass(id, "pass:0", sample(5000, 94), 1, "completed"); s.finish(id, "completed")
        val j = s.snapshot(id)
        assertTrue(j.getBoolean("external_power_observed")); assertTrue(j.getBoolean("recording_overlap_observed"))
        assertTrue(j.getBoolean("samples_truncated")); assertEquals(64, j.getJSONArray("samples").length())
    }
    @Test fun metadataCapacityPreservesExistingUndeliveredSessions() {
        val s = SyncSessionStore(Files.createTempDirectory("sync-limit").toFile(), capacity = 2)
        create(s); create(s)
        try { create(s); fail("Metadata cap ignored") } catch (_: IllegalStateException) { }
        assertEquals(2, s.all().size)
    }
    @Test fun automaticPreExecutionQueueTimeIsExplicitlyUnavailable() {
        val s = store(); val id = create(s, false); s.begin(id, "pass:0", sample(2000, 95), "p")
        s.endPass(id, "pass:0", sample(7000, 94), 1, "completed"); s.finish(id, "completed")
        assertEquals(-1, s.snapshot(id).getLong("initial_queue_ms"))
        assertTrue(s.snapshot(id).getString("report").contains("Receipt delivery and its retries are excluded"))
    }
    @Test fun abortedRecoveryUsesLastObservedEndpointNotLaterStartupBattery() {
        val s = store(); val id = create(s); s.begin(id, "pass:0", sample(2000, 95), "p")
        s.endPass(id, "pass:0", sample(7000, 94), 2, "cancel")
        s.finish(id, "aborted_reconciled")
        assertEquals(94, s.snapshot(id).getJSONObject("last_sample").getInt("battery_pct"))
        assertEquals("aborted_reconciled", s.snapshot(id).getString("outcome"))
    }
    @Test fun missingQueuedWorkWaitsForEnqueueGraceThenAbortsWithoutInventingAnEndpoint() {
        val s = store(); val id = create(s)
        assertFalse(SyncSessionStore.orphanGraceElapsed(s.snapshot(id), sample(2000, 94)))
        assertTrue(SyncSessionStore.orphanGraceElapsed(s.snapshot(id), sample(601001, 94)))
        s.finish(id, "aborted_orphan_reconciled")
        assertFalse(s.snapshot(id).has("last_sample"))
        assertEquals(-1, s.snapshot(id).getLong("elapsed_ms"))
    }
    @Test fun prunedCancelledWorkCanRecoverAfterGraceUsingOnlyItsLastSample() {
        val s = store(); val id = create(s); s.begin(id, "pass:0", sample(2000, 95), "p")
        s.endPass(id, "pass:0", sample(7000, 94), 2, "cancel")
        assertTrue(SyncSessionStore.orphanGraceElapsed(s.snapshot(id), sample(700000, 80)))
        s.finish(id, "aborted_orphan_reconciled")
        assertEquals(94, s.snapshot(id).getJSONObject("last_sample").getInt("battery_pct"))
    }

}
