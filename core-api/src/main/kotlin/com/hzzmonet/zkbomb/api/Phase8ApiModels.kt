package com.hzzmonet.zkbomb.api

import android.os.Parcelable
import com.hzzmonet.zkbomb.domain.recorder.PlatformRecordingRequest
import com.hzzmonet.zkbomb.domain.recorder.PlatformRecordingRequestValidator
import com.hzzmonet.zkbomb.domain.recorder.PlatformRecordingOutcome
import com.hzzmonet.zkbomb.domain.recorder.PlatformRecordingState
import com.hzzmonet.zkbomb.domain.recorder.CaptureSupport
import com.hzzmonet.zkbomb.domain.recorder.RecordingFormat
import com.hzzmonet.zkbomb.domain.recorder.RecordingKind
import kotlinx.parcelize.Parcelize

@Parcelize
data class RecordingRequestParcel(
    val sessionId: String,
    val kind: String,
    val format: String,
) : Parcelable {
    fun toDomain(): PlatformRecordingRequest? {
        val request = runCatching {
            PlatformRecordingRequest(
                sessionId,
                RecordingKind.valueOf(kind),
                RecordingFormat.valueOf(format),
            )
        }.getOrNull() ?: return null
        return request.takeIf { PlatformRecordingRequestValidator.violations(it).isEmpty() }
    }
}

/** Contains operational metadata only; no number, contact, app title, or output path. */
@Parcelize
data class RecordingBackendStatus(
    val state: String,
    val activeSessionId: String?,
    val activeKind: String?,
    val startedAtElapsedRealtimeMillis: Long?,
    val cellularSupport: String,
    val voipSupport: String,
    val capturePermissionHeld: Boolean,
    val activeClientSilenced: Boolean?,
    val lastOutcome: String?,
    val lastPeakAmplitude: Int?,
) : Parcelable {
    fun resolvedState(): PlatformRecordingState? =
        PlatformRecordingState.entries.firstOrNull { it.name == state }

    fun resolvedActiveKind(): RecordingKind? =
        activeKind?.let { value -> RecordingKind.entries.firstOrNull { it.name == value } }

    fun resolvedCellularSupport(): CaptureSupport? =
        CaptureSupport.entries.firstOrNull { it.name == cellularSupport }

    fun resolvedVoipSupport(): CaptureSupport? =
        CaptureSupport.entries.firstOrNull { it.name == voipSupport }

    fun resolvedLastOutcome(): PlatformRecordingOutcome? =
        lastOutcome?.let { value -> PlatformRecordingOutcome.entries.firstOrNull { it.name == value } }

    /** Rejects malformed or cross-version snapshots without inventing defaults. */
    fun isStructurallyValid(): Boolean {
        val resolved = resolvedState() ?: return false
        if (resolvedCellularSupport() == null || resolvedVoipSupport() == null) return false
        if (lastOutcome != null && resolvedLastOutcome() == null) return false
        if (lastPeakAmplitude != null && lastPeakAmplitude !in 0..32_767) return false
        val activeMetadataPresent = activeSessionId != null &&
            PlatformRecordingRequestValidator.isValidSessionId(activeSessionId) &&
            resolvedActiveKind() != null && startedAtElapsedRealtimeMillis != null
        return when (resolved) {
            PlatformRecordingState.IDLE ->
                activeSessionId == null && activeKind == null &&
                    startedAtElapsedRealtimeMillis == null && activeClientSilenced == null
            PlatformRecordingState.RECORDING,
            PlatformRecordingState.STOPPING,
            -> activeMetadataPresent && startedAtElapsedRealtimeMillis!! >= 0
        }
    }
}
