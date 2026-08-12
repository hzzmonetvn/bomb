package com.hzzmonet.zkbomb.domain.performance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrequencyScalingModelsTest {
    @Test fun `target ids are bounded by their typed topology`() {
        val cpu = FrequencyLimitRequest("policy7", FrequencyTargetKind.CPU_POLICY, 300, 2_000)
        val gpu = FrequencyLimitRequest("gpu0", FrequencyTargetKind.GPU, 200, 900)

        assertTrue(FrequencyLimitRequestValidator.violations(cpu).isEmpty())
        assertTrue(FrequencyLimitRequestValidator.violations(gpu).isEmpty())
        assertTrue(
            FrequencyLimitRequestValidator.violations(cpu.copy(targetId = "../policy7")).isNotEmpty(),
        )
        assertTrue(
            FrequencyLimitRequestValidator.violations(gpu.copy(targetId = "policy0")).isNotEmpty(),
        )
    }

    @Test fun `minimum cannot exceed maximum`() {
        val request = FrequencyLimitRequest("gpu0", FrequencyTargetKind.GPU, 900, 200)
        assertTrue(FrequencyLimitRequestValidator.violations(request).isNotEmpty())
    }

    @Test fun `snapshot separates dynamic cpu policies from gpu`() {
        val cpu = target("policy0", FrequencyTargetKind.CPU_POLICY)
        val gpu = target("gpu0", FrequencyTargetKind.GPU)
        val snapshot = FrequencyScalingSnapshot(listOf(cpu, gpu))

        assertEquals(listOf(cpu), snapshot.cpuPolicies())
        assertEquals(gpu, snapshot.gpu())
        assertNull(FrequencyScalingSnapshot(listOf(cpu)).gpu())
    }

    private fun target(id: String, kind: FrequencyTargetKind) = FrequencyScalingTarget(
        id = id,
        kind = kind,
        availableMHz = listOf(300, 600),
        boostMHz = emptyList(),
        currentMinMHz = 300,
        currentMaxMHz = 600,
        writable = true,
    )
}
