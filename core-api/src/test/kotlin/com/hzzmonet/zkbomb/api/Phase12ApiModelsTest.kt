package com.hzzmonet.zkbomb.api

import com.hzzmonet.zkbomb.domain.performance.FrequencyTargetKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase12ApiModelsTest {
    @Test fun `request rejects unknown kind path-like id and inverted range`() {
        val request = FrequencyLimitRequestParcel("policy4", "CPU_POLICY", 300, 2_400)
        assertNotNull(request.toDomain())
        assertNull(request.copy(kind = "FUTURE_KIND").toDomain())
        assertNull(request.copy(targetId = "../policy4").toDomain())
        assertNull(request.copy(minMHz = 2_400, maxMHz = 300).toDomain())
    }

    @Test fun `target snapshot requires sorted unique dynamic points`() {
        val target = FrequencyScalingTargetParcel(
            id = "gpu0",
            kind = FrequencyTargetKind.GPU.name,
            availableMHz = listOf(200, 400, 800),
            boostMHz = listOf(800),
            currentMinMHz = 200,
            currentMaxMHz = 800,
            writable = true,
        )

        assertTrue(target.isStructurallyValid())
        assertFalse(target.copy(availableMHz = listOf(400, 200)).isStructurallyValid())
        assertFalse(target.copy(boostMHz = listOf(900)).isStructurallyValid())
        assertFalse(target.copy(id = "policy0").isStructurallyValid())
    }

    @Test fun `snapshot rejects duplicate public target ids`() {
        val target = FrequencyScalingTargetParcel(
            "policy0", "CPU_POLICY", listOf(300, 600), emptyList(), 300, 600, false,
        )
        assertTrue(FrequencyScalingSnapshot(listOf(target)).isStructurallyValid())
        assertFalse(FrequencyScalingSnapshot(listOf(target, target)).isStructurallyValid())
    }
}

