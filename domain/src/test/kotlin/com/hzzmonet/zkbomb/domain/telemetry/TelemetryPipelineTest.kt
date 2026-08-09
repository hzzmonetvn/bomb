package com.hzzmonet.zkbomb.domain.telemetry

import com.hzzmonet.zkbomb.domain.model.TelemetrySnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class TelemetryPipelineTest {

    @Test
    fun `computeDeltas calculates CPU usage percentage from ticks`() {
        val prev = TelemetrySnapshot(
            sampledAtElapsedRealtimeMillis = 1000L,
            uptimeMillis = 1000L,
            totalMemoryBytes = 8_000_000_000L,
            availableMemoryBytes = 4_000_000_000L,
            cpuTotalTicks = 1000L,
            cpuIdleTicks = 800L,
        )

        val current = TelemetrySnapshot(
            sampledAtElapsedRealtimeMillis = 2000L,
            uptimeMillis = 2000L,
            totalMemoryBytes = 8_000_000_000L,
            availableMemoryBytes = 4_000_000_000L,
            cpuTotalTicks = 1100L, // 100 total ticks delta
            cpuIdleTicks = 850L,  // 50 idle ticks delta -> 50 active ticks -> 50% usage
        )

        val result = TelemetryRepository.computeDeltas(prev, current)

        assertNotNull(result.cpuUsagePercent)
        assertEquals(50.0, result.cpuUsagePercent!!, 0.01)
    }

    @Test
    fun `computeDeltas calculates estimated power wattage from voltage and current`() {
        val snapshot = TelemetrySnapshot(
            sampledAtElapsedRealtimeMillis = 1000L,
            uptimeMillis = 1000L,
            totalMemoryBytes = 8_000_000_000L,
            availableMemoryBytes = 4_000_000_000L,
            batteryVoltageMillivolts = 4000, // 4.0 V
            batteryCurrentMicroamps = -500_000, // 0.5 A discharge -> P = 4.0 * 0.5 = 2.0 W
        )

        val result = TelemetryRepository.computeDeltas(null, snapshot)

        assertNotNull(result.estimatedPowerWatts)
        assertEquals(2.0, result.estimatedPowerWatts!!, 0.01)
    }

    @Test
    fun `subscriber-aware telemetry repository does not poll without active collectors`() = runTest {
        val collectCount = AtomicInteger(0)
        val testCollector = TelemetryCollector {
            collectCount.incrementAndGet()
            TelemetrySnapshot(
                sampledAtElapsedRealtimeMillis = System.currentTimeMillis(),
                uptimeMillis = 100L,
                totalMemoryBytes = 8_000_000_000L,
                availableMemoryBytes = 4_000_000_000L,
            )
        }

        val repository = TelemetryRepository(
            collector = testCollector,
            scope = backgroundScope,
            initialPollIntervalMs = 100L,
            stopTimeoutMs = 200L,
        )

        // Without subscribers, collectCount should stay 0
        advanceTimeBy(500L)
        assertEquals(0, collectCount.get())

        // Collect using subscriber flow
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.telemetryState.collect {}
        }

        advanceTimeBy(350L)
        assertTrue(collectCount.get() >= 2)

        // Cancel subscriber
        job.cancel()
        advanceTimeBy(300L)
        val countAfterCancel = collectCount.get()

        // Wait past stopTimeoutMs
        advanceTimeBy(500L)
        // No new polls should occur after stopTimeoutMs
        assertEquals(countAfterCancel, collectCount.get())
    }
}
