package com.hzzmonet.zkbomb.core

import android.content.Context
import com.hzzmonet.zkbomb.api.BatteryLabSnapshot
import com.hzzmonet.zkbomb.api.BombResult
import com.hzzmonet.zkbomb.domain.battery.BatteryLabPolicyEngine
import com.hzzmonet.zkbomb.domain.battery.BatteryLabProfile
import com.hzzmonet.zkbomb.domain.battery.BatteryLabProfileValidator
import com.hzzmonet.zkbomb.domain.battery.BatteryLabState
import com.hzzmonet.zkbomb.domain.battery.ChargeGuardAction
import com.hzzmonet.zkbomb.domain.battery.ChargeGuardReason

internal data class BatteryLabRuntimeState(
    val profile: BatteryLabProfile,
    val thresholdCapture: CapturedChargeControl? = null,
    val gateCapture: CapturedChargeControl? = null,
    val currentLimitCapture: CapturedCurrentLimit? = null,
    val lastDecisionReason: ChargeGuardReason? = null,
)

internal interface BatteryLabStateStore {
    fun load(): BatteryLabRuntimeState?
    fun save(state: BatteryLabRuntimeState?)
}

internal class SharedPreferencesBatteryLabStateStore(context: Context) : BatteryLabStateStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun load(): BatteryLabRuntimeState? {
        if (!preferences.getBoolean(KEY_ENABLED, false)) return null
        val profile = BatteryLabProfile(
            chargeLimitPercent = nullableInt(KEY_CHARGE_LIMIT),
            maxTemperatureDeciCelsius = nullableInt(KEY_TEMPERATURE_LIMIT),
            capacityResumeHysteresisPercent = preferences.getInt(KEY_CAPACITY_HYSTERESIS, 5),
            temperatureResumeHysteresisDeciCelsius =
            preferences.getInt(KEY_TEMPERATURE_HYSTERESIS, 30),
            maxChargeCurrentMicroamps = nullableInt(KEY_CURRENT_LIMIT),
        )
        if (BatteryLabProfileValidator.violations(profile).isNotEmpty()) return null
        return BatteryLabRuntimeState(
            profile = profile,
            thresholdCapture = readCapture(PREFIX_THRESHOLD, threshold = true),
            gateCapture = readCapture(PREFIX_GATE, threshold = false),
            currentLimitCapture = readCurrentCapture(),
            lastDecisionReason = preferences.getString(KEY_LAST_REASON, null)?.let { name ->
                ChargeGuardReason.entries.firstOrNull { it.name == name }
            },
        )
    }

    override fun save(state: BatteryLabRuntimeState?) {
        preferences.edit().apply {
            clear()
            if (state == null) {
                putBoolean(KEY_ENABLED, false)
            } else {
                putBoolean(KEY_ENABLED, true)
                state.profile.chargeLimitPercent?.let { putInt(KEY_CHARGE_LIMIT, it) }
                state.profile.maxTemperatureDeciCelsius?.let {
                    putInt(KEY_TEMPERATURE_LIMIT, it)
                }
                putInt(KEY_CAPACITY_HYSTERESIS, state.profile.capacityResumeHysteresisPercent)
                putInt(
                    KEY_TEMPERATURE_HYSTERESIS,
                    state.profile.temperatureResumeHysteresisDeciCelsius,
                )
                state.profile.maxChargeCurrentMicroamps?.let { putInt(KEY_CURRENT_LIMIT, it) }
                writeCapture(PREFIX_THRESHOLD, state.thresholdCapture)
                writeCapture(PREFIX_GATE, state.gateCapture)
                writeCurrentCapture(state.currentLimitCapture)
                state.lastDecisionReason?.let { putString(KEY_LAST_REASON, it.name) }
            }
            apply()
        }
    }

    fun hasActiveProfile(): Boolean = load() != null

    private fun nullableInt(key: String): Int? =
        if (preferences.contains(key)) preferences.getInt(key, 0) else null

    private fun readCapture(prefix: String, threshold: Boolean): CapturedChargeControl? {
        val supply = preferences.getString("${prefix}_supply", null) ?: return null
        if (!SUPPLY_NAME.matches(supply)) return null
        val kindName = preferences.getString("${prefix}_kind", null) ?: return null
        val kind = ChargeControlKind.entries.firstOrNull { it.name == kindName } ?: return null
        if (threshold != (kind == ChargeControlKind.CHARGE_CONTROL_END_THRESHOLD)) return null
        val original = preferences.getString("${prefix}_original", null) ?: return null
        val applied = preferences.getString("${prefix}_applied", null) ?: return null
        if (!CONTROL_VALUE.matches(original)) return null
        if (!CONTROL_VALUE.matches(applied)) return null
        val originalNumber = original.toIntOrNull() ?: return null
        val appliedNumber = applied.toIntOrNull() ?: return null
        if (threshold && (originalNumber !in 0..100 || appliedNumber !in 50..100)) return null
        if (!threshold && (originalNumber !in 0..1 || appliedNumber !in 0..1)) return null
        return CapturedChargeControl(ChargeControlRef(supply, kind), original, applied)
    }

    private fun android.content.SharedPreferences.Editor.writeCapture(
        prefix: String,
        capture: CapturedChargeControl?,
    ) {
        if (capture == null) return
        putString("${prefix}_supply", capture.ref.supplyName)
        putString("${prefix}_kind", capture.ref.kind.name)
        putString("${prefix}_original", capture.originalValue)
        putString("${prefix}_applied", capture.appliedValue)
    }

    private fun readCurrentCapture(): CapturedCurrentLimit? {
        val supply = preferences.getString("${PREFIX_CURRENT}_supply", null) ?: return null
        if (!SUPPLY_NAME.matches(supply)) return null
        val nodeName = preferences.getString("${PREFIX_CURRENT}_node", null) ?: return null
        val node = CURRENT_NODES.firstOrNull { it.name == nodeName } ?: return null
        val original = preferences.getInt("${PREFIX_CURRENT}_original", -1).takeIf { it > 0 } ?: return null
        val applied = preferences.getInt("${PREFIX_CURRENT}_applied", -1).takeIf { it > 0 } ?: return null
        return CapturedCurrentLimit(CurrentLimitRef(supply, node), original, applied)
    }

    private fun android.content.SharedPreferences.Editor.writeCurrentCapture(
        capture: CapturedCurrentLimit?,
    ) {
        if (capture == null) return
        putString("${PREFIX_CURRENT}_supply", capture.ref.supplyName)
        putString("${PREFIX_CURRENT}_node", capture.ref.node.name)
        putInt("${PREFIX_CURRENT}_original", capture.originalMicroamps)
        putInt("${PREFIX_CURRENT}_applied", capture.appliedMicroamps)
    }

    private companion object {
        const val PREFERENCES_NAME = "bomb_battery_lab"
        const val KEY_ENABLED = "enabled"
        const val KEY_CHARGE_LIMIT = "charge_limit"
        const val KEY_TEMPERATURE_LIMIT = "temperature_limit"
        const val KEY_CAPACITY_HYSTERESIS = "capacity_hysteresis"
        const val KEY_TEMPERATURE_HYSTERESIS = "temperature_hysteresis"
        const val KEY_CURRENT_LIMIT = "current_limit"
        const val KEY_LAST_REASON = "last_reason"
        const val PREFIX_THRESHOLD = "threshold"
        const val PREFIX_GATE = "gate"
        const val PREFIX_CURRENT = "current"
        val SUPPLY_NAME = Regex("^[A-Za-z0-9_.-]{1,64}$")
        val CONTROL_VALUE = Regex("^-?[0-9]{1,12}$")
        val CURRENT_NODES = listOf(
            PowerSupplyNode.CONSTANT_CHARGE_CURRENT_MAX,
            PowerSupplyNode.INPUT_CURRENT_LIMIT,
        )
    }
}

