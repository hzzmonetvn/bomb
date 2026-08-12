package com.hzzmonet.zkbomb.api

import android.os.Parcelable
import com.hzzmonet.zkbomb.domain.performance.FrequencyLimitRequest
import com.hzzmonet.zkbomb.domain.performance.FrequencyLimitRequestValidator
import com.hzzmonet.zkbomb.domain.performance.FrequencyScalingSnapshot as DomainFrequencyScalingSnapshot
import com.hzzmonet.zkbomb.domain.performance.FrequencyScalingTarget
import com.hzzmonet.zkbomb.domain.performance.FrequencyTargetKind
import kotlinx.parcelize.Parcelize

@Parcelize
data class FrequencyScalingTargetParcel(
    val id: String,
    val kind: String,
    val availableMHz: List<Int>,
    val boostMHz: List<Int>,
    val currentMinMHz: Int?,
    val currentMaxMHz: Int?,
    val writable: Boolean,
) : Parcelable {
    fun isStructurallyValid(): Boolean {
        val resolvedKind = FrequencyTargetKind.entries.firstOrNull { it.name == kind } ?: return false
        val idProbe = FrequencyLimitRequest(id, resolvedKind, 1, 1)
        if (FrequencyLimitRequestValidator.violations(idProbe).isNotEmpty()) return false
        if (availableMHz.isEmpty() || availableMHz.size > MAX_FREQUENCY_POINTS) return false
        if (availableMHz.any { it <= 0 } || availableMHz != availableMHz.distinct().sorted()) return false
        if (boostMHz.any { it !in availableMHz } || boostMHz != boostMHz.distinct().sorted()) return false
        if (currentMinMHz != null && currentMinMHz <= 0) return false
        if (currentMaxMHz != null && currentMaxMHz <= 0) return false
        return currentMinMHz == null || currentMaxMHz == null || currentMinMHz <= currentMaxMHz
    }

    companion object {
        private const val MAX_FREQUENCY_POINTS = 256

        fun fromDomain(target: FrequencyScalingTarget) = FrequencyScalingTargetParcel(
            id = target.id,
            kind = target.kind.name,
            availableMHz = target.availableMHz,
            boostMHz = target.boostMHz,
            currentMinMHz = target.currentMinMHz,
            currentMaxMHz = target.currentMaxMHz,
            writable = target.writable,
        )
    }
}

@Parcelize
data class FrequencyScalingSnapshot(
    val targets: List<FrequencyScalingTargetParcel>,
) : Parcelable {
    fun isStructurallyValid(): Boolean = targets.size <= MAX_TARGETS &&
        targets.all { it.isStructurallyValid() } &&
        targets.map { it.id }.distinct().size == targets.size

    companion object {
        private const val MAX_TARGETS = 33

        fun fromDomain(snapshot: DomainFrequencyScalingSnapshot) = FrequencyScalingSnapshot(
            snapshot.targets.map { FrequencyScalingTargetParcel.fromDomain(it) },
        )
    }
}

@Parcelize
data class FrequencyLimitRequestParcel(
    val targetId: String,
    val kind: String,
    val minMHz: Int,
    val maxMHz: Int,
) : Parcelable {
    fun toDomain(): FrequencyLimitRequest? {
        val resolvedKind = FrequencyTargetKind.entries.firstOrNull { it.name == kind } ?: return null
        val request = FrequencyLimitRequest(targetId, resolvedKind, minMHz, maxMHz)
        return request.takeIf { FrequencyLimitRequestValidator.violations(it).isEmpty() }
    }
}
