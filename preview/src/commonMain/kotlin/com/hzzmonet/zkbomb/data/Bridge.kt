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
 * The state of the Bomb Bridge status poller.
 *
 * [Ready] carries the real renderer availability probed on the device plus the
 * events currently live. The notification renderer is the guaranteed fallback,
 * so it is always reachable; HyperOS live-update and HyperIsland only when the
 * device actually exposes them.
 */
sealed interface BridgeUiState {
    data object Loading : BridgeUiState
    data class Unsupported(val reason: String) : BridgeUiState
    data class Error(val message: String) : BridgeUiState
    data class Ready(val status: BombBridgeStatus) : BridgeUiState
}

/**
 * Client-side mirror of the domain `BombLiveEventValidator` bounds (v10).
 *
 * The service revalidates every event and returns a [BombOperationResult] shown
 * verbatim, but the UI disables Publish on an event it can already see is
 * malformed rather than firing a doomed write.
 */
object BombLiveEventBounds {
    const val MIN_VERSION = 10
    const val MAX_TITLE_LENGTH = 120
    const val MAX_SUBTITLE_LENGTH = 240
    const val MAX_COMPACT_LENGTH = 128

    private val ID = Regex("^[A-Za-z0-9._:-]{1,128}$")

    /** Reason the composed event would be rejected, or null when acceptable. */
    fun violation(event: BombLiveEvent): String? {
        if (!ID.matches(event.id)) return "Event id must be 1–128 of A–Z, 0–9, . _ : -"
        if (event.title.isBlank() || event.title.length > MAX_TITLE_LENGTH || event.title.hasControl()) {
            return "Title must be 1–$MAX_TITLE_LENGTH characters"
        }
        event.subtitle?.let {
            if (it.length > MAX_SUBTITLE_LENGTH || it.hasControl()) return "Subtitle is too long"
        }
        event.compactText?.let {
            if (it.length > MAX_COMPACT_LENGTH || it.hasControl()) return "Compact text is too long"
        }
        event.progressFraction?.let {
            if (event.progressIndeterminate) return "Progress can be a value or indeterminate, not both"
            if (!it.isFinite() || it !in 0.0..1.0) return "Progress must be between 0 and 1"
        }
        return null
    }

    // Newlines are allowed by the validator; every other control char is not.
    private fun String.hasControl(): Boolean = any { it.isISOControl() && it != '\n' }
}

/**
 * Polls the bridge status while [active] and stops the moment the caller leaves
 * composition. Event states move as renderers accept or a source updates them,
 * so this samples on an interval; a [refreshKey] bump forces an immediate
 * resample after a publish or dismiss.
 */
@Composable
fun rememberBridge(
    service: BombServiceState,
    active: Boolean,
    intervalMillis: Long,
    refreshKey: Int = 0,
): BridgeUiState {
    val controller = service.controller
    val apiVersion = service.apiVersion ?: 0
    var state by remember { mutableStateOf<BridgeUiState>(BridgeUiState.Loading) }

    LaunchedEffect(active, controller, apiVersion, service.connection, intervalMillis, refreshKey) {
        if (!active) return@LaunchedEffect
        if (apiVersion < BombLiveEventBounds.MIN_VERSION) {
            state = BridgeUiState.Unsupported(
                "The connected service is older than v${BombLiveEventBounds.MIN_VERSION}.",
            )
            return@LaunchedEffect
        }
        if (controller == null) {
            state = BridgeUiState.Error("Bomb's privileged service is not reachable.")
            return@LaunchedEffect
        }
        while (isActive) {
            val status = controller.awaitBridge()
            state = if (status == null) {
                BridgeUiState.Error("The bridge status call failed.")
            } else {
                BridgeUiState.Ready(status)
            }
            delay(intervalMillis)
        }
    }
    return state
}

private suspend fun BombServiceController.awaitBridge(): BombBridgeStatus? =
    suspendCancellableCoroutine { continuation ->
        getBridgeStatus { result -> if (continuation.isActive) continuation.resume(result) }
    }
