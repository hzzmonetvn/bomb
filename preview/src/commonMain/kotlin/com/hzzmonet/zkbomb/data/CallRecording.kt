package com.hzzmonet.zkbomb.data

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * The state of the privileged recording-backend status poller (v11).
 *
 * [Ready] carries capability and active-session metadata only — never call
 * content or an output path. It distinguishes cellular from VoIP capture support
 * separately, so the UI enables each Start button only on the probe that proves
 * that specific path works.
 */
sealed interface RecordingBackendUiState {
    data object Loading : RecordingBackendUiState
    data class Unsupported(val reason: String) : RecordingBackendUiState
    data class Error(val message: String) : RecordingBackendUiState
    data class Ready(val status: BombRecordingBackendStatus) : RecordingBackendUiState
}

/** Version at which the platform recording backend was appended. */
object BombRecordingBounds {
    const val MIN_VERSION = 11
}

/**
 * Polls the recording-backend status while [active] and stops the moment the
 * caller leaves composition. A recording's state and peak amplitude move while it
 * runs, so this samples on an interval; a [refreshKey] bump forces an immediate
 * resample after a start or stop.
 */
@Composable
fun rememberRecordingBackend(
    service: BombServiceState,
    active: Boolean,
    intervalMillis: Long,
    refreshKey: Int = 0,
): RecordingBackendUiState {
    val controller = service.controller
    val apiVersion = service.apiVersion ?: 0
    var state by remember { mutableStateOf<RecordingBackendUiState>(RecordingBackendUiState.Loading) }

    LaunchedEffect(active, controller, apiVersion, service.connection, intervalMillis, refreshKey) {
        if (!active) return@LaunchedEffect
        if (apiVersion < BombRecordingBounds.MIN_VERSION) {
            state = RecordingBackendUiState.Unsupported(
                "The connected service is older than v${BombRecordingBounds.MIN_VERSION}.",
            )
            return@LaunchedEffect
        }
        if (controller == null) {
            state = RecordingBackendUiState.Error("Bomb's privileged service is not reachable.")
            return@LaunchedEffect
        }
        while (isActive) {
            val status = controller.awaitRecordingBackend()
            state = if (status == null) {
                RecordingBackendUiState.Error("The recording-status call failed.")
            } else {
                RecordingBackendUiState.Ready(status)
            }
            delay(intervalMillis)
        }
    }
    return state
}

private suspend fun BombServiceController.awaitRecordingBackend(): BombRecordingBackendStatus? =
    suspendCancellableCoroutine { continuation ->
        getRecordingBackendStatus { result -> if (continuation.isActive) continuation.resume(result) }
    }
