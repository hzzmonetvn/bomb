package com.hzzmonet.zkbomb.domain.freeze

/**
 * Product-level freeze intent, from master plan §7.
 *
 * A mode is *what the user asked for*. It is deliberately not a mechanism:
 * `docs/BOMB_PLAN.md` §4.3 requires that `suspend`, `disable`, `hide`, force stop
 * and the framework's cached-app freezer stay distinct capabilities, and that no
 * single mechanism is hardcoded into UI or domain code. [FreezePolicyEngine] is
 * the only place a mode becomes a mechanism.
 */
enum class FreezeMode {
    /** Not frozen. The app runs under normal platform policy. */
    NORMAL,

    /**
     * Processes suspended, package state untouched.
     *
     * Reversible without any `PackageManager` write, so the app stays installed,
     * queryable, launchable and attributable throughout.
     */
    SOFT_FREEZE,

    /**
     * App made inert: no components resolvable, no background work, no wakeups.
     *
     * Survives reboot, because it is package state rather than process state.
     */
    DEEP_FREEZE,

    /** Package disabled through `PackageManager`. */
    DISABLED,
}

/**
 * A concrete platform mechanism.
 *
 * These are four *different* states backed by four different `ApplicationInfo`
 * bits plus one runtime process state — `docs/research/HAIL.md` §1 traces each to
 * its own bit (`HPackages.kt:47,49,53,57`). Conflating them is the single most
 * common bug in freeze implementations, and the reason
 * [ObservedFreezeState.resolveMode] reads all of them separately.
 */
enum class FreezeMechanism {
    /**
     * `CachedAppOptimizer` / `Freezer` — cgroup freeze of the app's processes.
     *
     * No `ApplicationInfo` bit at all; purely a runtime state, lost on reboot.
     * Binder must be frozen before the cgroup or in-flight transactions are lost
     * (`CachedAppOptimizer.java:2292-2361`, D7), which is why Bomb expresses
     * intent through the platform freezer and never writes `cgroup.freeze`.
     */
    PLATFORM_FREEZER,

    /**
     * `setPackagesSuspended` → `FLAG_SUSPENDED`.
     *
     * Preferred over [HIDE] for [FreezeMode.DEEP_FREEZE] per D8: a suspended
     * package stays queryable and attributable, whereas a hidden one behaves as
     * uninstalled and breaks other apps' queries. Returns the **failed** package
     * names rather than throwing, so callers must inspect the return value.
     */
    SUSPEND,

    /**
     * `setApplicationHiddenSettingAsUser` → `PRIVATE_FLAG_HIDDEN`.
     *
     * Refused by the platform for device admins and for `"android"`
     * (`docs/research/HAIL.md` §2), so [ExclusionReason.DEVICE_ADMIN] must be
     * checked before this is attempted rather than after it fails.
     */
    HIDE,

    /**
     * `setApplicationEnabledSetting` → the enabled bit.
     *
     * Preceded by a force stop, because disabling does not itself stop a running
     * process.
     */
    DISABLE,
}

/**
 * Why a package may not be frozen.
 *
 * Every rejection carries one of these rather than a boolean, so the UI can say
 * *which* protection applied — master plan §3.5's "do not silently swallow
 * failures" applied to refusals as well as errors.
 */
enum class ExclusionReason {
    /** Bomb itself. Freezing Bomb would strand the user with no way back. */
    BOMB_ITSELF,

    /** SystemUI. Freezing it takes the status bar and recents with it. */
    SYSTEM_UI,

    /** The *resolved* home activity's package. */
    LAUNCHER,

    /** The **current** IME, not merely an enabled one — losing it loses input. */
    CURRENT_IME,

    /** Essential framework packages, including `"android"` itself. */
    FRAMEWORK,

    /** Vendor / device-critical packages declared by the device backend. */
    VENDOR_CRITICAL,

    /**
     * An active device administrator.
     *
     * The platform refuses [FreezeMechanism.HIDE] for these, so Bomb refuses
     * first and says so, instead of issuing a call it knows will fail.
     */
    DEVICE_ADMIN,

    /** The user put this package on the freeze exclusion list. */
    USER_EXCLUDED,
}

/**
 * Package and process state as **read back from the platform**.
 *
 * `docs/BOMB_PLAN.md` §4.3: results are re-read from platform state, never
 * inferred from a privileged call returning without throwing. Several of the
 * relevant setters return `void` or return a failure list, so "the call
 * completed" carries almost no information.
 */
data class ObservedFreezeState(
    /** The package's enabled state resolves to enabled. */
    val enabled: Boolean,
    /** `PRIVATE_FLAG_HIDDEN`. */
    val hidden: Boolean,
    /** `FLAG_SUSPENDED`. */
    val suspended: Boolean,
    /**
     * `FLAG_STOPPED` — set by force stop and cleared on next launch.
     *
     * Deliberately **not** a freeze state: force stop is a Task Manager action
     * (`docs/BOMB_PLAN.md` §4.3), and treating its residue as a mode would report
     * apps as frozen simply because they have not been opened since boot.
     */
    val stopped: Boolean,
    /** Every running process of this package is frozen by the platform freezer. */
    val allProcessesFrozen: Boolean,
    /** The package has at least one running process. */
    val hasRunningProcesses: Boolean,
) {
    /**
     * The mode this state actually represents.
     *
     * Order matters. Disabled is checked first because a disabled package may
     * also carry stale suspend/hide bits, and the strongest state is the true
     * one. [stopped] is never consulted, per the field's own contract.
     */
    fun resolveMode(): FreezeMode = when {
        !enabled -> FreezeMode.DISABLED
        suspended || hidden -> FreezeMode.DEEP_FREEZE
        hasRunningProcesses && allProcessesFrozen -> FreezeMode.SOFT_FREEZE
        else -> FreezeMode.NORMAL
    }

    /**
     * True when a soft freeze was requested but processes are still running
     * unfrozen.
     *
     * This is the "app reported frozen while `:push` still runs" risk named in
     * `docs/BOMB_PLAN.md` §4.3. A package with **no** running processes is not
     * leaking — there is simply nothing to freeze — so it is not flagged here.
     */
    fun hasUnfrozenProcesses(): Boolean = hasRunningProcesses && !allProcessesFrozen
}

/**
 * Which mechanisms this device actually supports.
 *
 * Master plan §3.3: never assume support. [FreezePolicyEngine] consults this
 * before choosing, and reports [FreezeDecision.Unsupported] listing what it tried
 * rather than falling back silently to a weaker mechanism the user did not ask
 * for.
 */
data class FreezeCapabilities(
    /** `Freezer.isFreezerSupported()` — the platform's own probe. */
    val platformFreezer: Boolean,
    /** `setPackagesSuspended` reachable and permitted. */
    val suspend: Boolean,
    /** `setApplicationHiddenSettingAsUser` reachable and permitted. */
    val hide: Boolean,
    /** `setApplicationEnabledSetting` reachable and permitted. */
    val disable: Boolean,
) {
    fun supports(mechanism: FreezeMechanism): Boolean = when (mechanism) {
        FreezeMechanism.PLATFORM_FREEZER -> platformFreezer
        FreezeMechanism.SUSPEND -> suspend
        FreezeMechanism.HIDE -> hide
        FreezeMechanism.DISABLE -> disable
    }

    companion object {
        /** Nothing supported — the honest default before any probe has run. */
        val NONE = FreezeCapabilities(
            platformFreezer = false,
            suspend = false,
            hide = false,
            disable = false,
        )
    }
}
