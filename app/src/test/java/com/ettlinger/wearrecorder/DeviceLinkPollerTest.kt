package com.ettlinger.wearrecorder

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class DeviceLinkPollerTest {
    private class Fixture {
        val file = Files.createTempDirectory("pending-link-test").resolve("session.json").toFile()
        var time = 1_000L
        var calls = 0
        val messages = mutableListOf<String>()
        val original = DeviceLinkSession("synthetic-device", "AAA-BBB-CCC", "https://www.google.com/device",
            100_000L, 5000L, 6000L)
        init { file.writeText(original.encode()) }
        fun load(): DeviceLinkSession? = if (file.exists()) DeviceLinkSession.decode(file.readText()) else null
        fun save(s: DeviceLinkSession): Boolean {
            if (load()?.deviceCode != s.deviceCode) return false
            file.writeText(s.encode()); return true
        }
        fun clear(code: String) { if (load()?.deviceCode == code) file.delete() }
        fun poller(wait: suspend (Long) -> Unit = { time += it },
                   response: suspend (String) -> DeviceLinkResult = { DeviceLinkResult.Success }) =
            DeviceLinkPoller(::load, ::save, ::clear, { code -> calls++; response(code) }, { time }, wait)
        val update: (DeviceLinkSession, String) -> Unit = { _, message -> messages.add(message) }
    }

    @Test fun recreatedAppRedeemsTheSamePersistedGrant() = runBlocking {
        val f = Fixture()
        // Reconstruction uses only the file, as a new Activity/process must do.
        assertEquals(f.original, DeviceLinkSession.decode(f.file.readText()))
        assertTrue(f.poller(response = { code ->
            assertEquals("synthetic-device", code)
            assertTrue(f.time >= 6000L)
            DeviceLinkResult.Success
        }).resume(f.update))
        assertNull(f.load())
        assertEquals(1, f.calls)
    }

    @Test fun screenOffDuringWaitCanResumeAfterPhoneApproval() = runBlocking {
        val f = Fixture()
        val waiting = CompletableDeferred<Unit>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            f.poller(wait = { waiting.complete(Unit); awaitCancellation() }).resume(f.update)
        }
        waiting.await(); job.cancelAndJoin()
        assertEquals(0, f.calls)
        assertEquals("synthetic-device", f.load()!!.deviceCode)
        f.time = 7000L // User approved on the phone while the watch was stopped.
        assertTrue(f.poller().resume(f.update))
        assertEquals(1, f.calls)
    }

    @Test fun interruptedHttpPollRetainsGrantAndPacingAcrossRecreation() = runBlocking {
        val f = Fixture()
        val polling = CompletableDeferred<Unit>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            f.poller(response = { polling.complete(Unit); awaitCancellation() }).resume(f.update)
        }
        polling.await(); job.cancelAndJoin()
        assertEquals(11_000L, f.load()!!.nextPollAt)
        assertTrue(f.poller(response = {
            assertTrue(f.time >= 11_000L)
            DeviceLinkResult.Success
        }).resume(f.update))
        assertEquals(2, f.calls)
    }

    @Test fun slowDownPersistsAcrossStopAndResume() = runBlocking {
        val f = Fixture()
        val waiting = CompletableDeferred<Unit>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            f.poller(wait = { ms ->
                if (f.calls > 0) { waiting.complete(Unit); awaitCancellation() }
                f.time += ms
            }, response = { DeviceLinkResult.SlowDown }).resume(f.update)
        }
        waiting.await(); job.cancelAndJoin()
        assertEquals(10_000L, f.load()!!.intervalMs)
        assertTrue(f.poller(response = {
            assertTrue(f.time >= 16_000L)
            DeviceLinkResult.Success
        }).resume(f.update))
    }

    @Test fun transientNetworkFailureKeepsTheCodeAndRetries() = runBlocking {
        val f = Fixture()
        assertTrue(f.poller(response = {
            if (f.calls == 1) DeviceLinkResult.Retryable else {
                assertTrue(f.time >= 36_000L)
                assertEquals("AAA-BBB-CCC", f.load()!!.userCode)
                DeviceLinkResult.Success
            }
        }).resume(f.update))
        assertEquals(2, f.calls)
        assertTrue(f.messages.any { it.contains("Connection interrupted") })
    }

    @Test fun expiredGrantIsNotPolledOrDisplayedAsUsable() = runBlocking {
        val f = Fixture(); f.time = 100_001L
        assertFalse(f.poller().resume(f.update))
        assertEquals(0, f.calls)
        assertNull(f.load())
        assertTrue(f.messages.last().contains("Code expired"))
    }

    @Test fun denialClearsGrantInsteadOfRetryingForever() = runBlocking {
        val f = Fixture()
        assertFalse(f.poller(response = { DeviceLinkResult.Error("Access denied") }).resume(f.update))
        assertNull(f.load())
        assertEquals(1, f.calls)
    }

    @Test fun oldWaitCannotOverwriteOrRedeemAReplacementSession() = runBlocking {
        val f = Fixture()
        assertFalse(f.poller(wait = { ms ->
            f.time += ms
            f.file.writeText(f.original.copy(deviceCode = "replacement").encode())
        }).resume(f.update))
        assertEquals(0, f.calls)
        assertEquals("replacement", f.load()!!.deviceCode)
    }
}
