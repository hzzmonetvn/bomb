package com.hzzmonet.zkbomb.core

import com.hzzmonet.zkbomb.api.BatteryLabBackendStatus
import com.hzzmonet.zkbomb.api.BatteryLabProfileParcel
import com.hzzmonet.zkbomb.api.BatteryLabSnapshot
import com.hzzmonet.zkbomb.domain.battery.BatteryLabProfile
import java.io.File

internal enum class PowerSupplyNode(val fileName: String) {
    TYPE("type"),
    STATUS("status"),
    CAPACITY("capacity"),
    TEMP("temp"),
    VOLTAGE_NOW("voltage_now"),
    CURRENT_NOW("current_now"),
    CHARGE_COUNTER("charge_counter"),
    CYCLE_COUNT("cycle_count"),
    CHARGE_FULL("charge_full"),
    CHARGE_FULL_DESIGN("charge_full_design"),
    ONLINE("online"),
    CHARGE_CONTROL_END_THRESHOLD("charge_control_end_threshold"),
    CHARGE_DISABLE("charge_disable"),
    CHARGING_ENABLED("charging_enabled"),
    INPUT_SUSPEND("input_suspend"),
    CONSTANT_CHARGE_CURRENT_MAX("constant_charge_current_max"),
    INPUT_CURRENT_LIMIT("input_current_limit"),
    CURRENT_MAX("current_max"),
}

/**
 * Read-only sysfs access. The app reads the power-supply nodes to probe, snapshot
 * and check ownership, but it never writes them — every charge write is routed
 * through [ChargeControlWriter] → bombd → init.
 */
internal interface PowerSupplyNodeAccess {
    fun supplyNames(): List<String>
    fun read(supplyName: String, node: PowerSupplyNode): String?
}

/** Real sysfs access. The only selectable files are [PowerSupplyNode] entries. */
internal class RealPowerSupplyNodeAccess(
    private val root: File = File("/sys/class/power_supply"),
) : PowerSupplyNodeAccess {
    override fun supplyNames(): List<String> = root.listFiles()
        ?.asSequence()
        ?.filter { SUPPLY_NAME.matches(it.name) }
        ?.map { it.name }
        ?.sorted()
        ?.toList()
        .orEmpty()

    override fun read(supplyName: String, node: PowerSupplyNode): String? =
        resolved(supplyName, node)?.let { file ->
            runCatching {
                file.bufferedReader(Charsets.US_ASCII).use { reader ->
                    reader.readLine()?.trim()?.take(MAX_NODE_TEXT)
                }
            }.getOrNull()
        }

    private fun resolved(supplyName: String, node: PowerSupplyNode): File? {
        if (!SUPPLY_NAME.matches(supplyName)) return null
        val supply = File(root, supplyName)
        if (!supply.exists()) return null
        // Sysfs class entries are symlinks. Resolve the directory once, then
        // append an enum-owned filename; no caller-controlled path survives.
        val canonicalSupply = runCatching { supply.canonicalFile }.getOrNull() ?: return null
        return File(canonicalSupply, node.fileName).takeIf { it.isFile }
    }

    private companion object {
        const val MAX_NODE_TEXT = 128
        val SUPPLY_NAME = Regex("^[A-Za-z0-9_.-]{1,64}$")
    }
}

/**
 * Publishes a charge-control write through the init-owned control plane. The
 * process never writes the sysfs node: [field] names a bounded request property
 * (`current_max`, `end_threshold`, `disable`, `charging_enabled`, `input_suspend`)
 * that bombd range-checks and sets, and init's bomb.rc trigger performs the write.
 */
internal interface ChargeControlWriter {
    val available: Boolean
    fun write(field: String, value: Int): Boolean
}

internal class BombdChargeControlWriter(
    private val control: RomControlPropertyWriter = RomControlPropertyWriter(),
) : ChargeControlWriter {
    override val available: Boolean get() = control.available

    override fun write(field: String, value: Int): Boolean =
        control.requestChargeControl(field, value)
}

