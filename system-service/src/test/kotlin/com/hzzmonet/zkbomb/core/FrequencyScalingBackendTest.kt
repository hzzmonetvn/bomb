package com.hzzmonet.zkbomb.core

import com.hzzmonet.zkbomb.api.BombResult
import com.hzzmonet.zkbomb.api.CapabilityState
import com.hzzmonet.zkbomb.domain.performance.FrequencyLimitRequest
import com.hzzmonet.zkbomb.domain.performance.FrequencyTargetKind
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrequencyScalingBackendTest {
    @Test fun `cpu probe merges runtime normal and boost tables without fixed MHz list`() {
        val target = cpuTarget("policy4")
        val access = FakeFrequencyAccess(listOf(target)).apply {
            set(target, FrequencySysfsNode.AVAILABLE, "300000 1804800")
            set(target, FrequencySysfsNode.BOOST, "2208000 1804800")
            set(target, FrequencySysfsNode.MIN, "300000")
            set(target, FrequencySysfsNode.MAX, "2208000")
        }

        val snapshot = FrequencyScalingBackend(access).snapshot().targets.single()

        assertEquals(listOf(300, 1_805, 2_208), snapshot.availableMHz)
        assertEquals(listOf(1_805, 2_208), snapshot.boostMHz)
        assertEquals(300, snapshot.currentMinMHz)
        assertEquals(2_208, snapshot.currentMaxMHz)
    }

    @Test fun `gpu probe converts devfreq Hz dynamically`() {
        val target = gpuTarget()
        val access = FakeFrequencyAccess(listOf(target)).apply {
            set(target, FrequencySysfsNode.AVAILABLE, "200000000 587000000 900000000")
            set(target, FrequencySysfsNode.MIN, "200000000")
            set(target, FrequencySysfsNode.MAX, "900000000")
        }

        val snapshot = FrequencyScalingBackend(access).snapshot().targets.single()

        assertEquals(listOf(200, 587, 900), snapshot.availableMHz)
        assertEquals(FrequencyTargetKind.GPU, snapshot.kind)
    }

    @Test fun `each cpu policy cluster is exposed as an independent target`() {
        val little = cpuTarget("policy0")
        val big = cpuTarget("policy4")
        val access = FakeFrequencyAccess(listOf(little, big)).apply {
            listOf(little, big).forEach { target ->
                set(target, FrequencySysfsNode.AVAILABLE, "300000 600000")
                set(target, FrequencySysfsNode.MIN, "300000")
                set(target, FrequencySysfsNode.MAX, "600000")
            }
        }

        val policies = FrequencyScalingBackend(access).snapshot().cpuPolicies()

        assertEquals(listOf("policy0", "policy4"), policies.map { it.id })
    }

    @Test fun `rounded MHz selection writes the exact advertised raw cpu value`() {
        val target = cpuTarget("policy0")
        val access = populatedCpu(target)
        val backend = FrequencyScalingBackend(access)

        val result = backend.set(cpuRequest("policy0", 300, 1_805))

        assertTrue(result.isSuccess)
        assertEquals("1804800", access.read(target, FrequencySysfsNode.MAX))
        assertTrue(access.writes.contains(FrequencySysfsNode.MAX to 1_804_800L))
    }

    @Test fun `raising minimum above current maximum writes maximum first`() {
        val target = cpuTarget("policy0")
        val access = populatedCpu(target).apply {
            set(target, FrequencySysfsNode.MAX, "600000")
        }

        val result = FrequencyScalingBackend(access).set(cpuRequest("policy0", 1_805, 2_208))

        assertTrue(result.isSuccess)
        assertEquals(FrequencySysfsNode.MAX, access.writes.first().first)
        assertEquals(FrequencySysfsNode.MIN, access.writes.last().first)
    }

    @Test fun `lowering maximum below current minimum writes minimum first`() {
        val target = cpuTarget("policy0")
        val access = populatedCpu(target).apply {
            set(target, FrequencySysfsNode.MIN, "1804800")
        }

        val result = FrequencyScalingBackend(access).set(cpuRequest("policy0", 300, 600))

        assertTrue(result.isSuccess)
        assertEquals(FrequencySysfsNode.MIN, access.writes.first().first)
        assertEquals(FrequencySysfsNode.MAX, access.writes.last().first)
    }

    @Test fun `frequency not present in fresh dynamic table is rejected without writes`() {
        val target = cpuTarget("policy0")
        val access = populatedCpu(target)

        val result = FrequencyScalingBackend(access).set(cpuRequest("policy0", 301, 1_805))

        assertEquals(BombResult.Status.INVALID_ARGUMENT, result.status)
        assertTrue(access.writes.isEmpty())
    }

    @Test fun `read-only target remains probeable but cannot be changed`() {
        val target = gpuTarget()
        val access = FakeFrequencyAccess(listOf(target), writable = false).apply {
            set(target, FrequencySysfsNode.AVAILABLE, "200000000 900000000")
            set(target, FrequencySysfsNode.MIN, "200000000")
            set(target, FrequencySysfsNode.MAX, "900000000")
        }
        val backend = FrequencyScalingBackend(access)

        assertFalse(backend.snapshot().targets.single().writable)
        assertEquals(BombResult.Status.UNSUPPORTED, backend.set(gpuRequest(200, 900)).status)
        assertEquals(
            FrequencyScalingCapabilities(false, false, true, false),
            backend.capabilities(),
        )
    }

    @Test fun `failed second write restores the original pair`() {
        val target = cpuTarget("policy0")
        val access = populatedCpu(target).apply { failNextNode = FrequencySysfsNode.MAX }

        val result = FrequencyScalingBackend(access).set(cpuRequest("policy0", 600, 1_805))

        assertEquals(BombResult.Status.FAILED, result.status)
        assertEquals("300000", access.read(target, FrequencySysfsNode.MIN))
        assertEquals("2208000", access.read(target, FrequencySysfsNode.MAX))
    }

    @Test fun `malformed table is not partially advertised`() {
        val target = cpuTarget("policy0")
        val access = populatedCpu(target).apply {
            set(target, FrequencySysfsNode.AVAILABLE, "300000 not-a-frequency 600000")
            set(target, FrequencySysfsNode.BOOST, "")
        }

        assertTrue(FrequencyScalingBackend(access).snapshot().targets.isEmpty())
    }

    @Test fun `capability mapping distinguishes absent read-only and writable targets`() {
        assertEquals(CapabilityState.NOT_PROBED, frequencyCapabilityState(false, false, false))
        assertEquals(CapabilityState.UNSUPPORTED, frequencyCapabilityState(true, false, false))
        assertEquals(CapabilityState.REQUIRES_ROOT, frequencyCapabilityState(true, true, false))
        assertEquals(CapabilityState.SUPPORTED, frequencyCapabilityState(true, true, true))
    }

    private fun populatedCpu(target: SysfsFrequencyTarget) = FakeFrequencyAccess(listOf(target)).apply {
        set(target, FrequencySysfsNode.AVAILABLE, "300000 600000 1804800")
        set(target, FrequencySysfsNode.BOOST, "2208000")
        set(target, FrequencySysfsNode.MIN, "300000")
        set(target, FrequencySysfsNode.MAX, "2208000")
    }

    private fun cpuRequest(id: String, min: Int, max: Int) = FrequencyLimitRequest(
        id, FrequencyTargetKind.CPU_POLICY, min, max,
    )

    private fun gpuRequest(min: Int, max: Int) = FrequencyLimitRequest(
        "gpu0", FrequencyTargetKind.GPU, min, max,
    )

    private fun cpuTarget(id: String) = SysfsFrequencyTarget(
        id,
        FrequencyTargetKind.CPU_POLICY,
        1_000,
        File("/fake/$id"),
        NODE_NAMES,
    )

    private fun gpuTarget() = SysfsFrequencyTarget(
        "gpu0",
        FrequencyTargetKind.GPU,
        1_000_000,
        File("/fake/gpu"),
        NODE_NAMES,
    )

    private class FakeFrequencyAccess(
        private val configuredTargets: List<SysfsFrequencyTarget>,
        private val writable: Boolean = true,
    ) : FrequencyNodeAccess {
        private val values = mutableMapOf<Pair<String, FrequencySysfsNode>, String>()
        val writes = mutableListOf<Pair<FrequencySysfsNode, Long>>()
        var failNextNode: FrequencySysfsNode? = null

        override fun targets() = configuredTargets

        override fun read(target: SysfsFrequencyTarget, node: FrequencySysfsNode): String? =
            values[target.directory.path to node]

        override fun canWrite(target: SysfsFrequencyTarget, node: FrequencySysfsNode) = writable

        override fun write(
            target: SysfsFrequencyTarget,
            node: FrequencySysfsNode,
            rawValue: Long,
        ): Boolean {
            writes += node to rawValue
            if (failNextNode == node) {
                failNextNode = null
                return false
            }
            set(target, node, rawValue.toString())
            return true
        }

        fun set(target: SysfsFrequencyTarget, node: FrequencySysfsNode, value: String) {
            values[target.directory.path to node] = value
        }
    }

    private companion object {
        val NODE_NAMES = FrequencySysfsNode.entries.associateWith { it.name.lowercase() }
    }
}
