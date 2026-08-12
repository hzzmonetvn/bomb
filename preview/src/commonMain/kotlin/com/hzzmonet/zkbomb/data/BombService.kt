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

// ---- Automation (contract v8) mirror types --------------------------------

/** The event types the automation engine can trigger on, by name (v8). */
object BombAutomationTrigger {
    const val APP_FOREGROUND = "APP_FOREGROUND"
    const val APP_BACKGROUND = "APP_BACKGROUND"
    const val SCREEN_ON = "SCREEN_ON"
    const val SCREEN_OFF = "SCREEN_OFF"
    val ALL = listOf(APP_FOREGROUND, APP_BACKGROUND, SCREEN_ON, SCREEN_OFF)

    /** True when a trigger is one of the app-scoped ones. */
    fun isAppTrigger(trigger: String): Boolean = trigger == APP_FOREGROUND || trigger == APP_BACKGROUND
}

/** Rule precedence scope, by name (v8). */
object BombRuleScope {
    const val GLOBAL = "GLOBAL"
    const val PER_APP = "PER_APP"
    const val SAFETY = "SAFETY"
}

/** What to do on the opposite trigger, by name (v8). */
object BombRestorePolicy {
    const val NONE = "NONE"
    const val ON_OPPOSITE_TRIGGER = "ON_OPPOSITE_TRIGGER"
}

/** Condition types, by name (v8). */
object BombAutomationConditionType {
    const val SCREEN_IS = "SCREEN_IS"
    const val FOREGROUND_PACKAGE_IS = "FOREGROUND_PACKAGE_IS"
    const val FOREGROUND_PACKAGE_IS_NOT = "FOREGROUND_PACKAGE_IS_NOT"
}

/** Action types, by name (v8). */
object BombAutomationActionType {
    const val SET_PERFORMANCE_PROFILE = "SET_PERFORMANCE_PROFILE"
    const val SET_FREEZE_MODE = "SET_FREEZE_MODE"
}

/** Performance profiles an automation action can set, by name. */
object BombPerformanceProfile {
    val ALL = listOf("ECO", "BALANCED", "PERFORMANCE", "GAMING", "SUSTAINABLE", "CUSTOM")
}

/** Screen states for a SCREEN_IS condition, by name. */
object BombScreenState {
    const val ON = "ON"
    const val OFF = "OFF"
}

/**
 * Freeze modes an automation action may set, by name. DISABLED is deliberately
 * absent: the engine rejects rules that would disable an application.
 */
object BombAutomationFreezeMode {
    val ALL = listOf("NORMAL", "SOFT_FREEZE", "DEEP_FREEZE")
}

/** One condition of a rule, mirroring `core-api AutomationConditionParcel`. */
data class BombAutomationCondition(val type: String, val value: String)

/** One action of a rule, mirroring `core-api AutomationActionParcel`. */
data class BombAutomationAction(
    val type: String,
    /** Profile or freeze-mode enum name, depending on [type]. */
    val value: String,
    val packageName: String?,
    val userId: Int,
)

/** One Bomb Rule, mirroring `core-api AutomationRuleParcel`. */
data class BombAutomationRule(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val trigger: String,
    val triggerPackageName: String?,
    val conditions: List<BombAutomationCondition>,
    val actions: List<BombAutomationAction>,
    val scope: String,
    val priority: Int,
    val cooldownMillis: Long,
    val debounceMillis: Long,
    val restorePolicy: String,
)

/**
 * The automation runtime snapshot, mirroring `core-api AutomationRulesSnapshot`.
 * Unlike the v6 write-only surfaces this one is readable, so the UI shows the
 * real current rule set, the master switch and the last trigger — no guessing.
 */
data class BombAutomationSnapshot(
    val enabled: Boolean,
    val observerRunning: Boolean,
    val rules: List<BombAutomationRule>,
    val lastTrigger: String?,
    val lastTriggeredAtMillis: Long?,
)

// ---- Battery Lab (contract v9) mirror types -------------------------------

/** Whether the Battery Lab backend answered at all. */
enum class BombBatteryLabBackendStatus {
    AVAILABLE,
    UNSUPPORTED,
    UNAVAILABLE,
    ;

    companion object {
        fun fromName(name: String): BombBatteryLabBackendStatus =
            entries.firstOrNull { it.name == name } ?: UNAVAILABLE
    }
}

