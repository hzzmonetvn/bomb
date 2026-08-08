package com.hzzmonet.zkbomb.data

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap

/**
 * What Bomb can actually measure in this build, right now.
 *
 * Every field is nullable and null means one thing only: **this device/build
 * cannot provide it**, so the UI shows an unsupported state instead of a number.
 * Nothing here is estimated or filled in from a sample.
 *
 * An unprivileged APK gets a real but limited set — RAM, battery, thermal
 * status, its own frame rate, total traffic. Global process CPU, per-app
 * traffic, GPU and sensor temperatures need the ROM or root backends described
 * in docs/ROM_INTEGRATION.md.
 */
@Immutable
data class LiveSnapshot(
    val isLive: Boolean = false,

    // memory
    val ramTotalBytes: Long? = null,
    val ramAvailableBytes: Long? = null,
    val lowMemory: Boolean? = null,

    // battery
    val batteryPercent: Int? = null,
    val batteryTempC: Float? = null,
    val batteryVoltageV: Float? = null,
    val batteryCurrentMa: Int? = null,
    val batteryStatus: String? = null,
    val batteryHealth: String? = null,
    val batteryTechnology: String? = null,
    val batteryCycleCount: Int? = null,
    val chargeCounterMah: Int? = null,

    // thermal / cpu
    val thermalStatus: String? = null,
    val cpuCoreCount: Int? = null,
    val coreFreqsKhz: List<Int?>? = null,
    val coreMaxFreqsKhz: List<Int?>? = null,
    val cpuPercent: Float? = null,
    val cpuPercentUnavailableReason: String? = null,

    // app-side
    val fps: Int? = null,
    val uptimeMillis: Long? = null,

    // network (device totals — per-app needs privilege)
    val netDownBps: Long? = null,
    val netUpBps: Long? = null,

    // own process
    val ownPssKb: Int? = null,
    val ownThreads: Int? = null,
) {
    val ramUsedBytes: Long?
        get() {
            val total = ramTotalBytes ?: return null
            val avail = ramAvailableBytes ?: return null
            return total - avail
        }

    val ramPercent: Int?
        get() {
            val total = ramTotalBytes ?: return null
            val used = ramUsedBytes ?: return null
            if (total <= 0) return null
            return ((used * 100) / total).toInt()
        }

    /** Estimated draw, from current × voltage. Null unless both are real. */
    val powerWatts: Float?
        get() {
            val current = batteryCurrentMa ?: return null
            val volts = batteryVoltageV ?: return null
            return kotlin.math.abs(current / 1000f * volts)
        }
}

/** Live installed-app record. Icons load separately — see [rememberAppIcon]. */
@Immutable
data class LiveApp(
    val name: String,
    val packageName: String,
    val isSystem: Boolean,
    val enabled: Boolean,
    val versionName: String?,
    val uid: Int,
    val targetSdk: Int,
    val minSdk: Int?,
    val firstInstallTime: Long?,
    val lastUpdateTime: Long?,
    val apkPath: String?,
    val permissionCount: Int?,
)

/**
 * Polls the platform every [intervalMillis]. Collection stops when the caller
 * leaves the composition — nothing samples in the background.
 */
@Composable
expect fun rememberLiveSnapshot(intervalMillis: Long = 2000L): LiveSnapshot

/** Null when the platform cannot enumerate packages. */
@Composable
expect fun rememberInstalledApps(): List<LiveApp>?

/** Loads one launcher icon, off the main thread. Null while loading or absent. */
@Composable
expect fun rememberAppIcon(packageName: String): ImageBitmap?

/** True when this build talks to a real device. */
@Composable
expect fun isLiveBuild(): Boolean

// ------------------------------------------------------------------ format

fun formatBytesGb(bytes: Long?): String? {
    if (bytes == null) return null
    val gb = bytes / 1024f / 1024f / 1024f
    val scaled = (gb * 10).toInt()
    return "${scaled / 10}.${scaled % 10}"
}

fun formatBytesMb(bytes: Long?): String? {
    if (bytes == null) return null
    return "${bytes / 1024 / 1024}"
}

fun formatRate(bytesPerSecond: Long?): String? {
    if (bytesPerSecond == null) return null
    val kb = bytesPerSecond / 1024f
    return if (kb >= 1024f) {
        val mb = (kb / 1024f * 10).toInt()
        "${mb / 10}.${mb % 10} MB/s"
    } else {
        "${kb.toInt()} KB/s"
    }
}

fun formatUptime(millis: Long?): String? {
    if (millis == null) return null
    val totalMinutes = millis / 60_000
    val days = totalMinutes / (60 * 24)
    val hours = (totalMinutes / 60) % 24
    val minutes = totalMinutes % 60
    return when {
        days > 0 -> "${days}d ${hours}h ${minutes}m"
        hours > 0 -> "${hours}h ${minutes}m"
        else -> "${minutes}m"
    }
}

fun formatOneDecimalValue(value: Float?): String? {
    if (value == null) return null
    val scaled = (value * 10).toInt()
    return "${scaled / 10}.${scaled % 10}"
}
