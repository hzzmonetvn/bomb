package com.hzzmonet.zkbomb.core

import com.hzzmonet.zkbomb.api.BombResult
import com.hzzmonet.zkbomb.api.PerformanceProfileParcel
import com.hzzmonet.zkbomb.api.PerformanceProfilesSnapshot
import com.hzzmonet.zkbomb.api.ThermalGuardianConfigParcel
import com.hzzmonet.zkbomb.domain.automation.PerformanceProfile
import com.hzzmonet.zkbomb.domain.automation.ResolvedAutomationAction
import com.hzzmonet.zkbomb.domain.performance.PerformanceProfileCatalog
import com.hzzmonet.zkbomb.domain.performance.ThermalGuardianConfig
import com.hzzmonet.zkbomb.domain.performance.ThermalGuardianConfigValidator
import com.hzzmonet.zkbomb.domain.performance.ThermalGuardianDecision
import com.hzzmonet.zkbomb.domain.performance.ThermalGuardianPolicy

/**
 * Owns manual and temperature-driven profile transitions. A restore is issued
 * only while the currently observed tuning still matches the value Bomb wrote.
 */
class PerformanceProfileCoordinator(
    private val backend: PerformanceProfileBackend,
    private val policy: ThermalGuardianPolicy = ThermalGuardianPolicy(),
) {
    private var activeProfile: PerformanceProfile? = null
    private var activeBaseline: ResolvedAutomationAction.RestorePerformanceTuning? = null
    private var thermalConfig: ThermalGuardianConfig? = null
    private var thermalActiveProfile: PerformanceProfile? = null
    private var thermalBaseline: ResolvedAutomationAction.RestorePerformanceTuning? = null
    private var lastThermalTransitionMillis: Long? = null

    @Synchronized
    fun setProfile(profile: PerformanceProfile): BombResult {
        if (PerformanceProfileCatalog.definition(profile) == null) {
            return BombResult.unsupported("CUSTOM profile has no validated tuning")
        }
        clearThermalOverride()?.let { if (!it.isSuccess) return it }
        clearManualProfile()?.let { if (!it.isSuccess) return it }

        val baseline = backend.capture()
            ?: return BombResult.backendUnavailable("Current memory tuning is unreadable")
        val result = backend.apply(profile)
        if (result.isSuccess) {
            activeProfile = profile
            activeBaseline = baseline
        }
        return result
    }

    @Synchronized
    fun clearProfile(): BombResult {
        clearThermalOverride()?.let { if (!it.isSuccess) return it }
        return clearManualProfile() ?: BombResult.success()
    }

    @Synchronized
    fun setThermalGuardian(config: ThermalGuardianConfig): BombResult {
        ThermalGuardianConfigValidator.violations(config).firstOrNull()?.let {
            return BombResult.invalidArgument(it)
        }
        if (!backend.available()) {
            return BombResult.unsupported("Performance memory control is unavailable")
        }
        thermalConfig = config
        return BombResult.success()
    }

    @Synchronized
    fun clearThermalGuardian(): BombResult {
        val restore = clearThermalOverride()
        thermalConfig = null
        lastThermalTransitionMillis = null
        return restore ?: BombResult.success()
    }

    @Synchronized
    fun evaluateTemperature(temperatureDeciCelsius: Int?, nowMillis: Long): BombResult {
        val config = thermalConfig ?: return BombResult.success()
        return when (
            val decision = policy.decide(
                config,
                temperatureDeciCelsius,
                thermalActiveProfile,
                lastThermalTransitionMillis,
                nowMillis,
            )
        ) {
            ThermalGuardianDecision.Hold -> BombResult.success()
            ThermalGuardianDecision.Restore ->
                clearThermalOverride() ?: BombResult.success()
            is ThermalGuardianDecision.Apply -> applyThermal(decision.profile, nowMillis)
        }
    }

    @Synchronized
    fun hasThermalGuardian(): Boolean = thermalConfig != null

    @Synchronized
    fun snapshot(): PerformanceProfilesSnapshot = PerformanceProfilesSnapshot(
        profiles = PerformanceProfileCatalog.definitions.map { PerformanceProfileParcel.fromDomain(it) },
        activeProfile = activeProfile?.name,
        memoryControlAvailable = backend.available(),
        appliedFields = if (backend.available()) listOf("swappiness", "pageCluster") else emptyList(),
        thermalGuardianConfig = thermalConfig?.let { ThermalGuardianConfigParcel.fromDomain(it) },
        thermalGuardianActiveProfile = thermalActiveProfile?.name,
    )

    private fun applyThermal(profile: PerformanceProfile, nowMillis: Long): BombResult {
        val baseline = thermalBaseline ?: backend.capture()
            ?: return BombResult.backendUnavailable("Current memory tuning is unreadable")
        val result = backend.apply(profile)
        if (result.isSuccess) {
            thermalBaseline = baseline
            thermalActiveProfile = profile
            lastThermalTransitionMillis = nowMillis
        }
        return result
    }

    private fun clearThermalOverride(): BombResult? {
        val profile = thermalActiveProfile ?: return null
        val baseline = thermalBaseline
        thermalActiveProfile = null
        thermalBaseline = null
        lastThermalTransitionMillis = null
        if (baseline == null) return BombResult.failed("Thermal baseline is missing")
        if (!backend.matches(profile)) {
            return BombResult.failed("Performance tuning changed outside Bomb; baseline was not restored")
        }
        return backend.restore(baseline)
    }

    private fun clearManualProfile(): BombResult? {
        val profile = activeProfile ?: return null
        val baseline = activeBaseline
        activeProfile = null
        activeBaseline = null
        if (baseline == null) return BombResult.failed("Performance baseline is missing")
        if (!backend.matches(profile)) {
            return BombResult.failed("Performance tuning changed outside Bomb; baseline was not restored")
        }
        return backend.restore(baseline)
    }
}
