package com.hzzmonet.zkbomb.api

import com.hzzmonet.zkbomb.domain.recorder.RecordingFormat
import com.hzzmonet.zkbomb.domain.recorder.RecordingKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase8ApiModelsTest {
    @Test fun `recording request uses enum names and bounded session id`() {
        val parcel = RecordingRequestParcel(
            "call-1",
            RecordingKind.CELLULAR.name,
            RecordingFormat.AAC_M4A.name,
        )
        assertEquals(RecordingKind.CELLULAR, parcel.toDomain()?.kind)
        assertNull(parcel.copy(kind = "UNKNOWN").toDomain())
        assertNull(parcel.copy(sessionId = "../call").toDomain())
        assertNull(parcel.copy(format = RecordingFormat.WAV.name).toDomain())
    }

    @Test fun `recording status resolves enum names without unsafe defaults`() {
        val status = RecordingBackendStatus(
            state = "RECORDING",
            activeSessionId = "call-1",
            activeKind = "VOIP",
            startedAtElapsedRealtimeMillis = 10,
            cellularSupport = "UNPROBED",
            voipSupport = "SUPPORTED",
            capturePermissionHeld = true,
            activeClientSilenced = false,
            lastOutcome = null,
            lastPeakAmplitude = null,
        )

        assertEquals(RecordingKind.VOIP, status.resolvedActiveKind())
        assertTrue(status.isStructurallyValid())
        assertNull(status.copy(state = "FUTURE_STATE").resolvedState())
        assertFalse(status.copy(state = "FUTURE_STATE").isStructurallyValid())
    }

    @Test fun `idle status cannot retain active-session metadata`() {
        val idle = RecordingBackendStatus(
            state = "IDLE",
            activeSessionId = null,
            activeKind = null,
            startedAtElapsedRealtimeMillis = null,
            cellularSupport = "UNPROBED",
            voipSupport = "UNPROBED",
            capturePermissionHeld = true,
            activeClientSilenced = null,
            lastOutcome = "COMPLETED",
            lastPeakAmplitude = 900,
        )

        assertTrue(idle.isStructurallyValid())
        assertFalse(idle.copy(activeSessionId = "stale").isStructurallyValid())
        assertFalse(idle.copy(lastPeakAmplitude = -1).isStructurallyValid())
    }
}
