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
    const val THREAD_TELEMETRY = "THREAD_TELEMETRY"
    const val GPU_TELEMETRY = "GPU_TELEMETRY"
    const val FPS_TELEMETRY = "FPS_TELEMETRY"
    const val THERMAL_TELEMETRY = "THERMAL_TELEMETRY"
    const val POWER_TELEMETRY = "POWER_TELEMETRY"
    const val SOFT_FREEZE = "SOFT_FREEZE"
    const val DEEP_FREEZE = "DEEP_FREEZE"
    const val COMPONENT_CONTROL = "COMPONENT_CONTROL"
    const val FRAMEWORK_PROCESS_CONTROL = "FRAMEWORK_PROCESS_CONTROL"
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
            FPS_TELEMETRY, GPU_TELEMETRY, THERMAL_TELEMETRY, POWER_TELEMETRY,
        ),
        "Freeze" to listOf(SOFT_FREEZE, DEEP_FREEZE, COMPONENT_CONTROL),
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
