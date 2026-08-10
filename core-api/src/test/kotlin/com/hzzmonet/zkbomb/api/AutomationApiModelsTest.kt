package com.hzzmonet.zkbomb.api

import com.hzzmonet.zkbomb.domain.automation.AutomationCondition
import com.hzzmonet.zkbomb.domain.automation.AutomationRule
import com.hzzmonet.zkbomb.domain.automation.AutomationTrigger
import com.hzzmonet.zkbomb.domain.automation.PerformanceProfile
import com.hzzmonet.zkbomb.domain.automation.RestorePolicy
import com.hzzmonet.zkbomb.domain.automation.RuleAction
import com.hzzmonet.zkbomb.domain.automation.RuleScope
import com.hzzmonet.zkbomb.domain.automation.ScreenState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AutomationApiModelsTest {
    @Test
    fun `rule wire model round trips without enum ordinals`() {
        val rule = AutomationRule(
            id = "game",
            name = "Gaming",
            trigger = AutomationTrigger.APP_FOREGROUND,
            triggerPackageName = "com.example.game",
            conditions = listOf(AutomationCondition.ScreenIs(ScreenState.ON)),
            actions = listOf(RuleAction.SetPerformanceProfile(PerformanceProfile.GAMING)),
            scope = RuleScope.PER_APP,
            priority = 42,
            cooldownMillis = 30_000,
            debounceMillis = 1_000,
            restorePolicy = RestorePolicy.ON_OPPOSITE_TRIGGER,
        )
        assertEquals(rule, AutomationRuleParcel.fromDomain(rule).toDomain())
    }

    @Test
    fun `wire model rejects unknown enum and unbounded action list`() {
        val valid = AutomationRuleParcel.fromDomain(
            AutomationRule(
                id = "rule",
                name = "Rule",
                trigger = AutomationTrigger.SCREEN_OFF,
                actions = listOf(
                    RuleAction.SetPerformanceProfile(PerformanceProfile.ECO),
                ),
            ),
        )
        assertNotNull(valid.toDomain())
        assertNull(valid.copy(trigger = "SOMEDAY").toDomain())
        assertNull(valid.copy(actions = List(17) { valid.actions.single() }).toDomain())
    }
}
