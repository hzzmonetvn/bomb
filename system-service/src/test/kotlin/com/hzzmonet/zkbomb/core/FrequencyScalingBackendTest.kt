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

        val snapshot = backend(access).snapshot().targets.single()

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

        val snapshot = backend(access).snapshot().targets.single()

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

        val policies = backend(access).snapshot().cpuPolicies()

        assertEquals(listOf("policy0", "policy4"), policies.map { it.id })
    }

    @Test fun `rounded MHz selection routes the exact advertised raw cpu value`() {
        val target = cpuTarget("policy0")
        val access = populatedCpu(target)
        val writer = FakeFrequencyControlWriter(access)

        val result = backend(access, writer).set(cpuRequest("policy0", 300, 1_805))

        assertTrue(result.isSuccess)
        assertEquals("1804800", access.read(target, FrequencySysfsNode.MAX))
        assertTrue(writer.calls.contains("cpu0_max" to 1_804_800L))
    }

    @Test fun `raising minimum above current maximum routes maximum first`() {
        val target = cpuTarget("policy0")
        val access = populatedCpu(target).apply {
            set(target, FrequencySysfsNode.MAX, "600000")
        }
        val writer = FakeFrequencyControlWriter(access)

        val result = backend(access, writer).set(cpuRequest("policy0", 1_805, 2_208))

        assertTrue(result.isSuccess)
        assertEquals("cpu0_max", writer.calls.first().first)
        assertEquals("cpu0_min", writer.calls.last().first)
    }

    @Test fun `lowering maximum below current minimum routes minimum first`() {
        val target = cpuTarget("policy0")
        val access = populatedCpu(target).apply {
            set(target, FrequencySysfsNode.MIN, "1804800")
        }
        val writer = FakeFrequencyControlWriter(access)

        val result = backend(access, writer).set(cpuRequest("policy0", 300, 600))

        assertTrue(result.isSuccess)
        assertEquals("cpu0_min", writer.calls.first().first)
        assertEquals("cpu0_max", writer.calls.last().first)
    }

    @Test fun `frequency not present in fresh dynamic table is rejected without routing`() {
        val target = cpuTarget("policy0")
        val access = populatedCpu(target)
        val writer = FakeFrequencyControlWriter(access)

        val result = backend(access, writer).set(cpuRequest("policy0", 301, 1_805))

        assertEquals(BombResult.Status.INVALID_ARGUMENT, result.status)
        assertTrue(writer.calls.isEmpty())
    }

    @Test fun `gpu limits route through the gpu request fields`() {
        val target = gpuTarget()
        val access = FakeFrequencyAccess(listOf(target)).apply {
            set(target, FrequencySysfsNode.AVAILABLE, "200000000 587000000 900000000")
            set(target, FrequencySysfsNode.MIN, "200000000")
            set(target, FrequencySysfsNode.MAX, "900000000")
        }
        val writer = FakeFrequencyControlWriter(access)

        val result = backend(access, writer).set(gpuRequest(200, 587))

        assertTrue(result.isSuccess)
        assertTrue(writer.calls.contains("gpu_max" to 587_000_000L))
        assertEquals("587000000", access.read(target, FrequencySysfsNode.MAX))
    }

    @Test fun `unreachable control plane leaves the target read-only`() {
        val target = gpuTarget()
        val access = FakeFrequencyAccess(listOf(target)).apply {
            set(target, FrequencySysfsNode.AVAILABLE, "200000000 900000000")
            set(target, FrequencySysfsNode.MIN, "200000000")
            set(target, FrequencySysfsNode.MAX, "900000000")
        }
        val writer = FakeFrequencyControlWriter(access, available = false)
        val backend = backend(access, writer)

        assertFalse(backend.snapshot().targets.single().writable)
        assertEquals(BombResult.Status.UNSUPPORTED, backend.set(gpuRequest(200, 900)).status)
        assertEquals(
            FrequencyScalingCapabilities(false, false, true, false),
            backend.capabilities(),
        )
    }

    @Test fun `a cpu policy beyond the routable allowlist is probeable but not writable`() {
        val target = cpuTarget("policy8")
        val access = populatedCpu(target)
        val writer = FakeFrequencyControlWriter(access)
        val backend = backend(access, writer)

        assertFalse(backend.snapshot().targets.single().writable)
        assertEquals(BombResult.Status.UNSUPPORTED, backend.set(cpuRequest("policy8", 300, 600)).status)
        assertTrue(writer.calls.isEmpty())
    }

    @Test fun `failed second routed write reports backend unavailable without rollback`() {
        val target = cpuTarget("policy0")
        val access = populatedCpu(target)
        val writer = FakeFrequencyControlWriter(access).apply { failField = "cpu0_max" }

        val result = backend(access, writer).set(cpuRequest("policy0", 600, 1_805))

        assertEquals(BombResult.Status.BACKEND_UNAVAILABLE, result.status)
        // The first routed write (min) landed; the failed max is left unchanged, and
        // min <= max still holds so there is nothing to roll back.
        assertEquals("600000", access.read(target, FrequencySysfsNode.MIN))
        assertEquals("2208000", access.read(target, FrequencySysfsNode.MAX))
    }

    @Test fun `malformed table is not partially advertised`() {
        val target = cpuTarget("policy0")
        val access = populatedCpu(target).apply {
            set(target, FrequencySysfsNode.AVAILABLE, "300000 not-a-frequency 600000")
            set(target, FrequencySysfsNode.BOOST, "")
        }

        assertTrue(backend(access).snapshot().targets.isEmpty())
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

    private fun backend(
        access: FakeFrequencyAccess,
        writer: FakeFrequencyControlWriter = FakeFrequencyControlWriter(access),
    ) = FrequencyScalingBackend(access, writer)

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
    ) : FrequencyNodeAccess {
        private val values = mutableMapOf<Pair<String, FrequencySysfsNode>, String>()

        override fun targets() = configuredTargets

        override fun read(target: SysfsFrequencyTarget, node: FrequencySysfsNode): String? =
            values[target.directory.path to node]

        fun set(target: SysfsFrequencyTarget, node: FrequencySysfsNode, value: String) {
            values[target.directory.path to node] = value
        }

        fun targetByPublicId(publicId: String): SysfsFrequencyTarget? =
            configuredTargets.firstOrNull { it.publicId == publicId }
    }

    /**
     * Records the routed field/value pairs and applies each accepted write back to
     * the fake sysfs map, mirroring bombd -> init landing the value on the node so
     * the backend's read-back sees it.
     */
    private class FakeFrequencyControlWriter(
        private val access: FakeFrequencyAccess,
        override val available: Boolean = true,
    ) : FrequencyControlWriter {
        val calls = mutableListOf<Pair<String, Long>>()
        var failField: String? = null

        override fun write(field: String, rawValue: Long): Boolean {
            calls += field to rawValue
            if (failField == field) {
                failField = null
                return false
            }
            val (publicId, node) = parseField(field) ?: return false
            val target = access.targetByPublicId(publicId) ?: return false
            access.set(target, node, rawValue.toString())
            return true
        }

        private fun parseField(field: String): Pair<String, FrequencySysfsNode>? {
            val node = when {
                field.endsWith("_min") -> FrequencySysfsNode.MIN
                field.endsWith("_max") -> FrequencySysfsNode.MAX
                else -> return null
            }
            val publicId = when {
                field.startsWith("cpu") ->
                    "policy" + field.removePrefix("cpu").substringBefore('_')
                field.startsWith("gpu") -> "gpu0"
                else -> return null
            }
            return publicId to node
        }
    }

    private companion object {
        val NODE_NAMES = FrequencySysfsNode.entries.associateWith { it.name.lowercase() }
    }
}
