package com.ettlinger.wearrecorder

import org.junit.Assert.*
import org.junit.Test

class RecorderAttentionUiTest {
    @Test fun terminalErrorTakesPriorityOverAnOlderRecoveryReason() {
        val display = RecorderAttentionUi.resolve(true, "RECOVERING MIC", "RECOVERING MIC",
            "Microphone permission was revoked", "Microphone interrupted; retrying")
        assertEquals("NEEDS ATTENTION", display.label)
        assertEquals("Microphone permission was revoked", display.reason)
        assertTrue(display.stopAction)
        assertFalse(display.recordingActive)
    }

    @Test fun recoveryRemainsStoppableBeforeTheServiceHasBound() {
        val display = RecorderAttentionUi.resolve(true, "RECOVERING MIC", null, null,
            "Microphone interrupted; retrying")
        assertEquals("RECOVERING MIC", display.label)
        assertEquals("Microphone interrupted; retrying", display.reason)
        assertTrue(display.stopAction)
        assertFalse(display.recordingActive)
    }

    @Test fun aPersistedRequestAloneDoesNotClaimLiveRecording() {
        val display = RecorderAttentionUi.resolve(true, "CHECK RECORDER", null, null, "Last recovery attempt")
        assertEquals("CHECK RECORDER", display.label)
        assertTrue(display.stopAction)
        assertFalse(display.recordingActive)
    }

    @Test fun anIdleBindingWhileAStartIsPendingStillAllowsStopWithoutClaimingAudio() {
        val display = RecorderAttentionUi.resolve(true, "STOPPED", "STOPPED", null, null)
        assertEquals("STARTING", display.label)
        assertTrue(display.stopAction)
        assertFalse(display.recordingActive)
    }

    @Test fun resumedAudioClearsTheWarningEvenIfAnOldRecoveryValueIsStillRead() {
        for (state in listOf("RECORDING", "LISTENING")) {
            val display = RecorderAttentionUi.resolve(true, state, state, null, "Old recovery reason")
            assertEquals(state, display.label)
            assertNull(display.reason)
            assertTrue(display.stopAction)
        }
    }

    @Test fun stoppedCaptureDoesNotExposeAnOldRecoveryMessageOrAStopAction() {
        val display = RecorderAttentionUi.resolve(false, "STOPPED", null, null, "Old recovery reason")
        assertEquals("STOPPED", display.label)
        assertNull(display.reason)
        assertFalse(display.stopAction)
    }
}
