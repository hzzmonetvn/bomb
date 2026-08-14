package com.hzzmonet.zkbomb.core

import com.hzzmonet.zkbomb.api.BombResult
import com.hzzmonet.zkbomb.domain.performance.FrequencyLimitRequest
import com.hzzmonet.zkbomb.domain.performance.FrequencyLimitRequestValidator
import com.hzzmonet.zkbomb.domain.performance.FrequencyScalingSnapshot
import com.hzzmonet.zkbomb.domain.performance.FrequencyScalingTarget
import com.hzzmonet.zkbomb.domain.performance.FrequencyTargetKind
import java.io.File

internal enum class FrequencySysfsNode { AVAILABLE, BOOST, MIN, MAX }

internal data class SysfsFrequencyTarget(
    val publicId: String,
    val kind: FrequencyTargetKind,
    val rawUnitsPerMHz: Long,
    val directory: File,
    val nodeNames: Map<FrequencySysfsNode, String>,
)

/**
 * Read-only sysfs access. The app reads the cpufreq/GPU nodes to probe and read
 * back, but it never writes them — every frequency write is routed through
 * [FrequencyControlWriter] → bombd → init.
 */
internal interface FrequencyNodeAccess {
    fun targets(): List<SysfsFrequencyTarget>
    fun read(target: SysfsFrequencyTarget, node: FrequencySysfsNode): String?
}

/** Discovers only fixed cpufreq nodes and GPU directories selected by bounded vendor patterns. */
internal class RealFrequencyNodeAccess(
    private val cpuRoot: File = File("/sys/devices/system/cpu/cpufreq"),
    private val kgslRoot: File = File("/sys/class/kgsl/kgsl-3d0"),
    private val vendorDevfreqRoot: File = File("/sys/class/devfreq"),
) : FrequencyNodeAccess {
    override fun targets(): List<SysfsFrequencyTarget> = cpuTargets() + gpuTargets()

    override fun read(target: SysfsFrequencyTarget, node: FrequencySysfsNode): String? =
        resolved(target, node)?.let { file ->
            runCatching {
                file.bufferedReader(Charsets.US_ASCII).use { reader ->
                    reader.readLine()?.trim()?.take(MAX_NODE_TEXT)
                }
            }.getOrNull()
        }

    private fun cpuTargets(): List<SysfsFrequencyTarget> = cpuRoot.listFiles()
        ?.asSequence()
        ?.filter { it.isDirectory && CPU_POLICY_ID.matches(it.name) }
        ?.sortedBy { it.name.removePrefix("policy").toIntOrNull() ?: Int.MAX_VALUE }
        ?.take(MAX_CPU_POLICIES)
        ?.mapNotNull { directory ->
            canonicalDirectory(directory)?.let {
                SysfsFrequencyTarget(
                    publicId = directory.name,
                    kind = FrequencyTargetKind.CPU_POLICY,
                    rawUnitsPerMHz = CPU_KHZ_PER_MHZ,
                    directory = it,
                    nodeNames = CPU_NODES,
                )
            }
        }
        ?.toList()
        .orEmpty()

    private fun gpuTargets(): List<SysfsFrequencyTarget> {
        val candidates = buildList {
            add(File(kgslRoot, "devfreq") to GPU_DEVFREQ_NODES)
            add(kgslRoot to GPU_KGSL_LEGACY_NODES)
            vendorDevfreqRoot.listFiles()
                ?.asSequence()
                ?.filter { it.isDirectory && VENDOR_DIRECTORY.matches(it.name) }
                ?.filter { directory ->
                    val name = directory.name.lowercase()
                    GPU_DIRECTORY_MARKERS.any(name::contains)
                }
                ?.sortedBy { it.name }
                ?.take(MAX_VENDOR_GPU_CANDIDATES)
                ?.forEach { add(it to GPU_DEVFREQ_NODES) }
        }
        val seen = mutableSetOf<String>()
        return candidates.mapNotNull { (directory, nodes) ->
            val canonical = canonicalDirectory(directory) ?: return@mapNotNull null
            if (!seen.add(canonical.path)) return@mapNotNull null
            SysfsFrequencyTarget(
                publicId = FrequencyLimitRequestValidator.GPU_TARGET_ID,
                kind = FrequencyTargetKind.GPU,
                rawUnitsPerMHz = GPU_HZ_PER_MHZ,
                directory = canonical,
                nodeNames = nodes,
            )
        }
    }

    private fun resolved(target: SysfsFrequencyTarget, node: FrequencySysfsNode): File? {
        val name = target.nodeNames[node] ?: return null
        if (!NODE_NAME.matches(name)) return null
        return File(target.directory, name).takeIf { it.isFile }
    }

    private fun canonicalDirectory(directory: File): File? =
        runCatching { directory.canonicalFile }.getOrNull()?.takeIf { it.isDirectory }

    private companion object {
        const val MAX_NODE_TEXT = 8_192
        const val MAX_CPU_POLICIES = 32
        const val MAX_VENDOR_GPU_CANDIDATES = 16
        const val CPU_KHZ_PER_MHZ = 1_000L
        const val GPU_HZ_PER_MHZ = 1_000_000L
        val CPU_POLICY_ID = Regex("^policy[0-9]{1,3}$")
        val VENDOR_DIRECTORY = Regex("^[A-Za-z0-9,._:@+-]{1,128}$")
        val NODE_NAME = Regex("^[a-z0-9_]{1,64}$")
        val GPU_DIRECTORY_MARKERS = listOf("gpu", "kgsl", "mali", "3d")
        val CPU_NODES = mapOf(
            FrequencySysfsNode.AVAILABLE to "scaling_available_frequencies",
            FrequencySysfsNode.BOOST to "scaling_boost_frequencies",
            FrequencySysfsNode.MIN to "scaling_min_freq",
            FrequencySysfsNode.MAX to "scaling_max_freq",
        )
        val GPU_DEVFREQ_NODES = mapOf(
            FrequencySysfsNode.AVAILABLE to "available_frequencies",
            FrequencySysfsNode.MIN to "min_freq",
            FrequencySysfsNode.MAX to "max_freq",
        )
        val GPU_KGSL_LEGACY_NODES = mapOf(
            FrequencySysfsNode.AVAILABLE to "gpu_available_frequencies",
            FrequencySysfsNode.MIN to "min_gpuclk",
            FrequencySysfsNode.MAX to "max_gpuclk",
        )
    }
}

