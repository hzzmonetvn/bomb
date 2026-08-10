package com.hzzmonet.zkbomb.api

import android.os.Parcelable
import com.hzzmonet.zkbomb.domain.automation.AutomationCondition
import com.hzzmonet.zkbomb.domain.automation.AutomationRule
import com.hzzmonet.zkbomb.domain.automation.AutomationRuleValidator
import com.hzzmonet.zkbomb.domain.automation.AutomationTrigger
import com.hzzmonet.zkbomb.domain.automation.PerformanceProfile
import com.hzzmonet.zkbomb.domain.automation.RestorePolicy
import com.hzzmonet.zkbomb.domain.automation.RuleAction
import com.hzzmonet.zkbomb.domain.automation.RuleScope
import com.hzzmonet.zkbomb.domain.automation.ScreenState
import com.hzzmonet.zkbomb.domain.freeze.FreezeMode
import kotlinx.parcelize.Parcelize

enum class AutomationConditionType {
    SCREEN_IS,
    FOREGROUND_PACKAGE_IS,
    FOREGROUND_PACKAGE_IS_NOT,
}

enum class AutomationActionType {
    SET_PERFORMANCE_PROFILE,
    SET_FREEZE_MODE,
}

@Parcelize
data class AutomationConditionParcel(
    val type: String,
    val value: String,
) : Parcelable {
    fun toDomain(): AutomationCondition? = when (
        runCatching { AutomationConditionType.valueOf(type) }.getOrNull()
    ) {
        AutomationConditionType.SCREEN_IS -> runCatching {
            AutomationCondition.ScreenIs(ScreenState.valueOf(value))
        }.getOrNull()
        AutomationConditionType.FOREGROUND_PACKAGE_IS ->
            AutomationCondition.ForegroundPackageIs(value)
        AutomationConditionType.FOREGROUND_PACKAGE_IS_NOT ->
            AutomationCondition.ForegroundPackageIsNot(value)
        null -> null
    }

    companion object {
        fun fromDomain(condition: AutomationCondition): AutomationConditionParcel = when (condition) {
            is AutomationCondition.ScreenIs -> AutomationConditionParcel(
                AutomationConditionType.SCREEN_IS.name,
                condition.required.name,
            )
            is AutomationCondition.ForegroundPackageIs -> AutomationConditionParcel(
                AutomationConditionType.FOREGROUND_PACKAGE_IS.name,
                condition.packageName,
            )
            is AutomationCondition.ForegroundPackageIsNot -> AutomationConditionParcel(
                AutomationConditionType.FOREGROUND_PACKAGE_IS_NOT.name,
                condition.packageName,
            )
        }
    }
}

@Parcelize
data class AutomationActionParcel(
    val type: String,
    /** Profile or FreezeMode enum name, depending on [type]. */
    val value: String,
    val packageName: String? = null,
    val userId: Int = 0,
) : Parcelable {
    fun toDomain(): RuleAction? = when (
        runCatching { AutomationActionType.valueOf(type) }.getOrNull()
    ) {
        AutomationActionType.SET_PERFORMANCE_PROFILE -> runCatching {
            RuleAction.SetPerformanceProfile(PerformanceProfile.valueOf(value))
        }.getOrNull()
        AutomationActionType.SET_FREEZE_MODE -> runCatching {
            RuleAction.SetFreezeMode(packageName, userId, FreezeMode.valueOf(value))
        }.getOrNull()
        null -> null
    }

    companion object {
        fun fromDomain(action: RuleAction): AutomationActionParcel = when (action) {
            is RuleAction.SetPerformanceProfile -> AutomationActionParcel(
                type = AutomationActionType.SET_PERFORMANCE_PROFILE.name,
                value = action.profile.name,
            )
            is RuleAction.SetFreezeMode -> AutomationActionParcel(
                type = AutomationActionType.SET_FREEZE_MODE.name,
                value = action.mode.name,
                packageName = action.packageName,
                userId = action.userId,
            )
        }
    }
}

/** Bounded wire representation of one Bomb Rule. */
@Parcelize
data class AutomationRuleParcel(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val trigger: String,
    val triggerPackageName: String?,
    val conditions: List<AutomationConditionParcel>,
    val actions: List<AutomationActionParcel>,
    val scope: String,
    val priority: Int,
    val cooldownMillis: Long,
    val debounceMillis: Long,
    val restorePolicy: String,
) : Parcelable {
    fun toDomain(): AutomationRule? {
        if (conditions.size > 16 || actions.size !in 1..16) return null
        val parsedConditions = conditions.map { it.toDomain() ?: return null }
        val parsedActions = actions.map { it.toDomain() ?: return null }
        val rule = runCatching {
            AutomationRule(
                id = id,
                name = name,
                enabled = enabled,
                trigger = AutomationTrigger.valueOf(trigger),
                triggerPackageName = triggerPackageName,
                conditions = parsedConditions,
                actions = parsedActions,
                scope = RuleScope.valueOf(scope),
                priority = priority,
                cooldownMillis = cooldownMillis,
                debounceMillis = debounceMillis,
                restorePolicy = RestorePolicy.valueOf(restorePolicy),
            )
        }.getOrNull() ?: return null
        return rule.takeIf { AutomationRuleValidator.validate(it).isEmpty() }
    }

    companion object {
        fun fromDomain(rule: AutomationRule): AutomationRuleParcel = AutomationRuleParcel(
            id = rule.id,
            name = rule.name,
            enabled = rule.enabled,
            trigger = rule.trigger.name,
            triggerPackageName = rule.triggerPackageName,
            conditions = rule.conditions.map(AutomationConditionParcel::fromDomain),
            actions = rule.actions.map(AutomationActionParcel::fromDomain),
            scope = rule.scope.name,
            priority = rule.priority,
            cooldownMillis = rule.cooldownMillis,
            debounceMillis = rule.debounceMillis,
            restorePolicy = rule.restorePolicy.name,
        )
    }
}

@Parcelize
data class AutomationRulesSnapshot(
    val enabled: Boolean,
    val observerRunning: Boolean,
    val rules: List<AutomationRuleParcel>,
    val lastTrigger: String?,
    val lastTriggeredAtMillis: Long?,
) : Parcelable
