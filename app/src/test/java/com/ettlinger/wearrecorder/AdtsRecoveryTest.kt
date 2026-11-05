package com.ettlinger.wearrecorder

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class AdtsRecoveryTest {
    private fun directory() = Files.createTempDirectory("rave-adts").toFile().apply { deleteOnExit() }
    private fun frame(n: Int = 25, value: Int = 4) = AdtsRecovery.header(n) + ByteArray(n) { value.toByte() }
    private val sync = RecordingStore.DirectorySync { }
    @Test fun completeFramesHaveExpectedLcMono16kHeader() {
        val dir = directory(); val part = File(dir, "one.aac.part")
        val bytes = frame(25) + frame(19)
        part.writeBytes(bytes)
        assertArrayEquals(byteArrayOf(0xff.toByte(), 0xf1.toByte(), 0x60, 0x40, 0x04, 0x1f, 0xfc.toByte()), AdtsRecovery.header(25))
        assertEquals(bytes.size.toLong(), AdtsRecovery.completePrefix(part))
        val sealed = AdtsRecovery.recover(part, sync)!!
        assertFalse(part.exists()); assertEquals("one.aac", sealed.name); assertArrayEquals(bytes, sealed.readBytes())
    }
    @Test fun everyTornFinalHeaderAndPayloadRetainsExactlyTheCompletePrefix() {
        val dir = directory(); val prefix = frame(22) + frame(31); val tail = frame(48)
        for (cut in tail.indices) {
            val part = File(dir, "torn-$cut.aac.part")
            part.writeBytes(prefix + tail.copyOf(cut))
            val recovered = AdtsRecovery.recover(part, sync)!!
            assertArrayEquals("cut=$cut", prefix, recovered.readBytes())
        }
    }
    @Test fun malformedInteriorHeaderQuarantinesOriginalWithoutResynchronizing() {
        val dir = directory(); val part = File(dir, "corrupt.aac.part")
        val original = frame() + ByteArray(8) { 0x77 } + frame()
        part.writeBytes(original)
        assertNull(AdtsRecovery.recover(part, sync))
        assertFalse(File(dir, "corrupt.aac").exists())
        val quarantined = dir.listFiles()!!.single()
        assertTrue(quarantined.name.startsWith("corrupt.aac.part.damaged-"))
        assertArrayEquals(original, quarantined.readBytes())
    }
    @Test fun emptyAndHeaderOnlyPartsAreRetainedInQuarantine() {
        val dir = directory()
        for ((name, bytes) in listOf("empty" to byteArrayOf(), "header" to AdtsRecovery.header(9))) {
            val part = File(dir, "$name.aac.part"); part.writeBytes(bytes)
            assertNull(AdtsRecovery.recover(part, sync))
            val quarantined = dir.listFiles()!!.single { it.name.startsWith("$name.aac.part.damaged-") }
            assertArrayEquals(bytes, quarantined.readBytes())
        }
    }
    @Test fun differentRateStereoOrProfileAreNotPublishedAsSupportedAudio() {
        val dir = directory()
        for ((index, alter) in listOf<(ByteArray) -> Unit>(
            { it[2] = (it[2].toInt() xor 4).toByte() },
            { it[3] = (it[3].toInt() xor 0xc0).toByte() },
            { it[2] = (it[2].toInt() xor 0x40).toByte() },
            { it[1] = 0xf0.toByte() }
        ).withIndex()) {
            val part = File(dir, "unsupported-$index.aac.part")
            part.writeBytes(frame().apply(alter))
            assertNull(AdtsRecovery.recover(part, sync))
            assertFalse(File(dir, "unsupported-$index.aac").exists())
        }
    }
    @Test fun existingPublishedFileIsNeverReplaced() {
        val dir = directory(); val part = File(dir, "collision.aac.part")
        part.writeBytes(frame()); val target = File(dir, "collision.aac").apply { writeText("original") }
        try { AdtsRecovery.recover(part, sync); fail("Must preserve collision") } catch (_: IOException) { }
        assertTrue(part.exists()); assertEquals("original", target.readText())
    }
    @Test fun directoryRecoveryOnlyVisitsOwnedAacParts() {
        val dir = directory(); File(dir, "recover.aac.part").writeBytes(frame())
        val unrelated = File(dir, "unrelated.wav.part").apply { writeText("untouched") }
        assertEquals(1, AdtsRecovery.recoverDirectory(dir, sync))
        assertEquals("untouched", unrelated.readText())
        assertEquals(0, AdtsRecovery.recoverDirectory(dir, sync))
    }
    @Test fun adtsSizeLimitsCannotProduceTruncatedHeaderLengths() {
        for (size in listOf(0, -1, 8185, Int.MAX_VALUE)) {
            try { AdtsRecovery.header(size); fail("Invalid size $size") } catch (_: IOException) { }
        }
        assertEquals(7, AdtsRecovery.header(8184).size)
    }
}
