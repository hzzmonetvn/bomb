package com.hzzmonet.zkbomb.domain.performance

import com.hzzmonet.zkbomb.domain.automation.PerformanceProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceProfilesTest {
    @Test fun `every non-custom profile has one bounded definition`() {
        val expected = PerformanceProfile.entries.filter { it != PerformanceProfile.CUSTOM }
        assertEquals(expected.toSet(), PerformanceProfileCatalog.definitions.map { it.profile }.toSet())
        assertEquals(expected.size, PerformanceProfileCatalog.definitions.size)
        assertTrue(PerformanceProfileCatalog.definitions.all { it.swappiness in 0..200 })
        assertTrue(PerformanceProfileCatalog.definitions.all { it.pageCluster in 0..6 })
    }

    @Test fun `thermal thresholds require ordered hysteresis and bounded cooldown`() {
        assertTrue(ThermalGuardianConfigValidator.violations(ThermalGuardianConfig()).isEmpty())
        assertTrue(
            ThermalGuardianConfigValidator.violations(
                ThermalGuardianConfig(420, 410, 425, 1),
            ).isNotEmpty(),
        )
    }

    @Test fun `thermal policy escalates immediately and restores only after cooldown`() {
        val policy = ThermalGuardianPolicy()
        val config = ThermalGuardianConfig(cooldownMillis = 60_000)
        assertEquals(
            ThermalGuardianDecision.Apply(PerformanceProfile.SUSTAINABLE),
            policy.decide(config, 425, null, null, 1_000),
        )
        assertEquals(
            ThermalGuardianDecision.Apply(PerformanceProfile.ECO),
            policy.decide(config, 455, PerformanceProfile.SUSTAINABLE, 1_000, 2_000),
        )
        assertEquals(
            ThermalGuardianDecision.Hold,
            policy.decide(config, 385, PerformanceProfile.ECO, 2_000, 30_000),
        )
        assertEquals(
            ThermalGuardianDecision.Restore,
            policy.decide(config, 385, PerformanceProfile.ECO, 2_000, 62_000),
        )
    }
}
