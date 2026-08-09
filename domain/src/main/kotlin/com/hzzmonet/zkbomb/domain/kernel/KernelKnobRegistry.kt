package com.hzzmonet.zkbomb.domain.kernel

/**
 * The knobs Bomb knows about, and what is actually true about each on the target
 * ROM.
 *
 * Every [KernelKnob.backend] and [KernelKnob.contention] below was established
 * by reading the target image, not by assuming what a kernel usually allows:
 *
 * - **backend** comes from the ROM's SELinux policy — whether an
 *   `allow init <type> (file (… write …))` rule exists for the node's type.
 *   `init` is what Bomb's ROM-mode path runs as, so no rule means no ROM-mode
 *   write, however permissive the file mode looks.
 * - **contention** comes from the ROM's service list and `.rc` files. A node a
 *   vendor daemon rewrites cannot be controlled by writing it once.
 *
 * A knob that is not offerable stays listed with its reason rather than being
 * hidden. "Needs root" and "a daemon will undo this" are different answers and
 * the user can act on the difference.
 *
 * **This registry is a starting map, not a finished survey.** Anything marked
 * [KnobBackend.UNVERIFIED] has not been checked; the value domains marked
 * [KnobValueDomain.Probed] are empty until something reads them off the device.
 * Neither is offered while in that state.
 */
object KernelKnobRegistry {

    // Evidence strings are shared where several knobs rest on the same finding,
    // so that a correction lands in one place.
    private const val EV_INIT_RULE =
        "plat_sepolicy.cil grants init write on this type"
    private const val EV_NO_INIT_RULE =
        "no `allow init <type> … write` rule in plat or vendor policy"
    private const val EV_PERF_DAEMONS =
        "owned by mi_thermald / perf-hal-2-3 / miuibooster / mcd, which rewrite it continuously"

