package com.hzzmonet.zkbomb.data

import androidx.compose.runtime.Composable

/** How the UI's connection to the privileged service stands. */
enum class BombConnection {
    /** Binding in progress. */
    CONNECTING,

    /** Bound, and [BombServiceState.capabilities] is a real probe result. */
    CONNECTED,

    /**
     * The service could not be bound.
     *
     * A real state, not an error to hide: on a build where the service is
     * missing or the bind is refused, every control must read as unusable
     * rather than as "probably fine".
     */
    UNAVAILABLE,

    /** No Android platform underneath — the service does not exist here. */
    NOT_SUPPORTED,
}

/**
 * What the privileged service reports, as the UI sees it.
 *
 * Capabilities cross as **string keys**, not as an enum shared with `:core-api`.
 * That is forced by the module layout — this is Kotlin Multiplatform common
 * code and cannot see an Android library's types — but it is also the same
 * shape the Binder contract already uses, and it degrades the same way: a
 * capability name this build does not recognise reads as "not probed" rather
 * than shifting the meaning of a neighbouring one.
 */
data class BombServiceState(
    val connection: BombConnection,
    /** The contract version the service implements, or null when not connected. */
    val apiVersion: Int?,
    /** Capability name → state name, exactly as the service reported them. */
    val capabilities: Map<String, String>,
    /**
     * How Bomb is deployed: "NORMAL", "ROM" or "ROOT".
     *
     * ROM is declared by the image through `persist.sys.zk.bomb=1`. It says the
     * integration was intended; [capabilities] still says what works.
     */
    val runtimeMode: String = "NORMAL",
    /** Effective log tier read back from the ROM, or null when unavailable. */
    val logLevel: String? = null,
    /** Typed operations. Null unless the Binder connection is alive. */
    val controller: BombServiceController? = null,
    /** Why the bind failed, when it did. Shown to the user, so it stays factual. */
    val error: String? = null,
) {
    /**
     * The reported state of [capability], or `NOT_PROBED` when the service did
     * not mention it — including when it is not connected at all.
     */
    fun stateOf(capability: String): String =
        capabilities[capability] ?: "NOT_PROBED"

    /**
     * True only when the service explicitly said `SUPPORTED`.
     *
     * Every other value — unsupported, requires-root, declared-but-unimplemented,
     * not probed, service unreachable — is false. A control gated on this is
     * therefore disabled by default and enabled only on evidence, which is the
     * direction that fails safely.
     */
    fun isSupported(capability: String): Boolean = stateOf(capability) == "SUPPORTED"

    companion object {
        val NOT_CONNECTED = BombServiceState(
            connection = BombConnection.CONNECTING,
            apiVersion = null,
            capabilities = emptyMap(),
        )
    }
}

data class BombOperationResult(val status: String, val message: String?) {
    val isSuccess: Boolean get() = status == "SUCCESS"
}

data class BombFreezeStatus(
    val mode: String,
    val hasUnfrozenProcesses: Boolean,
    val availableModes: List<String>,
    val exclusionReason: String?,
)

data class BombMemoryStatus(
    val zramPresent: Boolean,
    val disksizeBytes: Long?,
    val compressionRatio: Double?,
    val ramEfficiency: Double?,
    val currentAlgorithm: String?,
    val globalSwappiness: Int?,
)

/**
 * One process from the privileged snapshot, mirroring `core-api ProcessInfo`.
 *
 * A common-code copy because the shared UI cannot see the Android Parcelable in
 * `:core-api`. Every field the framework may withhold stays nullable and null is
 * carried through untouched: the UI renders "unavailable", never a zero. CPU is
 * deliberately absent here — it is a rate, computed from [cpuTimeTicks] deltas
 * between two snapshots, not a value any single snapshot can carry.
 */
