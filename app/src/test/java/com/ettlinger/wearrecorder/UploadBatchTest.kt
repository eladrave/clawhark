package com.ettlinger.wearrecorder

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class UploadBatchTest {
    private fun files(n: Int) = (1..n).map { File("file$it") }
    private fun copy(f: File) = DriveUploader.VerifiedCopy(f.name, 10, "checksum")

    @Test fun logsSpendTheSharedBudgetBeforeAudioAndRemainingWorkIsExplicit() = runBlocking {
        val batch = UploadBatch({ 0 }, maxObjects = 3)
        val order = mutableListOf<String>()
        val logs = batch.run(files(2), { true }, { order += "log"; copy(it) }, { _, _ -> })
        val audio = batch.run(files(4), { true }, { order += "audio"; copy(it) }, { _, _ -> })
        assertEquals(listOf("log", "log", "audio"), order)
        assertEquals(0, logs.remaining); assertEquals(3, audio.remaining)
        assertFalse(audio.failed); assertFalse(audio.paused)
    }
    @Test fun timeBudgetStopsBeforeStartingAnotherObject() = runBlocking {
        var now = 0L
        val batch = UploadBatch({ now }, budgetMs = 90_000)
        val result = batch.run(files(10), { true }, { now += 40_000; copy(it) }, { _, _ -> })
        assertEquals(3, result.verified); assertEquals(7, result.remaining)
    }
    @Test fun failedUploadCannotAcknowledgeOrLoseRemainingFiles() = runBlocking {
        var acknowledged = 0
        val result = UploadBatch({ 0 }).run(files(3), { true }, { null }, { _, _ -> acknowledged++ })
        assertTrue(result.failed); assertEquals(3, result.remaining); assertEquals(0, acknowledged)
    }
    @Test fun policyPauseIsNotANetworkFailure() = runBlocking {
        val result = UploadBatch({ 0 }).run(files(2), { false }, { error("Unexpected network") }, { _, _ -> error("Unexpected cleanup") })
        assertTrue(result.paused); assertFalse(result.failed); assertEquals(2, result.remaining)
    }
    @Test fun verifiedInFlightObjectMayFinishCleanupThenPolicyStopsTheNext() = runBlocking {
        var allowed = true
        var acknowledged = 0
        val result = UploadBatch({ 0 }).run(files(2), { allowed }, { allowed = false; copy(it) }, { _, _ -> acknowledged++ })
        assertEquals(1, acknowledged); assertEquals(1, result.remaining); assertTrue(result.paused)
    }
    @Test fun cancellationPropagatesWithoutAcknowledgment() {
        var acknowledged = 0
        try {
            runBlocking { UploadBatch({ 0 }).run(files(1), { true }, { throw CancellationException("worker stopped") }, { _, _ -> acknowledged++ }) }
            fail("Cancellation swallowed")
        } catch (_: CancellationException) { assertEquals(0, acknowledged) }
    }
    @Test fun continuationsExcludeFilesCreatedAfterOriginalSyncCutoff() {
        val dir = Files.createTempDirectory("sync-cutoff").toFile()
        try {
            val old = File(dir, "old.wav").apply { writeText("old"); assertTrue(setLastModified(1000)) }
            File(dir, "new.wav").apply { writeText("new"); assertTrue(setLastModified(3000)) }
            assertEquals(listOf(old), UploadBatch.snapshot(dir.listFiles()!!.toList(), 2000))
        } finally { dir.deleteRecursively() }
    }
}
