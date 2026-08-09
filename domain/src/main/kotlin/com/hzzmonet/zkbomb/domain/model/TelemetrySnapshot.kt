package com.hzzmonet.zkbomb.domain.model

/**
 * Standard Telemetry Snapshot model in pure JVM domain layer.
 */
data class TelemetrySnapshot(
    val sampledAtElapsedRealtimeMillis: Long,
    val uptimeMillis: Long,
    val totalMemoryBytes: Long,
    val availableMemoryBytes: Long,
    val lowMemoryThresholdBytes: Long = 0L,
    val isLowMemory: Boolean = false,
    val pssTotalBytes: Long? = null,
    val rssTotalBytes: Long? = null,
    val zramTotalBytes: Long? = null,
    val zramUsedBytes: Long? = null,
    val zramCompressionRatio: Double? = null,
    val cpuTotalTicks: Long? = null,
    val cpuIdleTicks: Long? = null,
    val cpuUsagePercent: Double? = null,
    val thermalStatus: Int? = null,
    val batteryPercent: Int? = null,
    val isBatteryCharging: Boolean? = null,
    val batteryTemperatureDeciCelsius: Int? = null,
    val batteryVoltageMillivolts: Int? = null,
    val batteryCurrentMicroamps: Long? = null,
    val estimatedPowerWatts: Double? = null,
    val totalRxBytes: Long? = null,
    val totalTxBytes: Long? = null,
) {
    val usedMemoryBytes: Long
        get() = (totalMemoryBytes - availableMemoryBytes).coerceAtLeast(0L)

    val memoryUsagePercentage: Double
        get() = if (totalMemoryBytes > 0) {
            (usedMemoryBytes.toDouble() / totalMemoryBytes.toDouble()) * 100.0
        } else {
            0.0
        }

    fun isValid(): Boolean = sampledAtElapsedRealtimeMillis > 0 && totalMemoryBytes >= 0

    companion object {
        val EMPTY = TelemetrySnapshot(
            sampledAtElapsedRealtimeMillis = 0L,
            uptimeMillis = 0L,
            totalMemoryBytes = 0L,
            availableMemoryBytes = 0L,
        )
    }
}
