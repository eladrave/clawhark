package com.ettlinger.wearrecorder

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.nio.file.Files
import java.util.ArrayDeque

class RecoveringMicrophoneTest {
    private data class Event(val value: Short = 7, val muted: Boolean = false, val count: Int = 4)
    private class Fake(vararg events: Event) : RecoveringMicrophone.Input {
        val events = ArrayDeque(events.toList())
        var current = Event()
        var reads = 0; var closed = false; var stops = 0
        override fun read(samples: ShortArray): Int {
            reads++; current = events.removeFirst()
            if (current.count > 0) samples.fill(current.value)
            return current.count
        }
        override fun silenced() = current.muted
        override fun stop() { stops++ }
        override fun close() { closed = true }
    }
    private class Fixture {
        var wanted = true; var now = 100L; var opens = 0
        val delays = mutableListOf<Long>()
        val notices = mutableListOf<RecoveringMicrophone.Waiting>()
        fun engine(open: () -> RecoveringMicrophone.Input, wait: suspend (Long) -> Unit = { now += it; delays.add(it) },
            notice: (RecoveringMicrophone.Waiting) -> Unit = { notices.add(it) }) =
            RecoveringMicrophone({ opens++; open() }, { wanted }, { now }, notice, wait)
    }
    @Test fun mutedInputIsDiscardedAndSameSessionResumesWithoutAnotherStart() = runBlocking {
        val f = Fixture(); val input = Fake(Event(1, true), Event(2, true), Event(9))
        val engine = f.engine({ input }); val samples = ShortArray(4)
        val result = engine.next(samples)!!
        assertEquals(4, result.samples); assertEquals(2_000L, result.gapMs)
        assertArrayEquals(shortArrayOf(9, 9, 9, 9), samples)
        assertEquals(1, f.opens); assertEquals(1, f.notices.size)
        assertTrue(f.notices.single().first); assertTrue(f.wanted)
        engine.close(); assertTrue(input.closed)
    }
    @Test fun stopWhileMutedCannotResumeOrReopen() = runBlocking {
        val f = Fixture(); val input = Fake(Event(muted = true), Event())
        val engine = f.engine({ input }, wait = { f.wanted = false })
        assertNull(engine.next(ShortArray(4)))
        assertEquals(1, input.reads); assertEquals(1, f.opens)
        engine.close(); assertTrue(input.closed)
    }
    @Test fun stopDuringOpenDisposesTheNewInputBeforeAnyRead() = runBlocking {
        val f = Fixture(); val input = Fake(Event())
        val engine = f.engine({ f.wanted = false; input })
        assertNull(engine.next(ShortArray(4)))
        assertEquals(0, input.reads); assertTrue(input.closed)
    }
    @Test fun stopDuringRetryDelayPreventsAnotherOpen() = runBlocking {
        val f = Fixture()
        val engine = f.engine({ throw IllegalStateException("mic busy") }, wait = { f.wanted = false })
        assertNull(engine.next(ShortArray(4))); assertEquals(1, f.opens)
    }
    @Test fun deadInputIsReleasedAndReopenedWithBoundedDelay() = runBlocking {
        val f = Fixture(); val dead = Fake(Event(count = -6)); val alive = Fake(Event(12))
        val engine = f.engine({ if (f.opens == 1) dead else alive })
        val result = engine.next(ShortArray(4))!!
        assertTrue(dead.closed); assertEquals(2, f.opens)
        assertEquals(listOf(1_000L), f.delays); assertEquals(1_000L, result.gapMs)
        engine.close()
    }
    @Test fun repeatedOpenFailuresBackOffAndStayStoppable() = runBlocking {
        val f = Fixture()
        val engine = f.engine({ throw IllegalStateException("busy") }, wait = {
            f.delays.add(it); f.now += it; if (f.delays.size == 7) f.wanted = false
        })
        assertNull(engine.next(ShortArray(4)))
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), f.delays)
        assertEquals(7, f.opens)
    }
    @Test fun permissionDenialRemainsActionableRatherThanRetryingForever() = runBlocking {
        val f = Fixture(); val engine = f.engine({ throw SecurityException("permission") })
        try { engine.next(ShortArray(4)); fail("Expected permission error") } catch (_: SecurityException) { }
        assertEquals(1, f.opens); assertTrue(f.delays.isEmpty())
    }
    @Test fun cancellationDuringRecoveryDelayIsPromptAndRetainsNoInputOwner() = runBlocking {
        val f = Fixture(); val input = Fake(Event(muted = true))
        val paused = CompletableDeferred<Unit>()
        val engine = f.engine({ input }, wait = { paused.complete(Unit); awaitCancellation() })
        val job = launch { try { engine.next(ShortArray(4)) } finally { engine.close() } }
        withTimeout(1_000) { paused.await(); f.wanted = false; job.cancelAndJoin() }
        assertEquals(1, f.opens); assertTrue(input.closed)
    }
    @Test fun storageFailureInInterruptionCallbackIsNeverHiddenAsAMicrophoneRetry() = runBlocking {
        val f = Fixture(); val input = Fake(Event(muted = true))
        val engine = f.engine({ input }, notice = { throw IOException("storage full") })
        try { engine.next(ShortArray(4)); fail("Expected storage failure") }
        catch (e: IOException) { assertEquals("storage full", e.message) }
        assertEquals(1, f.opens); assertTrue(f.delays.isEmpty()); engine.close()
    }
    @Test fun stalledReadWatchdogUnblocksAndReopensWithoutClearingIntent() = runBlocking {
        val f = Fixture(); val first = Fake(Event(), Event()); val second = Fake(Event(11))
        val engine = f.engine({ if (f.opens == 1) first else second })
        assertNotNull(engine.next(ShortArray(4)))
        f.now += 10_001
        assertTrue(engine.retryStalledRead(f.now)); assertFalse(engine.retryStalledRead(f.now))
        assertNotNull(engine.next(ShortArray(4)))
        assertTrue(first.closed); assertEquals(2, f.opens); assertTrue(f.wanted); engine.close()
    }
    @Test fun longMutePublishesThrottledRecoveryHeartbeatsAndThenClearsGap() = runBlocking {
        val f = Fixture(); val events = List(65) { Event(muted = true) } + Event(9) + Event(10)
        val engine = f.engine({ Fake(*events.toTypedArray()) })
        assertEquals(65_000L, engine.next(ShortArray(4))!!.gapMs)
        assertEquals(listOf(0L, 30_000L, 60_000L), f.notices.map { it.elapsedMs })
        assertNull(engine.next(ShortArray(4))!!.gapMs); engine.close()
    }
    @Test fun stalledReplacementInputCanStillBeRecoveredWhileGapIsActive() = runBlocking {
        val f = Fixture(); val dead = Fake(Event(count = -6)); val alive = Fake(Event(11))
        var replacementStopped = false
        lateinit var engine: RecoveringMicrophone
        engine = f.engine({ when (f.opens) {
            1 -> dead
            2 -> object : RecoveringMicrophone.Input {
                override fun read(samples: ShortArray): Int {
                    f.now += 10_001
                    assertTrue("A pending gap must not suppress the watchdog", engine.retryStalledRead(f.now))
                    return 0
                }
                override fun silenced() = false
                override fun stop() { replacementStopped = true }
                override fun close() = Unit
            }
            else -> alive
        } })
        assertNotNull(engine.next(ShortArray(4)))
        assertTrue(replacementStopped); assertEquals(3, f.opens); engine.close()
    }
    @Test fun interruptedAudioSealsBeforeResumeAndMutedSamplesNeverReachTheSpool() = runBlocking {
        val f = Fixture(); val store = RecordingStore(Files.createTempDirectory("rave-mic-gap").toFile())
        var segment: RecordingStore.Segment? = null
        val engine = f.engine({ Fake(Event(1), Event(2, true), Event(3)) }, notice = {
            if (it.first) { segment?.seal(); segment = null }
        })
        val samples = ShortArray(4)
        repeat(2) {
            val read = engine.next(samples)!!
            if (segment == null) segment = store.begin(f.now)
            val bytes = ByteArray(read.samples * 2)
            for (n in samples.indices) { bytes[2*n] = samples[n].toByte(); bytes[2*n+1] = 0 }
            segment!!.append(bytes, bytes.size)
        }
        segment!!.seal(); engine.close()
        val files = store.pending(); assertEquals(2, files.size)
        assertEquals(listOf(1, 3), files.map { it.readBytes()[44].toInt() }.sorted())
        assertTrue(files.all { it.length() == 52L })
    }
}