data class BombProcessInfo(
    val pid: Int,
    val uid: Int,
    val userId: Int,
    val processName: String,
    val packageNames: List<String>,
    val importance: Int,
    val foreground: Boolean,
    val pssBytes: Long?,
    val privateDirtyBytes: Long?,
    val rssBytes: Long?,
    val cpuTimeTicks: Long?,
    val threadCount: Int?,
    val startTimeTicks: Long?,
    val categoryName: String?,
)

/** A bounded process list sampled once; [truncated] flags a capped Binder reply. */
data class BombProcessSnapshot(
    val sampledAtElapsedRealtimeMillis: Long,
    val processes: List<BombProcessInfo>,
    val truncated: Boolean,
)

/**
 * System-wide counters from the privileged service, mirroring
 * `core-api SystemTelemetrySnapshot`. [cpuTotalTicks]/[cpuIdleTicks] are the raw
 * `/proc/stat` aggregates; a CPU percentage is derived from successive deltas,
 * never from one sample.
 */
data class BombSystemTelemetry(
    val sampledAtElapsedRealtimeMillis: Long,
    val uptimeMillis: Long,
    val totalMemoryBytes: Long,
    val availableMemoryBytes: Long,
    val lowMemoryThresholdBytes: Long,
    val lowMemory: Boolean,
    val cpuTotalTicks: Long?,
    val cpuIdleTicks: Long?,
    val thermalStatus: Int?,
    val batteryPercent: Int?,
    val batteryCharging: Boolean?,
    val batteryTemperatureDeciCelsius: Int?,
    val batteryVoltageMillivolts: Int?,
    val batteryCurrentMicroamps: Long?,
    val totalRxBytes: Long?,
    val totalTxBytes: Long?,
)

/** The six stable outcomes of the v7 selected-process memory call. */
enum class BombSelectedMemoryStatus {
    AVAILABLE,
    INVALID_PID,
    NOT_VISIBLE,
    DISAPPEARED,
    UNAVAILABLE,
    PERMISSION_DENIED,
    ;

    companion object {
        /** Maps the backend enum name; an unknown name degrades to [UNAVAILABLE]. */
        fun fromName(name: String): BombSelectedMemoryStatus =
            entries.firstOrNull { it.name == name } ?: UNAVAILABLE
    }
}

/**
 * One explicitly selected process's expensive memory sample, mirroring
 * `core-api SelectedProcessMemory`.
 *
 * Every metric is independently nullable and null means the platform withheld or
 * zeroed it — the UI shows "—", never a fabricated 0. This is only ever fetched
 * for a single process the user has opened, never for the whole list.
 */
data class BombSelectedProcessMemory(
    val pid: Int,
    val sampledAtElapsedRealtimeMillis: Long,
    val status: BombSelectedMemoryStatus,
    val pssBytes: Long?,
    val privateDirtyBytes: Long?,
    val rssBytes: Long?,
)

/** One manifest component, from the Package Inspector snapshot. */
data class BombPackageComponent(
    /** ACTIVITY, SERVICE, RECEIVER or PROVIDER. */
    val kind: String,
    val className: String,
    val processName: String?,
    val manifestEnabled: Boolean,
    val effectiveEnabled: Boolean,
    val exported: Boolean,
    val permission: String?,
    /** The override applied by Bomb/the framework: DEFAULT, ENABLED or DISABLED. */
    val overrideState: String,
    val authorities: String?,
)

/**
 * A Package Inspector snapshot, mirroring `core-api PackageSnapshot`. Component
 * lists are bounded: [componentsTruncated] means filters run on a partial view,
 * and [totalComponentCount] preserves the real total.
 */
data class BombPackageSnapshot(
    val packageName: String,
    val userId: Int,
    val uid: Int,
    val label: String,
    val versionName: String?,
    val longVersionCode: Long,
    val targetSdkVersion: Int,
    val minSdkVersion: Int,
    val enabled: Boolean,
    val stopped: Boolean,
    val suspended: Boolean,
    val systemApp: Boolean,
    val debuggable: Boolean,
    val installerPackageName: String?,
    val requestedPermissions: List<String>,
    val signingCertificateSha256: List<String>,
    val processNames: List<String>,
    val components: List<BombPackageComponent>,
    val totalComponentCount: Int,
    val componentsTruncated: Boolean,
    /** Non-null when the package is a protected critical one and must not be mutated. */
    val protectionReason: String?,
)