/** A charge/thermal policy, mirroring `core-api BatteryLabProfileParcel`. */
data class BombBatteryLabProfile(
    val chargeLimitPercent: Int?,
    val maxTemperatureDeciCelsius: Int?,
    val capacityResumeHysteresisPercent: Int,
    val temperatureResumeHysteresisDeciCelsius: Int,
)

/**
 * A `/sys/class/power_supply` snapshot, mirroring `core-api BatteryLabSnapshot`.
 * Every metric the node did not expose stays null and renders "—", never a
 * fabricated zero. [activeProfile] is the policy the backend reports as applied,
 * so the profile console reads back what it set rather than assuming.
 */
data class BombBatteryLabSnapshot(
    val sampledAtElapsedRealtimeMillis: Long,
    val backendStatus: BombBatteryLabBackendStatus,
    val powerSupplyName: String?,
    val status: String?,
    val capacityPercent: Int?,
    val temperatureDeciCelsius: Int?,
    val voltageMicrovolts: Long?,
    val currentMicroamps: Long?,
    val chargeCounterMicroampHours: Long?,
    val cycleCount: Int?,
    val chargeFullMicroampHours: Long?,
    val chargeFullDesignMicroampHours: Long?,
    val externalPowerPresent: Boolean?,
    val chargeControlKind: String?,
    val chargeLimitControlSupported: Boolean,
    val thermalChargeControlSupported: Boolean,
    val activeProfile: BombBatteryLabProfile?,
    val chargingSuspendedByBomb: Boolean,
    val lastDecisionReason: String?,
) {
    /** Battery wear health, if both charge-full counters are present. */
    val healthPercent: Int?
        get() {
            val full = chargeFullMicroampHours ?: return null
            val design = chargeFullDesignMicroampHours ?: return null
            if (design <= 0) return null
            return ((full * 100) / design).toInt()
        }
}

// ---- Bomb Bridge & Performance Profiles (contract v10) mirror types --------

/**
 * Live event types the bridge accepts, by name (v10). Mirrors the domain
 * `LiveEventType`; an unknown name is simply one this build will not offer.
 */
object BombLiveEventType {
    val ALL = listOf(
        "MEDIA", "DOWNLOAD", "UPLOAD", "NAVIGATION", "TIMER", "CALL",
        "CALL_RECORDING", "CHARGING", "HOTSPOT", "VPN", "FILE_TRANSFER",
        "GAME_STATS", "APP_INSTALL", "CUSTOM",
    )
}

/** Live event lifecycle states, by name (v10). */
object BombLiveEventState {
    const val ACTIVE = "ACTIVE"
    const val PAUSED = "PAUSED"
    const val COMPLETED = "COMPLETED"
    const val FAILED = "FAILED"
    const val DISMISSED = "DISMISSED"
    val ALL = listOf(ACTIVE, PAUSED, COMPLETED, FAILED, DISMISSED)
}

/** The renderer a bridge event was actually routed to, by name (v10). */
object BombBridgeRenderer {
    const val LIVE_UPDATE = "LIVE_UPDATE"
    const val HYPER_ISLAND = "HYPER_ISLAND"
    const val NOTIFICATION = "NOTIFICATION"
}

/**
 * One live event pushed to the bridge, mirroring `core-api BombLiveEventParcel`.
 * The service revalidates it and routes it to whichever renderer the device
 * supports; the notification renderer is the guaranteed fallback.
 */
data class BombLiveEvent(
    val id: String,
    val sourcePackage: String,
    val type: String,
    val title: String,
    val subtitle: String?,
    val compactText: String?,
    val progressFraction: Double?,
    val progressIndeterminate: Boolean,
    val state: String,
    val timestampMillis: Long,
)

/** One active event's renderer/state, mirroring `core-api BridgeEventStatusParcel`. */
data class BombBridgeEventStatus(
    val eventId: String,
    val renderer: String,
    val state: String,
    val updatedAtMillis: Long,
)

/**
 * The bridge runtime snapshot, mirroring `core-api BridgeStatusSnapshot`.
 * Readable, so the UI shows the real renderer availability probed on the device
 * and the events currently live — the notification fallback is always present.
 */
data class BombBridgeStatus(
    val notificationAvailable: Boolean,
    val liveUpdateAvailable: Boolean,
    val hyperIslandFeaturePresent: Boolean,
    val hyperIslandProtocolVersion: Int,
    val hyperIslandPermitted: Boolean,
    val hyperIslandPayloadAdapterAvailable: Boolean,
    val activeEvents: List<BombBridgeEventStatus>,
)

