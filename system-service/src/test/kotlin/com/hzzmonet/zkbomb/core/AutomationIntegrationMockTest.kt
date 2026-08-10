package com.hzzmonet.zkbomb.core

import com.hzzmonet.zkbomb.api.BombResult
import com.hzzmonet.zkbomb.domain.automation.AutomationEvent
import com.hzzmonet.zkbomb.domain.automation.AutomationRule
import com.hzzmonet.zkbomb.domain.automation.AutomationRuleEngine
import com.hzzmonet.zkbomb.domain.automation.AutomationTrigger
import com.hzzmonet.zkbomb.domain.automation.PerformanceProfile
import com.hzzmonet.zkbomb.domain.automation.ResolvedAutomationAction
import com.hzzmonet.zkbomb.domain.automation.RestorePolicy
import com.hzzmonet.zkbomb.domain.automation.RuleAction
import com.hzzmonet.zkbomb.domain.freeze.FreezeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationIntegrationMockTest {

    @Test
    fun `foreground profile is applied and exact prior tuning is restored on background`() {
        val previous = ResolvedAutomationAction.RestorePerformanceTuning(73, 2)
        val executor = RecordingExecutor(previous)
        val coordinator = coordinator(
            rules = listOf(
                AutomationRule(
                    id = "game",
                    name = "Gaming",
                    trigger = AutomationTrigger.APP_FOREGROUND,
                    triggerPackageName = "com.example.game",
                    actions = listOf(
                        RuleAction.SetPerformanceProfile(PerformanceProfile.GAMING),
                    ),
                    restorePolicy = RestorePolicy.ON_OPPOSITE_TRIGGER,
                    cooldownMillis = 0,
                    debounceMillis = 0,
                ),
            ),
            executor = executor,
        )

        val apply = coordinator.onEvent(
            AutomationEvent(AutomationTrigger.APP_FOREGROUND, 1_000, "com.example.game"),
        )
        val restore = coordinator.onEvent(
            AutomationEvent(AutomationTrigger.APP_BACKGROUND, 2_000, "com.example.game"),
        )

        assertTrue(apply.single().result.isSuccess)
        assertTrue(restore.single().result.isSuccess)
        assertEquals(
            listOf(
                ResolvedAutomationAction.SetPerformanceProfile(PerformanceProfile.GAMING),
                previous,
            ),
            executor.executed,
        )
    }

    @Test
    fun `screen off freezes configured app and screen on unfreezes it`() {
        val normal = ResolvedAutomationAction.SetFreezeMode(
            "com.example.social",
            0,
            FreezeMode.NORMAL,
        )
        val executor = RecordingExecutor(normal)
        val coordinator = coordinator(
            listOf(
                AutomationRule(
                    id = "screen-off",
                    name = "Freeze social",
                    trigger = AutomationTrigger.SCREEN_OFF,
                    actions = listOf(
                        RuleAction.SetFreezeMode(
                            "com.example.social",
                            0,
                            FreezeMode.DEEP_FREEZE,
                        ),
                    ),
                    restorePolicy = RestorePolicy.ON_OPPOSITE_TRIGGER,
                    cooldownMillis = 0,
                    debounceMillis = 0,
                ),
            ),
            executor,
        )

        coordinator.onEvent(AutomationEvent(AutomationTrigger.SCREEN_OFF, 10_000))
        coordinator.onEvent(AutomationEvent(AutomationTrigger.SCREEN_ON, 11_000))

        assertEquals(FreezeMode.DEEP_FREEZE, (executor.executed[0] as ResolvedAutomationAction.SetFreezeMode).mode)
        assertEquals(FreezeMode.NORMAL, (executor.executed[1] as ResolvedAutomationAction.SetFreezeMode).mode)
    }

    @Test
    fun `failed apply is never recorded and therefore never restored`() {
        val executor = RecordingExecutor(
            captured = ResolvedAutomationAction.RestorePerformanceTuning(100, 0),
            result = BombResult.backendUnavailable("mock unavailable"),
        )
        val coordinator = coordinator(listOf(profileRule()), executor)

        assertFalse(
            coordinator.onEvent(
                AutomationEvent(AutomationTrigger.APP_FOREGROUND, 1_000, "com.example.game"),
            ).single().result.isSuccess,
        )
        assertTrue(
            coordinator.onEvent(
                AutomationEvent(AutomationTrigger.APP_BACKGROUND, 2_000, "com.example.game"),
            ).isEmpty(),
        )
    }

    @Test
    fun `disabled repository consumes no actions`() {
        val repository = InMemoryAutomationRuleRepository(false, listOf(profileRule()))
        val executor = RecordingExecutor(null)
        val coordinator = AutomationCoordinator(
            repository,
            AutomationRuleEngine(),
            executor,
            screenInitiallyOn = true,
        )

        assertTrue(
            coordinator.onEvent(
                AutomationEvent(AutomationTrigger.APP_FOREGROUND, 1_000, "com.example.game"),
            ).isEmpty(),
        )
        assertTrue(executor.executed.isEmpty())
    }

    @Test
    fun `performance backend verifies mock write and restores non-profile values`() {
        val port = FakeMemoryPort(swappiness = 73, pageCluster = 2)
        val backend = PerformanceProfileBackend(port)
        val captured = backend.capture()!!

        assertTrue(backend.apply(PerformanceProfile.GAMING).isSuccess)
        assertEquals(200, port.swappiness)
        assertEquals(0, port.pageCluster)
        assertTrue(backend.restore(captured).isSuccess)
        assertEquals(73, port.swappiness)
        assertEquals(2, port.pageCluster)
        assertEquals(listOf(200 to 0, 73 to 2), port.requests)
    }

    private fun coordinator(
        rules: List<AutomationRule>,
        executor: RecordingExecutor,
    ) = AutomationCoordinator(
        InMemoryAutomationRuleRepository(true, rules),
        AutomationRuleEngine(),
        executor,
        screenInitiallyOn = true,
    )

    private fun profileRule() = AutomationRule(
        id = "game",
        name = "Gaming",
        trigger = AutomationTrigger.APP_FOREGROUND,
        triggerPackageName = "com.example.game",
        actions = listOf(RuleAction.SetPerformanceProfile(PerformanceProfile.GAMING)),
        restorePolicy = RestorePolicy.ON_OPPOSITE_TRIGGER,
        cooldownMillis = 0,
        debounceMillis = 0,
    )

    private class RecordingExecutor(
        private val captured: ResolvedAutomationAction?,
        private val result: BombResult = BombResult.success(),
    ) : AutomationActionExecutor {
        val executed = mutableListOf<ResolvedAutomationAction>()

        override fun capture(action: ResolvedAutomationAction): ResolvedAutomationAction? = captured

        override fun execute(action: ResolvedAutomationAction): BombResult {
            executed += action
            return result
        }
    }

    private class FakeMemoryPort(
        var swappiness: Int,
        var pageCluster: Int,
    ) : MemoryTuningPort {
        val requests = mutableListOf<Pair<Int, Int>>()
        override fun available(): Boolean = true
        override fun currentSwappiness(): Int = swappiness
        override fun currentPageCluster(): Int = pageCluster
        override fun request(swappiness: Int, pageCluster: Int): Boolean {
            requests += swappiness to pageCluster
            this.swappiness = swappiness
            this.pageCluster = pageCluster
            return true
        }
        override fun waitBeforeVerification() = Unit
    }
}
