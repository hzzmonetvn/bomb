package com.hzzmonet.zkbomb.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryLabApiModelsTest {

    @Test
    fun `wire profile validates and converts without changing units`() {
        val parcel = BatteryLabProfileParcel(80, 420, 5, 30)
        val domain = parcel.toDomain()!!

        assertEquals(80, domain.chargeLimitPercent)
        assertEquals(420, domain.maxTemperatureDeciCelsius)
        assertEquals(parcel, BatteryLabProfileParcel.fromDomain(domain))
    }

    @Test
    fun `invalid wire profile is rejected before backend access`() {
        assertNull(BatteryLabProfileParcel(20, 900, 0, 0).toDomain())
        assertNull(BatteryLabProfileParcel(null, null).toDomain())
    }

    @Test
    fun `unavailable snapshot carries null metrics and disabled controls`() {
        val snapshot = BatteryLabSnapshot.unavailable(123)

        assertEquals(BatteryLabBackendStatus.UNAVAILABLE, snapshot.parsedBackendStatus)
        assertNull(snapshot.capacityPercent)
        assertNull(snapshot.temperatureDeciCelsius)
        assertFalse(snapshot.chargeLimitControlSupported)
        assertFalse(snapshot.thermalChargeControlSupported)
        assertTrue(!snapshot.chargingSuspendedByBomb)
    }
}
