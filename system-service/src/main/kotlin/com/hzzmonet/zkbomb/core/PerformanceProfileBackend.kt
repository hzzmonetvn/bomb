package com.hzzmonet.zkbomb.core

import com.hzzmonet.zkbomb.api.BombResult
import com.hzzmonet.zkbomb.domain.automation.PerformanceProfile
import com.hzzmonet.zkbomb.domain.automation.ResolvedAutomationAction
import com.hzzmonet.zkbomb.domain.performance.PerformanceProfileCatalog

interface MemoryTuningPort {
    fun available(): Boolean
    fun currentSwappiness(): Int?
    fun currentPageCluster(): Int?
    fun request(swappiness: Int, pageCluster: Int): Boolean
    fun waitBeforeVerification()
}

/** Applies the memory part of a profile; dynamic CPU/GPU limits use FrequencyScalingBackend. */
class PerformanceProfileBackend(private val port: MemoryTuningPort) {

    fun available(): Boolean = port.available() && capture() != null

    fun capture(): ResolvedAutomationAction.RestorePerformanceTuning? {
        val swappiness = port.currentSwappiness() ?: return null
        val pageCluster = port.currentPageCluster() ?: return null
        return ResolvedAutomationAction.RestorePerformanceTuning(swappiness, pageCluster)
    }

    fun apply(profile: PerformanceProfile): BombResult {
        val tuning = PerformanceProfileCatalog.definition(profile)
            ?: return BombResult.unsupported("CUSTOM profile has no validated tuning")
        return applyExact(tuning.swappiness, tuning.pageCluster)
    }

    fun matches(profile: PerformanceProfile): Boolean {
        val tuning = PerformanceProfileCatalog.definition(profile) ?: return false
        return port.currentSwappiness() == tuning.swappiness &&
            port.currentPageCluster() == tuning.pageCluster
    }

    fun restore(action: ResolvedAutomationAction.RestorePerformanceTuning): BombResult =
        applyExact(action.swappiness, action.pageCluster)

    private fun applyExact(swappiness: Int, pageCluster: Int): BombResult {
        if (swappiness !in 0..200 || pageCluster !in 0..3) {
            return BombResult.invalidArgument("Captured memory tuning is outside safe bounds")
        }
        if (!port.available()) {
            return BombResult.unsupported("Integrated ROM memory control is unavailable")
        }
        if (port.currentSwappiness() == swappiness && port.currentPageCluster() == pageCluster) {
            return BombResult.success()
        }
        if (!port.request(swappiness, pageCluster)) {
            return BombResult.backendUnavailable("ROM memory profile request was rejected")
        }
        repeat(VERIFY_ATTEMPTS) {
            if (port.currentSwappiness() == swappiness &&
                port.currentPageCluster() == pageCluster
            ) {
                return BombResult.success()
            }
            port.waitBeforeVerification()
        }
        return BombResult.failed(
            "Profile read-back differs: swappiness=${port.currentSwappiness()}, " +
                "page-cluster=${port.currentPageCluster()}",
        )
    }

    private companion object {
        const val VERIFY_ATTEMPTS = 5
    }
}
