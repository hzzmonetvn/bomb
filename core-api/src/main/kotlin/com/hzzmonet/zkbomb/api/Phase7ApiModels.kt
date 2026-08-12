package com.hzzmonet.zkbomb.api

import android.os.Parcelable
import com.hzzmonet.zkbomb.domain.automation.PerformanceProfile
import com.hzzmonet.zkbomb.domain.bridge.BombLiveEvent
import com.hzzmonet.zkbomb.domain.bridge.BombLiveEventValidator
import com.hzzmonet.zkbomb.domain.bridge.LiveEventProgress
import com.hzzmonet.zkbomb.domain.bridge.LiveEventState
import com.hzzmonet.zkbomb.domain.bridge.LiveEventType
import com.hzzmonet.zkbomb.domain.performance.PerformanceProfileDefinition
import com.hzzmonet.zkbomb.domain.performance.ThermalGuardianConfig
import com.hzzmonet.zkbomb.domain.performance.ThermalGuardianConfigValidator
import kotlinx.parcelize.Parcelize

@Parcelize
data class BombLiveEventParcel(
    val id: String,
    val sourcePackage: String,
    val type: String,
    val title: String,
    val subtitle: String?,
    val compactText: String?,
    val progressFraction: Double?,
    val progressIndeterminate: Boolean,
    val state: String,
    val timestampMillis: Long,
) : Parcelable {
    fun toDomain(): BombLiveEvent? {
        val event = runCatching {
            BombLiveEvent(
                id = id,
                sourcePackage = sourcePackage,
                type = LiveEventType.valueOf(type),
                title = title,
                subtitle = subtitle,
                compactText = compactText,
                progress = if (progressFraction != null || progressIndeterminate) {
                    LiveEventProgress(progressFraction, progressIndeterminate)
                } else null,
                state = LiveEventState.valueOf(state),
                timestampMillis = timestampMillis,
            )
        }.getOrNull() ?: return null
        return event.takeIf { BombLiveEventValidator.violations(it).isEmpty() }
    }
}

@Parcelize
data class BridgeEventStatusParcel(
    val eventId: String,
    val renderer: String,
    val state: String,
    val updatedAtMillis: Long,
) : Parcelable

@Parcelize
data class BridgeStatusSnapshot(
    val notificationAvailable: Boolean,
    val liveUpdateAvailable: Boolean,
    val hyperIslandFeaturePresent: Boolean,
    val hyperIslandProtocolVersion: Int,
    val hyperIslandPermitted: Boolean,
    val hyperIslandPayloadAdapterAvailable: Boolean,
    val activeEvents: List<BridgeEventStatusParcel>,
) : Parcelable

@Parcelize
data class PerformanceProfileParcel(
    val name: String,
    val swappiness: Int,
    val pageCluster: Int,
    val refreshRateHz: Int?,
    val cpuStrategy: String,
    val gpuStrategy: String,
    val thermalStrategy: String,
    val monitorPreset: String,
) : Parcelable {
    companion object {
        fun fromDomain(definition: PerformanceProfileDefinition): PerformanceProfileParcel =
            PerformanceProfileParcel(
                name = definition.profile.name,
                swappiness = definition.swappiness,
                pageCluster = definition.pageCluster,
                refreshRateHz = definition.refreshRateHz,
                cpuStrategy = definition.cpuStrategy.name,
                gpuStrategy = definition.gpuStrategy.name,
                thermalStrategy = definition.thermalStrategy.name,
                monitorPreset = definition.monitorPreset.name,
            )
    }
}

@Parcelize
data class ThermalGuardianConfigParcel(
    val sustainableAtDeciCelsius: Int,
    val ecoAtDeciCelsius: Int,
    val restoreAtDeciCelsius: Int,
    val cooldownMillis: Long,
) : Parcelable {
    fun toDomain(): ThermalGuardianConfig? {
        val config = ThermalGuardianConfig(
            sustainableAtDeciCelsius,
            ecoAtDeciCelsius,
            restoreAtDeciCelsius,
            cooldownMillis,
        )
        return config.takeIf { ThermalGuardianConfigValidator.violations(it).isEmpty() }
    }

    companion object {
        fun fromDomain(config: ThermalGuardianConfig): ThermalGuardianConfigParcel =
            ThermalGuardianConfigParcel(
                config.sustainableAtDeciCelsius,
                config.ecoAtDeciCelsius,
                config.restoreAtDeciCelsius,
                config.cooldownMillis,
            )
    }
}

@Parcelize
data class PerformanceProfilesSnapshot(
    val profiles: List<PerformanceProfileParcel>,
    val activeProfile: String?,
    val memoryControlAvailable: Boolean,
    val appliedFields: List<String>,
    val thermalGuardianConfig: ThermalGuardianConfigParcel?,
    val thermalGuardianActiveProfile: String?,
) : Parcelable {
    fun resolvedActiveProfile(): PerformanceProfile? =
        activeProfile?.let { name -> PerformanceProfile.entries.firstOrNull { it.name == name } }
}
