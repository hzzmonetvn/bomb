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
 * The state of the Battery Lab snapshot poller.
 *
 * [Ready] carries the live `/sys/class/power_supply` snapshot, whose own
 * `backendStatus` further distinguishes AVAILABLE / UNSUPPORTED / UNAVAILABLE —
 * the UI reads that rather than pretending an unreadable node is zero.
 */
sealed interface BatteryLabState {
    data object Loading : BatteryLabState
    data class Unsupported(val reason: String) : BatteryLabState
    data class Error(val message: String) : BatteryLabState
    data class Ready(val snapshot: BombBatteryLabSnapshot) : BatteryLabState
}

/**
 * Client-side mirror of the service's `BatteryLabProfileValidator` bounds (v9).
 *
 * The service is the authority — every write returns a [BombOperationResult] — but
 * the UI constrains its sliders and disables Apply on a doomed profile rather than
 * firing a call it knows will come back INVALID_ARGUMENT.
 */
object BombBatteryBounds {
    const val MIN_VERSION = 9
    const val MIN_CHARGE_LIMIT_PERCENT = 50
    const val MAX_CHARGE_LIMIT_PERCENT = 100
    const val MIN_TEMPERATURE_DECI_CELSIUS = 350
    const val MAX_TEMPERATURE_DECI_CELSIUS = 550
    const val MIN_CAPACITY_HYSTERESIS_PERCENT = 2
    const val MAX_CAPACITY_HYSTERESIS_PERCENT = 20
    const val MIN_TEMPERATURE_HYSTERESIS_DECI_CELSIUS = 10
    const val MAX_TEMPERATURE_HYSTERESIS_DECI_CELSIUS = 100
    const val DEFAULT_CAPACITY_HYSTERESIS_PERCENT = 5
    const val DEFAULT_TEMPERATURE_HYSTERESIS_DECI_CELSIUS = 30
    const val MIN_CAPACITY_RESUME_FLOOR_PERCENT = 20
    const val MIN_TEMPERATURE_RESUME_FLOOR_DECI_CELSIUS = 300

    // Max charge current, in µA (sysfs's native unit, matching currentMicroamps).
    // The floor keeps a limit from starving the charger; the ceiling is per-device
    // (snapshot.maxSupportedChargeCurrentMicroamps) and only this fallback is used
    // when the device did not advertise one. The step is the slider granularity.
    const val MIN_CHARGE_CURRENT_MICROAMPS = 500_000
    const val DEFAULT_CHARGE_CURRENT_MICROAMPS = 2_000_000
    const val FALLBACK_CEILING_CHARGE_CURRENT_MICROAMPS = 5_000_000
    const val CHARGE_CURRENT_STEP_MICROAMPS = 100_000

    /** Reason the composed profile would be rejected, or null when acceptable. */
    fun violation(profile: BombBatteryLabProfile): String? {
        if (profile.chargeLimitPercent == null &&
            profile.maxTemperatureDeciCelsius == null &&
            profile.maxChargeCurrentMicroamps == null
        ) {
            return "Set a charge limit, a temperature limit or a current limit"
        }
        profile.maxChargeCurrentMicroamps?.let { microamps ->
            if (microamps < MIN_CHARGE_CURRENT_MICROAMPS) {
                return "Charge current must be at least ${MIN_CHARGE_CURRENT_MICROAMPS / 1000} mA"
            }
        }
        profile.chargeLimitPercent?.let { limit ->
            if (limit !in MIN_CHARGE_LIMIT_PERCENT..MAX_CHARGE_LIMIT_PERCENT) {
                return "Charge limit must be $MIN_CHARGE_LIMIT_PERCENT–$MAX_CHARGE_LIMIT_PERCENT%"
            }
            if (limit - profile.capacityResumeHysteresisPercent < MIN_CAPACITY_RESUME_FLOOR_PERCENT) {
                return "Resume threshold must stay at or above $MIN_CAPACITY_RESUME_FLOOR_PERCENT%"
            }
        }
        profile.maxTemperatureDeciCelsius?.let { limit ->
            if (limit !in MIN_TEMPERATURE_DECI_CELSIUS..MAX_TEMPERATURE_DECI_CELSIUS) {
                return "Temperature limit must be 35.0–55.0 °C"
            }
            if (limit - profile.temperatureResumeHysteresisDeciCelsius < MIN_TEMPERATURE_RESUME_FLOOR_DECI_CELSIUS) {
                return "Temperature resume must stay at or above 30.0 °C"
            }
        }
        if (profile.capacityResumeHysteresisPercent !in MIN_CAPACITY_HYSTERESIS_PERCENT..MAX_CAPACITY_HYSTERESIS_PERCENT) {
            return "Capacity hysteresis must be $MIN_CAPACITY_HYSTERESIS_PERCENT–$MAX_CAPACITY_HYSTERESIS_PERCENT%"
        }
        if (profile.temperatureResumeHysteresisDeciCelsius !in
            MIN_TEMPERATURE_HYSTERESIS_DECI_CELSIUS..MAX_TEMPERATURE_HYSTERESIS_DECI_CELSIUS
        ) {
            return "Temperature hysteresis must be 1.0–10.0 °C"
        }
        return null
    }
}

/**
 * Polls the Battery Lab snapshot while [active] and stops the moment the caller
 * leaves composition (the Battery Lab route). Capacity and temperature move
 * continuously, so this samples on an interval; there is no delta baseline, so a
 * restart on recomposition simply refetches. A [refreshKey] bump forces an
 * immediate resample after Apply/Clear.
 */
@Composable
fun rememberBatteryLab(
    service: BombServiceState,
    active: Boolean,
    intervalMillis: Long,
    refreshKey: Int = 0,
): BatteryLabState {
    val controller = service.controller
    val apiVersion = service.apiVersion ?: 0
    var state by remember { mutableStateOf<BatteryLabState>(BatteryLabState.Loading) }

    LaunchedEffect(active, controller, apiVersion, service.connection, intervalMillis, refreshKey) {
        if (!active) return@LaunchedEffect
        if (apiVersion < BombBatteryBounds.MIN_VERSION) {
            state = BatteryLabState.Unsupported(
                "The connected service is older than v${BombBatteryBounds.MIN_VERSION}.",
            )
            return@LaunchedEffect
        }
        if (controller == null) {
            state = BatteryLabState.Error("Bomb's privileged service is not reachable.")
            return@LaunchedEffect
        }
        while (isActive) {
            val snapshot = controller.awaitBatteryLab()
            state = if (snapshot == null) {
                BatteryLabState.Error("The Battery Lab snapshot call failed.")
            } else {
                BatteryLabState.Ready(snapshot)
            }
            delay(intervalMillis)
        }
    }
    return state
}

private suspend fun BombServiceController.awaitBatteryLab(): BombBatteryLabSnapshot? =
    suspendCancellableCoroutine { continuation ->
        getBatteryLabSnapshot { result -> if (continuation.isActive) continuation.resume(result) }
    }
