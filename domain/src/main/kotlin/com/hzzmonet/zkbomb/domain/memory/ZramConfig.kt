package com.hzzmonet.zkbomb.domain.memory

/**
 * The swappiness knobs HyperOS actually uses on this ROM.
 *
 * An enumerated set, not a caller-supplied path — `CLAUDE.md` forbids an
 * arbitrary-sysfs-write API, and "write swappiness" with a free path string
 * would be exactly that wearing a different name.
 *
 * The ROM writes five of these at boot (`init.rc:39-43`), which is the first
 * thing that makes this subsystem unusual: Bomb is **modifying existing ROM
 * behaviour**, not configuring an unset knob, so "restore default" means the
 * ROM's captured value and not the kernel's 60.
 */
enum class SwappinessTarget(val path: String, val writable: Boolean) {
    /** `/proc/sys/vm/swappiness` — the global knob, set to 100 by the ROM. */
    GLOBAL("/proc/sys/vm/swappiness", writable = true),

    /** The root memcg. */
    MEMCG_ROOT("/dev/memcg/memory.swappiness", writable = true),

    /**
     * The memcg HyperOS puts frozen apps into, at swappiness 60.
     *
     * This couples swap policy to the Freeze Engine: a swappiness control that
     * only touched [GLOBAL] would be silently wrong for exactly the apps Bomb
     * froze.
     */
    MEMCG_FREEZE_APP("/dev/memcg/freeze-app/memory.swappiness", writable = true),

    /** Foreground apps. */
    MEMCG_APPS("/dev/memcg/apps/memory.swappiness", writable = true),

    /** System processes. */
    MEMCG_SYSTEM("/dev/memcg/system/memory.swappiness", writable = true),

    /**
     * `sys_critical`, at swappiness 0 — **never written**.
     *
     * `mcd_default.conf` uses this cgroup to pin ueventd, vold, netd,
     * surfaceflinger and servicemanager out of swap entirely. Raising its
     * swappiness would let the processes that keep the device usable be swapped
     * out under pressure. It is listed rather than omitted so that the refusal is
     * explicit and testable instead of an absence someone could later "fix".
     */
    MEMCG_SYS_CRITICAL("/dev/memcg/sys_critical/memory.swappiness", writable = false),
    ;

    companion object {
        /** The targets a swappiness change may touch. */
        val WRITABLE: List<SwappinessTarget> = entries.filter { it.writable }
    }
}

/**
 * A requested zram/swap configuration.
 *
 * [disksizeBytes] and [algorithm] are **boot-time only**. Both require the
 * device to be empty, which means `swapoff`, which faults every swapped page
 * back into RAM — 5.28 GB in the verified sample. On a loaded phone that is an
 * immediate OOM storm or a multi-minute freeze, so no live path exists and
 * `disksize` is deliberately not a Performance Profile field: a profile switch
 * must never be able to trigger a swapoff.
 */
data class ZramConfig(
    /** Requested zram device size, applied at next boot. Null leaves it unchanged. */
    val disksizeBytes: Long?,
    /** Requested compression algorithm, applied at next boot. Null leaves it unchanged. */
    val algorithm: String?,
    /** Live. Applied to every writable [SwappinessTarget]. Null leaves it unchanged. */
    val swappiness: Int?,
    /** Live. Null leaves it unchanged. */
    val pageCluster: Int?,
)

/** Why a [ZramConfig] was rejected. */
enum class ZramViolation {
    /** zram is not present on this device. */
    ZRAM_UNAVAILABLE,

    /** The algorithm is not one the kernel reported as available. */
    ALGORITHM_NOT_AVAILABLE,

    /** `disksize` was zero or negative. */
    DISKSIZE_NOT_POSITIVE,

    /** `disksize` exceeds the ceiling relative to physical RAM. */
    DISKSIZE_ABSURD,

    /** `swappiness` outside the kernel's accepted range. */
    SWAPPINESS_OUT_OF_RANGE,

    /** `page-cluster` outside Bomb's accepted range. */
    PAGE_CLUSTER_OUT_OF_RANGE,

    /** An attribute was requested that this kernel does not expose. */
    ATTRIBUTE_UNSUPPORTED,
}

/**
 * Validates a requested configuration against what the device reported.
 *
 * The algorithm is checked against the **probed** list rather than a hardcoded
 * one, which is the whole reason [ZramAlgorithms] parses the available set out
 * of the same line that carries the current value.
 */
