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
 * The state of the Performance Profiles snapshot poller.
 *
 * [Ready] carries the profile catalog, the active profile, the fields that
 * actually applied and the Thermal Guardian config. Because it is read back from
 * the backend, the UI shows what the device honoured rather than what was asked —
 * a profile that requested a 120 Hz refresh on a 60 Hz panel simply will not list
 * `refreshRateHz` in `appliedFields`.
 */
sealed interface PerformanceUiState {
    data object Loading : PerformanceUiState
    data class Unsupported(val reason: String) : PerformanceUiState
    data class Error(val message: String) : PerformanceUiState
    data class Ready(val snapshot: BombPerformanceProfilesSnapshot) : PerformanceUiState
}

/**
 * Client-side mirror of the domain `ThermalGuardianConfigValidator` bounds (v10).
 *
 * The service is the authority — `setThermalGuardianConfig` returns a
 * [BombOperationResult] shown verbatim — but the UI constrains its sliders and
 * disables Apply on a config it can already see the engine will reject.
 */
object BombThermalGuardianBounds {
    const val MIN_VERSION = 10

    const val MIN_RESTORE_DECI_CELSIUS = 250
    const val MAX_RESTORE_DECI_CELSIUS = 440
    const val MIN_SUSTAINABLE_DECI_CELSIUS = 350
    const val MAX_SUSTAINABLE_DECI_CELSIUS = 500
    const val MIN_ECO_DECI_CELSIUS = 400
    const val MAX_ECO_DECI_CELSIUS = 550
    const val MIN_COOLDOWN_MILLIS = 5_000L
    const val MAX_COOLDOWN_MILLIS = 3_600_000L

    val DEFAULT = BombThermalGuardianConfig(
        sustainableAtDeciCelsius = 420,
        ecoAtDeciCelsius = 450,
        restoreAtDeciCelsius = 390,
        cooldownMillis = 60_000L,
    )

    /** Reason the composed config would be rejected, or null when acceptable. */
    fun violation(config: BombThermalGuardianConfig): String? {
        if (config.restoreAtDeciCelsius !in MIN_RESTORE_DECI_CELSIUS..MAX_RESTORE_DECI_CELSIUS) {
            return "Restore threshold must be 25.0–44.0 °C"
        }
        if (config.sustainableAtDeciCelsius !in MIN_SUSTAINABLE_DECI_CELSIUS..MAX_SUSTAINABLE_DECI_CELSIUS) {
            return "Sustainable threshold must be 35.0–50.0 °C"
        }
        if (config.ecoAtDeciCelsius !in MIN_ECO_DECI_CELSIUS..MAX_ECO_DECI_CELSIUS) {
            return "Eco threshold must be 40.0–55.0 °C"
        }
        if (config.restoreAtDeciCelsius >= config.sustainableAtDeciCelsius) {
            return "Restore must stay below the sustainable threshold"
        }
        if (config.sustainableAtDeciCelsius >= config.ecoAtDeciCelsius) {
            return "Sustainable must stay below the eco threshold"
        }
        if (config.cooldownMillis !in MIN_COOLDOWN_MILLIS..MAX_COOLDOWN_MILLIS) {
            return "Cooldown must be 5–3600 s"
        }
        return null
    }
}

/**
 * Polls the performance snapshot while [active] and stops when the caller leaves
 * composition. The Thermal Guardian can change the active profile on its own, so
 * this samples on an interval to reflect that; a [refreshKey] bump forces an
 * immediate resample after the user applies or clears a profile or the guardian.
 */
@Composable
fun rememberPerformance(
    service: BombServiceState,
    active: Boolean,
    intervalMillis: Long,
    refreshKey: Int = 0,
): PerformanceUiState {
    val controller = service.controller
    val apiVersion = service.apiVersion ?: 0
    var state by remember { mutableStateOf<PerformanceUiState>(PerformanceUiState.Loading) }

    LaunchedEffect(active, controller, apiVersion, service.connection, intervalMillis, refreshKey) {
        if (!active) return@LaunchedEffect
        if (apiVersion < BombThermalGuardianBounds.MIN_VERSION) {
            state = PerformanceUiState.Unsupported(
                "The connected service is older than v${BombThermalGuardianBounds.MIN_VERSION}.",
            )
            return@LaunchedEffect
        }
        if (controller == null) {
            state = PerformanceUiState.Error("Bomb's privileged service is not reachable.")
            return@LaunchedEffect
        }
        while (isActive) {
            val snapshot = controller.awaitPerformance()
            state = if (snapshot == null) {
                PerformanceUiState.Error("The performance snapshot call failed.")
            } else {
                PerformanceUiState.Ready(snapshot)
            }
            delay(intervalMillis)
        }
    }
    return state
}

private suspend fun BombServiceController.awaitPerformance(): BombPerformanceProfilesSnapshot? =
    suspendCancellableCoroutine { continuation ->
        getPerformanceProfiles { result -> if (continuation.isActive) continuation.resume(result) }
    }
