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
        val backend = PowerSupplyBackend(io) { 1234L }

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
    fun `readable but non-writable nodes never advertise charge control`() {
        val io = batteryIo(
            PowerSupplyNode.CAPACITY to "80",
            PowerSupplyNode.TEMP to "390",
            PowerSupplyNode.CHARGE_CONTROL_END_THRESHOLD to "100",
            PowerSupplyNode.CHARGE_DISABLE to "0",
        )
        val caps = PowerSupplyBackend(io) { 0L }.capabilities()

        assertTrue(caps.telemetry)
        assertTrue(caps.thresholdNodePresent)
        assertTrue(caps.gateNodePresent)
        assertFalse(caps.chargeLimitControl)
        assertFalse(caps.thermalChargeControl)
        assertFalse(caps.anyChargeControl)
    }

    @Test
    fun `hardware charge threshold is preferred and exact prior value is restored`() {
        val io = batteryIo(
            PowerSupplyNode.CAPACITY to "70",
            PowerSupplyNode.TEMP to "380",
            PowerSupplyNode.CHARGE_CONTROL_END_THRESHOLD to "96",
        ).apply {
            writable("battery", PowerSupplyNode.CHARGE_CONTROL_END_THRESHOLD)
        }
        val store = MemoryBatteryLabStore()
        val coordinator = BatteryLabCoordinator(PowerSupplyBackend(io) { 0L }, store)

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
        ).apply {
            writable("battery", PowerSupplyNode.CHARGE_DISABLE)
        }
        val store = MemoryBatteryLabStore()
        val coordinator = BatteryLabCoordinator(PowerSupplyBackend(io) { 0L }, store)

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
        ).apply {
            writable("battery", PowerSupplyNode.CHARGING_ENABLED)
        }
        val coordinator = BatteryLabCoordinator(
            PowerSupplyBackend(io) { 0L },
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
            writable("usb", PowerSupplyNode.INPUT_SUSPEND)
        }
        val coordinator = BatteryLabCoordinator(
            PowerSupplyBackend(io) { 0L },
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
        ).apply {
            writable("battery", PowerSupplyNode.CHARGE_DISABLE)
        }
        val coordinator = BatteryLabCoordinator(
            PowerSupplyBackend(io) { 0L },
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
        ).apply {
            writable("battery", PowerSupplyNode.CHARGE_CONTROL_END_THRESHOLD)
            acknowledgeWrites = false
        }
        val store = MemoryBatteryLabStore()
        val result = BatteryLabCoordinator(PowerSupplyBackend(io) { 0L }, store)
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
        ).apply {
            writable("battery", PowerSupplyNode.CHARGE_CONTROL_END_THRESHOLD)
        }
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
        val coordinator = BatteryLabCoordinator(PowerSupplyBackend(io) { 0L }, store)

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
        ).apply {
            writable("battery", PowerSupplyNode.CHARGE_CONTROL_END_THRESHOLD)
        }
        val coordinator = BatteryLabCoordinator(
            PowerSupplyBackend(io) { 0L },
            MemoryBatteryLabStore(),
        )
        assertTrue(
            coordinator.setProfile(BatteryLabProfile(chargeLimitPercent = 80)).isSuccess,
        )

        io.set("battery", PowerSupplyNode.CHARGE_CONTROL_END_THRESHOLD, "85")
        assertTrue(coordinator.clear().isSuccess)
        assertEquals("85", io.value("battery", PowerSupplyNode.CHARGE_CONTROL_END_THRESHOLD))
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
        private val writable = mutableSetOf<Pair<String, PowerSupplyNode>>()
        val writes = mutableListOf<Triple<String, PowerSupplyNode, String>>()
        var acknowledgeWrites = true

        fun addSupply(
            name: String,
            vararg nodes: Pair<PowerSupplyNode, String>,
        ) {
            nodes.forEach { (node, value) -> values[name to node] = value }
        }

        fun writable(name: String, node: PowerSupplyNode) {
            writable += name to node
        }

        fun set(name: String, node: PowerSupplyNode, value: String) {
            values[name to node] = value
        }

        fun value(name: String, node: PowerSupplyNode): String? = values[name to node]

        override fun supplyNames(): List<String> = values.keys.map { it.first }.distinct()

        override fun read(supplyName: String, node: PowerSupplyNode): String? =
            values[supplyName to node]

        override fun canWrite(supplyName: String, node: PowerSupplyNode): Boolean =
            supplyName to node in writable

        override fun write(
            supplyName: String,
            node: PowerSupplyNode,
            value: String,
        ): Boolean {
            if (!canWrite(supplyName, node)) return false
            writes += Triple(supplyName, node, value)
            if (acknowledgeWrites) values[supplyName to node] = value
            return true
        }
    }
}