object ZramConfigValidator {

    /**
     * Kernel range for `vm.swappiness`.
     *
     * 200 rather than 100: this ROM's `swappiness_on_launcher` can push 200, so a
     * 0..100 bound would reject a value the device itself sets.
     */
    val SWAPPINESS_RANGE = 0..200

    /**
     * Bomb's own bound on `page-cluster`.
     *
     * The value is a power-of-two exponent for swap readahead, so 6 already means
     * 64 pages and nothing above that has a sensible use on a phone. This is a
     * Bomb policy limit, not a kernel constant that was verified.
     */
    val PAGE_CLUSTER_RANGE = 0..6

    /**
     * Ceiling on `disksize` as a multiple of physical RAM.
     *
     * zram legitimately exceeds RAM because it stores compressed pages, but a
     * request beyond this is a typo rather than an intention.
     */
    const val MAX_DISKSIZE_RAM_MULTIPLE = 4

    fun validate(
        config: ZramConfig,
        capabilities: ZramCapabilities,
        algorithms: ZramAlgorithms?,
        totalRamBytes: Long,
    ): List<ZramViolation> = buildList {
        if (!capabilities.present) {
            add(ZramViolation.ZRAM_UNAVAILABLE)
            return@buildList
        }

        config.disksizeBytes?.let { size ->
            if (!capabilities.disksize) add(ZramViolation.ATTRIBUTE_UNSUPPORTED)
            if (size <= 0) {
                add(ZramViolation.DISKSIZE_NOT_POSITIVE)
            } else if (size > totalRamBytes * MAX_DISKSIZE_RAM_MULTIPLE) {
                add(ZramViolation.DISKSIZE_ABSURD)
            }
        }

        config.algorithm?.let { requested ->
            if (!capabilities.compAlgorithm) {
                add(ZramViolation.ATTRIBUTE_UNSUPPORTED)
            } else if (algorithms == null || requested !in algorithms.available) {
                add(ZramViolation.ALGORITHM_NOT_AVAILABLE)
            }
        }

        config.swappiness?.let { value ->
            if (!capabilities.swappiness) add(ZramViolation.ATTRIBUTE_UNSUPPORTED)
            if (value !in SWAPPINESS_RANGE) add(ZramViolation.SWAPPINESS_OUT_OF_RANGE)
        }

        config.pageCluster?.let { value ->
            if (!capabilities.pageCluster) add(ZramViolation.ATTRIBUTE_UNSUPPORTED)
            if (value !in PAGE_CLUSTER_RANGE) add(ZramViolation.PAGE_CLUSTER_OUT_OF_RANGE)
        }
    }
}

/** The verdict on changing zram's size or algorithm. */
sealed interface ResizeVerdict {

    /**
     * Safe to apply at the next boot, where swap is empty by definition.
     *
     * The only path Bomb ships.
     */
    data object DeferToBoot : ResizeVerdict

    /**
     * A live change would have to fault [wouldFaultInBytes] back into
     * [availableBytes] of free memory.
     *
     * Reported with the actual numbers rather than a bare refusal, because the
     * numbers are the argument.
     */
    data class UnsafeLive(
        val wouldFaultInBytes: Long,
        val availableBytes: Long,
    ) : ResizeVerdict
}

/**
 * The gate any live resize would have to pass.
 *
 * No live path exists today — [ZramConfig] applies size and algorithm at boot.
 * This gate is written and tested anyway so that a future live path cannot be
 * added without one: the failure mode it prevents (swapping 5 GB back into a
 * phone with 1 GB free) is not recoverable by the user.
 */
object ZramResizeSafety {

    /**
     * Headroom multiple required before a live swapoff could be contemplated.
     *
     * Faulting pages back in needs room for the uncompressed data *plus* the
     * working set that continues to run during the operation.
     */
    const val REQUIRED_HEADROOM = 1.2

    fun evaluate(mmStat: ZramMmStat, memAvailableBytes: Long): ResizeVerdict {
        val needed = (mmStat.origDataSize * REQUIRED_HEADROOM).toLong()
        return if (memAvailableBytes >= needed) {
            ResizeVerdict.DeferToBoot
        } else {
            ResizeVerdict.UnsafeLive(
                wouldFaultInBytes = mmStat.origDataSize,
                availableBytes = memAvailableBytes,
            )
        }
    }
}
