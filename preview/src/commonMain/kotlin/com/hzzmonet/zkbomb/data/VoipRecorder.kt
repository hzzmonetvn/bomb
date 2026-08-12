package com.hzzmonet.zkbomb.data

import androidx.compose.runtime.Composable

/**
 * Whether call capture is usable on this build, as the UI sees it.
 *
 * A common-code mirror of the domain's `CaptureSupport`: the shared UI cannot
 * depend on an Android or JVM library type, so the state crosses as this enum and
 * the Android layer maps into it. Only [SUPPORTED] enables recording controls;
 * every other value disables them and shows [VoipRecorderState.supportDetail].
 */
enum class VoipCaptureSupport {
    SUPPORTED, SILENT, DENIED, UNAVAILABLE, UNPROBED,
    ;

    companion object {
        /** Maps the domain `CaptureSupport` name; an unknown name degrades to [UNPROBED]. */
        fun fromName(name: String): VoipCaptureSupport =
            entries.firstOrNull { it.name == name } ?: UNPROBED
    }
}

/** One finished recording, as listed on the recorder screen. */
data class VoipRecording(
    val id: String,
    val packageName: String,
    val startedAtEpochMillis: Long,
    val durationMillis: Long,
    val sizeBytes: Long,
    /** The capture came out silent — surfaced so the user is not misled that it worked. */
    val silent: Boolean,
)

/**
 * The recorder's live state, rendered by the UI and produced by the Android
 * layer. Off-platform (no Android under it) it stays at its defaults, which read
 * as "not supported, nothing recorded" — the honest degraded state.
 */
data class VoipRecorderState(
    val support: VoipCaptureSupport = VoipCaptureSupport.UNPROBED,
    val supportDetail: String? = null,
    /** The recorder is enabled and holding a capture grant (armed for calls). */
    val enabled: Boolean = false,
    val isRecording: Boolean = false,
    /** The package whose call is being recorded right now, or null. */
    val activePackage: String? = null,
    val recordings: List<VoipRecording> = emptyList(),
    val storageBytes: Long = 0,
    /** ASK mode: a watched app is in a call and the user has not answered yet. */
    val pendingPromptPackage: String? = null,
    /** The most recent user-facing error, or null. Cleared via the controller. */
    val error: String? = null,
) {
    val recordable: Boolean get() = support == VoipCaptureSupport.SUPPORTED
}

/**
 * Commands the recorder screen issues. Implementations do all heavy work off the
 * main thread; enabling additionally drives the platform capture-consent dialog,
 * which is why it is a command here rather than something the UI does directly.
 */
interface VoipRecorderController {
    /** Turn recording on: prompts for capture consent, then arms for [watched] apps. */
    fun enable(watched: List<String>, automatic: Boolean)

    /** Turn recording off and release the capture grant. */
    fun disable()

    /** Update the watched set / automatic flag while already enabled. */
    fun updateConfig(watched: List<String>, automatic: Boolean)

    /** Stop the recording in progress, keeping the recorder armed. */
    fun stopCurrent()

    /** Answer an ASK-mode prompt for [packageName]; [remember] stores it per app. */
    fun answerPrompt(packageName: String, record: Boolean, remember: Boolean)

    /** Delete a stored recording. */
    fun delete(id: String)

    /**
     * Delete recordings older than [days], or keep everything when null.
     *
     * Called by the screen when the retention setting or the recordings list
     * changes; the pure cutoff logic lives in the domain's RetentionEnforcer.
     */
    fun applyRetention(days: Int?)

    /** Re-run the capability probe (permission + microphone check). */
    fun probe()

    /** Clear [VoipRecorderState.error]. */
    fun clearError()
}

/** State plus the commands that act on it, returned by [rememberVoipRecorder]. */
data class VoipRecorderHandle(
    val state: VoipRecorderState,
    val controller: VoipRecorderController,
)

/**
 * Binds the call recorder for as long as the caller is composed.
 *
 * Mirrors [rememberBombService]: the shared UI depends only on this common
 * surface, and each platform provides the real implementation. The non-Android
 * default returns an inert handle whose state is "not supported".
 */
@Composable
expect fun rememberVoipRecorder(): VoipRecorderHandle
