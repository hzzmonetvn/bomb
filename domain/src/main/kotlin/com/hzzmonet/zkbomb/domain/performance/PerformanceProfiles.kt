package com.hzzmonet.zkbomb.domain.performance

import com.hzzmonet.zkbomb.domain.automation.PerformanceProfile

enum class CpuStrategy { POWER_SAVE, BALANCED, RESPONSIVE, SUSTAINED }
enum class GpuStrategy { POWER_SAVE, BALANCED, RESPONSIVE, SUSTAINED }
enum class ThermalStrategy { STOCK, CONSERVATIVE, HEADROOM_AWARE, SUSTAINABLE }
enum class MonitorPreset { BATTERY_SAVER, BALANCED, REALTIME }

/** A typed intent profile. Backends apply only fields their capabilities prove. */
data class PerformanceProfileDefinition(
    val profile: PerformanceProfile,
    val swappiness: Int,
    val pageCluster: Int,
    val refreshRateHz: Int?,
    val cpuStrategy: CpuStrategy,
    val gpuStrategy: GpuStrategy,
    val thermalStrategy: ThermalStrategy,
    val monitorPreset: MonitorPreset,
)

object PerformanceProfileCatalog {
    val definitions: List<PerformanceProfileDefinition> = listOf(
        PerformanceProfileDefinition(
            PerformanceProfile.ECO, 80, 0, 60,
            CpuStrategy.POWER_SAVE, GpuStrategy.POWER_SAVE,
            ThermalStrategy.CONSERVATIVE, MonitorPreset.BATTERY_SAVER,
        ),
        PerformanceProfileDefinition(
            PerformanceProfile.BALANCED, 100, 0, null,
            CpuStrategy.BALANCED, GpuStrategy.BALANCED,
            ThermalStrategy.STOCK, MonitorPreset.BALANCED,
        ),
        PerformanceProfileDefinition(
            PerformanceProfile.PERFORMANCE, 160, 0, 120,
            CpuStrategy.RESPONSIVE, GpuStrategy.RESPONSIVE,
            ThermalStrategy.HEADROOM_AWARE, MonitorPreset.REALTIME,
        ),
        PerformanceProfileDefinition(
            PerformanceProfile.GAMING, 200, 0, 120,
            CpuStrategy.SUSTAINED, GpuStrategy.SUSTAINED,
            ThermalStrategy.SUSTAINABLE, MonitorPreset.REALTIME,
        ),
        PerformanceProfileDefinition(
            PerformanceProfile.SUSTAINABLE, 60, 0, 60,
            CpuStrategy.SUSTAINED, GpuStrategy.SUSTAINED,
            ThermalStrategy.SUSTAINABLE, MonitorPreset.BALANCED,
        ),
    )

    fun definition(profile: PerformanceProfile): PerformanceProfileDefinition? =
        definitions.firstOrNull { it.profile == profile }
}

data class ThermalGuardianConfig(
    val sustainableAtDeciCelsius: Int = 420,
    val ecoAtDeciCelsius: Int = 450,
    val restoreAtDeciCelsius: Int = 390,
    val cooldownMillis: Long = 60_000,
)

object ThermalGuardianConfigValidator {
    fun violations(config: ThermalGuardianConfig): List<String> = buildList {
        if (config.restoreAtDeciCelsius !in 250..440) {
            add("restoreAtDeciCelsius must be in 250..440")
        }
        if (config.sustainableAtDeciCelsius !in 350..500) {
            add("sustainableAtDeciCelsius must be in 350..500")
        }
        if (config.ecoAtDeciCelsius !in 400..550) {
            add("ecoAtDeciCelsius must be in 400..550")
        }
        if (config.restoreAtDeciCelsius >= config.sustainableAtDeciCelsius) {
            add("restore threshold must be below sustainable threshold")
        }
        if (config.sustainableAtDeciCelsius >= config.ecoAtDeciCelsius) {
            add("sustainable threshold must be below eco threshold")
        }
        if (config.cooldownMillis !in 5_000..3_600_000) {
            add("cooldownMillis must be in 5000..3600000")
        }
    }
}

sealed interface ThermalGuardianDecision {
    data object Hold : ThermalGuardianDecision
    data class Apply(val profile: PerformanceProfile) : ThermalGuardianDecision
    data object Restore : ThermalGuardianDecision
}

/** Pure hysteresis/cooldown policy. Hotter safety escalation never waits. */
class ThermalGuardianPolicy {
    fun decide(
        config: ThermalGuardianConfig,
        temperatureDeciCelsius: Int?,
        activeThermalProfile: PerformanceProfile?,
        lastTransitionMillis: Long?,
        nowMillis: Long,
    ): ThermalGuardianDecision {
        if (ThermalGuardianConfigValidator.violations(config).isNotEmpty()) {
            return ThermalGuardianDecision.Hold
        }
        val temperature = temperatureDeciCelsius ?: return ThermalGuardianDecision.Hold
        if (nowMillis < 0 || lastTransitionMillis?.let { it > nowMillis } == true) {
            return ThermalGuardianDecision.Hold
        }

        if (temperature >= config.ecoAtDeciCelsius) {
            return if (activeThermalProfile == PerformanceProfile.ECO) {
                ThermalGuardianDecision.Hold
            } else {
                ThermalGuardianDecision.Apply(PerformanceProfile.ECO)
            }
        }
        if (temperature >= config.sustainableAtDeciCelsius &&
            activeThermalProfile == null
        ) {
            return ThermalGuardianDecision.Apply(PerformanceProfile.SUSTAINABLE)
        }

        val cooldownElapsed = lastTransitionMillis == null ||
            nowMillis - lastTransitionMillis >= config.cooldownMillis
        if (temperature <= config.restoreAtDeciCelsius &&
            activeThermalProfile != null && cooldownElapsed
        ) {
            return ThermalGuardianDecision.Restore
        }
        return ThermalGuardianDecision.Hold
    }
}