    val ALL: List<KernelKnob> = listOf(

        // ---- Memory: the group that actually works today -------------------

        KernelKnob(
            id = "vm.swappiness",
            path = "/proc/sys/vm/swappiness",
            group = KnobGroup.MEMORY,
            valueDomain = KnobValueDomain.Bounded(0, 200),
            backend = KnobBackend.INIT,
            // Not NONE: perfinit.conf's swappiness_on_launcher pushes 200 in some
            // situations, so a Bomb value can be replaced without warning.
            contention = KnobContention.OCCASIONAL,
            evidence = "the ROM's own init.rc:39 writes it, so init reaches it; " +
                "perfinit.conf swappiness_on_launcher also writes it",
            summary = "How readily the kernel swaps pages out. Higher means more swapping.",
        ),

        KernelKnob(
            id = "vm.page-cluster",
            path = "/proc/sys/vm/page-cluster",
            group = KnobGroup.MEMORY,
            // A power-of-two exponent: 3 already means 8 pages read at once.
            valueDomain = KnobValueDomain.Bounded(0, 6),
            backend = KnobBackend.INIT,
            contention = KnobContention.NONE,
            selinuxType = "proc_page_cluster",
            evidence = EV_INIT_RULE,
            summary = "How many pages are read from swap at once. 0 suits zram.",
        ),

        KernelKnob(
            id = "vm.dirty_ratio",
            path = "/proc/sys/vm/dirty_ratio",
            group = KnobGroup.MEMORY,
            valueDomain = KnobValueDomain.Bounded(1, 90, "%"),
            backend = KnobBackend.INIT,
            contention = KnobContention.NONE,
            selinuxType = "proc_dirty",
            evidence = EV_INIT_RULE,
            summary = "Share of memory that may hold unwritten data before writers block.",
        ),

        KernelKnob(
            id = "vm.dirty_background_ratio",
            path = "/proc/sys/vm/dirty_background_ratio",
            group = KnobGroup.MEMORY,
            valueDomain = KnobValueDomain.Bounded(1, 90, "%"),
            backend = KnobBackend.INIT,
            contention = KnobContention.NONE,
            selinuxType = "proc_dirty",
            evidence = EV_INIT_RULE,
            summary = "When background writeback starts.",
        ),

        KernelKnob(
            id = "vm.watermark_boost_factor",
            path = "/proc/sys/vm/watermark_boost_factor",
            group = KnobGroup.MEMORY,
            valueDomain = KnobValueDomain.Bounded(0, 30000),
            backend = KnobBackend.INIT,
            contention = KnobContention.NONE,
            selinuxType = "proc_watermark_boost_factor",
            evidence = EV_INIT_RULE,
            summary = "How aggressively the kernel reclaims after fragmentation.",
        ),

        KernelKnob(
            id = "vm.watermark_scale_factor",
            path = "/proc/sys/vm/watermark_scale_factor",
            group = KnobGroup.MEMORY,
            valueDomain = KnobValueDomain.Bounded(10, 1000),
            // Only `extra_free_kbytes` holds the write rule, not init.
            backend = KnobBackend.ROOT,
            contention = KnobContention.NONE,
            selinuxType = "proc_watermark_scale_factor",
            evidence = "write rule belongs to the extra_free_kbytes domain, not init",
            summary = "How much free memory the kernel keeps in reserve.",
        ),

        // ---- Scheduler ------------------------------------------------------

        KernelKnob(
            id = "kernel.sched",
            path = "/proc/sys/kernel/sched_*",
            group = KnobGroup.SCHEDULER,
            valueDomain = KnobValueDomain.Probed(),
            backend = KnobBackend.INIT,
            contention = KnobContention.UNKNOWN,
            selinuxType = "proc_sched",
            evidence = "$EV_INIT_RULE; which sched_* files exist is kernel-specific " +
                "and has not been enumerated on this device",
            summary = "Scheduler tunables. Which exist depends on the kernel.",
        ),

        KernelKnob(
            id = "kernel.perf_event_paranoid",
            path = "/proc/sys/kernel/perf_event_paranoid",
            group = KnobGroup.SCHEDULER,
            valueDomain = KnobValueDomain.Bounded(-1, 3),
            backend = KnobBackend.INIT,
            contention = KnobContention.NONE,
            selinuxType = "proc_perf",
            evidence = EV_INIT_RULE,
            summary = "How freely performance counters may be read. Lowering it widens profiling.",
        ),

        // ---- CPU: not reachable from ROM mode --------------------------------

        KernelKnob(
            id = "cpu.scaling_governor",
            path = "/sys/devices/system/cpu/cpufreq/policy*/scaling_governor",
            group = KnobGroup.CPU,
            valueDomain = KnobValueDomain.Probed(),
            backend = KnobBackend.ROOT,
            contention = KnobContention.VENDOR_DAEMON,
            selinuxType = "sysfs_devices_system_cpu",
            evidence = "$EV_NO_INIT_RULE; $EV_PERF_DAEMONS",
            summary = "CPU frequency governor per cluster.",
        ),

        KernelKnob(
            id = "cpu.scaling_max_freq",
            path = "/sys/devices/system/cpu/cpufreq/policy*/scaling_max_freq",
            group = KnobGroup.CPU,
            valueDomain = KnobValueDomain.Probed(),
            backend = KnobBackend.ROOT,
            contention = KnobContention.VENDOR_DAEMON,
            selinuxType = "sysfs_devices_system_cpu",
            evidence = "$EV_NO_INIT_RULE; $EV_PERF_DAEMONS",
            summary = "Upper clock limit per cluster.",
        ),

        KernelKnob(
            id = "cpu.scaling_min_freq",
            path = "/sys/devices/system/cpu/cpufreq/policy*/scaling_min_freq",
            group = KnobGroup.CPU,
            valueDomain = KnobValueDomain.Probed(),
            backend = KnobBackend.ROOT,
            contention = KnobContention.VENDOR_DAEMON,
            selinuxType = "sysfs_devices_system_cpu",
            evidence = "$EV_NO_INIT_RULE; $EV_PERF_DAEMONS",
            summary = "Lower clock limit per cluster.",
        ),

        // ---- GPU -------------------------------------------------------------

        KernelKnob(
            id = "gpu.devfreq_governor",
            path = "/sys/class/kgsl/kgsl-3d0/devfreq/governor",
            group = KnobGroup.GPU,
            valueDomain = KnobValueDomain.Probed(),
            backend = KnobBackend.ROOT,
            contention = KnobContention.VENDOR_DAEMON,
            selinuxType = "sysfs_kgsl",
            evidence = "$EV_NO_INIT_RULE; $EV_PERF_DAEMONS",
            summary = "Adreno frequency governor.",
        ),

        KernelKnob(
            id = "gpu.max_pwrlevel",
            path = "/sys/class/kgsl/kgsl-3d0/max_pwrlevel",
            group = KnobGroup.GPU,
            valueDomain = KnobValueDomain.Probed(),
            backend = KnobBackend.ROOT,
            contention = KnobContention.VENDOR_DAEMON,
            selinuxType = "sysfs_kgsl",
            evidence = "$EV_NO_INIT_RULE; $EV_PERF_DAEMONS",
            summary = "Highest GPU power level allowed. Lower numbers are faster.",
        ),

        // ---- Power / charging -------------------------------------------------

        KernelKnob(
            id = "power.charge_current_max",
            path = "/sys/class/power_supply/battery/constant_charge_current_max",
            group = KnobGroup.POWER,
            valueDomain = KnobValueDomain.Probed(),
            backend = KnobBackend.ROOT,
            contention = KnobContention.VENDOR_DAEMON,
            selinuxType = "vendor_sysfs_battery_supply",
            evidence = "no init rule at all; eleven vendor domains hold the write, " +
                "including hal_micharge_default, vendor_hvdcp and mi_thermald, " +
                "which negotiate charge current as a closed control loop",
            summary = "Charging current limit. Part of a live negotiation, not a setting.",
        ),

        // ---- I/O ---------------------------------------------------------------

        KernelKnob(
            id = "io.scheduler",
            path = "/sys/block/sda/queue/scheduler",
            group = KnobGroup.IO,
            valueDomain = KnobValueDomain.Probed(),
            backend = KnobBackend.UNVERIFIED,
            contention = KnobContention.UNKNOWN,
            evidence = "not checked — the block device name and its SELinux type " +
                "have not been established on this device",
            summary = "Block I/O scheduler.",
        ),

        KernelKnob(
            id = "io.read_ahead_kb",
            path = "/sys/block/sda/queue/read_ahead_kb",
            group = KnobGroup.IO,
            valueDomain = KnobValueDomain.Bounded(0, 8192, "KB"),
            backend = KnobBackend.UNVERIFIED,
            contention = KnobContention.UNKNOWN,
            evidence = "not checked — same as io.scheduler",
            summary = "How far ahead the kernel reads on sequential access.",
        ),

        // ---- Network -------------------------------------------------------------

        KernelKnob(
            id = "net.tcp_congestion_control",
            path = "/proc/sys/net/ipv4/tcp_congestion_control",
            group = KnobGroup.NETWORK,
            valueDomain = KnobValueDomain.Probed(),
            backend = KnobBackend.UNVERIFIED,
            contention = KnobContention.UNKNOWN,
            evidence = "not checked — net sysctls carry their own types and none " +
                "was queried",
            summary = "TCP congestion algorithm.",
        ),
    )

    fun byId(id: String): KernelKnob? = ALL.firstOrNull { it.id == id }

    fun byGroup(group: KnobGroup): List<KernelKnob> = ALL.filter { it.group == group }

    /** Knobs Bomb can write today in ROM mode with no root. */
    fun romModeUsable(): List<KernelKnob> = ALL.filter { it.usableInRomMode }

    /** Knobs that genuinely need the root module. */
    fun rootOnly(): List<KernelKnob> = ALL.filter { it.requiresRoot }

    /** Knobs nobody has established anything about yet. */
    fun unverified(): List<KernelKnob> = ALL.filter { it.backend == KnobBackend.UNVERIFIED }
}
