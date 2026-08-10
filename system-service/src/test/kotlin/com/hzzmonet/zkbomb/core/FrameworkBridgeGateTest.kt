package com.hzzmonet.zkbomb.core

import com.hzzmonet.zkbomb.api.BombResult
import com.hzzmonet.zkbomb.api.CapabilityState
import org.junit.Assert.assertEquals
import org.junit.Test

class FrameworkBridgeGateTest {

    @Test
    fun `ROM and patch marker without bridge acknowledgement stay unsupported`() {
        assertEquals(
            CapabilityState.UNSUPPORTED,
            FrameworkBridgeGate.markerOnlyCapabilityState(
                romDeclared = true,
                patchMarkerDeclared = true,
            ),
        )
    }

    @Test
    fun `missing ROM or patch marker also stays unsupported`() {
        listOf(
            false to false,
            false to true,
            true to false,
        ).forEach { (romDeclared, patchMarkerDeclared) ->
            assertEquals(
                CapabilityState.UNSUPPORTED,
                FrameworkBridgeGate.markerOnlyCapabilityState(
                    romDeclared = romDeclared,
                    patchMarkerDeclared = patchMarkerDeclared,
                ),
            )
        }
    }

    @Test
    fun `unavailable visibility and settings writes never invoke backend mutation`() {
        listOf("visibility", "settings").forEach { feature ->
            var mutationCount = 0

            val result = FrameworkBridgeGate.executeWrite(
                capabilityState = CapabilityState.UNSUPPORTED,
                unavailableDetail = "$feature bridge unavailable",
            ) {
                mutationCount += 1
                BombResult.success()
            }

            assertEquals(BombResult.Status.UNSUPPORTED, result.status)
            assertEquals("$feature mutation must not run", 0, mutationCount)
        }
    }
}
