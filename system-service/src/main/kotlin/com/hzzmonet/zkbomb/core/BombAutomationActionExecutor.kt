package com.hzzmonet.zkbomb.core

import com.hzzmonet.zkbomb.api.BombResult
import com.hzzmonet.zkbomb.domain.automation.ResolvedAutomationAction
import com.hzzmonet.zkbomb.domain.freeze.FreezeMode

interface FreezeActionPort {
    fun currentMode(packageName: String, userId: Int): FreezeMode?
    fun setMode(packageName: String, userId: Int, mode: FreezeMode): BombResult
}

class BombAutomationActionExecutor(
    private val freeze: FreezeActionPort,
    private val performance: PerformanceProfileBackend,
) : AutomationActionExecutor {

    override fun capture(action: ResolvedAutomationAction): ResolvedAutomationAction? = when (action) {
        is ResolvedAutomationAction.SetPerformanceProfile,
        is ResolvedAutomationAction.RestorePerformanceTuning,
        -> performance.capture()
        is ResolvedAutomationAction.SetFreezeMode -> freeze.currentMode(
            action.packageName,
            action.userId,
        )?.let { previous ->
            ResolvedAutomationAction.SetFreezeMode(
                action.packageName,
                action.userId,
                previous,
            )
        }
    }

    override fun execute(action: ResolvedAutomationAction): BombResult = when (action) {
        is ResolvedAutomationAction.SetPerformanceProfile -> performance.apply(action.profile)
        is ResolvedAutomationAction.RestorePerformanceTuning -> performance.restore(action)
        is ResolvedAutomationAction.SetFreezeMode -> freeze.setMode(
            action.packageName,
            action.userId,
            action.mode,
        )
    }
}
