package com.hzzmonet.zkbomb.domain.recorder

/**
 * The recorder's three user-facing modes, in the order the UI presents them.
 *
 * The distinction between [ASK] and [AUTOMATIC] is the whole reason this is not
 * a boolean: on [ASK] nothing is captured until the user says yes for that call,
 * and the answer can be remembered per app; on [AUTOMATIC] a watched app's call
 * is recorded without a prompt. [OFF] arms nothing at all.
 */
enum class RecordingMode { OFF, ASK, AUTOMATIC }

/**
 * The audio system's mode, mapped from `AudioManager.MODE_*`.
 *
 * Only [IN_COMMUNICATION] means a VoIP call is up — it is what a VoIP app sets
 * while a call is connected. [IN_CALL] is a cellular call, a separate feature
 * path; the VoIP recorder deliberately does not arm on it. Everything else means
 * no call.
 */
enum class AudioCallMode {
    NORMAL,
    RINGTONE,
    IN_CALL,
    IN_COMMUNICATION,
    ;

    companion object {
        /** Maps the raw `AudioManager.getMode()` int, defaulting to [NORMAL]. */
        fun fromAudioManagerMode(mode: Int): AudioCallMode = when (mode) {
            // AudioManager.MODE_RINGTONE = 1
            1 -> RINGTONE
            // AudioManager.MODE_IN_CALL = 2
            2 -> IN_CALL
            // AudioManager.MODE_IN_COMMUNICATION = 3
            3 -> IN_COMMUNICATION
            else -> NORMAL
        }
    }
}

/**
 * What a capture probe found this device actually does.
 *
 * The lesson carried over from every other Bomb capability: "the API call
 * returned without throwing" is not "it works". A capture path that hands back
 * nothing but silence is [SILENT], and that is a refusal reason, not a success —
 * recording an hour of zeroes and calling it a saved call is the worst possible
 * outcome. Nothing is offered as recordable until a probe reports [SUPPORTED].
 */
enum class CaptureSupport {
    /** A probe captured real, non-silent audio. Only this state permits recording. */
    SUPPORTED,

    /** The path opened but delivered only silence — the HAL does not route call audio here. */
    SILENT,

    /** The OS refused the capture: a required privileged permission is not held. */
    DENIED,

    /** No capture path exists on this build (too old, or the audio API is missing). */
    UNAVAILABLE,

    /** Not checked yet. Treated as unusable until a probe runs. */
    UNPROBED,
    ;

    val isUsable: Boolean get() = this == SUPPORTED
}

/**
 * A recording container, with the real MIME/encoder facts an implementation
 * needs and the trade-off the user is actually choosing between.
 *
 * All three are producible on-device with no third-party library: AAC and Opus
 * through `MediaCodec` + `MediaMuxer`, WAV as PCM under a 44-byte header.
 */
enum class RecordingFormat(
    val displayName: String,
    val fileExtension: String,
    /** The `MediaCodec` MIME, or null for WAV which is not encoded. */
    val encoderMime: String?,
    /** Whether the stream is compressed. WAV is not. */
    val compressed: Boolean,
) {
    AAC_M4A("AAC (m4a)", "m4a", "audio/mp4a-latm", compressed = true),
    OPUS_OGG("Opus", "ogg", "audio/opus", compressed = true),
    WAV("WAV", "wav", encoderMime = null, compressed = false),
}

/**
 * How long finished recordings are kept before automatic deletion.
 *
 * [FOREVER] is modelled as a null cutoff rather than a huge number so the
 * retention math has one unambiguous "never delete" case instead of an arbitrary
 * horizon that eventually arrives.
 */
enum class RetentionPolicy(val displayName: String, val days: Int?) {
    DAYS_7("7 days", 7),
    DAYS_30("30 days", 30),
    DAYS_90("90 days", 90),
    FOREVER("Keep forever", null),
}

/**
 * Everything the policy needs to decide, in one immutable snapshot.
 *
 * Deliberately a plain data class of already-resolved facts: the foreground
 * package, the audio mode, the watched set, the user's mode and the probed
 * capture state. Nothing here reaches out to the system — the Android layer
 * gathers these and the decision stays a pure function of them, which is what
 * keeps [CallRecordingPolicy] unit-testable.
 */
data class CallSignal(
    /** The package currently in the foreground, or null if unknown/none. */
    val foregroundPackage: String?,
    val audioMode: AudioCallMode,
    /** Packages the user chose to watch. */
    val watched: Set<String>,
    val mode: RecordingMode,
    val capture: CaptureSupport,
)

/**
 * The single decision the recorder acts on, given a [CallSignal].
 *
 * Each variant is an instruction to the Android layer, not a description of a
 * screen. The layer never re-derives intent; it starts, stops, prompts or stands
 * by exactly as told. Splitting [Prompt] from [StartRecording] is what keeps the
 * consent gate in the policy where it can be tested, rather than in a UI branch
 * where it cannot.
 */
sealed interface RecorderDecision {

    /** Capture cannot work on this device; [reason] is user-facing. */
    data class Unsupported(val reason: String) : RecorderDecision

    /** Nothing to do: feature off, or no watched app in a call. */
    data object Standby : RecorderDecision

    /** A watched app is in the foreground but no call is active yet. */
    data class Armed(val packageName: String) : RecorderDecision

    /** ASK mode, a call is active, and the app has no remembered answer. */
    data class Prompt(val packageName: String) : RecorderDecision

    /** Begin capturing [packageName]'s call now. */
    data class StartRecording(val packageName: String) : RecorderDecision

    /** Already recording [packageName], and it should keep going. */
    data class ContinueRecording(val packageName: String) : RecorderDecision

    /** ASK mode with a remembered "no" for [packageName]: stay armed, do not record. */
    data class Suppressed(val packageName: String) : RecorderDecision

    /** A recording is in progress and must stop; [reason] is for the log/UI. */
    data class StopRecording(val reason: String) : RecorderDecision
}
