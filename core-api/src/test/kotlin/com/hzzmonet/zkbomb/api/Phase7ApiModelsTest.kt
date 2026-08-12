package com.hzzmonet.zkbomb.api

import com.hzzmonet.zkbomb.domain.bridge.LiveEventState
import com.hzzmonet.zkbomb.domain.bridge.LiveEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase7ApiModelsTest {
    @Test fun `live event parcel validates names and structured progress`() {
        val parcel = BombLiveEventParcel(
            "charging:0", "com.hzzmonet.zkbomb", LiveEventType.CHARGING.name,
            "Charging", "67 percent", "67% | 28 W | 37 C", 0.67, false,
            LiveEventState.ACTIVE.name, 10,
        )
        assertEquals(LiveEventType.CHARGING, parcel.toDomain()?.type)
        assertNull(parcel.copy(type = "UNKNOWN").toDomain())
        assertNull(parcel.copy(progressFraction = 2.0).toDomain())
    }

    @Test fun `thermal config parcel rejects unordered thresholds`() {
        assertTrue(ThermalGuardianConfigParcel(420, 450, 390, 60_000).toDomain() != null)
        assertNull(ThermalGuardianConfigParcel(420, 410, 430, 1).toDomain())
    }
}
