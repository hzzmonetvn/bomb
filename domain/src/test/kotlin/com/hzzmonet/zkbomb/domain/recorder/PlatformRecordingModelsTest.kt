package com.hzzmonet.zkbomb.domain.recorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformRecordingModelsTest {
    @Test fun `cellular and VoIP require distinct active audio modes`() {
        assertTrue(
            PlatformRecordingPolicy.mayStart(
                RecordingKind.CELLULAR,
                AudioCallMode.IN_CALL,
                true,
                CaptureSupport.UNPROBED,
            ),
        )
        assertTrue(
            PlatformRecordingPolicy.mayStart(
                RecordingKind.VOIP,
                AudioCallMode.IN_COMMUNICATION,
                true,
                CaptureSupport.UNAVAILABLE,
            ),
        )
        assertFalse(
            PlatformRecordingPolicy.mayStart(
                RecordingKind.CELLULAR,
                AudioCallMode.IN_COMMUNICATION,
                true,
                CaptureSupport.UNPROBED,
            ),
        )
        assertTrue(
            PlatformRecordingPolicy.mayStart(
                RecordingKind.VOIP,
                AudioCallMode.IN_COMMUNICATION,
                true,
                CaptureSupport.SUPPORTED,
            ),
        )
    }

    @Test fun `silent framework capture is never classified as supported`() {
        assertEquals(CaptureSupport.SILENT, RecordingSignalClassifier.classify(true, true, 8_000))
        assertEquals(CaptureSupport.SILENT, RecordingSignalClassifier.classify(true, false, 100))
        assertEquals(CaptureSupport.SUPPORTED, RecordingSignalClassifier.classify(true, false, 800))
        assertEquals(CaptureSupport.UNAVAILABLE, RecordingSignalClassifier.classify(false, false, 800))
    }

    @Test fun `platform request is bounded and only advertises implemented container`() {
        val valid = PlatformRecordingRequest(
            "call-20260812-1",
            RecordingKind.CELLULAR,
            RecordingFormat.AAC_M4A,
        )
        assertTrue(PlatformRecordingRequestValidator.violations(valid).isEmpty())
        assertTrue(
            PlatformRecordingRequestValidator.violations(valid.copy(sessionId = "../escape")).isNotEmpty(),
        )
        assertTrue(
            PlatformRecordingRequestValidator.violations(valid.copy(format = RecordingFormat.WAV)).isNotEmpty(),
        )
    }
}
