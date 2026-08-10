package com.hzzmonet.zkbomb.domain.automation

import com.hzzmonet.zkbomb.domain.freeze.FreezeMode
import com.hzzmonet.zkbomb.domain.freeze.PackageNameValidator

/** Event types currently emitted by the Android automation signal source. */
enum class AutomationTrigger {
    APP_FOREGROUND,
    APP_BACKGROUND,
    SCREEN_ON,
    SCREEN_OFF,
}

enum class ScreenState { ON, OFF }

/** Higher values win when two matching rules write the same target. */
enum class RuleScope(val precedence: Int) {
    GLOBAL(0),
    PER_APP(1),
    SAFETY(2),
}

enum class RestorePolicy {
    NONE,
    /** Restore the value captured immediately before apply on the inverse trigger. */
    ON_OPPOSITE_TRIGGER,
}

enum class AutomationEventOrigin { EXTERNAL, RULE_ENGINE }

enum class PerformanceProfile {
    ECO,
    BALANCED,
    PERFORMANCE,
    GAMING,
    SUSTAINABLE,
    CUSTOM,
}

data class AutomationEvent(
    val trigger: AutomationTrigger,
    val timestampMillis: Long,
    val packageName: String? = null,
    val userId: Int = 0,
    val origin: AutomationEventOrigin = AutomationEventOrigin.EXTERNAL,
    val causationRuleIds: Set<String> = emptySet(),
) {
    init {
        require(timestampMillis >= 0) { "timestampMillis must be non-negative" }
        require(userId >= 0) { "userId must be non-negative" }
    }
}

/** Device/app state after [AutomationEvent] has been incorporated. */
data class AutomationState(
    val screenState: ScreenState,
    val foregroundPackage: String?,
    val userId: Int = 0,
)

sealed interface AutomationCondition {
    fun matches(state: AutomationState): Boolean

    data class ScreenIs(val required: ScreenState) : AutomationCondition {
        override fun matches(state: AutomationState): Boolean = state.screenState == required
    }

    data class ForegroundPackageIs(val packageName: String) : AutomationCondition {
        override fun matches(state: AutomationState): Boolean =
            state.foregroundPackage == packageName
    }

    data class ForegroundPackageIsNot(val packageName: String) : AutomationCondition {
        override fun matches(state: AutomationState): Boolean =
            state.foregroundPackage != packageName
    }
}

/** An action as stored in a rule. A null freeze package means the event package. */
sealed interface RuleAction {
    data class SetPerformanceProfile(val profile: PerformanceProfile) : RuleAction

    data class SetFreezeMode(
        val packageName: String? = null,
        val userId: Int = 0,
        val mode: FreezeMode,
    ) : RuleAction
}

/** Fully resolved action handed to a privileged backend. */
sealed interface ResolvedAutomationAction {
    val conflictKey: String

    data class SetPerformanceProfile(val profile: PerformanceProfile) : ResolvedAutomationAction {
        override val conflictKey: String = "performance-profile"
    }

    /** Exact captured values used only by restore; rules cannot construct this. */
    data class RestorePerformanceTuning(
        val swappiness: Int,
        val pageCluster: Int,
    ) : ResolvedAutomationAction {
        override val conflictKey: String = "performance-profile"
    }

    data class SetFreezeMode(
        val packageName: String,
        val userId: Int,
        val mode: FreezeMode,
    ) : ResolvedAutomationAction {
        override val conflictKey: String = "freeze:$userId:$packageName"
    }
}

data class AutomationRule(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val trigger: AutomationTrigger,
    /** Exact app match for APP_* triggers; null means any app. */
    val triggerPackageName: String? = null,
    val conditions: List<AutomationCondition> = emptyList(),
    val actions: List<RuleAction>,
    val scope: RuleScope = RuleScope.GLOBAL,
    val priority: Int = 0,
    val cooldownMillis: Long = DEFAULT_COOLDOWN_MILLIS,
    val debounceMillis: Long = DEFAULT_DEBOUNCE_MILLIS,
    val restorePolicy: RestorePolicy = RestorePolicy.NONE,
) {
    companion object {
        const val DEFAULT_COOLDOWN_MILLIS = 30_000L
        const val DEFAULT_DEBOUNCE_MILLIS = 1_000L
    }
}

