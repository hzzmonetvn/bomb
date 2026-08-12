package com.hzzmonet.zkbomb.core

import com.hzzmonet.zkbomb.api.CapabilityState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargeCapabilityProbeTest {
    @Test
    fun `probed node requires the integrated bombd control plane`() {
        assertEquals(
            CapabilityState.REQUIRES_ROOT,
            chargeControlCapabilityState(true, true, false),
        )
    }

    @Test
    fun `updated privileged install with reachable control plane is supported`() {
        assertEquals(
            CapabilityState.SUPPORTED,
            chargeControlCapabilityState(true, true, true),
        )
    }

    @Test
    fun `absent control node remains unsupported`() {
        assertEquals(
            CapabilityState.UNSUPPORTED,
            chargeControlCapabilityState(false, false, true),
        )
    }

    @Test
    fun `bombd charge protocol accepts only bounded allowlisted fields`() {
        assertTrue(isValidChargeControl("current_max", 100_000))
        assertTrue(isValidChargeControl("current_max", 20_000_000))
        assertTrue(isValidChargeControl("end_threshold", 80))
        assertTrue(isValidChargeControl("input_suspend", 1))
        assertFalse(isValidChargeControl("current_max", 99_999))
        assertFalse(isValidChargeControl("end_threshold", 101))
        assertFalse(isValidChargeControl("input_suspend", 2))
        assertFalse(isValidChargeControl("/sys/class/power_supply/battery/current_max", 1))
    }
}