/** The only three component override states the backend accepts. */
object BombComponentState {
    const val DEFAULT = "DEFAULT"
    const val ENABLED = "ENABLED"
    const val DISABLED = "DISABLED"
}

/** The two firewall access states the backend accepts, by name (v6). */
object BombNetworkAccess {
    const val ALLOW = "ALLOW"
    const val DENY = "DENY"
}

/** The two caller-visibility policy modes the backend accepts, by name (v6). */
object BombVisibilityMode {
    const val BLACKLIST = "BLACKLIST"
    const val WHITELIST = "WHITELIST"
}

/** Settings namespaces the backend can virtualize, by name (v6). */
object BombSettingsNamespace {
    const val SYSTEM = "SYSTEM"
    const val SECURE = "SECURE"
    const val GLOBAL = "GLOBAL"
}

/** Typed settings-override value types the backend accepts, by name (v6). */
object BombSettingsValueType {
    const val STRING = "STRING"
    const val INTEGER = "INTEGER"
    const val LONG = "LONG"
    const val FLOAT = "FLOAT"
    const val BOOLEAN = "BOOLEAN"
    const val NULL = "NULL"
}

/** UI-safe command surface; implementations dispatch Binder work off-main. */
interface BombServiceController {
    fun getFreezeStatus(
        packageName: String,
        onResult: (BombFreezeStatus?) -> Unit,
    )

    fun setFreezeMode(
        packageName: String,
        mode: String,
        onResult: (BombOperationResult) -> Unit,
    )

    fun setLogLevel(
        level: String,
        auditRatePerSecond: Int = -1,
        onResult: (BombOperationResult) -> Unit,
    )

    fun getMemoryStatus(onResult: (BombMemoryStatus?) -> Unit)

    fun setMemoryConfig(
        swappiness: Int?,
        pageCluster: Int?,
        onResult: (BombOperationResult) -> Unit,
    )

    /**
     * A bounded process snapshot, or null when the contract is older than v3 or
     * the call fails. Never blocks the caller's thread; the result is delivered
     * on the main thread.
     */
    fun getProcessSnapshot(onResult: (BombProcessSnapshot?) -> Unit)

    /** System-wide telemetry, or null when unavailable (contract < v3, or failure). */
    fun getSystemTelemetry(onResult: (BombSystemTelemetry?) -> Unit)

    /**
     * Package Inspector snapshot for the current user, or null when the contract
     * is older than v5 or the call fails.
     */
    fun getPackageSnapshot(packageName: String, onResult: (BombPackageSnapshot?) -> Unit)

    /** Force-stop a package (needs PACKAGE_FORCE_STOP; contract v5+). */
    fun forceStopPackage(packageName: String, onResult: (BombOperationResult) -> Unit)

    /**
     * Set a component's override state to one of [BombComponentState] (needs
     * COMPONENT_CONTROL; contract v5+).
     */
    fun setComponentState(
        packageName: String,
        className: String,
        state: String,
        onResult: (BombOperationResult) -> Unit,
    )

    /**
     * Sample expensive memory for exactly one process (contract v7+).
     *
     * On-demand only — called when a process detail is opened, never for the
     * list. Returns null when the contract is older than v7 or the call fails,
     * which the caller renders as unsupported/error rather than a zero.
     */
    fun getSelectedProcessMemory(pid: Int, onResult: (BombSelectedProcessMemory?) -> Unit)

    // ---- App Visibility (contract v6, PACKAGE_VISIBILITY_VIRTUALIZATION) ----

