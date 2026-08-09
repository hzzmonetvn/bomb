package com.hzzmonet.zkbomb.domain.freeze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The escalation ladder from master plan §7 — foreground exit → +30 s
 * background-restricted → +5 min deep freeze — plus the two cases that make it
 * safe: manual override precedence, and surviving a service restart without
 * either losing or restarting the clock.
 */
class FreezeSchedulerTest {

    private val scheduler = FreezeScheduler()

    private val policy = FreezePolicy(
        autoFreeze = true,
        softFreezeDelayMillis = 30_000L,
        deepFreezeDelayMillis = 300_000L,
        freezeWhenScreenOff = false,
        maxMode = FreezeMode.DEEP_FREEZE,
    )

    private val exitedAt = 1_000_000L

    private fun state(
        currentMode: FreezeMode = FreezeMode.NORMAL,
        isForeground: Boolean = false,
        lastExit: Long? = exitedAt,
        manualOverride: ManualOverride? = null,
    ) = AppRuntimeState(
        packageName = "com.example.app",
        isForeground = isForeground,
        lastForegroundExitMillis = lastExit,
        currentMode = currentMode,
        manualOverride = manualOverride,
    )

    // ---- the ladder ----------------------------------------------------------

    @Test
    fun `before the soft deadline it waits for exactly that deadline`() {
        val outcome = scheduler.evaluate(state(), policy, now = exitedAt + 10_000L, screenOff = false)
        assertEquals(ScheduleOutcome.Wait(exitedAt + 30_000L), outcome)
    }

    @Test
    fun `at the soft deadline it escalates to soft freeze`() {
        val outcome = scheduler.evaluate(state(), policy, now = exitedAt + 30_000L, screenOff = false)
        assertEquals(ScheduleOutcome.Escalate(FreezeMode.SOFT_FREEZE), outcome)
    }

    @Test
    fun `soft frozen app waits for the deep deadline`() {
        val outcome = scheduler.evaluate(
            state(currentMode = FreezeMode.SOFT_FREEZE),
            policy,
            now = exitedAt + 60_000L,
            screenOff = false,
        )
        assertEquals(ScheduleOutcome.Wait(exitedAt + 300_000L), outcome)
    }

    @Test
    fun `at the deep deadline it escalates to deep freeze`() {
        val outcome = scheduler.evaluate(
            state(currentMode = FreezeMode.SOFT_FREEZE),
            policy,
            now = exitedAt + 300_000L,
            screenOff = false,
        )
        assertEquals(ScheduleOutcome.Escalate(FreezeMode.DEEP_FREEZE), outcome)
    }

    @Test
    fun `the top of the ladder is idle`() {
        val outcome = scheduler.evaluate(
            state(currentMode = FreezeMode.DEEP_FREEZE),
            policy,
            now = exitedAt + 10_000_000L,
            screenOff = false,
        )
        assertEquals(ScheduleOutcome.Idle, outcome)
    }

    @Test
    fun `maxMode caps the ladder`() {
        val capped = policy.copy(maxMode = FreezeMode.SOFT_FREEZE)
        val outcome = scheduler.evaluate(
            state(currentMode = FreezeMode.SOFT_FREEZE),
            capped,
            now = exitedAt + 10_000_000L,
            screenOff = false,
        )
        assertEquals(ScheduleOutcome.Idle, outcome)
    }

    @Test
    fun `a disabled app is never escalated`() {
        val outcome = scheduler.evaluate(
            state(currentMode = FreezeMode.DISABLED),
            policy,
            now = exitedAt + 10_000_000L,
            screenOff = false,
        )
        assertEquals(ScheduleOutcome.Idle, outcome)
    }

    // ---- restart survival ----------------------------------------------------

    @Test
    fun `deadlines are recomputed from the exit timestamp after a restart`() {
        // Simulates Bomb being killed at exit+10 s and restarted at exit+200 s
        // with only the stored exit timestamp. The app has by then passed the
        // soft deadline but not the deep one, and the scheduler must land on the
        // correct rung rather than restarting the 30 s wait.
        val afterRestart = scheduler.evaluate(state(), policy, now = exitedAt + 200_000L, screenOff = false)
        assertEquals(ScheduleOutcome.Escalate(FreezeMode.SOFT_FREEZE), afterRestart)

        val next = scheduler.evaluate(
            state(currentMode = FreezeMode.SOFT_FREEZE),
            policy,
            now = exitedAt + 200_000L,
            screenOff = false,
        )
        assertEquals(ScheduleOutcome.Wait(exitedAt + 300_000L), next)
    }

