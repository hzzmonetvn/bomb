package com.hzzmonet.zkbomb.domain.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveEventModelsTest {
    @Test fun `valid structured event passes and progress maps to percent`() {
        val event = event(progress = LiveEventProgress(0.675, false))
        assertTrue(BombLiveEventValidator.violations(event).isEmpty())
        assertEquals(67, event.progress?.percent)
    }

    @Test fun `invalid identity text and progress are rejected`() {
        val invalid = event(
            id = "../event",
            title = "bad\u0000title",
            progress = LiveEventProgress(1.5, false),
        )
        assertTrue(BombLiveEventValidator.violations(invalid).containsAll(listOf("id", "title", "progress")))
        assertTrue(
            BombLiveEventValidator.violations(
                event(progress = LiveEventProgress(0.5, true)),
            ).contains("progress"),
        )
    }

    @Test fun `routing prefers official Live Update and retains notification floor`() {
        assertEquals(
            listOf(
                BridgeRendererKind.LIVE_UPDATE,
                BridgeRendererKind.HYPER_ISLAND,
                BridgeRendererKind.NOTIFICATION,
            ),
            BridgeRoutingPolicy.candidates(BridgeRendererAvailability(true, true, true)),
        )
        assertEquals(
            listOf(BridgeRendererKind.NOTIFICATION),
            BridgeRoutingPolicy.candidates(BridgeRendererAvailability(false, false, true)),
        )
    }

    private fun event(
        id: String = "charging:0",
        title: String = "Charging",
        progress: LiveEventProgress? = null,
    ) = BombLiveEvent(
        id = id,
        sourcePackage = "com.hzzmonet.zkbomb",
        type = LiveEventType.CHARGING,
        title = title,
        subtitle = "67 percent",
        compactText = "67% | 28 W | 37 C",
        progress = progress,
        state = LiveEventState.ACTIVE,
        timestampMillis = 1,
    )
}
