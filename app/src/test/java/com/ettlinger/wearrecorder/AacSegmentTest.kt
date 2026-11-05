package com.ettlinger.wearrecorder

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.ArrayDeque

/** Fake codec tests exercise backpressure, EOS, bounded finalization and spool behavior.
 * They do not qualify the watch's actual MediaCodec implementation.
 */
class AacSegmentTest {
    private class FakeEncoder : AacSegment.Encoder {
        val output = ArrayDeque<AacSegment.Packet>()
        val received = mutableListOf<Short>()
        val pts = mutableListOf<Long>()
        var closed = false
        var offers = 0
        var ends = 0
        var stallInput = false
        var stallEnd = false
        var throwOutput = false
        override fun offer(samples: ShortArray, offset: Int, count: Int, presentationUs: Long): Int {
            if (stallInput) return 0
            if (++offers % 2 == 1) return 0 // real codecs can briefly lack an input buffer
            val n = minOf(37, count)
            pts.add(presentationUs); received.addAll(samples.copyOfRange(offset, offset + n).toList())
            output.add(AacSegment.Packet(byteArrayOf(7, 8, 9)))
            return n
        }
        override fun offerEnd(presentationUs: Long): Boolean {
            ends++
            if (stallEnd) return false
            // Nonempty EOS output must be retained, not dropped before recognizing EOS.
            output.add(AacSegment.Packet(byteArrayOf(4, 5), eos = true))
            return true
        }
        override fun poll(): AacSegment.Packet? {
            if (throwOutput) throw IOException("codec failed")
            return output.pollFirst()
        }
        override fun close() { closed = true }
    }
    private data class Fixture(val store: RecordingStore, val codec: FakeEncoder, val segment: AacSegment)
    private fun fixture(): Fixture {
        val store = RecordingStore(Files.createTempDirectory("rave-aac").toFile(), RecordingStore.DirectorySync { })
        val codec = FakeEncoder(); var ticks = 0L
        val segment = AacSegment(store, 1_000, { ticks += 20; ticks }, { codec })
        return Fixture(store, codec, segment)
    }
    @Test fun backpressurePreservesInputPtsAndWritesDataCarriedByEos() {
        val f = fixture(); val pcm = ShortArray(103) { (it * 17).toShort() }
        f.codec.output.add(AacSegment.Packet(byteArrayOf(1, 2), config = true))
        f.segment.append(pcm, pcm.size); f.segment.checkpoint()
        val sealed = f.segment.seal()!!
        assertEquals(103L, f.segment.inputSamples)
        assertArrayEquals(pcm, f.codec.received.toShortArray())
        assertEquals(listOf(0L, 2312L, 4625L), f.codec.pts)
        assertEquals(1, f.codec.ends); assertTrue(f.codec.closed)
        assertEquals(39L, sealed.length()) // 3x(7+3), plus final(7+2), excludes codec config
        assertEquals(sealed.length(), AdtsRecovery.completePrefix(sealed))
        assertArrayEquals(byteArrayOf(4, 5), sealed.readBytes().takeLast(2).toByteArray())
        assertEquals(sealed, f.segment.seal())
    }
    @Test fun zeroInputIsNeverPublishedAsAnAudioFile() {
        val f = fixture()
        assertNull(f.segment.seal()); assertTrue(f.codec.closed)
        assertTrue(f.store.directory().listFiles()!!.none { it.name.endsWith(".aac") })
    }
    @Test fun inputStallIsBoundedAndClosePreservesPartial() {
        val f = fixture(); f.codec.stallInput = true
        try { f.segment.append(ShortArray(20), 20); fail("Expected timeout") } catch (_: IOException) { }
        f.segment.close(); assertTrue(f.codec.closed)
        assertTrue(f.store.directory().listFiles()!!.any { it.name.endsWith(".aac.part") })
    }
    @Test fun eosStallIsBoundedAndEmittedAudioCanBeRecovered() {
        val f = fixture(); f.segment.append(ShortArray(40), 40); f.codec.stallEnd = true
        try { f.segment.seal(); fail("Expected EOS timeout") } catch (_: IOException) { }
        assertTrue(f.codec.closed)
        assertTrue(f.store.directory().listFiles()!!.none { it.name.endsWith(".aac") })
        assertEquals(1, AdtsRecovery.recoverDirectory(f.store.directory(), RecordingStore.DirectorySync { }))
    }
    @Test fun encoderFailureNeverPublishesUnverifiedContainer() {
        val f = fixture(); f.segment.append(ShortArray(40), 40)
        f.codec.throwOutput = true
        try { f.segment.append(ShortArray(40), 40); fail("Expected codec error") } catch (_: IOException) { }
        f.codec.throwOutput = false // Even a recovered codec cannot turn this failed trial into normal finalization.
        try { f.segment.seal(); fail("Failed append must poison publication") } catch (_: IOException) { }
        assertTrue(f.codec.closed)
        assertTrue(f.store.directory().listFiles()!!.none { it.name.endsWith(".aac") })
        assertEquals(1, AdtsRecovery.recoverDirectory(f.store.directory(), RecordingStore.DirectorySync { }))
    }
    @Test fun factoryFailurePrecedesPartialCreation() {
        val store = RecordingStore(Files.createTempDirectory("rave-aac-init").toFile(), RecordingStore.DirectorySync { })
        try { AacSegment(store, 1000, { 0L }, { throw IOException("unsupported codec") }); fail("Expected startup error") }
        catch (_: IOException) { }
        assertTrue(store.directory().listFiles()!!.isEmpty())
    }
}