/** Owns profile persistence, exact restoration and hysteresis evaluation. */
internal class BatteryLabCoordinator(
    private val backend: PowerSupplyBackend,
    private val store: BatteryLabStateStore,
    private val policy: BatteryLabPolicyEngine = BatteryLabPolicyEngine(),
) {
    private var state: BatteryLabRuntimeState? = store.load()

    @Synchronized
    fun hasActiveProfile(): Boolean = state != null

    @Synchronized
    fun snapshot(): BatteryLabSnapshot = backend.snapshot(
        activeProfile = state?.profile,
        suspendedByBomb = state?.gateCapture != null,
        lastDecisionReason = state?.lastDecisionReason?.name,
        advertisedCurrentCeilingMicroamps = state?.currentLimitCapture?.originalMicroamps,
    )

    @Synchronized
    fun setProfile(profile: BatteryLabProfile): BombResult {
        BatteryLabProfileValidator.violations(profile).firstOrNull()?.let {
            return BombResult.invalidArgument(it)
        }
        val caps = backend.capabilities()
        if (profile.chargeLimitPercent != null && !caps.chargeLimitControl) {
            return BombResult.unsupported("No writable charge-limit or charging-gate node")
        }
        if (profile.maxTemperatureDeciCelsius != null && !caps.thermalChargeControl) {
            return BombResult.unsupported("Temperature or writable charging-gate node unavailable")
        }
        if (profile.maxChargeCurrentMicroamps != null && !caps.chargeCurrentControl) {
            return BombResult.unsupported("No writable charge-current-limit node")
        }
        clearInternal(removeProfile = true).takeUnless { it.isSuccess }?.let { return it }

        var next = BatteryLabRuntimeState(profile)
        val limit = profile.chargeLimitPercent
        if (limit != null && caps.thresholdControl != null) {
            val captured = backend.setThreshold(limit)
                ?: return BombResult.backendUnavailable("Charge threshold write was not acknowledged")
            next = next.copy(thresholdCapture = captured)
        }
        val currentLimit = profile.maxChargeCurrentMicroamps
        if (currentLimit != null && caps.currentLimitControl != null) {
            val captured = backend.setCurrentLimit(currentLimit)
            if (captured == null) {
                // Undo a threshold that may have just landed, so a failed activation
                // never leaves an untracked write on the device.
                next.thresholdCapture?.let { backend.restore(it) }
                return BombResult.backendUnavailable("Charge-current write was not acknowledged")
            }
            next = next.copy(currentLimitCapture = captured)
        }
        state = next
        store.save(next)
        val evaluated = evaluate()
        if (!evaluated.isSuccess) {
            val rollback = clearInternal(removeProfile = true)
            if (!rollback.isSuccess) {
                return BombResult.failed(
                    "Profile activation failed and the previous node value could not be restored",
                )
            }
            return evaluated
        }
        return BombResult.success()
    }

    @Synchronized
    fun evaluate(): BombResult {
        val current = state ?: return BombResult.success()
        val reading = backend.read()
            ?: return BombResult.backendUnavailable("Battery power-supply telemetry is unavailable")

        // A hardware threshold owns the capacity boundary. The software gate is
        // then reserved for thermal policy, avoiding two writers for one limit.
        val policyProfile = if (current.thresholdCapture != null) {
            current.profile.copy(chargeLimitPercent = null)
        } else {
            current.profile
        }
        if (policyProfile.chargeLimitPercent == null &&
            policyProfile.maxTemperatureDeciCelsius == null
        ) {
            return BombResult.success()
        }
        val decision = policy.decide(
            profile = policyProfile,
            state = BatteryLabState(
                capacityPercent = reading.capacityPercent,
                temperatureDeciCelsius = reading.temperatureDeciCelsius,
                externalPowerPresent = reading.externalPowerPresent,
                charging = reading.charging,
            ),
            suspendedByBomb = current.gateCapture != null,
        )

        var next = current.copy(lastDecisionReason = decision.reason)
        when (decision.action) {
            ChargeGuardAction.HOLD -> Unit
            ChargeGuardAction.SUSPEND -> if (current.gateCapture == null) {
                val capture = backend.suspendCharging()
                    ?: return BombResult.backendUnavailable("Charging suspend was not acknowledged")
                next = next.copy(gateCapture = capture)
            }
            ChargeGuardAction.RESTORE -> current.gateCapture?.let { capture ->
                if (!backend.restore(capture)) {
                    return BombResult.failed("Could not restore the previous charging-gate value")
                }
                next = next.copy(gateCapture = null)
            }
        }
        state = next
        store.save(next)
        return BombResult.success()
    }

    @Synchronized
    fun resumeAtStartup(): BombResult {
        val current = state ?: return BombResult.success()
        val limit = current.profile.chargeLimitPercent
        val thresholdCapture = current.thresholdCapture
        if (limit != null) {
            if (thresholdCapture != null) {
                if (!backend.reconcile(thresholdCapture.ref, limit.toString())) {
                    return BombResult.backendUnavailable("Charge threshold could not be reconciled")
                }
            } else if (backend.capabilities().thresholdControl != null) {
                val capture = backend.setThreshold(limit)
                    ?: return BombResult.backendUnavailable("Charge threshold write was not acknowledged")
                state = current.copy(thresholdCapture = capture)
                store.save(state)
            }
        }
        state?.gateCapture?.let { capture ->
            if (!backend.reconcile(capture.ref, capture.ref.suspendedValue)) {
                return BombResult.backendUnavailable("Charging gate could not be reconciled")
            }
        }
        state?.currentLimitCapture?.let { capture ->
            if (!backend.reconcileCurrentLimit(capture)) {
                return BombResult.backendUnavailable("Charge-current limit could not be reconciled")
            }
        }
        return evaluate()
    }

    /** Restore device state while retaining the user's profile for next start. */
    @Synchronized
    fun pause(): BombResult = clearInternal(removeProfile = false)

    @Synchronized
    fun clear(): BombResult = clearInternal(removeProfile = true)

    private fun clearInternal(removeProfile: Boolean): BombResult {
        val current = state ?: return BombResult.success()
        current.gateCapture?.let {
            if (!backend.restore(it)) {
                return BombResult.failed("Could not restore the previous charging-gate value")
            }
        }
        current.thresholdCapture?.let {
            if (!backend.restore(it)) {
                val partiallyRestored = current.copy(gateCapture = null)
                state = partiallyRestored
                store.save(partiallyRestored)
                return BombResult.failed("Could not restore the previous charge threshold")
            }
        }
        current.currentLimitCapture?.let {
            if (!backend.restoreCurrentLimit(it)) {
                val partiallyRestored = current.copy(gateCapture = null, thresholdCapture = null)
                state = partiallyRestored
                store.save(partiallyRestored)
                return BombResult.failed("Could not restore the previous charge-current value")
            }
        }
        state = if (removeProfile) {
            null
        } else {
            current.copy(thresholdCapture = null, gateCapture = null, currentLimitCapture = null)
        }
        store.save(state)
        return BombResult.success()
    }
}
