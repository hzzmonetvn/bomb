package com.hzzmonet.zkbomb.core

import com.hzzmonet.zkbomb.domain.bridge.BombLiveEvent
import com.hzzmonet.zkbomb.domain.bridge.LiveEventState
import com.hzzmonet.zkbomb.domain.bridge.LiveEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveUpdateBridgeBackendTest {
    @Test
    fun `declined promoted update falls back to notification`() {
        val port = FakeBridgePort(notification = true, live = true).apply {
            declinedModes += BridgeNotificationMode.LIVE_UPDATE
        }
        val backend = LiveUpdateBridgeBackend(port, source())

        assertTrue(backend.publish(event()).isSuccess)
        assertEquals(
            listOf(BridgeNotificationMode.LIVE_UPDATE, BridgeNotificationMode.NOTIFICATION),
            port.attempts,
        )
        assertEquals("NOTIFICATION", backend.snapshot().activeEvents.single().renderer)
    }

    @Test
    fun `hyper island requires all probe signals and a versioned encoder`() {
        val port = FakeBridgePort(notification = true, live = false)
        val encoder = object : HyperIslandPayloadEncoder {
            override val available = true
            override fun encode(event: BombLiveEvent, protocolVersion: Int) =
                HyperIslandPayload("protocol-$protocolVersion")
        }
        val backend = LiveUpdateBridgeBackend(
            port,
            source(HyperIslandProbeSnapshot(true, 2, true)),
            encoder,
        )

        assertTrue(backend.publish(event()).isSuccess)
        assertEquals(listOf(BridgeNotificationMode.HYPER_ISLAND), port.attempts)
        assertEquals("protocol-2", port.payloads.single()?.jsonParam)
    }

    @Test
    fun `status retains metadata but no mirrored title or subtitle`() {
        val backend = LiveUpdateBridgeBackend(
            FakeBridgePort(notification = true, live = false),
            source(),
        )
        backend.publish(event(title = "secret title", subtitle = "secret subtitle"))

        val status = backend.snapshot().activeEvents.single()
        assertEquals("event-1", status.eventId)
        assertFalse(status.toString().contains("secret"))
    }

    private fun source(
        snapshot: HyperIslandProbeSnapshot = HyperIslandProbeSnapshot(false, 0, false),
    ) = HyperIslandCapabilitySource { snapshot }

    private fun event(title: String = "Download", subtitle: String? = "42 percent") =
        BombLiveEvent(
            id = "event-1",
            sourcePackage = "com.hzzmonet.zkbomb",
            type = LiveEventType.DOWNLOAD,
            title = title,
            subtitle = subtitle,
            compactText = "42%",
            progress = null,
            state = LiveEventState.ACTIVE,
            timestampMillis = 1_000,
        )

    private class FakeBridgePort(
        private val notification: Boolean,
        private val live: Boolean,
    ) : BridgeNotificationPort {
        val attempts = mutableListOf<BridgeNotificationMode>()
        val payloads = mutableListOf<HyperIslandPayload?>()
        val declinedModes = mutableSetOf<BridgeNotificationMode>()

        override fun notificationAvailable() = notification
        override fun liveUpdateAvailable() = live
        override fun post(
            event: BombLiveEvent,
            mode: BridgeNotificationMode,
            payload: HyperIslandPayload?,
        ): Boolean {
            attempts += mode
            payloads += payload
            return mode !in declinedModes
        }
        override fun cancel(eventId: String) = Unit
    }
}