    /**
     * Register or replace the caller-scoped visibility policy for [callingUid].
     *
     * [mode] is a [BombVisibilityMode] name and [packageNames] the black/whitelist.
     * The user id is supplied by the implementation from the running user. Returns
     * UNSUPPORTED when the contract is older than v6, and carries the backend's own
     * result otherwise — the write is validated server-side.
     */
    fun setVisibilityPolicy(
        callingUid: Int,
        mode: String,
        packageNames: List<String>,
        onResult: (BombOperationResult) -> Unit,
    )

    /** Remove the visibility policy for [callingUid], restoring default visibility (v6+). */
    fun clearVisibilityPolicy(callingUid: Int, onResult: (BombOperationResult) -> Unit)

    // ---- Settings Virtualization (contract v6, SETTINGS_VIRTUALIZATION) ----

    /** Create or replace a named settings profile (v6+). */
    fun createSettingsProfile(profileId: String, profileName: String, onResult: (BombOperationResult) -> Unit)

    /** Delete a settings profile and all its overrides (v6+). */
    fun deleteSettingsProfile(profileId: String, onResult: (BombOperationResult) -> Unit)

    /**
     * Add or replace one override in [profileId]. The backend accepts only
     * classified APP_READ keys and canonical typed values; anything else comes
     * back INVALID_ARGUMENT, which the caller surfaces rather than swallows (v6+).
     */
    fun addSettingsOverride(
        profileId: String,
        namespace: String,
        key: String,
        valueType: String,
        value: String?,
        enabled: Boolean,
        onResult: (BombOperationResult) -> Unit,
    )

    /** Remove one override (profileId + namespace + key) from a profile (v6+). */
    fun removeSettingsOverride(
        profileId: String,
        namespace: String,
        key: String,
        onResult: (BombOperationResult) -> Unit,
    )

    /** Assign [profileId] to [targetPackage] for the current user; last call wins (v6+). */
    fun assignSettingsProfile(targetPackage: String, profileId: String, onResult: (BombOperationResult) -> Unit)

    /** Clear the settings assignment for [targetPackage]; the app reads real settings again (v6+). */
    fun clearSettingsAssignment(targetPackage: String, onResult: (BombOperationResult) -> Unit)

    // ---- Firewall (contract v6, FIREWALL) ----

    /**
     * Set or replace the firewall policy for one app [uid]. Each access argument is
     * a [BombNetworkAccess] name. The user id is supplied by the implementation.
     * Returns UNSUPPORTED below v6, otherwise the backend's verified result (v6+).
     */
    fun setFirewallRule(
        uid: Int,
        wifiAccess: String,
        mobileAccess: String,
        backgroundAccess: String,
        note: String?,
        onResult: (BombOperationResult) -> Unit,
    )

    /** Remove the firewall policy for [uid], restoring platform defaults (all allowed) (v6+). */
    fun clearFirewallRule(uid: Int, onResult: (BombOperationResult) -> Unit)

    // ---- AdBlock (contract v6, AD_BLOCK) ----

    /**
     * Trigger an atomic blocklist compile-and-swap from the service's current source
     * data. Returns FAILED (keeping the previous blocklist) when the compiled result
     * is too small, UNSUPPORTED without the capability, SUCCESS otherwise (v6+).
     */
    fun reloadAdBlockRules(onResult: (BombOperationResult) -> Unit)
}

/**
 * Capability keys, mirroring `BombCapability` in `:core-api`.
 *
 * Kept as constants rather than a duplicated enum: a second enum would have to
 * be mapped, and a mapping is a thing that can be wrong. A constant that stops
 * matching simply reads as not-probed and the control stays disabled — visible
 * as a missing feature, never as a feature that claims to work and does not.
 */
