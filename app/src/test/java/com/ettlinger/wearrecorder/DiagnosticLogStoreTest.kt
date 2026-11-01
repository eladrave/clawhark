package com.ettlinger.wearrecorder

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class DiagnosticLogStoreTest {
    private fun spool() = RecordingStore(Files.createTempDirectory("diagnostic-test").toFile())
    @Test fun mutableLogIsHiddenUntilSealedAndLaterWritesNeverChangeIt() {
        val spool = spool(); val logs = DiagnosticLogStore(spool, reserve = 0)
        logs.append("start battery=90", 1_000)
        assertTrue(spool.pendingLogs().isEmpty())
        logs.seal()
        val first = spool.pendingLogs().single(); val bytes = first.readBytes()
        logs.append("upload battery=89", 2_000)
        assertArrayEquals(bytes, first.readBytes())
        assertEquals(1, spool.pendingLogs().size)
        logs.seal(); assertEquals(2, spool.pendingLogs().size)
    }
    @Test fun processRestartRecoversAnInterruptedLogBatch() {
        val spool = spool(); DiagnosticLogStore(spool, reserve = 0).append("app action", 1000)
        DiagnosticLogStore(spool, reserve = 0)
        assertEquals("app action\n", spool.pendingLogs().single().readText())
    }
    @Test fun quotaStopsNewLogsWithoutEvictingAnUnuploadedLog() {
        val spool = spool(); val logs = DiagnosticLogStore(spool, budget = 30, reserve = 0)
        logs.append("keep this", 1000); logs.seal()
        val file = spool.pendingLogs().single(); val before = file.readBytes()
        try { logs.append("a".repeat(31), 2000); fail("Quota must reject write") } catch (_: IllegalStateException) { }
        assertArrayEquals(before, file.readBytes())
    }
    @Test fun logCleanupRequiresTheSameVerifiedReceiptAsRecordings() {
        val spool = spool(); val logs = DiagnosticLogStore(spool, reserve = 0)
        logs.append("battery=88", 1000); logs.seal()
        val file = spool.pendingLogs().single(); spool.saveDriveId(file, "stable_log_id")
        try { spool.acknowledge(file, "stable_log_id", file.length(), "0".repeat(32)); fail("Wrong checksum") } catch (_: java.io.IOException) { }
        assertTrue(file.exists())
        spool.acknowledge(file, "stable_log_id", file.length(), RecordingStore.digest(file, "MD5"))
        assertFalse(file.exists())
    }
    @Test fun secretsAreRedactedFromDiagnostics() {
        val line = DiagnosticLogStore.redact("Bearer synthetic-token client_secret=secret refresh_token=refresh device_code=grant user_code=code GOCSPX-synthetic")
        for (secret in listOf("synthetic-token", "=secret", "=refresh", "=grant", "=code", "GOCSPX-synthetic")) assertFalse(line.contains(secret))
    }
    @Test fun recordingNamesUseWatchDateTimeOffsetAndUniqueSuffix() {
        val spool = spool(); val a = spool.begin(1_700_000_000_000); val b = spool.begin(1_700_000_000_000)
        try {
            val names = spool.directory().listFiles()!!.filter { it.name.endsWith(".wav.part") }.map { it.name }
            assertEquals(2, names.toSet().size)
            assertTrue(names.all { it.matches(Regex("RaveAI_\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}-\\d{3}_[+-]\\d{4}_.+\\.wav.part")) })
        } finally { a.close(); b.close() }
    }
}
