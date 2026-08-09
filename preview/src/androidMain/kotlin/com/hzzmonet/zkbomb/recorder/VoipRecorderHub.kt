package com.hzzmonet.zkbomb.recorder

import com.hzzmonet.zkbomb.data.VoipCaptureSupport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Process-wide bridge from [CallRecordingService] to the UI.
 *
 * The service and the composable that renders the recorder run in the same
 * process but have no direct reference to each other — the service is started and
 * commanded through Intents, and reports back by writing here. The UI collects
 * [state]; the service is the only writer.
 *
 * A plain object rather than a bound service on purpose: the state is small,
 * read-mostly, and needs to outlive any single screen so a recording that starts
 * on one screen is still visible after navigating away and back. It holds no
 * `Context` and no service reference, so it cannot leak either.
 */
internal object VoipRecorderHub {

    /**
     * The parts of the recorder state the service owns. The UI layer merges this
     * with the on-disk recordings list, which the service does not carry so that
     * a large list never rides on every state update.
     */
    data class Runtime(
        val support: VoipCaptureSupport = VoipCaptureSupport.UNPROBED,
        val supportDetail: String? = null,
        val enabled: Boolean = false,
        val isRecording: Boolean = false,
        val activePackage: String? = null,
        val pendingPromptPackage: String? = null,
        val error: String? = null,
        /** Bumped whenever the stored recordings change, to trigger a reload. */
        val recordingsRevision: Int = 0,
    )

    private val _state = MutableStateFlow(Runtime())
    val state: StateFlow<Runtime> = _state

    fun update(block: (Runtime) -> Runtime) = _state.update(block)

    fun bumpRecordings() = _state.update { it.copy(recordingsRevision = it.recordingsRevision + 1) }
}