/**
 * Publishes a frequency-limit write through the init-owned control plane. The
 * process never writes the sysfs node: [field] names a bounded request property
 * (`cpu0_min`..`cpu7_max`, `gpu_min`, `gpu_max`) that bombd range-checks and
 * sets, and init's bomb.rc trigger performs the write.
 */
internal interface FrequencyControlWriter {
    val available: Boolean
    fun write(field: String, rawValue: Long): Boolean
}

internal class BombdFrequencyControlWriter(
    private val control: RomControlPropertyWriter = RomControlPropertyWriter(),
) : FrequencyControlWriter {
    override val available: Boolean get() = control.available

    override fun write(field: String, rawValue: Long): Boolean =
        control.requestFrequencyControl(field, rawValue)
}

data class FrequencyScalingCapabilities(
    val cpuPresent: Boolean,
    val cpuWritable: Boolean,
    val gpuPresent: Boolean,
    val gpuWritable: Boolean,
) {
    companion object {
        val NONE = FrequencyScalingCapabilities(false, false, false, false)
    }
}

class FrequencyScalingBackend internal constructor(
    private val access: FrequencyNodeAccess = RealFrequencyNodeAccess(),
    // No frequency write touches sysfs from this process: the raw min/max value is
    // published as a bounded request through bombd, and init owns the node write.
    private val writer: FrequencyControlWriter = BombdFrequencyControlWriter(),
) {
    @Synchronized
    fun snapshot(): FrequencyScalingSnapshot = FrequencyScalingSnapshot(
        probeTargets().map { it.snapshot },
    )

    @Synchronized
    fun capabilities(): FrequencyScalingCapabilities {
        val targets = probeTargets().map { it.snapshot }
        val cpu = targets.filter { it.kind == FrequencyTargetKind.CPU_POLICY }
        val gpu = targets.filter { it.kind == FrequencyTargetKind.GPU }
        return FrequencyScalingCapabilities(
            cpuPresent = cpu.isNotEmpty(),
            cpuWritable = cpu.any { it.writable },
            gpuPresent = gpu.isNotEmpty(),
            gpuWritable = gpu.any { it.writable },
        )
    }

    @Synchronized
    fun set(request: FrequencyLimitRequest): BombResult {
        FrequencyLimitRequestValidator.violations(request).firstOrNull()?.let {
            return BombResult.invalidArgument(it)
        }
        val target = probeTargets().firstOrNull {
            it.snapshot.id == request.targetId && it.snapshot.kind == request.kind
        } ?: return BombResult.unsupported("Frequency target is not present")
        if (!target.snapshot.writable) {
            return BombResult.unsupported("Frequency target is read-only under the installed policy")
        }
        val minRaw = target.rawByMHz[request.minMHz]
            ?: return BombResult.invalidArgument("minMHz is not an advertised frequency point")
        val maxRaw = target.rawByMHz[request.maxMHz]
            ?: return BombResult.invalidArgument("maxMHz is not an advertised frequency point")
        if (minRaw > maxRaw) return BombResult.invalidArgument("minMHz must not exceed maxMHz")
        val minField = fieldFor(target.ref, FrequencySysfsNode.MIN)
        val maxField = fieldFor(target.ref, FrequencySysfsNode.MAX)
            ?: return BombResult.unsupported("Frequency target is not routable")
        if (minField == null) return BombResult.unsupported("Frequency target is not routable")

        // A no-op is success without touching the control plane.
        val currentMin = readSingleRaw(target.ref, FrequencySysfsNode.MIN)
        val currentMax = readSingleRaw(target.ref, FrequencySysfsNode.MAX)
        if (currentMin == minRaw && currentMax == maxRaw) return BombResult.success()

        // Order the two routed writes so the kernel never sees min > max midway:
        // when the new floor is above the current ceiling, raise the ceiling first.
        val ordered = if (currentMax != null && minRaw > currentMax) {
            listOf(
                Triple(maxField, maxRaw, FrequencySysfsNode.MAX),
                Triple(minField, minRaw, FrequencySysfsNode.MIN),
            )
        } else {
            listOf(
                Triple(minField, minRaw, FrequencySysfsNode.MIN),
                Triple(maxField, maxRaw, FrequencySysfsNode.MAX),
            )
        }
        for ((field, raw, node) in ordered) {
            // A node already at the target value is skipped: init would re-fire the
            // trigger for nothing. Unlike the old direct writer there is no
            // read-back rollback — init applies the write asynchronously, so a
            // failed publish reports backend-unavailable and leaves the already
            // applied node in place (min <= max is preserved by the ordering).
            if (readSingleRaw(target.ref, node) == raw) continue
            if (!writer.write(field, raw)) {
                return BombResult.backendUnavailable(
                    "Frequency limit was rejected or bombd was unreachable",
                )
            }
        }
        return BombResult.success()
    }

    /**
     * The bombd/init request field for a target's min/max node, or null when the
     * target cannot be routed (a CPU policy outside the allowlisted 0..7 range).
     */
    private fun fieldFor(ref: SysfsFrequencyTarget, node: FrequencySysfsNode): String? {
        val suffix = if (node == FrequencySysfsNode.MIN) "min" else "max"
        return when (ref.kind) {
            FrequencyTargetKind.CPU_POLICY -> {
                val index = ref.publicId.removePrefix("policy").toIntOrNull()
                    ?.takeIf { it in 0..MAX_ROUTABLE_POLICY } ?: return null
                "cpu${index}_$suffix"
            }
            FrequencyTargetKind.GPU -> "gpu_$suffix"
        }
    }

    private fun probeTargets(): List<ProbedTarget> {
        val resolved = access.targets().mapNotNull(::probeTarget)
        // Several GPU class paths can point at the same device. The first fully
        // probeable schema owns gpu0; duplicate public targets never cross IPC.
        return resolved.distinctBy { it.snapshot.kind to it.snapshot.id }
    }

    private fun probeTarget(ref: SysfsFrequencyTarget): ProbedTarget? {
        if (ref.rawUnitsPerMHz <= 0) return null
        val normalRaw = parseRawList(access.read(ref, FrequencySysfsNode.AVAILABLE))
        val boostRaw = parseRawList(access.read(ref, FrequencySysfsNode.BOOST))
        val allRaw = (normalRaw + boostRaw).distinct().sorted()
        if (allRaw.isEmpty()) return null

        // AIDL exposes integer MHz, but sysfs is written using this exact raw
        // map. Ambiguous rounded MHz points are omitted instead of guessed.
        val rawByMHz: Map<Int, Long> = allRaw
            .groupBy { toMHz(it, ref.rawUnitsPerMHz) }
            .mapNotNull { (mhz, values) ->
                val resolvedMHz = mhz ?: return@mapNotNull null
                val unique = values.distinct()
                if (unique.size == 1) resolvedMHz to unique.single() else null
            }.toMap().toSortedMap()
        if (rawByMHz.isEmpty()) return null

        val currentMinRaw = readSingleRaw(ref, FrequencySysfsNode.MIN)
        val currentMaxRaw = readSingleRaw(ref, FrequencySysfsNode.MAX)
        val currentMinMHz = currentMinRaw?.let { toMHz(it, ref.rawUnitsPerMHz) }
        val currentMaxMHz = currentMaxRaw?.let { toMHz(it, ref.rawUnitsPerMHz) }
        if (currentMinMHz != null && currentMaxMHz != null && currentMinMHz > currentMaxMHz) {
            return null
        }
        val boostMHz = boostRaw.mapNotNull { raw ->
            val mhz = toMHz(raw, ref.rawUnitsPerMHz)
            mhz?.takeIf { rawByMHz[it] == raw }
        }.distinct().sorted()
        // Presence-based, not app-writability: writes go through init (which owns
        // the node), so the DAC bit on this process is always clear and is the
        // wrong signal. A target is writable when both min and max read back, it
        // maps to a routable request field, and the control plane is reachable;
        // CapabilityProbe further requires ROM mode before reporting SUPPORTED.
        val nodesPresent = currentMinRaw != null && currentMaxRaw != null
        val routable = fieldFor(ref, FrequencySysfsNode.MIN) != null &&
            fieldFor(ref, FrequencySysfsNode.MAX) != null
        val writable = nodesPresent && routable && writer.available
        return ProbedTarget(
            ref,
            rawByMHz,
            FrequencyScalingTarget(
                id = ref.publicId,
                kind = ref.kind,
                availableMHz = rawByMHz.keys.toList(),
                boostMHz = boostMHz,
                currentMinMHz = currentMinMHz,
                currentMaxMHz = currentMaxMHz,
                writable = writable,
            ),
        )
    }

    private fun parseRawList(text: String?): List<Long> {
        val tokens = text?.trim()?.split(WHITESPACE)?.filter(String::isNotEmpty).orEmpty()
        if (tokens.isEmpty() || tokens.size > MAX_FREQUENCY_POINTS) return emptyList()
        val parsed = tokens.map { token -> token.toLongOrNull()?.takeIf { it > 0 } }
        return if (parsed.any { it == null }) emptyList() else parsed.filterNotNull()
    }

    private fun readSingleRaw(target: SysfsFrequencyTarget, node: FrequencySysfsNode): Long? =
        access.read(target, node)?.trim()?.toLongOrNull()?.takeIf { it > 0 }

    private fun toMHz(raw: Long, rawUnitsPerMHz: Long): Int? {
        if (raw <= 0 || raw > Long.MAX_VALUE - rawUnitsPerMHz / 2) return null
        val rounded = (raw + rawUnitsPerMHz / 2) / rawUnitsPerMHz
        return rounded.takeIf { it in 1L..Int.MAX_VALUE.toLong() }?.toInt()
    }

    private data class ProbedTarget(
        val ref: SysfsFrequencyTarget,
        val rawByMHz: Map<Int, Long>,
        val snapshot: FrequencyScalingTarget,
    )

    private companion object {
        const val MAX_FREQUENCY_POINTS = 256

        // Fixed init/property fan-out covers policy0..policy7 (see bomb.rc). A
        // policy beyond this range has no request property and is not routable.
        const val MAX_ROUTABLE_POLICY = 7
        val WHITESPACE = Regex("\\s+")
    }
}
