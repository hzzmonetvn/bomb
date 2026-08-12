package com.hzzmonet.zkbomb.core

import com.hzzmonet.zkbomb.api.BatteryLabBackendStatus
import com.hzzmonet.zkbomb.api.BombResult
import com.hzzmonet.zkbomb.domain.battery.BatteryLabProfile
import com.hzzmonet.zkbomb.domain.battery.ChargeGuardReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryLabPhase6Test {

    @Test
    fun `power supply telemetry is parsed with kernel units and null for absent metrics`() {
        val io = batteryIo(
            PowerSupplyNode.CAPACITY to "79",
            PowerSupplyNode.TEMP to "413",
            PowerSupplyNode.VOLTAGE_NOW to "4_020_000".replace("_", ""),
            PowerSupplyNode.CURRENT_NOW to "-615000",
            PowerSupplyNode.CYCLE_COUNT to "321",
        ).apply {
            addSupply("usb", PowerSupplyNode.TYPE to "USB", PowerSupplyNode.ONLINE to "1")
        }
        val backend = backend(io) { 1234L }

        val snapshot = backend.snapshot(null, false, null)

        assertEquals(BatteryLabBackendStatus.AVAILABLE, snapshot.parsedBackendStatus)
        assertEquals("battery", snapshot.powerSupplyName)
        assertEquals(79, snapshot.capacityPercent)
        assertEquals(413, snapshot.temperatureDeciCelsius)
        assertEquals(4_020_000L, snapshot.voltageMicrovolts)
        assertEquals(-615_000L, snapshot.currentMicroamps)
        assertEquals(321, snapshot.cycleCount)
        assertEquals(true, snapshot.externalPowerPresent)
        assertNull(snapshot.chargeCounterMicroampHours)
        assertEquals(1234L, snapshot.sampledAtElapsedRealtimeMillis)
    }

    @Test
    fun `readable control nodes are probed without requiring app sysfs write access`() {
        val io = batteryIo(
            PowerSupplyNode.CAPACITY to "80",
            PowerSupplyNode.TEMP to "390",
            PowerSupplyNode.CHARGE_CONTROL_END_THRESHOLD to "100",
            PowerSupplyNode.CHARGE_DISABLE to "0",
        )
        val caps = backend(io) { 0L }.capabilities()

        assertTrue(caps.telemetry)
        assertTrue(caps.thresholdNodePresent)
        assertTrue(caps.gateNodePresent)
        assertTrue(caps.chargeLimitControl)
        assertTrue(caps.thermalChargeControl)
        assertTrue(caps.anyChargeControl)
    }

    @Test
    fun `unreachable bombd keeps readable nodes present but controls disabled`() {
        val io = batteryIo(
            PowerSupplyNode.CAPACITY to "80",
            PowerSupplyNode.TEMP to "390",
            PowerSupplyNode.CHARGE_CONTROL_END_THRESHOLD to "100",
            PowerSupplyNode.CHARGE_DISABLE to "0",
            PowerSupplyNode.CURRENT_MAX to "2400000",
        )
        val unavailableWriter = object : ChargeControlWriter {
            override val available = false
            override fun write(field: String, value: Int) = false
        }
        val caps = PowerSupplyBackend(
            access = io,
            chargeWriter = unavailableWriter,
            elapsedRealtimeMillis = { 0L },
        ).capabilities()

        assertTrue(caps.thresholdNodePresent)
        assertTrue(caps.gateNodePresent)
        assertTrue(caps.currentLimitNodePresent)
        assertFalse(caps.chargeLimitControl)
        assertFalse(caps.thermalChargeControl)
        assertFalse(caps.chargeCurrentControl)
    }

    @Test
    fun `hardware charge threshold is preferred and exact prior value is restored`() {
        val io = batteryIo(
            PowerSupplyNode.CAPACITY to "70",
            PowerSupplyNode.TEMP to "380",
            PowerSupplyNode.CHARGE_CONTROL_END_THRESHOLD to "96",
        )
        val store = MemoryBatteryLabStore()
        val coordinator = BatteryLabCoordinator(backend(io) { 0L }, store)

        assertTrue(
            coordinator.setProfile(BatteryLabProfile(chargeLimitPercent = 80)).isSuccess,
        )
        assertEquals("80", io.value("battery", PowerSupplyNode.CHARGE_CONTROL_END_THRESHOLD))
        assertFalse(coordinator.snapshot().chargingSuspendedByBomb)

        assertTrue(coordinator.clear().isSuccess)
        assertEquals("96", io.value("battery", PowerSupplyNode.CHARGE_CONTROL_END_THRESHOLD))
        assertNull(store.state)
    }

    @Test
    fun `thermal guard suspends then observes hysteresis before exact restore`() {
        val io = batteryIo(
            PowerSupplyNode.CAPACITY to "65",
            PowerSupplyNode.TEMP to "425",
            PowerSupplyNode.CHARGE_DISABLE to "0",
        )
        val store = MemoryBatteryLabStore()
        val coordinator = BatteryLabCoordinator(backend(io) { 0L }, store)

        assertTrue(
            coordinator.setProfile(
                BatteryLabProfile(maxTemperatureDeciCelsius = 420),
            ).isSuccess,
        )
        assertEquals("1", io.value("battery", PowerSupplyNode.CHARGE_DISABLE))
        assertTrue(coordinator.snapshot().chargingSuspendedByBomb)
        assertEquals(ChargeGuardReason.THERMAL_LIMIT.name, coordinator.snapshot().lastDecisionReason)

        io.set("battery", PowerSupplyNode.TEMP, "405")
        assertTrue(coordinator.evaluate().isSuccess)
        assertEquals("1", io.value("battery", PowerSupplyNode.CHARGE_DISABLE))
        assertEquals(ChargeGuardReason.HYSTERESIS.name, coordinator.snapshot().lastDecisionReason)

        io.set("battery", PowerSupplyNode.TEMP, "390")
        assertTrue(coordinator.evaluate().isSuccess)
        assertEquals("0", io.value("battery", PowerSupplyNode.CHARGE_DISABLE))
        assertFalse(coordinator.snapshot().chargingSuspendedByBomb)
    }

    @Test
    fun `capacity guard falls back to charging gate when hardware threshold is absent`() {
        val io = batteryIo(
            PowerSupplyNode.CAPACITY to "82",
            PowerSupplyNode.TEMP to "370",
            PowerSupplyNode.CHARGING_ENABLED to "1",
        )
        val coordinator = BatteryLabCoordinator(
            backend(io) { 0L },
            MemoryBatteryLabStore(),
        )

        assertTrue(
            coordinator.setProfile(BatteryLabProfile(chargeLimitPercent = 80)).isSuccess,
        )
        assertEquals("0", io.value("battery", PowerSupplyNode.CHARGING_ENABLED))

        io.set("battery", PowerSupplyNode.CAPACITY, "75")
        assertTrue(coordinator.evaluate().isSuccess)
        assertEquals("1", io.value("battery", PowerSupplyNode.CHARGING_ENABLED))
    }

    @Test
    fun `input suspend on USB supply is capability probed as a thermal gate`() {
        val io = batteryIo(
            PowerSupplyNode.CAPACITY to "60",
            PowerSupplyNode.TEMP to "430",
        ).apply {
            addSupply(
                "usb",
                PowerSupplyNode.TYPE to "USB",
                PowerSupplyNode.ONLINE to "1",
                PowerSupplyNode.INPUT_SUSPEND to "0",
            )
        }
        val coordinator = BatteryLabCoordinator(
            backend(io) { 0L },
            MemoryBatteryLabStore(),
        )

        assertTrue(
            coordinator.setProfile(
                BatteryLabProfile(maxTemperatureDeciCelsius = 420),
            ).isSuccess,
        )
        assertEquals("1", io.value("usb", PowerSupplyNode.INPUT_SUSPEND))
        assertEquals("INPUT_SUSPEND", coordinator.snapshot().chargeControlKind)
    }

    @Test
    fun `unsupported sensor profile is rejected without any write`() {
        val io = batteryIo(
            PowerSupplyNode.CAPACITY to "60",
            PowerSupplyNode.CHARGE_DISABLE to "0",
        )
        val coordinator = BatteryLabCoordinator(
            backend(io) { 0L },
            MemoryBatteryLabStore(),
        )

        val result = coordinator.setProfile(
            BatteryLabProfile(maxTemperatureDeciCelsius = 420),
        )

        assertEquals(BombResult.Status.UNSUPPORTED, result.status)
        assertTrue(io.writes.isEmpty())
    }

    @Test
    fun `acknowledgement failure is backend unavailable and profile is not persisted`() {
        val io = batteryIo(
            PowerSupplyNode.CAPACITY to "70",
            PowerSupplyNode.CHARGE_CONTROL_END_THRESHOLD to "100",
        ).apply { acknowledgeWrites = false }
        val store = MemoryBatteryLabStore()
        val result = BatteryLabCoordinator(backend(io) { 0L }, store)
            .setProfile(BatteryLabProfile(chargeLimitPercent = 80))

        assertEquals(BombResult.Status.BACKEND_UNAVAILABLE, result.status)
        assertNull(store.state)
    }

    @Test
    fun `persisted capture is reconciled without replacing original restore value`() {
        val io = batteryIo(
            PowerSupplyNode.CAPACITY to "70",
            PowerSupplyNode.TEMP to "380",
            PowerSupplyNode.CHARGE_CONTROL_END_THRESHOLD to "100",
        )
        val capture = CapturedChargeControl(
            ChargeControlRef("battery", ChargeControlKind.CHARGE_CONTROL_END_THRESHOLD),
            originalValue = "95",
            appliedValue = "80",
        )
        val store = MemoryBatteryLabStore(
            BatteryLabRuntimeState(
                BatteryLabProfile(chargeLimitPercent = 80),
                thresholdCapture = capture,
            ),
        )
        val coordinator = BatteryLabCoordinator(backend(io) { 0L }, store)

        assertTrue(coordinator.resumeAtStartup().isSuccess)
        assertEquals("80", io.value("battery", PowerSupplyNode.CHARGE_CONTROL_END_THRESHOLD))
        assertTrue(coordinator.clear().isSuccess)
        assertEquals("95", io.value("battery", PowerSupplyNode.CHARGE_CONTROL_END_THRESHOLD))
    }

    @Test
    fun `restore does not overwrite a value replaced by the vendor controller`() {
        val io = batteryIo(
            PowerSupplyNode.CAPACITY to "70",
            PowerSupplyNode.CHARGE_CONTROL_END_THRESHOLD to "96",
        )
        val coordinator = BatteryLabCoordinator(
            backend(io) { 0L },
            MemoryBatteryLabStore(),
        )
        assertTrue(
            coordinator.setProfile(BatteryLabProfile(chargeLimitPercent = 80)).isSuccess,
        )

        io.set("battery", PowerSupplyNode.CHARGE_CONTROL_END_THRESHOLD, "85")
        assertTrue(coordinator.clear().isSuccess)
        assertEquals("85", io.value("battery", PowerSupplyNode.CHARGE_CONTROL_END_THRESHOLD))
    }

    @Test
    fun `charge current profile captures applies and restores microamp ceiling`() {
        val io = batteryIo(
            PowerSupplyNode.CONSTANT_CHARGE_CURRENT_MAX to "3000000",
        )
        val coordinator = BatteryLabCoordinator(backend(io) { 7L }, MemoryBatteryLabStore())

        assertTrue(
            coordinator.setProfile(
                BatteryLabProfile(maxChargeCurrentMicroamps = 1_500_000),
            ).isSuccess,
        )
        assertEquals(
            "1500000",
            io.value("battery", PowerSupplyNode.CONSTANT_CHARGE_CURRENT_MAX),
        )
        assertEquals(3_000_000, coordinator.snapshot().maxSupportedChargeCurrentMicroamps)
        assertTrue(coordinator.snapshot().chargeCurrentControlSupported)

        assertTrue(coordinator.clear().isSuccess)
        assertEquals(
            "3000000",
            io.value("battery", PowerSupplyNode.CONSTANT_CHARGE_CURRENT_MAX),
        )
    }

    @Test
    fun `generic current max is probed as the final vendor fallback`() {
        val io = batteryIo(PowerSupplyNode.CURRENT_MAX to "2400000")
        val caps = backend(io) { 0L }.capabilities()

        assertEquals(PowerSupplyNode.CURRENT_MAX, caps.currentLimitControl?.node)
        assertEquals(2_400_000, caps.advertisedCurrentLimitMicroamps)
    }

    @Test
    fun `input supply current limit is dynamically probed`() {
        val io = batteryIo(PowerSupplyNode.CAPACITY to "70").apply {
            addSupply(
                "usb",
                PowerSupplyNode.TYPE to "USB",
                PowerSupplyNode.INPUT_CURRENT_LIMIT to "1800000",
            )
        }

        val caps = backend(io) { 0L }.capabilities()

        assertEquals("usb", caps.currentLimitControl?.supplyName)
        assertEquals(PowerSupplyNode.INPUT_CURRENT_LIMIT, caps.currentLimitControl?.node)
        assertEquals(1_800_000, caps.advertisedCurrentLimitMicroamps)
    }

    @Test
    fun `non allowlisted vendor supply is telemetry only and never advertised writable`() {
        val io = FakePowerSupplyNodeAccess().apply {
            addSupply(
                "main-battery",
                PowerSupplyNode.TYPE to "Battery",
                PowerSupplyNode.STATUS to "Charging",
                PowerSupplyNode.CURRENT_MAX to "2400000",
            )
        }

        val caps = backend(io) { 0L }.capabilities()

        assertTrue(caps.telemetry)
        assertFalse(caps.currentLimitNodePresent)
        assertNull(caps.currentLimitControl)
    }

    @Test
    fun `current ceiling cannot raise the vendor advertised maximum`() {
        val io = batteryIo(PowerSupplyNode.INPUT_CURRENT_LIMIT to "2000000")
        val store = MemoryBatteryLabStore()
        val result = BatteryLabCoordinator(backend(io) { 0L }, store).setProfile(
            BatteryLabProfile(maxChargeCurrentMicroamps = 2_500_000),
        )

        assertEquals(BombResult.Status.BACKEND_UNAVAILABLE, result.status)
        assertEquals("2000000", io.value("battery", PowerSupplyNode.INPUT_CURRENT_LIMIT))
        assertNull(store.state)
    }

    private fun batteryIo(
        vararg values: Pair<PowerSupplyNode, String>,
    ) = FakePowerSupplyNodeAccess().apply {
        addSupply(
            "battery",
            PowerSupplyNode.TYPE to "Battery",
            PowerSupplyNode.STATUS to "Charging",
            *values,
        )
    }

    private fun backend(
        io: FakePowerSupplyNodeAccess,
        elapsedRealtimeMillis: () -> Long,
    ): PowerSupplyBackend = PowerSupplyBackend(
        access = io,
        chargeWriter = io.chargeWriter(),
        elapsedRealtimeMillis = elapsedRealtimeMillis,
    )

    private class MemoryBatteryLabStore(
        var state: BatteryLabRuntimeState? = null,
    ) : BatteryLabStateStore {
        override fun load(): BatteryLabRuntimeState? = state
        override fun save(state: BatteryLabRuntimeState?) {
            this.state = state
        }
    }

    private class FakePowerSupplyNodeAccess : PowerSupplyNodeAccess {
        private val values = linkedMapOf<Pair<String, PowerSupplyNode>, String>()
        val writes = mutableListOf<Triple<String, PowerSupplyNode, String>>()
        var acknowledgeWrites = true

        fun addSupply(
            name: String,
            vararg nodes: Pair<PowerSupplyNode, String>,
        ) {
            nodes.forEach { (node, value) -> values[name to node] = value }
        }

        fun set(name: String, node: PowerSupplyNode, value: String) {
            values[name to node] = value
        }

        fun value(name: String, node: PowerSupplyNode): String? = values[name to node]

        override fun supplyNames(): List<String> = values.keys.map { it.first }.distinct()

        override fun read(supplyName: String, node: PowerSupplyNode): String? =
            values[supplyName to node]

        fun chargeWriter(): ChargeControlWriter = object : ChargeControlWriter {
            override val available: Boolean get() = true

            override fun write(field: String, value: Int): Boolean {
                if (!acknowledgeWrites) return false
                val node = when (field) {
                    "end_threshold" -> PowerSupplyNode.CHARGE_CONTROL_END_THRESHOLD
                    "disable" -> PowerSupplyNode.CHARGE_DISABLE
                    "charging_enabled" -> PowerSupplyNode.CHARGING_ENABLED
                    "input_suspend" -> PowerSupplyNode.INPUT_SUSPEND
                    "current_max" -> listOf(
                        PowerSupplyNode.CONSTANT_CHARGE_CURRENT_MAX,
                        PowerSupplyNode.INPUT_CURRENT_LIMIT,
                        PowerSupplyNode.CURRENT_MAX,
                    ).firstOrNull { candidate -> values.keys.any { it.second == candidate } }
                        ?: return false
                    else -> return false
                }
                val targets = values.keys.filter { it.second == node }
                if (targets.isEmpty()) return false
                targets.forEach { (supply, selectedNode) ->
                    writes += Triple(supply, selectedNode, value.toString())
                    values[supply to selectedNode] = value.toString()
                }
                return true
            }
        }
    }
}
