package com.hzzmonet.zkbomb.api

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/** A bounded, point-in-time view of one process known to ActivityManager. */
@Parcelize
data class ProcessInfo(
    val pid: Int,
    val uid: Int,
    val userId: Int,
    val processName: String,
    val packageNames: List<String>,
    val importance: Int,
    val importanceReasonCode: Int,
    val foreground: Boolean,
    /** Proportional set size, or null when the framework withheld it. */
    val pssBytes: Long?,
    /** Private dirty memory, or null when unavailable. */
    val privateDirtyBytes: Long?,
    /** Resident set size, or null when unavailable. */
    val rssBytes: Long? = null,
    /** Cumulative kernel CPU ticks, or null when procfs access is unavailable. */
    val cpuTimeTicks: Long?,
    val threadCount: Int?,
    /** Process start time in kernel ticks since boot, or null when unavailable. */
    val startTimeTicks: Long?,
    /** Category name classification, or null when unavailable. */
    val categoryName: String? = null,
) : Parcelable

/**
 * A process list sampled once by the privileged service.
 *
 * [truncated] is explicit because Binder replies are deliberately bounded. The
 * service never risks a transaction-too-large crash merely because a device is
 * running an unusual number of isolated processes.
 */
@Parcelize
data class ProcessSnapshot(
    val sampledAtElapsedRealtimeMillis: Long,
    val processes: List<ProcessInfo>,
    val truncated: Boolean,
) : Parcelable

/** Reliable system-wide telemetry available through stable Android APIs. */
@Parcelize
data class SystemTelemetrySnapshot(
    val sampledAtElapsedRealtimeMillis: Long,
    val uptimeMillis: Long,
    val totalMemoryBytes: Long,
    val availableMemoryBytes: Long,
    val lowMemoryThresholdBytes: Long,
    val lowMemory: Boolean,
    /** Aggregate CPU counters from /proc/stat, or null when policy blocks it. */
    val cpuTotalTicks: Long?,
    val cpuIdleTicks: Long?,
    /** PowerManager thermal status, or null when the service is absent. */
    val thermalStatus: Int?,
    val batteryPercent: Int?,
    val batteryCharging: Boolean?,
    val batteryTemperatureDeciCelsius: Int?,
    val batteryVoltageMillivolts: Int?,
    val batteryCurrentMicroamps: Long?,
    val totalRxBytes: Long?,
    val totalTxBytes: Long?,
) : Parcelable
