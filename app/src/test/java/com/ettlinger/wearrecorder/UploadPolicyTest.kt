package com.ettlinger.wearrecorder

import org.junit.Assert.*
import org.junit.Test

class UploadPolicyTest {
    @Test fun manualOnlyRejectsEveryAutomaticUploadButPermitsExplicitSync() {
        for (content in 0..2) for (logFile in listOf(false, true))
            assertFalse(UploadPolicy.mayUpload(UploadPolicy.MANUAL, false, content, logFile))
        assertTrue(UploadPolicy.mayUpload(UploadPolicy.MANUAL, true, 0, false))
        assertTrue(UploadPolicy.mayUpload(UploadPolicy.MANUAL, true, 0, true))
    }
    @Test fun contentSelectionAppliesEvenToManualSyncAndRetainsTheExcludedKind() {
        for (timing in 0..5) for (manual in listOf(false, true)) {
            assertFalse(UploadPolicy.mayUpload(timing, manual, 1, false))
            assertFalse(UploadPolicy.mayUpload(timing, manual, 2, true))
            if (UploadPolicy.mayRun(timing, manual)) {
                assertTrue(UploadPolicy.mayUpload(timing, manual, 1, true))
                assertTrue(UploadPolicy.mayUpload(timing, manual, 2, false))
            }
        }
    }
    @Test fun anInvalidatedManualGrantCannotRunEvenUnderAnAutomaticPolicy() {
        for (timing in 0..5) {
            assertFalse(UploadPolicy.mayRun(timing, manual = true, currentGrant = false))
            assertFalse(UploadPolicy.mayUpload(timing, true, 0, false, currentGrant = false))
        }
        assertTrue(UploadPolicy.mayRun(UploadPolicy.EVERY_15, manual = false, currentGrant = false))
    }
    @Test fun batchedPoliciesCannotAccidentallyKickPerSegmentUploads() {
        for (timing in listOf(UploadPolicy.EVERY_15, UploadPolicy.EVERY_30, UploadPolicy.EVERY_60, UploadPolicy.MANUAL))
            assertFalse(UploadPolicy.automaticKick(timing))
        assertTrue(UploadPolicy.automaticKick(UploadPolicy.IMMEDIATE))
        assertTrue(UploadPolicy.automaticKick(UploadPolicy.CHARGING))
        assertEquals(15L, UploadPolicy.intervalMinutes(UploadPolicy.EVERY_15))
        assertEquals(30L, UploadPolicy.intervalMinutes(UploadPolicy.EVERY_30))
        assertEquals(60L, UploadPolicy.intervalMinutes(UploadPolicy.EVERY_60))
    }
    @Test fun reducedDiagnosticsRetainEvidenceAndFailuresWithoutSpeechChatter() {
        for (tag in listOf("App", "Battery", "Settings", "Recorder", "Profile", "Service", "Test"))
            assertTrue(UploadPolicy.keepDiagnostic(1, "I", tag))
        for (tag in listOf("VAD", "Auth", "Upload", "UI")) {
            assertFalse(UploadPolicy.keepDiagnostic(1, "I", tag))
            assertTrue(UploadPolicy.keepDiagnostic(1, "W", tag))
            assertTrue(UploadPolicy.keepDiagnostic(1, "E", tag))
            assertTrue(UploadPolicy.keepDiagnostic(0, "I", tag))
        }
    }
    @Test fun togglingToManualOnlyClosesAdmissionForAnExistingAutomaticPass() {
        var timing = UploadPolicy.EVERY_15
        val requestAllowed = { UploadPolicy.mayUpload(timing, false, 0, false) }
        assertTrue(requestAllowed())
        timing = UploadPolicy.MANUAL
        assertFalse(requestAllowed())
    }
}