    @Test
    fun `a long outage escalates through the ladder without skipping a rung`() {
        // Restarting after both deadlines have passed still walks NORMAL →
        // SOFT_FREEZE → DEEP_FREEZE, so the executed sequence is identical to the
        // timed path and no mechanism is applied out of order.
        val first = scheduler.evaluate(state(), policy, now = exitedAt + 900_000L, screenOff = false)
        assertEquals(ScheduleOutcome.Escalate(FreezeMode.SOFT_FREEZE), first)

        val second = scheduler.evaluate(
            state(currentMode = FreezeMode.SOFT_FREEZE),
            policy,
            now = exitedAt + 900_000L,
            screenOff = false,
        )
        assertEquals(ScheduleOutcome.Escalate(FreezeMode.DEEP_FREEZE), second)
    }

    // ---- precedence ----------------------------------------------------------

    @Test
    fun `manual override outranks the timer`() {
        val outcome = scheduler.evaluate(
            state(manualOverride = ManualOverride(FreezeMode.NORMAL, exitedAt + 5_000L)),
            policy,
            now = exitedAt + 900_000L,
            screenOff = false,
        )
        assertEquals(ScheduleOutcome.HeldByManualOverride(FreezeMode.NORMAL), outcome)
    }

    @Test
    fun `manual override is checked before auto freeze is even consulted`() {
        val outcome = scheduler.evaluate(
            state(manualOverride = ManualOverride(FreezeMode.DEEP_FREEZE, exitedAt)),
            policy.copy(autoFreeze = false),
            now = exitedAt + 900_000L,
            screenOff = false,
        )
        assertEquals(ScheduleOutcome.HeldByManualOverride(FreezeMode.DEEP_FREEZE), outcome)
    }

    @Test
    fun `auto freeze off means idle`() {
        val outcome = scheduler.evaluate(
            state(),
            policy.copy(autoFreeze = false),
            now = exitedAt + 900_000L,
            screenOff = false,
        )
        assertEquals(ScheduleOutcome.Idle, outcome)
    }

    @Test
    fun `a foreground app is never escalated`() {
        val outcome = scheduler.evaluate(
            state(isForeground = true),
            policy,
            now = exitedAt + 900_000L,
            screenOff = false,
        )
        assertEquals(ScheduleOutcome.Idle, outcome)
    }

    @Test
    fun `an app never seen in the foreground has no reference point`() {
        val outcome = scheduler.evaluate(
            state(lastExit = null),
            policy,
            now = exitedAt + 900_000L,
            screenOff = false,
        )
        assertEquals(ScheduleOutcome.Idle, outcome)
    }

    // ---- screen off ----------------------------------------------------------

    @Test
    fun `screen off waives the remaining delay when enabled`() {
        val outcome = scheduler.evaluate(
            state(),
            policy.copy(freezeWhenScreenOff = true),
            now = exitedAt + 1L,
            screenOff = true,
        )
        assertEquals(ScheduleOutcome.Escalate(FreezeMode.SOFT_FREEZE), outcome)
    }

    @Test
    fun `screen off is ignored when the option is off`() {
        val outcome = scheduler.evaluate(
            state(),
            policy,
            now = exitedAt + 1L,
            screenOff = true,
        )
        assertEquals(ScheduleOutcome.Wait(exitedAt + 30_000L), outcome)
    }

    // ---- policy validation ---------------------------------------------------

    @Test
    fun `a valid policy has no violations`() {
        assertTrue(FreezePolicyValidator.validate(policy).isEmpty())
    }

    @Test
    fun `negative delays are rejected`() {
        val violations = FreezePolicyValidator.validate(policy.copy(softFreezeDelayMillis = -1L))
        assertTrue(PolicyViolation.NEGATIVE_DELAY in violations)
    }

    @Test
    fun `a deep delay earlier than the soft delay is rejected`() {
        val violations = FreezePolicyValidator.validate(
            policy.copy(softFreezeDelayMillis = 300_000L, deepFreezeDelayMillis = 30_000L),
        )
        assertTrue(PolicyViolation.DEEP_BEFORE_SOFT in violations)
    }

    @Test
    fun `escalation may not target disabled`() {
        val violations = FreezePolicyValidator.validate(policy.copy(maxMode = FreezeMode.DISABLED))
        assertTrue(PolicyViolation.UNREACHABLE_MAX_MODE in violations)
    }

    @Test
    fun `the documented default ladder is valid`() {
        assertTrue(FreezePolicyValidator.validate(FreezePolicy.DEFAULT).isEmpty())
        assertEquals(30_000L, FreezePolicy.DEFAULT.softFreezeDelayMillis)
        assertEquals(300_000L, FreezePolicy.DEFAULT.deepFreezeDelayMillis)
    }
}