/**
 * One typed performance-profile intent, mirroring `core-api PerformanceProfileParcel`.
 * A backend applies only the fields its capabilities prove; [BombPerformanceProfilesSnapshot.appliedFields]
 * reports which ones actually took.
 */
data class BombPerformanceProfileDef(
    val name: String,
    val swappiness: Int,
    val pageCluster: Int,
    val refreshRateHz: Int?,
    val cpuStrategy: String,
    val gpuStrategy: String,
    val thermalStrategy: String,
    val monitorPreset: String,
)

/** Thermal Guardian thresholds, mirroring `core-api ThermalGuardianConfigParcel`. */
data class BombThermalGuardianConfig(
    val sustainableAtDeciCelsius: Int,
    val ecoAtDeciCelsius: Int,
    val restoreAtDeciCelsius: Int,
    val cooldownMillis: Long,
)

/**
 * The performance snapshot, mirroring `core-api PerformanceProfilesSnapshot`.
 * Readable: [activeProfile] and [appliedFields] read back what the backend
 * actually did, so the UI never claims a field applied that the device could
 * not honour. [thermalGuardianActiveProfile] is the profile the guardian has
 * forced, distinct from a profile the user selected.
 */
data class BombPerformanceProfilesSnapshot(
    val profiles: List<BombPerformanceProfileDef>,
    val activeProfile: String?,
    val memoryControlAvailable: Boolean,
    val appliedFields: List<String>,
    val thermalGuardianConfig: BombThermalGuardianConfig?,
    val thermalGuardianActiveProfile: String?,
)

// ---- Call / VoIP platform recording (contract v11) mirror types -----------

/** Platform recording lifecycle state, by name (v11). Mirrors `PlatformRecordingState`. */
object BombRecordingState {
    const val IDLE = "IDLE"
    const val RECORDING = "RECORDING"
    const val STOPPING = "STOPPING"
}

/** The two call kinds the platform backend records, by name (v11). */
object BombRecordingKind {
    const val CELLULAR = "CELLULAR"
    const val VOIP = "VOIP"
}

/** The outcome of the last finished platform capture, by name (v11). */
object BombRecordingOutcome {
    const val COMPLETED = "COMPLETED"
    const val SILENT = "SILENT"
    const val FAILED = "FAILED"
}

/**
 * The privileged recording backend's status, mirroring `core-api RecordingBackendStatus`.
 *
 * Operational metadata only: by contract this never carries a phone number, a
 * contact, an app title or an output path. [cellularSupport]/[voipSupport] are the
 * probed [VoipCaptureSupport] states — only SUPPORTED permits recording, so a
 * SILENT path is a refusal reason, not a green light. [lastOutcome] and
 * [lastPeakAmplitude] describe the previous capture so a silent result is surfaced
 * rather than passed off as a saved call.
 */
