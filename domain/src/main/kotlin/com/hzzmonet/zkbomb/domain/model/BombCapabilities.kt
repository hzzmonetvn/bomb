package com.hzzmonet.zkbomb.domain.model

/**
 * Capability keys matching master plan §3.3.
 */
enum class CapabilityKey {
    TASK_MANAGER,
    PROCESS_CPU_TELEMETRY,
    THREAD_TELEMETRY,
    GPU_TELEMETRY,
    FPS_TELEMETRY,
    THERMAL_TELEMETRY,
    POWER_TELEMETRY,
    SYSTEM_CPU_TELEMETRY,
    SOFT_FREEZE,
    DEEP_FREEZE,
    COMPONENT_CONTROL,
    FRAMEWORK_PROCESS_CONTROL,
    PACKAGE_VISIBILITY_VIRTUALIZATION,
    SETTINGS_VIRTUALIZATION,
    AD_BLOCK,
    DNS_CONTROL,
    FIREWALL,
    PROXY_GATEWAY,
    PERFORMANCE_CONTROL,
    CHARGE_CONTROL,
    CHARGE_LIMIT_CONTROL,
    THERMAL_CHARGE_CONTROL,
    ZRAM_CONTROL,
    AUTOMATION_RULES,
    LOG_REDUCE,
    LOG_DISABLE,
    HYPER_ISLAND_BRIDGE,
    LIVE_UPDATE_BRIDGE,
    PHONE_RECORDING,
    VOIP_RECORDING,
    AIRDROP_INTEROP,
}

/** How well a capability is supported. */
enum class CapabilityState {
    SUPPORTED,
    UNSUPPORTED,
    REQUIRES_ROOT,
    DECLARED_NOT_IMPLEMENTED,
    NOT_PROBED,
}

/**
 * Pure JVM domain representation of probed device capabilities.
 * Never assumes a capability is supported unless explicitly probed.
 */
data class BombCapabilities(
    val states: Map<CapabilityKey, CapabilityState> = emptyMap(),
) {
    fun get(key: CapabilityKey): CapabilityState = states[key] ?: CapabilityState.NOT_PROBED

    fun isSupported(key: CapabilityKey): Boolean = get(key) == CapabilityState.SUPPORTED

    fun probed(): Map<CapabilityKey, CapabilityState> =
        CapabilityKey.entries
            .associateWith { get(it) }
            .filterValues { it != CapabilityState.NOT_PROBED }

    class Builder {
        private val states = LinkedHashMap<CapabilityKey, CapabilityState>()

        fun set(key: CapabilityKey, state: CapabilityState): Builder = apply {
            states[key] = state
        }

        fun set(key: CapabilityKey, supported: Boolean): Builder = set(
            key,
            if (supported) CapabilityState.SUPPORTED else CapabilityState.UNSUPPORTED,
        )

        fun build(): BombCapabilities = BombCapabilities(states.toMap())
    }

    companion object {
        val NONE = BombCapabilities(emptyMap())
    }
}