internal enum class ChargeControlKind(val field: String) {
    CHARGE_CONTROL_END_THRESHOLD("end_threshold"),
    CHARGE_DISABLE("disable"),
    CHARGING_ENABLED("charging_enabled"),
    INPUT_SUSPEND("input_suspend"),
}

internal data class ChargeControlRef(
    val supplyName: String,
    val kind: ChargeControlKind,
) {
    val node: PowerSupplyNode
        get() = when (kind) {
            ChargeControlKind.CHARGE_CONTROL_END_THRESHOLD ->
                PowerSupplyNode.CHARGE_CONTROL_END_THRESHOLD
            ChargeControlKind.CHARGE_DISABLE -> PowerSupplyNode.CHARGE_DISABLE
            ChargeControlKind.CHARGING_ENABLED -> PowerSupplyNode.CHARGING_ENABLED
            ChargeControlKind.INPUT_SUSPEND -> PowerSupplyNode.INPUT_SUSPEND
        }

    val suspendedValue: String
        get() = when (kind) {
            ChargeControlKind.CHARGING_ENABLED -> "0"
            ChargeControlKind.CHARGE_DISABLE,
            ChargeControlKind.INPUT_SUSPEND,
            -> "1"
            ChargeControlKind.CHARGE_CONTROL_END_THRESHOLD -> error("threshold is not a gate")
        }
}

internal data class CapturedChargeControl(
    val ref: ChargeControlRef,
    val originalValue: String,
    /** Value Bomb wrote and therefore still owns only while it remains current. */
    val appliedValue: String,
)

/**
 * A writable charge-current-limit node. Unlike a gate/threshold this holds a
 * microamp value, and only two node names are ever selectable — both enum-owned,
 * never a caller path.
 */
internal data class CurrentLimitRef(
    val supplyName: String,
    val node: PowerSupplyNode,
)

internal data class CapturedCurrentLimit(
    val ref: CurrentLimitRef,
    val originalMicroamps: Int,
    /** Value Bomb wrote; it owns the node only while this remains current. */
    val appliedMicroamps: Int,
)

internal data class PowerSupplyReading(
    val batterySupplyName: String,
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
) {
    val charging: Boolean?
        get() = status?.let { it.equals("Charging", ignoreCase = true) }
}

internal data class PowerSupplyCapabilities(
    val telemetry: Boolean,
    val capacityTelemetry: Boolean,
    val temperatureTelemetry: Boolean,
    val thresholdNodePresent: Boolean,
    val gateNodePresent: Boolean,
    val thresholdControl: ChargeControlRef?,
    val gateControl: ChargeControlRef?,
    val currentLimitNodePresent: Boolean = false,
    val currentLimitControl: CurrentLimitRef? = null,
    /** The current-limit node's present value in µA — the advertised ceiling. */
    val advertisedCurrentLimitMicroamps: Int? = null,
) {
    val chargeLimitControl: Boolean
        get() = capacityTelemetry && (thresholdControl != null || gateControl != null)
    val thermalChargeControl: Boolean
        get() = temperatureTelemetry && gateControl != null
    val chargeCurrentControl: Boolean get() = currentLimitControl != null
    val anyChargeControl: Boolean get() = chargeLimitControl || thermalChargeControl || chargeCurrentControl
}

