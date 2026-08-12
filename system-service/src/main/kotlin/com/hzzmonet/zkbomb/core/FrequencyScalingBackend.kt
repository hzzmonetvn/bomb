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

internal interface FrequencyNodeAccess {
    fun targets(): List<SysfsFrequencyTarget>
    fun read(target: SysfsFrequencyTarget, node: FrequencySysfsNode): String?
    fun canWrite(target: SysfsFrequencyTarget, node: FrequencySysfsNode): Boolean
    fun write(target: SysfsFrequencyTarget, node: FrequencySysfsNode, rawValue: Long): Boolean
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

    override fun canWrite(target: SysfsFrequencyTarget, node: FrequencySysfsNode): Boolean =
        resolved(target, node)?.canWrite() == true

    override fun write(
        target: SysfsFrequencyTarget,
        node: FrequencySysfsNode,
        rawValue: Long,
    ): Boolean {
        if (rawValue <= 0) return false
        val file = resolved(target, node) ?: return false
        return runCatching {
            file.outputStream().bufferedWriter(Charsets.US_ASCII).use { it.write(rawValue.toString()) }
            true
        }.getOrDefault(false)
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
    private val waitBeforeVerification: () -> Unit = {},
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

        val originalMin = readSingleRaw(target.ref, FrequencySysfsNode.MIN)
            ?: return BombResult.backendUnavailable("Current minimum frequency is unreadable")
        val originalMax = readSingleRaw(target.ref, FrequencySysfsNode.MAX)
            ?: return BombResult.backendUnavailable("Current maximum frequency is unreadable")
        if (originalMin == minRaw && originalMax == maxRaw) return BombResult.success()

        if (!writePair(target.ref, originalMin, originalMax, minRaw, maxRaw)) {
            val observedMin = readSingleRaw(target.ref, FrequencySysfsNode.MIN) ?: originalMin
            val observedMax = readSingleRaw(target.ref, FrequencySysfsNode.MAX) ?: originalMax
            writePair(target.ref, observedMin, observedMax, originalMin, originalMax)
            return BombResult.failed("Frequency limits were rejected or failed read-back verification")
        }
        return BombResult.success()
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
        val writable = access.canWrite(ref, FrequencySysfsNode.MIN) &&
            access.canWrite(ref, FrequencySysfsNode.MAX)
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

    private fun writePair(
        target: SysfsFrequencyTarget,
        currentMin: Long,
        currentMax: Long,
        desiredMin: Long,
        desiredMax: Long,
    ): Boolean {
        val writes = when {
            desiredMin > currentMax -> listOf(
                FrequencySysfsNode.MAX to desiredMax,
                FrequencySysfsNode.MIN to desiredMin,
            )
            desiredMax < currentMin -> listOf(
                FrequencySysfsNode.MIN to desiredMin,
                FrequencySysfsNode.MAX to desiredMax,
            )
            else -> listOf(
                FrequencySysfsNode.MIN to desiredMin,
                FrequencySysfsNode.MAX to desiredMax,
            )
        }
        for ((node, value) in writes) {
            val current = readSingleRaw(target, node)
            if (current == value) continue
            if (!access.write(target, node, value)) return false
            waitBeforeVerification()
            if (readSingleRaw(target, node) != value) return false
        }
        return readSingleRaw(target, FrequencySysfsNode.MIN) == desiredMin &&
            readSingleRaw(target, FrequencySysfsNode.MAX) == desiredMax
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
        val WHITESPACE = Regex("\\s+")
    }
}
