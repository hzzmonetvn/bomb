package com.hzzmonet.zkbomb.domain.recorder

enum class RecordingKind { CELLULAR, VOIP }

enum class PlatformRecordingState { IDLE, RECORDING, STOPPING }

enum class PlatformRecordingOutcome { COMPLETED, SILENT, FAILED }

data class PlatformRecordingRequest(
    val sessionId: String,
    val kind: RecordingKind,
    val format: RecordingFormat,
)

object PlatformRecordingRequestValidator {
    private val SESSION_ID = Regex("^[A-Za-z0-9._-]{1,96}$")

    fun isValidSessionId(sessionId: String): Boolean = SESSION_ID.matches(sessionId)

    fun violations(request: PlatformRecordingRequest): List<String> = buildList {
        if (!isValidSessionId(request.sessionId)) add("sessionId")
        if (request.format != RecordingFormat.AAC_M4A) {
            add("platform backend supports AAC_M4A only")
        }
    }
}

/** A call kind is recordable only while its matching platform audio mode is active. */
object PlatformRecordingPolicy {
    fun expectedMode(kind: RecordingKind): AudioCallMode = when (kind) {
        RecordingKind.CELLULAR -> AudioCallMode.IN_CALL
        RecordingKind.VOIP -> AudioCallMode.IN_COMMUNICATION
    }

    fun mayStart(
        kind: RecordingKind,
        audioMode: AudioCallMode,
        capturePermissionHeld: Boolean,
        knownSupport: CaptureSupport,
    ): Boolean = capturePermissionHeld &&
        audioMode == expectedMode(kind) &&
        knownSupport != CaptureSupport.SILENT
}

object RecordingSignalClassifier {
    const val SILENCE_PEAK_THRESHOLD = 500

    fun classify(completed: Boolean, clientSilenced: Boolean, peakAmplitude: Int): CaptureSupport =
        when {
            !completed -> CaptureSupport.UNAVAILABLE
            clientSilenced || peakAmplitude < SILENCE_PEAK_THRESHOLD -> CaptureSupport.SILENT
            else -> CaptureSupport.SUPPORTED
        }
}
