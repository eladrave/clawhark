package com.ettlinger.wearrecorder

import org.junit.Assert.*
import org.junit.Test

class PowerOptionsTest {
    @Test fun defaultsRetainOriginalDurableCapture() {
        val baseline = PowerOptions()
        assertEquals(PowerOptions.WAV, baseline.audioFormat)
        assertTrue(baseline.captureWakeLock)
        assertFalse(baseline.efficientUploads)
        assertFalse(baseline.reuseSpeechBuffers)
    }
    @Test fun efficientPresetDoesNotChangeCaptureFormatOrWakeLock() {
        val options = PowerOptions.EFFICIENT
        assertTrue(options.efficientUploads && options.reuseSpeechBuffers)
        assertEquals(PowerOptions.WAV, options.audioFormat)
        assertTrue(options.captureWakeLock)
        options.checkCapture(true)
    }
    @Test fun aacCannotAccidentallyRunOnSpeechClipBoundaries() {
        val options = PowerOptions(audioFormat = PowerOptions.AAC)
        options.checkCapture(false)
        try { options.checkCapture(true); fail("AAC speech combination accepted") }
        catch (expected: IllegalArgumentException) { assertTrue(expected.message!!.contains("Continuous")) }
    }
    @Test fun diagnosticsIdentifyIndependentExperimentalSettings() {
        val options = PowerOptions(efficientUploads = true, captureWakeLock = false, audioFormat = PowerOptions.AAC)
        assertTrue(options.diagnostic.contains("capture_wake_lock=false"))
        assertTrue(options.diagnostic.contains("audio_format=aac_lc_adts_24k"))
        assertEquals("Custom experiments", options.label)
    }
}
