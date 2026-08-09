package com.hzzmonet.zkbomb.api

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * zram and swap telemetry.
 *
 * [compressionRatio] and [ramEfficiency] are **nullable rather than sentinel
 * values**. On a fresh zram both divide by zero, and the difference between
 * sending null and sending `-1` or `Infinity` is the difference between the UI
 * showing "unavailable" and the UI showing a number that is not true. The
 * two figures are also carried separately rather than flattened into one: same
 * pages are deduplicated, not compressed, so a single combined number always
 * flatters the result.
 */
@Parcelize
data class MemoryStatus(
    val zramPresent: Boolean,
    /** Configured zram device size in bytes, or null when zram is absent. */
    val disksizeBytes: Long?,
    val origDataSize: Long?,
    val comprDataSize: Long?,
    val memUsedTotal: Long?,
    /** `orig / compr`, or null when nothing has been swapped yet. */
    val compressionRatio: Double?,
    /** `orig / mem_used_total` — the honest figure, always the lower one. */
    val ramEfficiency: Double?,
    val currentAlgorithm: String?,
    /** Algorithms the kernel reported. The validation domain, read from the device. */
    val availableAlgorithms: List<String>,
    /** Global `vm.swappiness`, or null when unreadable. */
    val globalSwappiness: Int?,
    /**
     * Per-memcg swappiness by [com.hzzmonet.zkbomb.domain.memory.SwappinessTarget]
     * name.
     *
     * Reported per target rather than as one number because this ROM writes five
     * of them at different values, and presenting a single global swappiness as
     * if it were the whole picture would be wrong on this device specifically.
     */
    val swappinessByTarget: Map<String, Int>,
    /**
     * Memory Extension state as reported by each of its two independent
     * switches.
     *
     * `perfinit.conf` declares `extm_on: 1` while `build.prop` carries
     * `persist.miui.extm.enable=0`. Two switches, one feature — so both are
     * reported and neither is trusted as authoritative.
     */
    val memoryExtensionSwitches: Map<String, String>,
) : Parcelable

/**
 * A requested zram/swap change.
 *
 * [disksizeBytes] and [algorithm] apply at the **next boot**. Both require the
 * device to be empty, which means `swapoff`, which faults every swapped page
 * back into RAM — 5.28 GB in the measured sample. There is deliberately no live
 * path, and `disksize` is deliberately not a performance-profile field, so a
 * profile switch can never trigger a swapoff.
 */
@Parcelize
data class MemoryConfig(
    val disksizeBytes: Long? = null,
    val algorithm: String? = null,
    val swappiness: Int? = null,
    val pageCluster: Int? = null,
) : Parcelable
