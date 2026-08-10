package com.hzzmonet.zkbomb.domain.battery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryLabPolicyTest {

    private val engine = BatteryLabPolicyEngine()
    private val profile = BatteryLabProfile(
        chargeLimitPercent = 80,
        maxTemperatureDeciCelsius = 420,
        capacityResumeHysteresisPercent = 5,
        temperatureResumeHysteresisDeciCelsius = 30,
    )

    @Test
    fun `profile validation rejects empty and unsafe ranges`() {
        assertTrue(BatteryLabProfileValidator.violations(BatteryLabProfile()).isNotEmpty())
        assertTrue(
            BatteryLabProfileValidator.violations(
                BatteryLabProfile(chargeLimitPercent = 49),
            ).isNotEmpty(),
        )
        assertTrue(
            BatteryLabProfileValidator.violations(
                BatteryLabProfile(maxTemperatureDeciCelsius = 560),
            ).isNotEmpty(),
        )
        assertTrue(BatteryLabProfileValidator.violations(profile).isEmpty())
    }

    @Test
    fun `capacity limit suspends charging`() {
        val decision = engine.decide(
            profile,
            state(capacity = 80, temperature = 380),
            suspendedByBomb = false,
        )
        assertEquals(ChargeGuardAction.SUSPEND, decision.action)
        assertEquals(ChargeGuardReason.CAPACITY_LIMIT, decision.reason)
    }

    @Test
    fun `thermal limit suspends charging independently of capacity`() {
        val decision = engine.decide(
            profile,
            state(capacity = 60, temperature = 425),
            suspendedByBomb = false,
        )
        assertEquals(ChargeGuardAction.SUSPEND, decision.action)
        assertEquals(ChargeGuardReason.THERMAL_LIMIT, decision.reason)
    }

    @Test
    fun `hysteresis prevents resume near either boundary`() {
        assertEquals(
            ChargeGuardAction.HOLD,
            engine.decide(
                profile,
                state(capacity = 77, temperature = 380),
                suspendedByBomb = true,
            ).action,
        )
        assertEquals(
            ChargeGuardAction.HOLD,
            engine.decide(
                profile,
                state(capacity = 70, temperature = 405),
                suspendedByBomb = true,
            ).action,
        )
    }

    @Test
    fun `resume requires every configured metric to be safely below threshold`() {
        assertEquals(
            ChargeGuardAction.RESTORE,
            engine.decide(
                profile,
                state(capacity = 75, temperature = 390),
                suspendedByBomb = true,
            ).action,
        )
    }

    @Test
    fun `missing telemetry holds a Bomb suspension fail safe`() {
        val decision = engine.decide(
            profile,
            state(capacity = 70, temperature = null),
            suspendedByBomb = true,
        )
        assertEquals(ChargeGuardAction.HOLD, decision.action)
        assertEquals(ChargeGuardReason.INCOMPLETE_TELEMETRY, decision.reason)
    }

    @Test
    fun `missing configured telemetry is explicit before any suspension`() {
        val decision = engine.decide(
            profile,
            state(capacity = null, temperature = 380),
            suspendedByBomb = false,
        )
        assertEquals(ChargeGuardAction.HOLD, decision.action)
        assertEquals(ChargeGuardReason.INCOMPLETE_TELEMETRY, decision.reason)
    }

    @Test
    fun `power disconnect restores the exact previous control`() {
        val decision = engine.decide(
            profile,
            state(capacity = 77, temperature = 410, power = false),
            suspendedByBomb = true,
        )
        assertEquals(ChargeGuardAction.RESTORE, decision.action)
        assertEquals(ChargeGuardReason.POWER_DISCONNECTED, decision.reason)
    }

    private fun state(
        capacity: Int?,
        temperature: Int?,
        power: Boolean = true,
    ) = BatteryLabState(
        capacityPercent = capacity,
        temperatureDeciCelsius = temperature,
        externalPowerPresent = power,
        charging = true,
    )
}
