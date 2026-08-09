package com.hzzmonet.zkbomb.domain.telemetry

import com.hzzmonet.zkbomb.domain.model.TelemetrySnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlin.math.abs

/**
 * Central subscriber-aware Telemetry Repository.
 *
 * Emits [TelemetrySnapshot] via [telemetryState].
 * Polling automatically pauses when there are no active subscribers (SharingStarted.WhileSubscribed).
 */
class TelemetryRepository(
    private val collector: TelemetryCollector,
    private val scope: CoroutineScope,
    initialPollIntervalMs: Long = 1000L,
    private val stopTimeoutMs: Long = 5000L,
) {

    private val _pollIntervalMs = MutableStateFlow(initialPollIntervalMs.coerceAtLeast(100L))
    val pollIntervalMs: StateFlow<Long> = _pollIntervalMs.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private var previousSnapshot: TelemetrySnapshot? = null

    val telemetryState: StateFlow<TelemetrySnapshot> = flow {
        while (true) {
            if (!_isPaused.value) {
                val raw = collector.collect()
                val snapshot = computeDeltas(previousSnapshot, raw)
                previousSnapshot = snapshot
                emit(snapshot)
            }
            delay(_pollIntervalMs.value)
        }
    }.stateIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMs),
        initialValue = TelemetrySnapshot.EMPTY,
    )

    fun setPollInterval(intervalMs: Long) {
        _pollIntervalMs.value = intervalMs.coerceAtLeast(100L)
    }

    fun setPaused(paused: Boolean) {
        _isPaused.value = paused
    }

    companion object {
        fun computeDeltas(prev: TelemetrySnapshot?, current: TelemetrySnapshot): TelemetrySnapshot {
            var cpuUsagePercent: Double? = current.cpuUsagePercent
            if (prev != null && prev.cpuTotalTicks != null && prev.cpuIdleTicks != null &&
                current.cpuTotalTicks != null && current.cpuIdleTicks != null
            ) {
                val totalDelta = current.cpuTotalTicks - prev.cpuTotalTicks
                val idleDelta = current.cpuIdleTicks - prev.cpuIdleTicks
                if (totalDelta > 0) {
                    val activeDelta = (totalDelta - idleDelta).coerceAtLeast(0)
                    cpuUsagePercent = (activeDelta.toDouble() / totalDelta.toDouble()) * 100.0
                }
            }

            var estimatedWatts: Double? = current.estimatedPowerWatts
            if (estimatedWatts == null &&
                current.batteryVoltageMillivolts != null &&
                current.batteryCurrentMicroamps != null
            ) {
                val v = current.batteryVoltageMillivolts.toDouble() / 1000.0
                val i = abs(current.batteryCurrentMicroamps.toDouble()) / 1_000_000.0
                estimatedWatts = v * i
            }

            return current.copy(
                cpuUsagePercent = cpuUsagePercent,
                estimatedPowerWatts = estimatedWatts,
            )
        }
    }
}
