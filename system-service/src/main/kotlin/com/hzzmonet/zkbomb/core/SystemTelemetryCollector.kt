package com.hzzmonet.zkbomb.core

import com.hzzmonet.zkbomb.domain.model.TelemetrySnapshot
import com.hzzmonet.zkbomb.domain.telemetry.TelemetryCollector
import kotlin.math.abs

/**
 * Concrete Android/system implementation of [TelemetryCollector].
 */
class SystemTelemetryCollector(
    private val processTelemetryBackend: ProcessTelemetryBackend,
    private val zramReader: ZramReader = ZramReader(),
) : TelemetryCollector {

    override suspend fun collect(): TelemetrySnapshot {
        val sysSnapshot = processTelemetryBackend.systemTelemetrySnapshot()
        val zramMmStat = zramReader.mmStat()
        val zramDisksize = zramReader.disksizeBytes()

        val voltage = sysSnapshot.batteryVoltageMillivolts
        val current = sysSnapshot.batteryCurrentMicroamps
        val wattage = if (voltage != null && current != null) {
            val v = voltage.toDouble() / 1000.0
            val i = abs(current.toDouble()) / 1_000_000.0
            v * i
        } else null

        return TelemetrySnapshot(
            sampledAtElapsedRealtimeMillis = sysSnapshot.sampledAtElapsedRealtimeMillis,
            uptimeMillis = sysSnapshot.uptimeMillis,
            totalMemoryBytes = sysSnapshot.totalMemoryBytes,
            availableMemoryBytes = sysSnapshot.availableMemoryBytes,
            lowMemoryThresholdBytes = sysSnapshot.lowMemoryThresholdBytes,
            isLowMemory = sysSnapshot.lowMemory,
            pssTotalBytes = null,
            rssTotalBytes = null,
            zramTotalBytes = zramDisksize,
            zramUsedBytes = zramMmStat?.memUsedTotal,
            zramCompressionRatio = zramMmStat?.compressionRatio,
            cpuTotalTicks = sysSnapshot.cpuTotalTicks,
            cpuIdleTicks = sysSnapshot.cpuIdleTicks,
            thermalStatus = sysSnapshot.thermalStatus,
            batteryPercent = sysSnapshot.batteryPercent,
            isBatteryCharging = sysSnapshot.batteryCharging,
            batteryTemperatureDeciCelsius = sysSnapshot.batteryTemperatureDeciCelsius,
            batteryVoltageMillivolts = sysSnapshot.batteryVoltageMillivolts,
            batteryCurrentMicroamps = sysSnapshot.batteryCurrentMicroamps,
            estimatedPowerWatts = wattage,
            totalRxBytes = sysSnapshot.totalRxBytes,
            totalTxBytes = sysSnapshot.totalTxBytes,
        )
    }
}
