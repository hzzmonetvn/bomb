package com.hzzmonet.zkbomb.core

import com.hzzmonet.zkbomb.api.AutomationRuleParcel
import com.hzzmonet.zkbomb.api.AutomationRulesSnapshot
import com.hzzmonet.zkbomb.api.BombResult
import com.hzzmonet.zkbomb.domain.automation.AutomationEvent
import com.hzzmonet.zkbomb.domain.automation.AutomationRuleEngine
import com.hzzmonet.zkbomb.domain.automation.AutomationState
import com.hzzmonet.zkbomb.domain.automation.AutomationTrigger
import com.hzzmonet.zkbomb.domain.automation.ResolvedAutomationAction
import com.hzzmonet.zkbomb.domain.automation.ScreenState

interface AutomationActionExecutor {
    /** Exact pre-action value, used by ON_OPPOSITE_TRIGGER restore. */
    fun capture(action: ResolvedAutomationAction): ResolvedAutomationAction?
    fun execute(action: ResolvedAutomationAction): BombResult
}

data class AutomationExecution(
    val ruleId: String,
    val action: ResolvedAutomationAction,
    val result: BombResult,
)

class AutomationCoordinator(
    private val repository: AutomationRuleRepository,
    private val engine: AutomationRuleEngine,
    private val executor: AutomationActionExecutor,
    screenInitiallyOn: Boolean,
) {
    private var state = AutomationState(
        screenState = if (screenInitiallyOn) ScreenState.ON else ScreenState.OFF,
        foregroundPackage = null,
    )
    private var observerRunning = false
    private var lastEvent: AutomationEvent? = null

    @Synchronized
    fun onEvent(event: AutomationEvent): List<AutomationExecution> {
        state = reduce(state, event)
        lastEvent = event
        if (!repository.isEnabled()) return emptyList()

        val evaluation = engine.evaluate(event, state, repository.rules())
        return evaluation.plans.map { plan ->
            val previous = executor.capture(plan.action)
            val result = executor.execute(plan.action)
            if (result.isSuccess) {
                engine.recordSuccess(plan, previous)
            }
            AutomationExecution(plan.ruleId, plan.action, result)
        }
    }

    @Synchronized
    fun upsert(rule: AutomationRuleParcel): BombResult {
        val domain = rule.toDomain() ?: return BombResult.invalidArgument("rule")
        return if (repository.upsert(domain)) {
            engine.clearRule(domain.id)
            BombResult.success()
        } else {
            BombResult.failed("Rule limit reached")
        }
    }

    @Synchronized
    fun delete(ruleId: String): BombResult {
        if (!RULE_ID.matches(ruleId)) return BombResult.invalidArgument("ruleId")
        val removed = repository.delete(ruleId)
        engine.clearRule(ruleId)
        return if (removed) BombResult.success() else BombResult.invalidArgument("Unknown ruleId")
    }

    @Synchronized
    fun setEnabled(enabled: Boolean) {
        repository.setEnabled(enabled)
        if (!enabled) engine.clearRuntimeState()
    }

    @Synchronized
    fun setObserverRunning(running: Boolean) {
        observerRunning = running
    }

    @Synchronized
    fun snapshot(): AutomationRulesSnapshot = AutomationRulesSnapshot(
        enabled = repository.isEnabled(),
        observerRunning = observerRunning,
        rules = repository.rules().map(AutomationRuleParcel::fromDomain),
        lastTrigger = lastEvent?.trigger?.name,
        lastTriggeredAtMillis = lastEvent?.timestampMillis,
    )

    @Synchronized
    fun recordManualAction(action: ResolvedAutomationAction, timestampMillis: Long) {
        engine.recordManualAction(action, timestampMillis)
    }

    private fun reduce(current: AutomationState, event: AutomationEvent): AutomationState =
        when (event.trigger) {
            AutomationTrigger.APP_FOREGROUND -> current.copy(
                foregroundPackage = event.packageName,
                userId = event.userId,
            )
            AutomationTrigger.APP_BACKGROUND -> current.copy(
                foregroundPackage = current.foregroundPackage.takeUnless { it == event.packageName },
                userId = event.userId,
            )
            AutomationTrigger.SCREEN_ON -> current.copy(screenState = ScreenState.ON)
            AutomationTrigger.SCREEN_OFF -> current.copy(screenState = ScreenState.OFF)
        }

    private companion object {
        val RULE_ID = Regex("[A-Za-z0-9._-]{1,128}")
    }
}