enum class PlanKind { APPLY, RESTORE }

data class AutomationPlan(
    val ruleId: String,
    val action: ResolvedAutomationAction,
    val kind: PlanKind,
    val event: AutomationEvent,
    val restorePolicy: RestorePolicy = RestorePolicy.NONE,
    /** Internal identity for a pending restore; null for normal applies. */
    val restoreToken: Long? = null,
)

enum class SuppressionReason {
    DEBOUNCE,
    COOLDOWN,
    CONFLICT,
    REPEATED_ACTION,
    MANUAL_OVERRIDE,
    LOOP_PREVENTION,
    INVALID_DYNAMIC_TARGET,
}

data class SuppressedRuleAction(
    val ruleId: String,
    val reason: SuppressionReason,
    val action: ResolvedAutomationAction? = null,
)

data class RuleEvaluation(
    val plans: List<AutomationPlan>,
    val suppressed: List<SuppressedRuleAction>,
)

data class AutomationHistoryEntry(
    val ruleId: String,
    val action: ResolvedAutomationAction,
    val kind: PlanKind,
    val timestampMillis: Long,
)

object AutomationRuleValidator {
    private val idPattern = Regex("[A-Za-z0-9._-]{1,128}")

    fun validate(rule: AutomationRule): List<String> = buildList {
        if (!idPattern.matches(rule.id)) add("id")
        if (rule.name.isBlank() || rule.name.length > 256) add("name")
        if (rule.priority !in 0..1_000) add("priority")
        if (rule.cooldownMillis !in 0..86_400_000L) add("cooldownMillis")
        if (rule.debounceMillis !in 0..60_000L) add("debounceMillis")
        if (rule.actions.isEmpty() || rule.actions.size > 16) add("actions")
        if (rule.conditions.size > 16) add("conditions")
        if (rule.triggerPackageName != null &&
            !PackageNameValidator.isValid(rule.triggerPackageName)
        ) {
            add("triggerPackageName")
        }
        if (rule.scope == RuleScope.PER_APP && rule.triggerPackageName == null) {
            add("PER_APP requires triggerPackageName")
        }
        rule.conditions.forEach { condition ->
            val packageName = when (condition) {
                is AutomationCondition.ForegroundPackageIs -> condition.packageName
                is AutomationCondition.ForegroundPackageIsNot -> condition.packageName
                is AutomationCondition.ScreenIs -> null
            }
            if (packageName != null && !PackageNameValidator.isValid(packageName)) {
                add("condition.packageName")
            }
        }
        rule.actions.forEach { action ->
            if (action is RuleAction.SetFreezeMode) {
                if (action.userId !in 0..9_999) add("action.userId")
                if (action.packageName != null && !PackageNameValidator.isValid(action.packageName)) {
                    add("action.packageName")
                }
                if (action.packageName == null &&
                    rule.trigger !in setOf(
                        AutomationTrigger.APP_FOREGROUND,
                        AutomationTrigger.APP_BACKGROUND,
                    )
                ) {
                    add("screen-triggered freeze requires action.packageName")
                }
                if (action.mode == FreezeMode.DISABLED) {
                    add("automation may not disable applications")
                }
            }
        }
    }.distinct()
}

internal fun AutomationTrigger.opposite(): AutomationTrigger = when (this) {
    AutomationTrigger.APP_FOREGROUND -> AutomationTrigger.APP_BACKGROUND
    AutomationTrigger.APP_BACKGROUND -> AutomationTrigger.APP_FOREGROUND
    AutomationTrigger.SCREEN_ON -> AutomationTrigger.SCREEN_OFF
    AutomationTrigger.SCREEN_OFF -> AutomationTrigger.SCREEN_ON
}
