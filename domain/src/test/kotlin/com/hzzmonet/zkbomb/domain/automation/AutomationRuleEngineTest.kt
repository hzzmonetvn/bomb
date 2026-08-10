package com.hzzmonet.zkbomb.domain.automation

import com.hzzmonet.zkbomb.domain.freeze.FreezeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationRuleEngineTest {

    @Test
    fun `app trigger and conditions must both match`() {
        val engine = AutomationRuleEngine()
        val rule = profileRule(
            triggerPackage = "com.example.game",
            conditions = listOf(AutomationCondition.ScreenIs(ScreenState.ON)),
        )

        assertTrue(
            engine.evaluate(
                event(AutomationTrigger.APP_FOREGROUND, "com.example.other"),
                state(ScreenState.ON, "com.example.other"),
                listOf(rule),
            ).plans.isEmpty(),
        )
        assertTrue(
            engine.evaluate(
                event(AutomationTrigger.APP_FOREGROUND, "com.example.game", at = 2_000),
                state(ScreenState.OFF, "com.example.game"),
                listOf(rule),
            ).plans.isEmpty(),
        )
        assertEquals(
            PerformanceProfile.GAMING,
            (engine.evaluate(
                event(AutomationTrigger.APP_FOREGROUND, "com.example.game", at = 4_000),
                state(ScreenState.ON, "com.example.game"),
                listOf(rule),
            ).plans.single().action as ResolvedAutomationAction.SetPerformanceProfile).profile,
        )
    }

    @Test
    fun `safety beats per-app and per-app beats global before priority`() {
        val engine = AutomationRuleEngine()
        val rules = listOf(
            profileRule("global", RuleScope.GLOBAL, priority = 1_000, PerformanceProfile.PERFORMANCE),
            profileRule("app", RuleScope.PER_APP, priority = 0, PerformanceProfile.GAMING),
            profileRule("safety", RuleScope.SAFETY, priority = 0, PerformanceProfile.ECO),
        )

        val result = engine.evaluate(
            event(AutomationTrigger.APP_FOREGROUND, "com.example.game"),
            state(ScreenState.ON, "com.example.game"),
            rules,
        )

        assertEquals("safety", result.plans.single().ruleId)
        assertEquals(2, result.suppressed.count { it.reason == SuppressionReason.CONFLICT })
    }

    @Test
    fun `higher priority wins inside the same scope`() {
        val engine = AutomationRuleEngine()
        val result = engine.evaluate(
            event(AutomationTrigger.APP_FOREGROUND, "com.example.game"),
            state(ScreenState.ON, "com.example.game"),
            listOf(
                profileRule("low", RuleScope.PER_APP, 10, PerformanceProfile.ECO),
                profileRule("high", RuleScope.PER_APP, 20, PerformanceProfile.GAMING),
            ),
        )
        assertEquals("high", result.plans.single().ruleId)
    }

    @Test
    fun `debounce cooldown and repeated action are separate suppressions`() {
        val engine = AutomationRuleEngine()
        val rule = profileRule().copy(debounceMillis = 500, cooldownMillis = 2_000)
        val firstEvent = event(AutomationTrigger.APP_FOREGROUND, "com.example.game", 1_000)
        val first = engine.evaluate(firstEvent, state(ScreenState.ON, "com.example.game"), listOf(rule))
        engine.recordSuccess(first.plans.single(), previousAction = balanced(), timestampMillis = 1_000)

        assertEquals(
            SuppressionReason.DEBOUNCE,
            engine.evaluate(
                event(AutomationTrigger.APP_FOREGROUND, "com.example.game", 1_100),
                state(ScreenState.ON, "com.example.game"),
                listOf(rule),
            ).suppressed.single().reason,
        )
        assertEquals(
            SuppressionReason.COOLDOWN,
            engine.evaluate(
                event(AutomationTrigger.APP_FOREGROUND, "com.example.game", 1_800),
                state(ScreenState.ON, "com.example.game"),
                listOf(rule),
            ).suppressed.single().reason,
        )
        assertEquals(
            SuppressionReason.REPEATED_ACTION,
            engine.evaluate(
                event(AutomationTrigger.APP_FOREGROUND, "com.example.game", 4_000),
                state(ScreenState.ON, "com.example.game"),
                listOf(rule),
            ).suppressed.single().reason,
        )
    }

    @Test
    fun `manual action blocks all rule scopes and cancels pending restore`() {
        val engine = AutomationRuleEngine(manualOverrideMillis = 5_000)
        engine.recordManualAction(balanced(), 1_000)

        val held = engine.evaluate(
            event(AutomationTrigger.APP_FOREGROUND, "com.example.game", 2_000),
            state(ScreenState.ON, "com.example.game"),
            listOf(profileRule(scope = RuleScope.SAFETY)),
        )
        assertTrue(held.plans.isEmpty())
        assertEquals(SuppressionReason.MANUAL_OVERRIDE, held.suppressed.single().reason)

        val released = engine.evaluate(
            event(AutomationTrigger.APP_FOREGROUND, "com.example.game", 6_001),
            state(ScreenState.ON, "com.example.game"),
            listOf(profileRule(scope = RuleScope.SAFETY)),
        )
        assertEquals(1, released.plans.size)
    }

    @Test
    fun `opposite event restores the exact captured action`() {
        val engine = AutomationRuleEngine()
        val rule = profileRule().copy(restorePolicy = RestorePolicy.ON_OPPOSITE_TRIGGER)
        val foreground = event(AutomationTrigger.APP_FOREGROUND, "com.example.game", 1_000)
        val apply = engine.evaluate(
            foreground,
            state(ScreenState.ON, "com.example.game"),
            listOf(rule),
        ).plans.single()
        engine.recordSuccess(apply, previousAction = balanced())

        val restore = engine.evaluate(
            event(AutomationTrigger.APP_BACKGROUND, "com.example.game", 2_000),
            state(ScreenState.ON, null),
            listOf(rule),
        ).plans.single()

        assertEquals(PlanKind.RESTORE, restore.kind)
        assertEquals(balanced(), restore.action)
    }

    @Test
    fun `restore runs before a new rule applying to the same target`() {
        val engine = AutomationRuleEngine()
        val foregroundRule = profileRule().copy(
            restorePolicy = RestorePolicy.ON_OPPOSITE_TRIGGER,
        )
        val backgroundRule = AutomationRule(
            id = "background-eco",
            name = "Background eco",
            trigger = AutomationTrigger.APP_BACKGROUND,
            triggerPackageName = "com.example.game",
            actions = listOf(RuleAction.SetPerformanceProfile(PerformanceProfile.ECO)),
            scope = RuleScope.PER_APP,
            cooldownMillis = 0,
            debounceMillis = 0,
        )
        val apply = engine.evaluate(
            event(AutomationTrigger.APP_FOREGROUND, "com.example.game"),
            state(ScreenState.ON, "com.example.game"),
            listOf(foregroundRule, backgroundRule),
        ).plans.single()
        engine.recordSuccess(apply, balanced())

        val plans = engine.evaluate(
            event(AutomationTrigger.APP_BACKGROUND, "com.example.game", 2_000),
            state(ScreenState.ON, null),
            listOf(foregroundRule, backgroundRule),
        ).plans

        assertEquals(listOf(PlanKind.RESTORE, PlanKind.APPLY), plans.map { it.kind })
        assertEquals(
            PerformanceProfile.ECO,
            (plans.last().action as ResolvedAutomationAction.SetPerformanceProfile).profile,
        )
    }

    @Test
    fun `engine-originated and self-caused events cannot loop`() {
        val engine = AutomationRuleEngine()
        val rule = profileRule()
        val state = state(ScreenState.ON, "com.example.game")
        val engineEvent = event(AutomationTrigger.APP_FOREGROUND, "com.example.game")
            .copy(origin = AutomationEventOrigin.RULE_ENGINE)
        val causedEvent = event(AutomationTrigger.APP_FOREGROUND, "com.example.game", 2_000)
            .copy(causationRuleIds = setOf(rule.id))

        assertEquals(
            SuppressionReason.LOOP_PREVENTION,
            engine.evaluate(engineEvent, state, listOf(rule)).suppressed.single().reason,
        )
        assertEquals(
            SuppressionReason.LOOP_PREVENTION,
            engine.evaluate(causedEvent, state, listOf(rule)).suppressed.single().reason,
        )
    }

    @Test
    fun `screen freeze requires explicit package and automation cannot disable apps`() {
        val missingTarget = AutomationRule(
            id = "screen",
            name = "screen",
            trigger = AutomationTrigger.SCREEN_OFF,
            actions = listOf(RuleAction.SetFreezeMode(mode = FreezeMode.DEEP_FREEZE)),
        )
        val disabling = missingTarget.copy(
            actions = listOf(
                RuleAction.SetFreezeMode("com.example.app", mode = FreezeMode.DISABLED),
            ),
        )

        assertTrue("screen-triggered freeze requires action.packageName" in AutomationRuleValidator.validate(missingTarget))
        assertTrue("automation may not disable applications" in AutomationRuleValidator.validate(disabling))
    }

    private fun profileRule(
        id: String = "gaming",
        scope: RuleScope = RuleScope.PER_APP,
        priority: Int = 0,
        profile: PerformanceProfile = PerformanceProfile.GAMING,
        triggerPackage: String? = "com.example.game",
        conditions: List<AutomationCondition> = emptyList(),
    ) = AutomationRule(
        id = id,
        name = id,
        trigger = AutomationTrigger.APP_FOREGROUND,
        triggerPackageName = triggerPackage,
        conditions = conditions,
        actions = listOf(RuleAction.SetPerformanceProfile(profile)),
        scope = scope,
        priority = priority,
        cooldownMillis = 0,
        debounceMillis = 0,
    )

    private fun event(trigger: AutomationTrigger, packageName: String?, at: Long = 1_000) =
        AutomationEvent(trigger, at, packageName)

    private fun state(screen: ScreenState, packageName: String?) =
        AutomationState(screen, packageName)

    private fun balanced() =
        ResolvedAutomationAction.SetPerformanceProfile(PerformanceProfile.BALANCED)
}