data class BombRecordingBackendStatus(
    val state: String,
    val activeSessionId: String?,
    val activeKind: String?,
    val startedAtElapsedRealtimeMillis: Long?,
    val cellularSupport: VoipCaptureSupport,
    val voipSupport: VoipCaptureSupport,
    val capturePermissionHeld: Boolean,
    val activeClientSilenced: Boolean?,
    val lastOutcome: String?,
    val lastPeakAmplitude: Int?,
) {
    val isRecording: Boolean get() = state == BombRecordingState.RECORDING || state == BombRecordingState.STOPPING

    /** The probed support for [kind], or UNPROBED for an unknown name. */
    fun supportFor(kind: String): VoipCaptureSupport = when (kind) {
        BombRecordingKind.CELLULAR -> cellularSupport
        BombRecordingKind.VOIP -> voipSupport
        else -> VoipCaptureSupport.UNPROBED
    }
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

    // ---- Automation (contract v8, AUTOMATION_RULES) ----

    /**
     * The current rule set, master switch and observer status, or null when the
     * contract is older than v8 or the call fails. Readable — the UI shows real
     * state rather than a composed guess (v8+).
     */
    fun getAutomationRules(onResult: (BombAutomationSnapshot?) -> Unit)

    /** Create or replace one validated rule; the id is the stable upsert key (v8+). */
    fun upsertAutomationRule(rule: BombAutomationRule, onResult: (BombOperationResult) -> Unit)

    /** Delete one rule by id, plus any pending restore it owns (v8+). */
    fun deleteAutomationRule(ruleId: String, onResult: (BombOperationResult) -> Unit)

    /** Master switch; disabling stops observation and clears runtime state (v8+). */
    fun setAutomationEnabled(enabled: Boolean, onResult: (BombOperationResult) -> Unit)

    // ---- Battery Lab (contract v9, CHARGE_LIMIT_CONTROL / THERMAL_CHARGE_CONTROL) ----

    /**
     * A fresh capability-filtered power-supply snapshot including the active
     * profile, or null when the contract is older than v9 or the call fails (v9+).
     */
    fun getBatteryLabSnapshot(onResult: (BombBatteryLabSnapshot?) -> Unit)

    /** Persist and activate a bounded charge/thermal policy (v9+). */
    fun setBatteryLabProfile(profile: BombBatteryLabProfile, onResult: (BombOperationResult) -> Unit)

    /** Disable the policy and restore the control value Bomb captured (v9+). */
    fun clearBatteryLabProfile(onResult: (BombOperationResult) -> Unit)

    // ---- Bomb Bridge (contract v10, LIVE_UPDATE_BRIDGE / HYPER_ISLAND_BRIDGE) ----

    /**
     * The bridge runtime snapshot — renderer availability plus the live events —
     * or null when the contract is older than v10 or the call fails. Readable, so
     * the UI reflects what the device actually supports (v10+).
     */
    fun getBridgeStatus(onResult: (BombBridgeStatus?) -> Unit)

    /**
     * Publish or update one live event. The service revalidates it and routes it
     * to a supported renderer, falling back to a notification. The id is the
     * stable update key (v10+).
     */
    fun publishLiveEvent(event: BombLiveEvent, onResult: (BombOperationResult) -> Unit)

    /** Dismiss one live event across every renderer that showed it (v10+). */
    fun dismissLiveEvent(eventId: String, onResult: (BombOperationResult) -> Unit)

    // ---- Performance Profiles (contract v10, PERFORMANCE_CONTROL) ----

    /**
     * The profile catalog, the active profile and the fields that actually
     * applied, or null when the contract is older than v10 or the call fails.
     * Readable — the UI shows real applied state, not the requested intent (v10+).
     */
    fun getPerformanceProfiles(onResult: (BombPerformanceProfilesSnapshot?) -> Unit)

    /** Activate one profile by name; backends apply only capability-proven fields (v10+). */
    fun setPerformanceProfile(profileName: String, onResult: (BombOperationResult) -> Unit)

    /** Restore the pre-profile control values Bomb captured (v10+). */
    fun clearPerformanceProfile(onResult: (BombOperationResult) -> Unit)

    /** Persist and enable the Thermal Guardian hysteresis config (v10+). */
    fun setThermalGuardianConfig(config: BombThermalGuardianConfig, onResult: (BombOperationResult) -> Unit)

    /** Disable the Thermal Guardian and release any profile it forced (v10+). */
    fun clearThermalGuardianConfig(onResult: (BombOperationResult) -> Unit)

    // ---- Call / VoIP recording (contract v11, PHONE_RECORDING / VOIP_RECORDING) ----

    /**
     * The privileged recording backend's status — capability and active-session
     * metadata only — or null when the contract is older than v11 or the call
     * fails. Readable; carries no call content or output path (v11+).
     */
    fun getRecordingBackendStatus(onResult: (BombRecordingBackendStatus?) -> Unit)

    /**
     * Start one platform recording of [kind] (a [BombRecordingKind] name) into a
     * caller-owned file the implementation allocates. The session id is assigned
     * by the implementation and surfaces in the status snapshot; Stop keys on it.
     * The backend accepts AAC/m4a only. Returns UNSUPPORTED below v11 (v11+).
     */
    fun startCallRecording(kind: String, onResult: (BombOperationResult) -> Unit)

    /** Stop the active session whose bounded id exactly matches [sessionId] (v11+). */
    fun stopCallRecording(sessionId: String, onResult: (BombOperationResult) -> Unit)
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
    const val CHARGE_LIMIT_CONTROL = "CHARGE_LIMIT_CONTROL"
    const val THERMAL_CHARGE_CONTROL = "THERMAL_CHARGE_CONTROL"
    const val ZRAM_CONTROL = "ZRAM_CONTROL"
    const val AUTOMATION_RULES = "AUTOMATION_RULES"
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
        "Device" to listOf(PERFORMANCE_CONTROL, CHARGE_CONTROL, CHARGE_LIMIT_CONTROL, THERMAL_CHARGE_CONTROL, ZRAM_CONTROL),
        "Automation" to listOf(AUTOMATION_RULES),
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
