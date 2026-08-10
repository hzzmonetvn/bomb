package com.hzzmonet.zkbomb.domain.automation

import java.util.ArrayDeque

/**
 * Stateful conflict/suppression core for Bomb Rules.
 *
 * The engine never reads a clock and never performs an action. The Android
 * coordinator serializes calls, supplies timestamps, captures the pre-action
 * value, executes the plan, then acknowledges only successful plans here.
 */
class AutomationRuleEngine(
    private val historyLimit: Int = 200,
    private val manualOverrideMillis: Long = 30_000L,
) {
    private val lastTriggerAt = mutableMapOf<String, Long>()
    private val lastSuccessAt = mutableMapOf<String, Long>()
    private val currentApplied = mutableMapOf<String, ResolvedAutomationAction>()
    private val manualHoldUntil = mutableMapOf<String, Long>()
    private val activeRestores = mutableMapOf<Long, RestoreEntry>()
    private val history = ArrayDeque<AutomationHistoryEntry>()
    private var nextRestoreToken = 1L

    init {
        require(historyLimit in 1..10_000) { "historyLimit must be 1..10000" }
        require(manualOverrideMillis >= 0) { "manualOverrideMillis must be non-negative" }
    }

    fun evaluate(
        event: AutomationEvent,
        state: AutomationState,
        rules: Collection<AutomationRule>,
    ): RuleEvaluation {
        expireManualHolds(event.timestampMillis)
        val suppressed = mutableListOf<SuppressedRuleAction>()
        val plans = mutableListOf<AutomationPlan>()

        activeRestores.values
            .filter { it.matches(event) }
            .sortedBy { it.token }
            .forEach { restore ->
                if (currentApplied[restore.appliedAction.conflictKey] == restore.appliedAction) {
                    plans += AutomationPlan(
                        ruleId = restore.ruleId,
                        action = restore.previousAction,
                        kind = PlanKind.RESTORE,
                        event = event,
                        restoreToken = restore.token,
                    )
                } else {
                    activeRestores.remove(restore.token)
                }
            }

        val candidates = mutableListOf<Candidate>()
        rules.asSequence()
            .filter { it.enabled }
            .filter { AutomationRuleValidator.validate(it).isEmpty() }
            .filter { it.trigger == event.trigger }
            .filter { it.triggerPackageName == null || it.triggerPackageName == event.packageName }
            .filter { rule -> rule.conditions.all { it.matches(state) } }
            .forEach { rule ->
                if (event.origin == AutomationEventOrigin.RULE_ENGINE ||
                    rule.id in event.causationRuleIds
                ) {
                    rule.actions.forEach {
                        suppressed += SuppressedRuleAction(rule.id, SuppressionReason.LOOP_PREVENTION)
                    }
                    return@forEach
                }

                val previousTrigger = lastTriggerAt.put(rule.id, event.timestampMillis)
                if (previousTrigger != null &&
                    event.timestampMillis - previousTrigger < rule.debounceMillis
                ) {
                    rule.actions.forEach {
                        suppressed += SuppressedRuleAction(rule.id, SuppressionReason.DEBOUNCE)
                    }
                    return@forEach
                }

                val previousSuccess = lastSuccessAt[rule.id]
                if (previousSuccess != null &&
                    event.timestampMillis - previousSuccess < rule.cooldownMillis
                ) {
                    rule.actions.forEach {
                        suppressed += SuppressedRuleAction(rule.id, SuppressionReason.COOLDOWN)
                    }
                    return@forEach
                }

                rule.actions.forEach { storedAction ->
                    val resolved = resolve(storedAction, event)
                    if (resolved == null) {
                        suppressed += SuppressedRuleAction(
                            rule.id,
                            SuppressionReason.INVALID_DYNAMIC_TARGET,
                        )
                    } else if ((manualHoldUntil[resolved.conflictKey] ?: Long.MIN_VALUE) >
                        event.timestampMillis
                    ) {
                        suppressed += SuppressedRuleAction(
                            rule.id,
                            SuppressionReason.MANUAL_OVERRIDE,
                            resolved,
                        )
                    } else if (currentApplied[resolved.conflictKey] == resolved) {
                        suppressed += SuppressedRuleAction(
                            rule.id,
                            SuppressionReason.REPEATED_ACTION,
                            resolved,
                        )
                    } else {
                        candidates += Candidate(rule, resolved)
                    }
                }
            }

        candidates.groupBy { it.action.conflictKey }.values.forEach { conflicts ->
            val winner = conflicts.maxWithOrNull(CANDIDATE_ORDER) ?: return@forEach
            plans += AutomationPlan(
                ruleId = winner.rule.id,
                action = winner.action,
                kind = PlanKind.APPLY,
                event = event,
                restorePolicy = winner.rule.restorePolicy,
            )
            conflicts.filterNot { it === winner }.forEach { loser ->
                suppressed += SuppressedRuleAction(
                    loser.rule.id,
                    SuppressionReason.CONFLICT,
                    loser.action,
                )
            }
        }

        return RuleEvaluation(
            // Restore first. If the opposite event also activates a new rule on
            // the same target, that new apply must be the final state.
            plans = plans.sortedWith(
                compareBy<AutomationPlan> { if (it.kind == PlanKind.RESTORE) 0 else 1 }
                    .thenBy { it.ruleId },
            ),
            suppressed = suppressed,
        )
    }

    /** Acknowledge only after the privileged backend verified the new state. */
    fun recordSuccess(
        plan: AutomationPlan,
        previousAction: ResolvedAutomationAction?,
        timestampMillis: Long = plan.event.timestampMillis,
    ) {
        currentApplied[plan.action.conflictKey] = plan.action
        lastSuccessAt[plan.ruleId] = timestampMillis
        when (plan.kind) {
            PlanKind.APPLY -> {
                removeRestoresFor(plan.action.conflictKey)
                if (plan.restorePolicy == RestorePolicy.ON_OPPOSITE_TRIGGER &&
                    previousAction != null &&
                    previousAction.conflictKey == plan.action.conflictKey &&
                    previousAction != plan.action
                ) {
                    val token = nextRestoreToken++
                    activeRestores[token] = RestoreEntry(
                        token = token,
                        ruleId = plan.ruleId,
                        appliedAction = plan.action,
                        previousAction = previousAction,
                        restoreTrigger = plan.event.trigger.opposite(),
                        eventPackageName = plan.event.packageName,
                    )
                }
            }
            PlanKind.RESTORE -> plan.restoreToken?.let(activeRestores::remove)
        }
        history.addLast(
            AutomationHistoryEntry(plan.ruleId, plan.action, plan.kind, timestampMillis),
        )
        while (history.size > historyLimit) history.removeFirst()
    }

    /** Manual actions invalidate pending restore and temporarily hold the target. */
    fun recordManualAction(action: ResolvedAutomationAction, timestampMillis: Long) {
        currentApplied[action.conflictKey] = action
        removeRestoresFor(action.conflictKey)
        manualHoldUntil[action.conflictKey] = saturatingAdd(timestampMillis, manualOverrideMillis)
    }

    fun history(): List<AutomationHistoryEntry> = history.toList()

    fun clearRule(ruleId: String) {
        lastTriggerAt.remove(ruleId)
        lastSuccessAt.remove(ruleId)
        activeRestores.entries.removeAll { it.value.ruleId == ruleId }
    }

    fun clearRuntimeState() {
        lastTriggerAt.clear()
        lastSuccessAt.clear()
        currentApplied.clear()
        manualHoldUntil.clear()
        activeRestores.clear()
        history.clear()
    }

    private fun resolve(
        action: RuleAction,
        event: AutomationEvent,
    ): ResolvedAutomationAction? = when (action) {
        is RuleAction.SetPerformanceProfile ->
            ResolvedAutomationAction.SetPerformanceProfile(action.profile)
        is RuleAction.SetFreezeMode -> {
            val target = action.packageName ?: event.packageName ?: return null
            if (!com.hzzmonet.zkbomb.domain.freeze.PackageNameValidator.isValid(target)) return null
            ResolvedAutomationAction.SetFreezeMode(target, action.userId, action.mode)
        }
    }

    private fun expireManualHolds(now: Long) {
        manualHoldUntil.entries.removeAll { it.value <= now }
    }

    private fun removeRestoresFor(conflictKey: String) {
        activeRestores.entries.removeAll { it.value.appliedAction.conflictKey == conflictKey }
    }

    private data class Candidate(
        val rule: AutomationRule,
        val action: ResolvedAutomationAction,
    )

    private data class RestoreEntry(
        val token: Long,
        val ruleId: String,
        val appliedAction: ResolvedAutomationAction,
        val previousAction: ResolvedAutomationAction,
        val restoreTrigger: AutomationTrigger,
        val eventPackageName: String?,
    ) {
        fun matches(event: AutomationEvent): Boolean =
            event.trigger == restoreTrigger &&
                (eventPackageName == null || event.packageName == eventPackageName)
    }

    private companion object {
        val CANDIDATE_ORDER = compareBy<Candidate>(
            { it.rule.scope.precedence },
            { it.rule.priority },
            // Stable final tie-breaker. Lexically larger ids win ties.
            { it.rule.id },
        )

        fun saturatingAdd(left: Long, right: Long): Long =
            if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
    }
}