/** Capability-probed, allowlisted backend for `/sys/class/power_supply`. */
class PowerSupplyBackend internal constructor(
    private val access: PowerSupplyNodeAccess = RealPowerSupplyNodeAccess(),
    // No charge write touches sysfs from this process: threshold, gate and current
    // are all published as bounded requests through bombd, and init owns the node
    // write. The app only reads back (to capture and to check ownership). Kept ahead
    // of elapsedRealtimeMillis so the latter stays the trailing-lambda parameter.
    private val chargeWriter: ChargeControlWriter = BombdChargeControlWriter(),
    private val elapsedRealtimeMillis: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {
    internal fun capabilities(): PowerSupplyCapabilities {
        val battery = batterySupplyName() ?: return PowerSupplyCapabilities(
            telemetry = false,
            capacityTelemetry = false,
            temperatureTelemetry = false,
            thresholdNodePresent = false,
            gateNodePresent = false,
            thresholdControl = null,
            gateControl = null,
        )
        val capacity = readInt(battery, PowerSupplyNode.CAPACITY, 0..100) != null
        val temperature = readInt(battery, PowerSupplyNode.TEMP, -400..1_000) != null
        val telemetry = capacity || temperature ||
            access.read(battery, PowerSupplyNode.STATUS) != null ||
            readLong(battery, PowerSupplyNode.VOLTAGE_NOW) != null

        val supplyNames = access.supplyNames().filter { SUPPLY_NAME.matches(it) }
        val batteryControlSupplies = supplyNames.filter {
            it in INIT_BATTERY_SUPPLIES
        }.sortedBy { INIT_BATTERY_SUPPLIES.indexOf(it) }
        val thresholdCandidate = batteryControlSupplies.firstNotNullOfOrNull { supply ->
            controlIfPresent(supply, ChargeControlKind.CHARGE_CONTROL_END_THRESHOLD)
        }
        val batteryGates = batteryControlSupplies.flatMap { supply ->
            listOf(
                ChargeControlKind.CHARGE_DISABLE,
                ChargeControlKind.CHARGING_ENABLED,
                ChargeControlKind.INPUT_SUSPEND,
            ).map { supply to it }
        }
        val inputControlSupplies = supplyNames
            .asSequence()
            .filter {
                it != battery && SUPPLY_NAME.matches(it) &&
                    it in INIT_INPUT_SUPPLIES
            }
            .sortedBy { INIT_INPUT_SUPPLIES.indexOf(it) }
            .filter { name ->
                access.read(name, PowerSupplyNode.TYPE)?.lowercase() in
                    setOf("usb", "mains", "wireless")
            }
            .toList()
        val inputSupplyGates = inputControlSupplies.map {
            it to ChargeControlKind.INPUT_SUSPEND
        }
        val gateCandidates = (batteryGates + inputSupplyGates)
            .mapNotNull { (supply, kind) -> controlIfPresent(supply, kind) }
        // Detection is presence-based, not app-writability: writes go through init
        // (which owns the node), so `canWrite` from this process is always false and
        // is the wrong signal. A node that reads a plausible value is taken as the
        // control; CapabilityProbe further requires ROM mode plus a reachable bombd
        // before reporting the capability SUPPORTED.
        val currentLimitCandidates = currentLimitCandidates(
            batterySupplies = batteryControlSupplies,
            inputSupplies = inputControlSupplies,
        )
        val currentLimitCandidate = currentLimitCandidates.firstOrNull { ref ->
            readCurrentMicroamps(ref) != null
        }
        val advertisedCurrent = currentLimitCandidate?.let { readCurrentMicroamps(it) }
        val anyControlNodePresent = thresholdCandidate != null ||
            gateCandidates.isNotEmpty() || currentLimitCandidate != null
        val chargeControlAvailable = anyControlNodePresent && chargeWriter.available
        val threshold = thresholdCandidate?.takeIf { chargeControlAvailable }
        val gate = gateCandidates.firstOrNull()?.takeIf { chargeControlAvailable }
        val currentLimit = currentLimitCandidate?.takeIf { chargeControlAvailable }
        return PowerSupplyCapabilities(
            telemetry = telemetry,
            capacityTelemetry = capacity,
            temperatureTelemetry = temperature,
            thresholdNodePresent = thresholdCandidate != null,
            gateNodePresent = gateCandidates.isNotEmpty(),
            thresholdControl = threshold,
            gateControl = gate,
            currentLimitNodePresent = advertisedCurrent != null,
            currentLimitControl = currentLimit,
            advertisedCurrentLimitMicroamps = advertisedCurrent,
        )
    }

    /**
     * Charge-current-limit candidates on fixed init-allowlisted battery/input
     * supplies. Battery nodes prefer `constant_charge_current_max` (the CC-phase
     * ceiling), then `input_current_limit`, then vendor fallback `current_max`;
     * input supplies expose the latter two.
     *
     * The list exactly matches the fixed nodes the init trigger in bomb.rc writes.
     * Detecting a node init does not write would report
     * the capability SUPPORTED while the write silently landed nowhere.
     */
    private fun currentLimitCandidates(
        batterySupplies: List<String>,
        inputSupplies: List<String>,
    ): List<CurrentLimitRef> = buildList {
        batterySupplies.forEach { supply ->
            add(CurrentLimitRef(supply, PowerSupplyNode.CONSTANT_CHARGE_CURRENT_MAX))
            add(CurrentLimitRef(supply, PowerSupplyNode.INPUT_CURRENT_LIMIT))
            add(CurrentLimitRef(supply, PowerSupplyNode.CURRENT_MAX))
        }
        inputSupplies.forEach { supply ->
            add(CurrentLimitRef(supply, PowerSupplyNode.INPUT_CURRENT_LIMIT))
            add(CurrentLimitRef(supply, PowerSupplyNode.CURRENT_MAX))
        }
    }

    private fun readCurrentMicroamps(ref: CurrentLimitRef): Int? =
        access.read(ref.supplyName, ref.node)?.toIntOrNull()
            ?.takeIf { it in MIN_CURRENT_MICROAMPS..MAX_CURRENT_MICROAMPS }

    internal fun read(): PowerSupplyReading? {
        val battery = batterySupplyName() ?: return null
        return PowerSupplyReading(
            batterySupplyName = battery,
            status = access.read(battery, PowerSupplyNode.STATUS)?.takeIf { it.length <= 32 },
            capacityPercent = readInt(battery, PowerSupplyNode.CAPACITY, 0..100),
            temperatureDeciCelsius = readInt(battery, PowerSupplyNode.TEMP, -400..1_000),
            voltageMicrovolts = readLong(battery, PowerSupplyNode.VOLTAGE_NOW, 0L..30_000_000L),
            currentMicroamps = readLong(
                battery,
                PowerSupplyNode.CURRENT_NOW,
                -20_000_000L..20_000_000L,
            ),
            chargeCounterMicroampHours = readLong(
                battery,
                PowerSupplyNode.CHARGE_COUNTER,
                -100_000_000L..100_000_000L,
            ),
            cycleCount = readInt(battery, PowerSupplyNode.CYCLE_COUNT, 0..100_000),
            chargeFullMicroampHours = readLong(
                battery,
                PowerSupplyNode.CHARGE_FULL,
                0L..100_000_000L,
            ),
            chargeFullDesignMicroampHours = readLong(
                battery,
                PowerSupplyNode.CHARGE_FULL_DESIGN,
                0L..100_000_000L,
            ),
            externalPowerPresent = readExternalPowerPresent(battery),
        )
    }

    internal fun snapshot(
        activeProfile: BatteryLabProfile?,
        suspendedByBomb: Boolean,
        lastDecisionReason: String?,
        // The captured original current, passed by the coordinator so the slider
        // ceiling stays the device maximum even while Bomb holds a lower cap.
        advertisedCurrentCeilingMicroamps: Int? = null,
    ): BatteryLabSnapshot {
        val caps = capabilities()
        val reading = read()
        val backendStatus = when {
            reading == null -> BatteryLabBackendStatus.UNSUPPORTED
            caps.telemetry -> BatteryLabBackendStatus.AVAILABLE
            else -> BatteryLabBackendStatus.UNAVAILABLE
        }
        return BatteryLabSnapshot(
            sampledAtElapsedRealtimeMillis = elapsedRealtimeMillis(),
            backendStatus = backendStatus.name,
            powerSupplyName = reading?.batterySupplyName,
            status = reading?.status,
            capacityPercent = reading?.capacityPercent,
            temperatureDeciCelsius = reading?.temperatureDeciCelsius,
            voltageMicrovolts = reading?.voltageMicrovolts,
            currentMicroamps = reading?.currentMicroamps,
            chargeCounterMicroampHours = reading?.chargeCounterMicroampHours,
            cycleCount = reading?.cycleCount,
            chargeFullMicroampHours = reading?.chargeFullMicroampHours,
            chargeFullDesignMicroampHours = reading?.chargeFullDesignMicroampHours,
            externalPowerPresent = reading?.externalPowerPresent,
            chargeControlKind = listOfNotNull(
                caps.thresholdControl?.kind?.name,
                caps.gateControl?.kind?.name,
            ).joinToString("+").ifEmpty { null },
            chargeLimitControlSupported = caps.chargeLimitControl,
            thermalChargeControlSupported = caps.thermalChargeControl,
            activeProfile = activeProfile?.let(BatteryLabProfileParcel::fromDomain),
            chargingSuspendedByBomb = suspendedByBomb,
            lastDecisionReason = lastDecisionReason,
            chargeCurrentControlSupported = caps.chargeCurrentControl,
            maxSupportedChargeCurrentMicroamps =
            advertisedCurrentCeilingMicroamps ?: caps.advertisedCurrentLimitMicroamps,
        )
    }

    internal fun setThreshold(limitPercent: Int): CapturedChargeControl? {
        if (limitPercent !in 50..100) return null
        val ref = capabilities().thresholdControl ?: return null
        val original = access.read(ref.supplyName, ref.node)?.toIntOrNull()?.toString() ?: return null
        return if (writeControl(ref, limitPercent.toString())) {
            CapturedChargeControl(ref, original, limitPercent.toString())
        } else {
            null
        }
    }

    internal fun suspendCharging(): CapturedChargeControl? {
        val ref = capabilities().gateControl ?: return null
        val original = access.read(ref.supplyName, ref.node)?.toIntOrNull()?.toString() ?: return null
        return if (writeControl(ref, ref.suspendedValue)) {
            CapturedChargeControl(ref, original, ref.suspendedValue)
        } else {
            null
        }
    }

    internal fun restore(captured: CapturedChargeControl): Boolean {
        if (!controlStillProbed(captured.ref)) return false
        val current = access.read(captured.ref.supplyName, captured.ref.node)
            ?.toIntOrNull()
            ?.toString()
            ?: return false
        // A vendor charging daemon may have replaced Bomb's value. At that
        // point Bomb no longer owns the node and must not overwrite the newer
        // controller state with its stale baseline.
        if (current != captured.appliedValue.toIntOrNull()?.toString()) return true
        return writeControl(captured.ref, captured.originalValue)
    }

    internal fun reconcile(ref: ChargeControlRef, value: String): Boolean {
        val expected = when (ref.kind) {
            ChargeControlKind.CHARGE_CONTROL_END_THRESHOLD ->
                capabilities().thresholdControl
            else -> capabilities().gateControl
        }
        if (expected != ref) return false
        val number = value.toIntOrNull() ?: return false
        val valid = when (ref.kind) {
            ChargeControlKind.CHARGE_CONTROL_END_THRESHOLD -> number in 50..100
            else -> number in 0..1
        }
        return valid && writeControl(ref, number.toString())
    }

    /**
     * Cap the charge current to [microamps] on the probed current-limit node,
     * capturing the value it held so [restoreCurrentLimit] can put it back. Returns
     * null when there is no writable node, the value is out of range, or the write
     * is not acknowledged.
     */
    internal fun setCurrentLimit(microamps: Int): CapturedCurrentLimit? {
        if (microamps !in MIN_CURRENT_MICROAMPS..MAX_CURRENT_MICROAMPS) return null
        val ref = capabilities().currentLimitControl ?: return null
        val original = readCurrentMicroamps(ref) ?: return null
        // This API is a ceiling, not an overclock request. Never raise the
        // device/vendor-advertised limit observed when the profile is activated.
        if (microamps > original) return null
        // The write is published through bombd; init performs the sysfs write. A
        // false here means bombd rejected or was unreachable.
        return if (chargeWriter.write(CURRENT_MAX_FIELD, microamps)) {
            CapturedCurrentLimit(ref, original, microamps)
        } else {
            null
        }
    }

    internal fun restoreCurrentLimit(captured: CapturedCurrentLimit): Boolean {
        if (capabilities().currentLimitControl != captured.ref) return false
        val current = readCurrentMicroamps(captured.ref) ?: return false
        // A vendor charging daemon may have moved the cap since Bomb set it. If so
        // Bomb no longer owns the node and must not push its stale baseline back.
        if (current != captured.appliedMicroamps) return true
        return chargeWriter.write(CURRENT_MAX_FIELD, captured.originalMicroamps)
    }

    /** Re-apply [captured] after a restart, if the node is still the probed one. */
    internal fun reconcileCurrentLimit(captured: CapturedCurrentLimit): Boolean {
        if (capabilities().currentLimitControl != captured.ref) return false
        return chargeWriter.write(CURRENT_MAX_FIELD, captured.appliedMicroamps)
    }

    private fun batterySupplyName(): String? {
        val names = access.supplyNames().filter { SUPPLY_NAME.matches(it) }
        return names.firstOrNull { name ->
            access.read(name, PowerSupplyNode.TYPE)?.equals("Battery", ignoreCase = true) == true
        } ?: names.firstOrNull { it.equals("battery", ignoreCase = true) }
    }

    private fun readExternalPowerPresent(batteryName: String): Boolean? {
        val online = access.supplyNames()
            .asSequence()
            .filter { it != batteryName && SUPPLY_NAME.matches(it) }
            .mapNotNull { name -> readInt(name, PowerSupplyNode.ONLINE, 0..1) }
            .toList()
        return online.takeIf { it.isNotEmpty() }?.any { it == 1 }
    }

    private fun controlIfPresent(
        supplyName: String,
        kind: ChargeControlKind,
    ): ChargeControlRef? {
        val ref = ChargeControlRef(supplyName, kind)
        val current = access.read(supplyName, ref.node)?.toIntOrNull()
        val valueIsValid = current != null && when (kind) {
            ChargeControlKind.CHARGE_CONTROL_END_THRESHOLD -> current in 0..100
            else -> current in 0..1
        }
        return ref.takeIf { valueIsValid }
    }

    private fun controlStillProbed(ref: ChargeControlRef): Boolean {
        val caps = capabilities()
        val expected = if (ref.kind == ChargeControlKind.CHARGE_CONTROL_END_THRESHOLD) {
            caps.thresholdControl
        } else {
            caps.gateControl
        }
        return expected == ref &&
            access.read(ref.supplyName, ref.node)?.toIntOrNull() != null
    }

    /** Route a threshold/gate write through bombd; init owns the sysfs write. */
    private fun writeControl(ref: ChargeControlRef, value: String): Boolean {
        val number = value.toIntOrNull() ?: return false
        return chargeWriter.write(ref.kind.field, number)
    }

    private fun readInt(supply: String, node: PowerSupplyNode, range: IntRange): Int? =
        access.read(supply, node)?.toIntOrNull()?.takeIf { it in range }

    private fun readLong(supply: String, node: PowerSupplyNode): Long? =
        access.read(supply, node)?.toLongOrNull()

    private fun readLong(
        supply: String,
        node: PowerSupplyNode,
        range: LongRange,
    ): Long? = readLong(supply, node)?.takeIf { it in range }

    private companion object {
        val SUPPLY_NAME = Regex("^[A-Za-z0-9_.-]{1,64}$")
        val INIT_BATTERY_SUPPLIES = listOf("battery", "bms")
        val INIT_INPUT_SUPPLIES = listOf("usb", "ac", "mains", "wireless")

        // The bombd/init request field for the charge-current cap.
        const val CURRENT_MAX_FIELD = "current_max"

        // Charge-current node value bounds in µA (plausibility gate for the probe).
        const val MIN_CURRENT_MICROAMPS = 100_000
        const val MAX_CURRENT_MICROAMPS = 20_000_000
    }
}
