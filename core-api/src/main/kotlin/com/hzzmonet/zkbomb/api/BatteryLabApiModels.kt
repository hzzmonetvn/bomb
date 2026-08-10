package com.hzzmonet.zkbomb.api

import android.os.Parcelable
import com.hzzmonet.zkbomb.domain.battery.BatteryLabProfile
import com.hzzmonet.zkbomb.domain.battery.BatteryLabProfileValidator
import kotlinx.parcelize.Parcelize

/** Typed, bounded Battery Lab profile crossing Binder. */
@Parcelize
data class BatteryLabProfileParcel(
    val chargeLimitPercent: Int?,
    val maxTemperatureDeciCelsius: Int?,
    val capacityResumeHysteresisPercent: Int = 5,
    val temperatureResumeHysteresisDeciCelsius: Int = 30,
) : Parcelable {
    fun toDomain(): BatteryLabProfile? {
        val profile = BatteryLabProfile(
            chargeLimitPercent = chargeLimitPercent,
            maxTemperatureDeciCelsius = maxTemperatureDeciCelsius,
            capacityResumeHysteresisPercent = capacityResumeHysteresisPercent,
            temperatureResumeHysteresisDeciCelsius = temperatureResumeHysteresisDeciCelsius,
        )
        return profile.takeIf { BatteryLabProfileValidator.violations(it).isEmpty() }
    }

    companion object {
        fun fromDomain(profile: BatteryLabProfile): BatteryLabProfileParcel =
            BatteryLabProfileParcel(
                chargeLimitPercent = profile.chargeLimitPercent,
                maxTemperatureDeciCelsius = profile.maxTemperatureDeciCelsius,
                capacityResumeHysteresisPercent = profile.capacityResumeHysteresisPercent,
                temperatureResumeHysteresisDeciCelsius =
                profile.temperatureResumeHysteresisDeciCelsius,
            )
    }
}

enum class BatteryLabBackendStatus {
    AVAILABLE,
    UNSUPPORTED,
    UNAVAILABLE,
}

/**
 * Snapshot read from `/sys/class/power_supply`. Missing files remain null; the
 * API never turns an unreadable metric into a plausible-looking zero.
 */
@Parcelize
data class BatteryLabSnapshot(
    val sampledAtElapsedRealtimeMillis: Long,
    val backendStatus: String,
    val powerSupplyName: String?,
    val status: String?,
    val capacityPercent: Int?,
    val temperatureDeciCelsius: Int?,
    val voltageMicrovolts: Long?,
    val currentMicroamps: Long?,
    val chargeCounterMicroampHours: Long?,
    val cycleCount: Int?,
    val chargeFullMicroampHours: Long?,
    val chargeFullDesignMicroampHours: Long?,
    val externalPowerPresent: Boolean?,
    /** Internal allowlisted strategy name, never a caller-provided path. */
    val chargeControlKind: String?,
    val chargeLimitControlSupported: Boolean,
    val thermalChargeControlSupported: Boolean,
    val activeProfile: BatteryLabProfileParcel?,
    val chargingSuspendedByBomb: Boolean,
    val lastDecisionReason: String?,
) : Parcelable {
    val parsedBackendStatus: BatteryLabBackendStatus
        get() = runCatching { BatteryLabBackendStatus.valueOf(backendStatus) }
            .getOrDefault(BatteryLabBackendStatus.UNAVAILABLE)

    companion object {
        fun unavailable(nowMillis: Long): BatteryLabSnapshot = BatteryLabSnapshot(
            sampledAtElapsedRealtimeMillis = nowMillis,
            backendStatus = BatteryLabBackendStatus.UNAVAILABLE.name,
            powerSupplyName = null,
            status = null,
            capacityPercent = null,
            temperatureDeciCelsius = null,
            voltageMicrovolts = null,
            currentMicroamps = null,
            chargeCounterMicroampHours = null,
            cycleCount = null,
            chargeFullMicroampHours = null,
            chargeFullDesignMicroampHours = null,
            externalPowerPresent = null,
            chargeControlKind = null,
            chargeLimitControlSupported = false,
            thermalChargeControlSupported = false,
            activeProfile = null,
            chargingSuspendedByBomb = false,
            lastDecisionReason = null,
        )
    }
}
