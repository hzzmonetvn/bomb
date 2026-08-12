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
 * The state of the CPU/GPU clock snapshot poller (v12).
 *
 * [Ready] carries the probed clock domains — their step ladders and current
 * scaling windows — so the picker offers only frequencies the system exposes.
 * On a service older than v12 this is [Unsupported]: the backend that reads the
 * ladders is simply not there yet.
 */
sealed interface ClockControlUiState {
    data object Loading : ClockControlUiState
    data class Unsupported(val reason: String) : ClockControlUiState
    data class Error(val message: String) : ClockControlUiState
    data class Ready(val snapshot: BombClockSnapshot) : ClockControlUiState
}

/**
 * Client-side guard for a composed clock-range write (v12).
 *
 * The backend revalidates against the live ladder and returns the authoritative
 * result, but the UI refuses to send a window it can already see is malformed —
 * a bound off the ladder, or min above max.
 */
object BombClockBounds {
    const val MIN_VERSION = 12

    /** Reason the composed [minKHz]…[maxKHz] window would be rejected, or null when valid. */
    fun violation(domain: BombClockDomain, minKHz: Int, maxKHz: Int): String? {
        if (domain.availableStepsKHz.isEmpty()) return "No frequency steps were probed for ${domain.label}"
        if (minKHz !in domain.availableStepsKHz) return "Minimum is not one of the probed steps"
        if (maxKHz !in domain.availableStepsKHz) return "Maximum is not one of the probed steps"
        if (minKHz > maxKHz) return "Minimum must not exceed maximum"
        return null
    }
}

/**
 * Polls the clock snapshot while [active] and stops when the caller leaves
 * composition. Something else can move the scaling window (a governor, the
 * Thermal Guardian), so this samples on an interval to reflect it; a [refreshKey]
 * bump forces an immediate resample after the user applies or resets a range.
 */
@Composable
fun rememberClockControl(
    service: BombServiceState,
    active: Boolean,
    intervalMillis: Long,
    refreshKey: Int = 0,
): ClockControlUiState {
    val controller = service.controller
    val apiVersion = service.apiVersion ?: 0
    var state by remember { mutableStateOf<ClockControlUiState>(ClockControlUiState.Loading) }

    LaunchedEffect(active, controller, apiVersion, service.connection, intervalMillis, refreshKey) {
        if (!active) return@LaunchedEffect
        if (apiVersion < BombClockBounds.MIN_VERSION) {
            state = ClockControlUiState.Unsupported(
                "The connected service is older than v${BombClockBounds.MIN_VERSION}.",
            )
            return@LaunchedEffect
        }
        if (controller == null) {
            state = ClockControlUiState.Error("Bomb's privileged service is not reachable.")
            return@LaunchedEffect
        }
        while (isActive) {
            val snapshot = controller.awaitClockSnapshot()
            state = if (snapshot == null) {
                ClockControlUiState.Error("The clock snapshot call failed.")
            } else {
                ClockControlUiState.Ready(snapshot)
            }
            delay(intervalMillis)
        }
    }
    return state
}

private suspend fun BombServiceController.awaitClockSnapshot(): BombClockSnapshot? =
    suspendCancellableCoroutine { continuation ->
        getClockSnapshot { result -> if (continuation.isActive) continuation.resume(result) }
    }
