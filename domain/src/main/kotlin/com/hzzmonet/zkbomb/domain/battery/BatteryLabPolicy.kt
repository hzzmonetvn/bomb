package com.hzzmonet.zkbomb.domain.battery

/**
 * A bounded Battery Lab policy. Temperatures use tenths of a Celsius degree,
 * matching the Linux power-supply `temp` ABI.
 */
data class BatteryLabProfile(
    val chargeLimitPercent: Int? = null,
    val maxTemperatureDeciCelsius: Int? = null,
    val capacityResumeHysteresisPercent: Int = 5,
    val temperatureResumeHysteresisDeciCelsius: Int = 30,
)

object BatteryLabProfileValidator {
    const val MIN_CHARGE_LIMIT_PERCENT = 50
    const val MAX_CHARGE_LIMIT_PERCENT = 100
    const val MIN_TEMPERATURE_DECI_CELSIUS = 350
    const val MAX_TEMPERATURE_DECI_CELSIUS = 550
    const val MIN_CAPACITY_HYSTERESIS_PERCENT = 2
    const val MAX_CAPACITY_HYSTERESIS_PERCENT = 20
    const val MIN_TEMPERATURE_HYSTERESIS_DECI_CELSIUS = 10
    const val MAX_TEMPERATURE_HYSTERESIS_DECI_CELSIUS = 100

    fun violations(profile: BatteryLabProfile): List<String> = buildList {
        if (profile.chargeLimitPercent == null && profile.maxTemperatureDeciCelsius == null) {
            add("At least one charge or temperature limit is required")
        }
        if (profile.chargeLimitPercent != null &&
            profile.chargeLimitPercent !in MIN_CHARGE_LIMIT_PERCENT..MAX_CHARGE_LIMIT_PERCENT
        ) {
            add("chargeLimitPercent must be in $MIN_CHARGE_LIMIT_PERCENT..$MAX_CHARGE_LIMIT_PERCENT")
        }
        if (profile.maxTemperatureDeciCelsius != null &&
            profile.maxTemperatureDeciCelsius !in
            MIN_TEMPERATURE_DECI_CELSIUS..MAX_TEMPERATURE_DECI_CELSIUS
        ) {
            add(
                "maxTemperatureDeciCelsius must be in " +
                    "$MIN_TEMPERATURE_DECI_CELSIUS..$MAX_TEMPERATURE_DECI_CELSIUS",
            )
        }
        if (profile.capacityResumeHysteresisPercent !in
            MIN_CAPACITY_HYSTERESIS_PERCENT..MAX_CAPACITY_HYSTERESIS_PERCENT
        ) {
            add(
                "capacityResumeHysteresisPercent must be in " +
                    "$MIN_CAPACITY_HYSTERESIS_PERCENT..$MAX_CAPACITY_HYSTERESIS_PERCENT",
            )
        }
        if (profile.temperatureResumeHysteresisDeciCelsius !in
            MIN_TEMPERATURE_HYSTERESIS_DECI_CELSIUS..
            MAX_TEMPERATURE_HYSTERESIS_DECI_CELSIUS
        ) {
            add(
                "temperatureResumeHysteresisDeciCelsius must be in " +
                    "$MIN_TEMPERATURE_HYSTERESIS_DECI_CELSIUS.." +
                    MAX_TEMPERATURE_HYSTERESIS_DECI_CELSIUS,
            )
        }
        if (profile.chargeLimitPercent != null &&
            profile.chargeLimitPercent - profile.capacityResumeHysteresisPercent < 20
        ) {
            add("capacity resume threshold must remain at or above 20 percent")
        }
        if (profile.maxTemperatureDeciCelsius != null &&
            profile.maxTemperatureDeciCelsius -
            profile.temperatureResumeHysteresisDeciCelsius < 300
        ) {
            add("temperature resume threshold must remain at or above 30.0 C")
        }
    }
}

data class BatteryLabState(
    val capacityPercent: Int?,
    val temperatureDeciCelsius: Int?,
    val externalPowerPresent: Boolean?,
    val charging: Boolean?,
)

enum class ChargeGuardAction {
    /** Do not touch the control node. */
    HOLD,

    /** Disable charging using the capability-probed control. */
    SUSPEND,

    /** Restore the value captured before Bomb suspended charging. */
    RESTORE,
}

enum class ChargeGuardReason {
    NONE,
    CAPACITY_LIMIT,
    THERMAL_LIMIT,
    CAPACITY_AND_THERMAL_LIMIT,
    HYSTERESIS,
    INCOMPLETE_TELEMETRY,
    POWER_DISCONNECTED,
}

data class ChargeGuardDecision(
    val action: ChargeGuardAction,
    val reason: ChargeGuardReason,
)

/** Pure policy engine; all sysfs I/O stays in the device backend. */
class BatteryLabPolicyEngine {

    fun decide(
        profile: BatteryLabProfile,
        state: BatteryLabState,
        suspendedByBomb: Boolean,
    ): ChargeGuardDecision {
        if (state.externalPowerPresent == false) {
            return ChargeGuardDecision(
                if (suspendedByBomb) ChargeGuardAction.RESTORE else ChargeGuardAction.HOLD,
                ChargeGuardReason.POWER_DISCONNECTED,
            )
        }
        val capacityExceeded = profile.chargeLimitPercent?.let { limit ->
            state.capacityPercent?.let { it >= limit }
        }
        val temperatureExceeded = profile.maxTemperatureDeciCelsius?.let { limit ->
            state.temperatureDeciCelsius?.let { it >= limit }
        }

        if (capacityExceeded == true || temperatureExceeded == true) {
            val reason = when {
                capacityExceeded == true && temperatureExceeded == true ->
                    ChargeGuardReason.CAPACITY_AND_THERMAL_LIMIT
                temperatureExceeded == true -> ChargeGuardReason.THERMAL_LIMIT
                else -> ChargeGuardReason.CAPACITY_LIMIT
            }
            return ChargeGuardDecision(ChargeGuardAction.SUSPEND, reason)
        }

        if ((profile.chargeLimitPercent != null && state.capacityPercent == null) ||
            (profile.maxTemperatureDeciCelsius != null &&
                state.temperatureDeciCelsius == null)
        ) {
            return ChargeGuardDecision(
                ChargeGuardAction.HOLD,
                ChargeGuardReason.INCOMPLETE_TELEMETRY,
            )
        }

        if (!suspendedByBomb) {
            return ChargeGuardDecision(ChargeGuardAction.HOLD, ChargeGuardReason.NONE)
        }

        val capacitySafe: Boolean? = if (profile.chargeLimitPercent == null) {
            true
        } else {
            state.capacityPercent?.let {
                it <= profile.chargeLimitPercent - profile.capacityResumeHysteresisPercent
            }
        }
        val temperatureSafe: Boolean? = if (profile.maxTemperatureDeciCelsius == null) {
            true
        } else {
            state.temperatureDeciCelsius?.let {
                it <= profile.maxTemperatureDeciCelsius -
                    profile.temperatureResumeHysteresisDeciCelsius
            }
        }

        if (capacitySafe == null || temperatureSafe == null) {
            return ChargeGuardDecision(
                ChargeGuardAction.HOLD,
                ChargeGuardReason.INCOMPLETE_TELEMETRY,
            )
        }
        return if (capacitySafe && temperatureSafe) {
            ChargeGuardDecision(ChargeGuardAction.RESTORE, ChargeGuardReason.NONE)
        } else {
            ChargeGuardDecision(ChargeGuardAction.HOLD, ChargeGuardReason.HYSTERESIS)
        }
    }
}
