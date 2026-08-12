package com.hzzmonet.zkbomb.core

import com.hzzmonet.zkbomb.api.BombResult
import com.hzzmonet.zkbomb.domain.automation.PerformanceProfile
import com.hzzmonet.zkbomb.domain.performance.ThermalGuardianConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceProfileCoordinatorTest {
    @Test
    fun `manual profile restores the exact captured tuning`() {
        val port = FakeMemoryPort(73, 2)
        val coordinator = PerformanceProfileCoordinator(PerformanceProfileBackend(port))

        assertTrue(coordinator.setProfile(PerformanceProfile.GAMING).isSuccess)
        assertEquals(200 to 0, port.tuning)
        assertTrue(coordinator.clearProfile().isSuccess)
        assertEquals(73 to 2, port.tuning)
    }

    @Test
    fun `thermal guardian escalates immediately then restores after hysteresis cooldown`() {
        val port = FakeMemoryPort(100, 0)
        val coordinator = PerformanceProfileCoordinator(PerformanceProfileBackend(port))
        assertTrue(
            coordinator.setThermalGuardian(ThermalGuardianConfig(cooldownMillis = 60_000)).isSuccess,
        )

        coordinator.evaluateTemperature(425, 1_000)
        assertEquals(60 to 0, port.tuning)
        coordinator.evaluateTemperature(455, 2_000)
        assertEquals(80 to 0, port.tuning)
        coordinator.evaluateTemperature(385, 30_000)
        assertEquals(80 to 0, port.tuning)
        coordinator.evaluateTemperature(385, 62_000)
        assertEquals(100 to 0, port.tuning)
        assertEquals(null, coordinator.snapshot().thermalGuardianActiveProfile)
    }

    @Test
    fun `clear never overwrites tuning changed by another owner`() {
        val port = FakeMemoryPort(100, 0)
        val coordinator = PerformanceProfileCoordinator(PerformanceProfileBackend(port))
        coordinator.setProfile(PerformanceProfile.PERFORMANCE)
        port.tuning = 111 to 1

        val result = coordinator.clearProfile()

        assertEquals(BombResult.Status.FAILED, result.status)
        assertEquals(111 to 1, port.tuning)
        assertFalse(coordinator.snapshot().activeProfile != null)
    }

    private class FakeMemoryPort(
        swappiness: Int,
        pageCluster: Int,
    ) : MemoryTuningPort {
        var tuning = swappiness to pageCluster
        override fun available() = true
        override fun currentSwappiness() = tuning.first
        override fun currentPageCluster() = tuning.second
        override fun request(swappiness: Int, pageCluster: Int): Boolean {
            tuning = swappiness to pageCluster
            return true
        }
        override fun waitBeforeVerification() = Unit
    }
}