object BombCapabilityKeys {
    const val TASK_MANAGER = "TASK_MANAGER"
    const val PROCESS_CPU_TELEMETRY = "PROCESS_CPU_TELEMETRY"
    const val SYSTEM_CPU_TELEMETRY = "SYSTEM_CPU_TELEMETRY"
    const val THREAD_TELEMETRY = "THREAD_TELEMETRY"
    const val GPU_TELEMETRY = "GPU_TELEMETRY"
    const val FPS_TELEMETRY = "FPS_TELEMETRY"
    const val THERMAL_TELEMETRY = "THERMAL_TELEMETRY"
    const val POWER_TELEMETRY = "POWER_TELEMETRY"
    const val SOFT_FREEZE = "SOFT_FREEZE"
    const val DEEP_FREEZE = "DEEP_FREEZE"
    const val COMPONENT_CONTROL = "COMPONENT_CONTROL"
    const val FRAMEWORK_PROCESS_CONTROL = "FRAMEWORK_PROCESS_CONTROL"
    const val PACKAGE_FORCE_STOP = "PACKAGE_FORCE_STOP"
    const val PACKAGE_VISIBILITY_VIRTUALIZATION = "PACKAGE_VISIBILITY_VIRTUALIZATION"
    const val SETTINGS_VIRTUALIZATION = "SETTINGS_VIRTUALIZATION"
    const val AD_BLOCK = "AD_BLOCK"
    const val DNS_CONTROL = "DNS_CONTROL"
    const val FIREWALL = "FIREWALL"
    const val PROXY_GATEWAY = "PROXY_GATEWAY"
    const val PERFORMANCE_CONTROL = "PERFORMANCE_CONTROL"
    const val CHARGE_CONTROL = "CHARGE_CONTROL"
    const val ZRAM_CONTROL = "ZRAM_CONTROL"
    const val LOG_REDUCE = "LOG_REDUCE"
    const val LOG_DISABLE = "LOG_DISABLE"
    const val HYPER_ISLAND_BRIDGE = "HYPER_ISLAND_BRIDGE"
    const val LIVE_UPDATE_BRIDGE = "LIVE_UPDATE_BRIDGE"
    const val PHONE_RECORDING = "PHONE_RECORDING"
    const val VOIP_RECORDING = "VOIP_RECORDING"
    const val AIRDROP_INTEROP = "AIRDROP_INTEROP"

    /** Display order for the capability report, grouped as the plan groups them. */
    val ORDERED: List<Pair<String, List<String>>> = listOf(
        "Processes" to listOf(
            TASK_MANAGER, PROCESS_CPU_TELEMETRY, THREAD_TELEMETRY, FRAMEWORK_PROCESS_CONTROL,
        ),
        "Telemetry" to listOf(
            SYSTEM_CPU_TELEMETRY, FPS_TELEMETRY, GPU_TELEMETRY, THERMAL_TELEMETRY, POWER_TELEMETRY,
        ),
        "Freeze" to listOf(SOFT_FREEZE, DEEP_FREEZE, COMPONENT_CONTROL, PACKAGE_FORCE_STOP),
        "Framework patches" to listOf(
            PACKAGE_VISIBILITY_VIRTUALIZATION, SETTINGS_VIRTUALIZATION,
        ),
        "Network" to listOf(AD_BLOCK, DNS_CONTROL, FIREWALL, PROXY_GATEWAY),
        "Device" to listOf(PERFORMANCE_CONTROL, CHARGE_CONTROL, ZRAM_CONTROL),
        "Logging" to listOf(LOG_REDUCE, LOG_DISABLE),
        "Bridge" to listOf(LIVE_UPDATE_BRIDGE, HYPER_ISLAND_BRIDGE),
        "Recording" to listOf(PHONE_RECORDING, VOIP_RECORDING),
        "Interop" to listOf(AIRDROP_INTEROP),
    )

    /** "SOFT_FREEZE" → "Soft freeze". */
    fun label(key: String): String =
        key.split('_').joinToString(" ") { word ->
            word.lowercase().replaceFirstChar { it.uppercase() }
        }.let { it }
}

/**
 * Binds the privileged service for as long as the caller is composed, and
 * unbinds when it leaves.
 */
@Composable
expect fun rememberBombService(): BombServiceState
