package com.hzzmonet.zkbomb.domain.performance

enum class FrequencyTargetKind { CPU_POLICY, GPU }

/** A target discovered from the device's current cpufreq/devfreq topology. */
data class FrequencyScalingTarget(
    val id: String,
    val kind: FrequencyTargetKind,
    val availableMHz: List<Int>,
    val boostMHz: List<Int>,
    val currentMinMHz: Int?,
    val currentMaxMHz: Int?,
    val writable: Boolean,
)

data class FrequencyScalingSnapshot(
    val targets: List<FrequencyScalingTarget>,
) {
    fun cpuPolicies(): List<FrequencyScalingTarget> =
        targets.filter { it.kind == FrequencyTargetKind.CPU_POLICY }

    fun gpu(): FrequencyScalingTarget? =
        targets.firstOrNull { it.kind == FrequencyTargetKind.GPU }
}

data class FrequencyLimitRequest(
    val targetId: String,
    val kind: FrequencyTargetKind,
    val minMHz: Int,
    val maxMHz: Int,
)

object FrequencyLimitRequestValidator {
    private val CPU_POLICY_ID = Regex("^policy[0-9]{1,3}$")

    fun violations(request: FrequencyLimitRequest): List<String> = buildList {
        val validId = when (request.kind) {
            FrequencyTargetKind.CPU_POLICY -> CPU_POLICY_ID.matches(request.targetId)
            FrequencyTargetKind.GPU -> request.targetId == GPU_TARGET_ID
        }
        if (!validId) add("targetId does not match target kind")
        if (request.minMHz <= 0) add("minMHz must be positive")
        if (request.maxMHz <= 0) add("maxMHz must be positive")
        if (request.minMHz > request.maxMHz) add("minMHz must not exceed maxMHz")
    }

    const val GPU_TARGET_ID = "gpu0"
}

