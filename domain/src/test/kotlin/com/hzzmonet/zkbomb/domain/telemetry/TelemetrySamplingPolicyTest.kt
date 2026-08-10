package com.hzzmonet.zkbomb.domain.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetrySamplingPolicyTest {

    @Test
    fun `cost matrix is exact for every interval and cost class`() {
        val expected = mapOf(
            TelemetrySamplingInterval.MS_500 to setOf(
                TelemetryCostClass.CHEAP,
            ),
            TelemetrySamplingInterval.MS_1000 to setOf(
                TelemetryCostClass.CHEAP,
                TelemetryCostClass.MODERATE,
            ),
            TelemetrySamplingInterval.MS_2000 to setOf(
                TelemetryCostClass.CHEAP,
                TelemetryCostClass.MODERATE,
                TelemetryCostClass.EXPENSIVE,
            ),
            TelemetrySamplingInterval.MS_5000 to TelemetryCostClass.entries.toSet(),
        )

        TelemetrySamplingInterval.entries.forEach { interval ->
            assertEquals(expected.getValue(interval), TelemetrySamplingPolicy.allowedCostsFor(interval))
            TelemetryCostClass.entries.forEach { cost ->
                assertEquals(
                    "$interval x $cost",
                    cost in expected.getValue(interval),
                    TelemetrySamplingPolicy.allows(interval, cost),
                )
            }
        }
    }

    @Test
    fun `only exact supported millisecond values produce typed intervals`() {
        TelemetrySamplingInterval.entries.forEach { interval ->
            assertEquals(interval, TelemetrySamplingInterval.fromMillis(interval.millis))
        }
    }

    @Test
    fun `unsupported millisecond values are rejected instead of clamped`() {
        listOf(0, 100, 999, 1_500, 10_000).forEach { millis ->
            assertNull("millis=$millis", TelemetrySamplingInterval.fromMillis(millis))
        }
    }

    @Test
    fun `fastest interval permits only cheap metrics`() {
        val interval = TelemetrySamplingInterval.MS_500

        assertTrue(TelemetrySamplingPolicy.allows(interval, TelemetryCostClass.CHEAP))
        assertFalse(TelemetrySamplingPolicy.allows(interval, TelemetryCostClass.MODERATE))
        assertFalse(TelemetrySamplingPolicy.allows(interval, TelemetryCostClass.EXPENSIVE))
        assertFalse(TelemetrySamplingPolicy.allows(interval, TelemetryCostClass.PLATFORM_LIMITED))
    }
}
