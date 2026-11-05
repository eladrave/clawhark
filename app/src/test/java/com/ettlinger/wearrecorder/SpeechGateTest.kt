package com.ettlinger.wearrecorder

import org.junit.Assert.*
import org.junit.Test

class SpeechGateTest {
    private class Sink {
        val audio = mutableListOf<Short>()
        val starts = mutableListOf<Long>()
        var ends = 0
        val gate = SpeechGate({ it[0].toInt() == 7 }, { pcm, n, at ->
            if (audio.isEmpty()) starts.add(at)
            audio.addAll(pcm.take(n))
        }, { ends++ }, { _, _ -> })
        var sample = 0
        fun frame(value: Int) { gate.accept(ShortArray(320) { value.toShort() }, 320, 1_000L + sample * 1000L / 16000); sample += 320 }
    }
    @Test fun longSilenceDoesNotProduceFilesOrPcm() {
        val s = Sink(); repeat(500) { s.frame(0) }; s.gate.finish()
        assertTrue(s.audio.isEmpty()); assertEquals(0, s.ends)
    }
    @Test fun speechRetainsOneSecondOfContextAndTwoSecondsOfTail() {
        val s = Sink(); repeat(100) { s.frame(0) }; repeat(10) { s.frame(7) }; repeat(110) { s.frame(0) }
        // Two onset frames: buffer includes 49 silent frames and the first speech frame.
        assertEquals((49 + 10 + 100) * 320, s.audio.size)
        assertEquals(2020L, s.starts.single())
        assertEquals(1, s.ends)
        assertEquals(10 * 320, s.audio.count { it.toInt() == 7 })
    }
    @Test fun shortPauseKeepsOneUtteranceAndAllWords() {
        val s = Sink(); repeat(3) { s.frame(7) }; repeat(50) { s.frame(0) }; repeat(3) { s.frame(7) }
        s.gate.finish()
        assertEquals(56 * 320, s.audio.size); assertEquals(1, s.ends)
    }
    @Test fun arbitraryInputReadBoundariesAndFinalPartialAreNotDropped() {
        val out = mutableListOf<Short>()
        val source = ShortArray(320 * 3 + 77) { (it % 100).toShort() }
        val gate = SpeechGate({ true }, { pcm, n, _ -> out.addAll(pcm.take(n)) }, {}, { _, _ -> })
        gate.accept(source.copyOfRange(0, 137), 137, 1000)
        gate.accept(source.copyOfRange(137, source.size), source.size - 137, 1008)
        gate.finish()
        assertArrayEquals(source, out.toShortArray())
    }
    @Test fun classifierFailureFailsOpenIncludingBufferedContext() {
        val out = mutableListOf<Short>(); var frames = 0; var fallback = false
        val gate = SpeechGate({ if (++frames >= 5) error("detector failed") else false },
            { pcm, n, _ -> out.addAll(pcm.take(n)) }, {}, { _, failed -> fallback = failed })
        val input = ShortArray(320 * 10) { 2 }
        gate.accept(input, input.size, 1000); gate.finish()
        assertTrue(fallback); assertTrue(gate.failedOpen); assertArrayEquals(input, out.toShortArray())
    }
    @Test fun isolatedPositiveFrameDoesNotOpenStorage() {
        val s = Sink(); repeat(10) { s.frame(0); s.frame(7); s.frame(0) }
        s.gate.finish(); assertTrue(s.audio.isEmpty())
    }
    private data class Trace(
        val audio: List<Short>, val timestamps: List<Long>, val lengths: List<Int>,
        val states: List<Pair<Boolean, Boolean>>, val ends: Int, val failedOpen: Boolean
    )

    private fun variedReadTrace(reuse: Boolean, failAt: Int? = null, interrupt: Boolean = false): Trace {
        val audio = mutableListOf<Short>()
        val timestamps = mutableListOf<Long>()
        val lengths = mutableListOf<Int>()
        val states = mutableListOf<Pair<Boolean, Boolean>>()
        var ends = 0
        var calls = 0
        val gate = SpeechGate({ frame ->
            if (++calls == failAt) throw UnsatisfiedLinkError("detector unavailable")
            frame[0].toInt() % 200 in 80..100
        }, { pcm, n, at ->
            audio.addAll(pcm.take(n)); timestamps.add(at); lengths.add(n)
        }, { ends++ }, { active, fallback -> states.add(active to fallback) }, reuseBuffers = reuse)
        // Unique PCM throughout each frame detects stale data and aliased pre-roll.
        val source = ShortArray(320 * 700 + 97) { sample ->
            val frame = sample / 320
            if (sample % 320 == 0) frame.toShort() else (frame * 31 + sample % 320).toShort()
        }
        val chunks = intArrayOf(137, 4096, 17, 640, 319, 1, 999)
        var offset = 0
        var next = 0
        var interrupted = false
        while (offset < source.size) {
            val n = minOf(chunks[next++ % chunks.size], source.size - offset)
            val chunk = source.copyOfRange(offset, offset + n)
            gate.accept(chunk, n, 10_000L + offset * 1000L / 16000)
            offset += n
            if (interrupt && !interrupted && offset > 320 * 95) {
                gate.finish()
                interrupted = true
            }
        }
        gate.finish()
        return Trace(audio, timestamps, lengths, states, ends, gate.failedOpen)
    }

    @Test fun pooledFramesMatchBaselinePcmTimingAndUtteranceBoundaries() {
        assertEquals(variedReadTrace(false), variedReadTrace(true))
    }

    @Test fun pooledFramesMatchBaselineWhenDetectorFailsOpen() {
        val optimized = variedReadTrace(true, failAt = 189)
        assertTrue(optimized.failedOpen)
        assertEquals(variedReadTrace(false, failAt = 189), optimized)
    }

    @Test fun pooledFramesMatchBaselineAfterInterruptedFinishAndResume() {
        assertEquals(variedReadTrace(false, interrupt = true), variedReadTrace(true, interrupt = true))
    }

    @Test fun poolRemainsBoundedAcrossSilenceSpeechAndTailTransitions() {
        val identities = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<ShortArray, Boolean>())
        var calls = 0
        val gate = SpeechGate({ frame ->
            identities.add(frame)
            ++calls % 400 in 200..210
        }, { _, _, _ -> }, {}, { _, _ -> }, reuseBuffers = true)
        repeat(5_000) { gate.accept(ShortArray(320), 320, it * 20L) }
        gate.finish()
        assertEquals(5_000, calls)
        // Fifty pre-roll frames plus the frame being filled, independent of trial length.
        assertTrue("Unbounded frame allocation: ${identities.size}", identities.size <= 51)
    }

}
