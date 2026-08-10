package com.hzzmonet.zkbomb.data

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import com.hzzmonet.zkbomb.preview.PreviewData

/**
 * What the screens read.
 *
 * One place decides, per field, between three outcomes:
 *  - a **live** measurement from the device,
 *  - a clearly-labelled fallback when a live collector is unavailable,
 *  - `null`, meaning *this device cannot report it* — the screen then shows an
 *    unsupported state rather than a number.
 *
 * Screens never mix the three themselves, so a metric can never quietly become
 * a sample on a real device.
 */
@Stable
class SystemView(
    private val live: LiveSnapshot,
    val history: LiveHistory,
) {
    val isLive: Boolean get() = live.isLive

    // ---------------------------------------------------------------- CPU

    /** Null when the platform blocks `/proc/stat` for unprivileged apps. */
    val cpuPercent: Int?
        get() = when {
            live.isLive -> live.cpuPercent?.toInt()
            else -> PreviewData.CPU_PERCENT
        }

    val cpuUnavailableReason: String?
        get() = if (live.isLive && live.cpuPercent == null) {
            live.cpuPercentUnavailableReason
                ?: "Total CPU needs privileged access on this build"
        } else {
            null
        }

    val coreCount: Int
        get() = if (live.isLive) live.cpuCoreCount ?: 0 else PreviewData.coreLoads.size

    val coreFreqsKhz: List<Int?>?
        get() = if (live.isLive) live.coreFreqsKhz else PreviewData.coreFreqs.map { it * 1000 }

    val coreMaxFreqsKhz: List<Int?>?
        get() = if (live.isLive) live.coreMaxFreqsKhz else PreviewData.coreMaxFreqs.map { it * 1000 }

    /** Per-core load needs privileged sampling; only the sample build has it. */
    val coreLoads: List<Float>?
        get() = if (live.isLive) null else PreviewData.coreLoads

    // ------------------------------------------------------------- memory

    val ramUsedGb: String?
        get() = if (live.isLive) formatBytesGb(live.ramUsedBytes) else PreviewData.RAM_USED_GB

    val ramTotalGb: String?
        get() = if (live.isLive) formatBytesGb(live.ramTotalBytes) else PreviewData.RAM_TOTAL_GB

    val ramAvailableGb: String?
        get() = if (live.isLive) formatBytesGb(live.ramAvailableBytes) else PreviewData.RAM_AVAILABLE_GB

    val ramPercent: Int?
        get() = if (live.isLive) live.ramPercent else PreviewData.RAM_PERCENT

    val lowMemory: Boolean? get() = live.lowMemory

    // ------------------------------------------------------------ battery

    val batteryPercent: Int?
        get() = if (live.isLive) live.batteryPercent else PreviewData.BATTERY_PERCENT

    val batteryTempC: String?
        get() = if (live.isLive) {
            formatOneDecimalValue(live.batteryTempC)
        } else {
            PreviewData.BATTERY_TEMP
        }

    val batteryVoltageV: String?
        get() = if (live.isLive) formatOneDecimalValue(live.batteryVoltageV) else "4.1"

    val batteryCurrentMa: Int?
        get() = if (live.isLive) live.batteryCurrentMa else -1284

    val batteryStatus: String?
        get() = if (live.isLive) live.batteryStatus else "Discharging"

    val batteryHealth: String?
        get() = if (live.isLive) live.batteryHealth else "Good"

    val batteryTechnology: String?
        get() = if (live.isLive) live.batteryTechnology else "Li-poly"

    val batteryCycleCount: Int?
        get() = if (live.isLive) live.batteryCycleCount else 184

    val chargeCounterMah: Int?
        get() = if (live.isLive) live.chargeCounterMah else 4721

    val powerWatts: String?
        get() = if (live.isLive) {
            formatOneDecimalValue(live.powerWatts)
        } else {
            PreviewData.POWER_WATTS
        }

    // ------------------------------------------------------------ thermal

    /** Framework thermal *status*, not a sensor reading — apps cannot read /sys. */
    val thermalStatus: String?
        get() = if (live.isLive) live.thermalStatus else "None"

    /** The only temperature an unprivileged app really has is the battery's. */
    val socTempC: String?
        get() = if (live.isLive) null else PreviewData.SOC_TEMP

    // -------------------------------------------------------------- misc

    val fps: Int? get() = if (live.isLive) live.fps else PreviewData.FPS

    val uptime: String? get() = if (live.isLive) formatUptime(live.uptimeMillis) else "2d 4h 11m"

    val netDown: String?
        get() = if (live.isLive) formatRate(live.netDownBps) else "12.4 MB/s"

    val netUp: String?
        get() = if (live.isLive) formatRate(live.netUpBps) else "2.1 MB/s"

    val ownPssMb: Int? get() = live.ownPssKb?.let { it / 1024 }

    val ownThreads: Int? get() = live.ownThreads

    /** GPU has no unprivileged path at all — always unsupported on a device. */
    val gpuPercent: Int?
        get() = if (live.isLive) null else PreviewData.GPU_PERCENT
}

/** Rolling window of samples for the trend lines. */
@Stable
class LiveHistory(private val capacity: Int = 20) {
    val cpu = mutableStateListOf<Float>()
    val ram = mutableStateListOf<Float>()
    val temperature = mutableStateListOf<Float>()

    fun record(snapshot: LiveSnapshot) {
        if (!snapshot.isLive) return
        snapshot.cpuPercent?.let { push(cpu, it) }
        snapshot.ramPercent?.let { push(ram, it.toFloat()) }
        snapshot.batteryTempC?.let { push(temperature, it) }
    }

    private fun push(target: MutableList<Float>, value: Float) {
        target.add(value)
        while (target.size > capacity) target.removeAt(0)
    }
}

@Composable
fun rememberSystemView(intervalMillis: Long = 2000L): SystemView {
    val live = rememberLiveSnapshot(intervalMillis)
    val history = remember { LiveHistory() }
    LaunchedEffect(live) { history.record(live) }
    return remember(live, history) { SystemView(live, history) }
}

// Trend for a metric. On a live device this is the *real* rolling history and
// nothing else: it may be empty or short at first (the sparkline simply draws
// nothing until it has two points), but it is never backfilled from a sample —
// that would have shown fabricated telemetry for the first seconds after launch.
// The PreviewData branch exists only for the non-live @Preview surface.
fun SystemView.cpuTrend(): List<Float> =
    if (isLive) history.cpu.toList() else PreviewData.cpuHistory

fun SystemView.ramTrend(): List<Float> =
    if (isLive) history.ram.toList() else PreviewData.gpuHistory

fun SystemView.temperatureTrend(): List<Float> =
    if (isLive) history.temperature.toList() else PreviewData.thermalHistory
