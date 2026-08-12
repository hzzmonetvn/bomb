package com.hzzmonet.zkbomb.api

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Everything a device may or may not be able to do.
 *
 * The list is master plan §3.3's capability model, extended with the four
 * subsystems added since (log tiers, zram, proxy, AirDrop interop).
 *
 * Each entry is a question the service answers by **probing**, never by
 * assuming — §3.3's rule is "never assume a device supports a feature", and the
 * matching rule for this file is that no capability may default to supported.
 */
enum class BombCapability {
    // ---- Task Manager and telemetry ----
    TASK_MANAGER,
    PROCESS_CPU_TELEMETRY,
    THREAD_TELEMETRY,
    /** Expensive PSS/private-dirty sampling for one explicitly selected PID. */
    PROCESS_PSS_TELEMETRY,
    GPU_TELEMETRY,
    FPS_TELEMETRY,
    THERMAL_TELEMETRY,
    POWER_TELEMETRY,
    /** Aggregate CPU counters, distinct from per-process CPU telemetry. */
    SYSTEM_CPU_TELEMETRY,

    // ---- Freeze ----
    SOFT_FREEZE,
    DEEP_FREEZE,
    COMPONENT_CONTROL,
    FRAMEWORK_PROCESS_CONTROL,
    /** Typed force-stop of a validated, non-protected package. */
    PACKAGE_FORCE_STOP,

    // ---- Framework patches. ROM mode only, per D6. ----
    PACKAGE_VISIBILITY_VIRTUALIZATION,
    SETTINGS_VIRTUALIZATION,

    // ---- Network ----
    AD_BLOCK,
    DNS_CONTROL,
    FIREWALL,
    PROXY_GATEWAY,

    // ---- Device control ----
    PERFORMANCE_CONTROL,
    /** Dynamic per-policy cpufreq min/max control. */
    CPU_FREQUENCY_CONTROL,
    /** Dynamic GPU devfreq min/max control. */
    GPU_FREQUENCY_CONTROL,
    CHARGE_CONTROL,
    /** Capacity telemetry plus an allowlisted writable charging gate. */
    CHARGE_LIMIT_CONTROL,
    /** Battery temperature telemetry plus an allowlisted writable charging gate. */
    THERMAL_CHARGE_CONTROL,
    /** An allowlisted writable charge-current-limit node (constant_charge_current_max / input_current_limit). */
    CHARGE_CURRENT_CONTROL,
    ZRAM_CONTROL,
    /** Bomb Rules orchestration and its app/screen event source. */
    AUTOMATION_RULES,

    // ---- Logging ----
    /** The `REDUCED` tier — needs a ROM-side init trigger. */
    LOG_REDUCE,

    /** The `OFF` tier — root-only by product decision, not by device limitation. */
    LOG_DISABLE,

    // ---- Bridge ----
    HYPER_ISLAND_BRIDGE,
    LIVE_UPDATE_BRIDGE,

    // ---- Recording ----
    PHONE_RECORDING,
    VOIP_RECORDING,

    /**
     * Quick Share ↔ AirDrop.
     *
     * On the current target this probes to [CapabilityState.DECLARED_NOT_IMPLEMENTED]:
     * the ROM declares the HAL in its VINTF matrix and whitelists the package,
     * but ships no `mosey_server` and no app. That state exists precisely so this
     * can be reported truthfully instead of collapsing to "unsupported".
     */
    AIRDROP_INTEROP,
}

/** How well a capability is supported. */
enum class CapabilityState {
    /** Probed and usable. */
    SUPPORTED,

    /** Probed and not available on this device. Hide or permanently disable. */
    UNSUPPORTED,

    /** Available, but only with a root backend that is not currently present. */
    REQUIRES_ROOT,

    /**
     * The platform advertises the feature but ships no implementation.
     *
     * A real state on real devices, and the honest answer for AirDrop interop on
     * the current target. Collapsing it into [UNSUPPORTED] would lose the
     * information that supplying the missing pieces would make it work.
     */
    DECLARED_NOT_IMPLEMENTED,

    /**
     * Not probed yet.
     *
     * The default for anything the service has not answered, so a capability
     * that is accidentally left out of the probe reads as unknown rather than as
     * supported.
     */
    NOT_PROBED,
}

/**
 * A probed capability snapshot, as it crosses Binder.
 *
 * Stored as `Map<String, String>` of enum *names* rather than ordinals or a
 * bitmask. Names cost a few hundred bytes per call and buy two things that
 * matter for a contract the plan requires to be append-only: adding or
 * reordering a [BombCapability] cannot silently shift the meaning of existing
 * entries, and a client older than the service simply ignores names it does not
 * know instead of misreading them. It also means a `dumpsys` of this object is
 * readable without the enum source.
 */
@Parcelize
data class BombCapabilities(
    private val states: Map<String, String>,
) : Parcelable {

    /**
     * The state of [capability], or [CapabilityState.NOT_PROBED] when the service
     * did not report it — including when this client is newer than the service
     * and knows about capabilities it has never heard of.
     */
    operator fun get(capability: BombCapability): CapabilityState {
        val raw = states[capability.name] ?: return CapabilityState.NOT_PROBED
        return runCatching { CapabilityState.valueOf(raw) }
            .getOrDefault(CapabilityState.NOT_PROBED)
    }

    fun isSupported(capability: BombCapability): Boolean =
        get(capability) == CapabilityState.SUPPORTED

    /** Every capability the service actually answered, for diagnostics. */
    fun probed(): Map<BombCapability, CapabilityState> =
        BombCapability.entries
            .associateWith { get(it) }
            .filterValues { it != CapabilityState.NOT_PROBED }

    class Builder {
        private val states = LinkedHashMap<String, String>()

        fun set(capability: BombCapability, state: CapabilityState): Builder = apply {
            states[capability.name] = state.name
        }

        /** Convenience for the common probed-boolean case. */
        fun set(capability: BombCapability, supported: Boolean): Builder =
            set(
                capability,
                if (supported) CapabilityState.SUPPORTED else CapabilityState.UNSUPPORTED,
            )

        fun build(): BombCapabilities = BombCapabilities(states.toMap())
    }

    companion object {
        /**
         * Nothing probed.
         *
         * Every capability reads [CapabilityState.NOT_PROBED], so a UI bound to
         * an unresponsive service shows unknown rather than enabled controls.
         */
        val NONE = BombCapabilities(emptyMap())
    }
}
