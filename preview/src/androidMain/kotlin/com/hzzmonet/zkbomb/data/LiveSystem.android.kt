package com.hzzmonet.zkbomb.data

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.view.Choreographer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

@Composable
actual fun isLiveBuild(): Boolean = true

@Composable
actual fun rememberLiveSnapshot(intervalMillis: Long): LiveSnapshot {
    val context = LocalContext.current
    val fps = rememberFps()
    var snapshot by remember { mutableStateOf(LiveSnapshot(isLive = true)) }

    // One sampler, tied to the composition: leaving the screen stops it.
    LaunchedEffect(intervalMillis) {
        var lastRx = TrafficStats.getTotalRxBytes()
        var lastTx = TrafficStats.getTotalTxBytes()
        var lastCpu: CpuTotals? = readCpuTotals()
        var lastAt = SystemClock.elapsedRealtime()

        while (true) {
            val sampled = withContext(Dispatchers.IO) {
                val now = SystemClock.elapsedRealtime()
                val seconds = ((now - lastAt).coerceAtLeast(1L)) / 1000f

                val rx = TrafficStats.getTotalRxBytes()
                val tx = TrafficStats.getTotalTxBytes()
                val downBps = deltaRate(rx, lastRx, seconds)
                val upBps = deltaRate(tx, lastTx, seconds)
                lastRx = rx
                lastTx = tx

                val cpu = readCpuTotals()
                val cpuPercent = computeCpuPercent(lastCpu, cpu)
                if (cpu != null) lastCpu = cpu
                lastAt = now

                buildSnapshot(
                    context = context,
                    cpuPercent = cpuPercent,
                    cpuUnavailable = if (cpu == null) {
                        "/proc/stat is not readable by an unprivileged app on this build"
                    } else {
                        null
                    },
                    downBps = downBps,
                    upBps = upBps,
                )
            }
            snapshot = sampled.copy(fps = fps)
            delay(intervalMillis)
        }
    }

    return snapshot.copy(fps = fps)
}

private fun deltaRate(current: Long, previous: Long, seconds: Float): Long? {
    if (current == TrafficStats.UNSUPPORTED.toLong() || previous == TrafficStats.UNSUPPORTED.toLong()) return null
    if (current < previous) return null
    return ((current - previous) / seconds).toLong()
}

private fun buildSnapshot(
    context: Context,
    cpuPercent: Float?,
    cpuUnavailable: String?,
    downBps: Long?,
    upBps: Long?,
): LiveSnapshot {
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val memoryInfo = ActivityManager.MemoryInfo()
    activityManager.getMemoryInfo(memoryInfo)

    val batteryIntent = runCatching {
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }.getOrNull()
    val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

    val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)?.takeIf { it >= 0 }
    val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1)?.takeIf { it > 0 }
    val tempTenth = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        ?.takeIf { it != Int.MIN_VALUE }
    val voltageMv = batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)?.takeIf { it > 0 }
    val currentUa = batteryManager
        ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        ?.takeIf { it != Int.MIN_VALUE && it != 0 }
    val chargeCounterUah = batteryManager
        ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        ?.takeIf { it > 0 }
    val cycleCount = if (Build.VERSION.SDK_INT >= 34) {
        batteryIntent?.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1)?.takeIf { it > 0 }
    } else {
        null
    }

    val thermal = (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
        ?.let { power ->
            runCatching { thermalLabel(power.currentThermalStatus) }.getOrNull()
        }

    val memoryProfile = runCatching {
        Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
    }.getOrNull()

    return LiveSnapshot(
        isLive = true,
        ramTotalBytes = memoryInfo.totalMem.takeIf { it > 0 },
        ramAvailableBytes = memoryInfo.availMem.takeIf { it > 0 },
        lowMemory = memoryInfo.lowMemory,
        batteryPercent = if (level != null && scale != null) level * 100 / scale else null,
        batteryTempC = tempTenth?.let { it / 10f },
        batteryVoltageV = voltageMv?.let { if (it > 1000) it / 1000f else it.toFloat() },
        batteryCurrentMa = currentUa?.let { it / 1000 },
        batteryStatus = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)?.let(::statusLabel),
        batteryHealth = batteryIntent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)?.let(::healthLabel),
        batteryTechnology = batteryIntent?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY),
        batteryCycleCount = cycleCount,
        chargeCounterMah = chargeCounterUah?.let { it / 1000 },
        thermalStatus = thermal,
        cpuCoreCount = Runtime.getRuntime().availableProcessors(),
        coreFreqsKhz = readCoreFreqs("scaling_cur_freq"),
        coreMaxFreqsKhz = readCoreFreqs("cpuinfo_max_freq"),
        cpuPercent = cpuPercent,
        cpuPercentUnavailableReason = cpuUnavailable,
        uptimeMillis = SystemClock.elapsedRealtime(),
        netDownBps = downBps,
        netUpBps = upBps,
        ownPssKb = memoryProfile?.totalPss?.takeIf { it > 0 },
        ownThreads = runCatching {
            File("/proc/${Process.myPid()}/status").readLines()
                .firstOrNull { it.startsWith("Threads:") }
                ?.filter { it.isDigit() }
                ?.toIntOrNull()
        }.getOrNull(),
    )
}

// ------------------------------------------------------------------ CPU

private data class CpuTotals(val idle: Long, val total: Long)

/**
 * `/proc/stat` is readable on some builds and blocked on others. Failure is a
 * capability answer, not an error to swallow — the UI says CPU is unavailable.
 */
private fun readCpuTotals(): CpuTotals? = runCatching {
    val line = File("/proc/stat").bufferedReader().use { it.readLine() } ?: return@runCatching null
    if (!line.startsWith("cpu ")) return@runCatching null
    val parts = line.split(" ").filter { it.isNotBlank() }.drop(1).mapNotNull { it.toLongOrNull() }
    if (parts.size < 4) return@runCatching null
    val idle = parts[3] + (parts.getOrNull(4) ?: 0L)
    CpuTotals(idle = idle, total = parts.sum())
}.getOrNull()

private fun computeCpuPercent(previous: CpuTotals?, current: CpuTotals?): Float? {
    if (previous == null || current == null) return null
    val totalDelta = current.total - previous.total
    val idleDelta = current.idle - previous.idle
    // Counter reset or wrap: report nothing rather than a spike.
    if (totalDelta <= 0 || idleDelta < 0) return null
    return ((totalDelta - idleDelta) * 100f / totalDelta).coerceIn(0f, 100f)
}

private fun readCoreFreqs(node: String): List<Int?>? {
    val cores = Runtime.getRuntime().availableProcessors()
    if (cores <= 0) return null
    val values = (0 until cores).map { core ->
        runCatching {
            File("/sys/devices/system/cpu/cpu$core/cpufreq/$node").readText().trim().toIntOrNull()
        }.getOrNull()
    }
    return if (values.all { it == null }) null else values
}

private fun thermalLabel(status: Int): String = when (status) {
    PowerManager.THERMAL_STATUS_NONE -> "None"
    PowerManager.THERMAL_STATUS_LIGHT -> "Light"
    PowerManager.THERMAL_STATUS_MODERATE -> "Moderate"
    PowerManager.THERMAL_STATUS_SEVERE -> "Severe"
    PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
    PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
    PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutdown"
    else -> "Unknown"
}

private fun statusLabel(status: Int): String = when (status) {
    BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
    BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
    BatteryManager.BATTERY_STATUS_FULL -> "Full"
    BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
    else -> "Unknown"
}

private fun healthLabel(health: Int): String = when (health) {
    BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
    BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
    BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
    BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
    BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
    else -> "Unknown"
}

// ------------------------------------------------------------------ FPS

/**
 * Frame rate measured from Choreographer.
 *
 * This is Bomb's own draw rate, which is the only frame rate an unprivileged
 * app can observe. A game's FPS needs `dumpsys gfxinfo` and the DUMP permission.
 */
@Composable
private fun rememberFps(): Int? {
    var fps by remember { mutableStateOf<Int?>(null) }
    DisposableEffect(Unit) {
        val choreographer = Choreographer.getInstance()
        var frames = 0
        var windowStart = 0L
        var running = true
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!running) return
                if (windowStart == 0L) windowStart = frameTimeNanos
                frames++
                val elapsed = frameTimeNanos - windowStart
                if (elapsed >= 1_000_000_000L) {
                    fps = (frames * 1_000_000_000L / elapsed).toInt()
                    frames = 0
                    windowStart = frameTimeNanos
                }
                choreographer.postFrameCallback(this)
            }
        }
        choreographer.postFrameCallback(callback)
        onDispose {
            running = false
            choreographer.removeFrameCallback(callback)
        }
    }
    return fps
}

// ------------------------------------------------------------------ apps

@Composable
actual fun rememberInstalledApps(): List<LiveApp>? {
    val context = LocalContext.current
    return produceState<List<LiveApp>?>(initialValue = null, context) {
        value = withContext(Dispatchers.IO) {
            runCatching { loadApps(context) }.getOrNull()
        }
    }.value
}

private fun loadApps(context: Context): List<LiveApp> {
    val pm = context.packageManager
    val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
    return installed.mapNotNull { info ->
        runCatching {
            val packageInfo = runCatching {
                pm.getPackageInfo(info.packageName, PackageManager.GET_PERMISSIONS)
            }.getOrNull()
            LiveApp(
                name = pm.getApplicationLabel(info).toString(),
                packageName = info.packageName,
                isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                    (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
                enabled = info.enabled,
                versionName = packageInfo?.versionName,
                uid = info.uid,
                targetSdk = info.targetSdkVersion,
                minSdk = if (Build.VERSION.SDK_INT >= 24) info.minSdkVersion else null,
                firstInstallTime = packageInfo?.firstInstallTime,
                lastUpdateTime = packageInfo?.lastUpdateTime,
                apkPath = info.sourceDir,
                permissionCount = packageInfo?.requestedPermissions?.size,
            )
        }.getOrNull()
    }.sortedBy { it.name.lowercase() }
}

// Decoded launcher icons, kept process-wide so a row scrolling back into view
// paints from memory instead of decoding the PackageManager drawable again — the
// repeated decode on a recycled LazyColumn row is the main source of list scroll
// jank. Bounded by byte size (~8 MB) so a large app list cannot grow it without
// limit; the least-recently-shown icons are evicted first.
private val appIconCache = object : android.util.LruCache<String, ImageBitmap>(8 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
}

@Composable
actual fun rememberAppIcon(packageName: String): ImageBitmap? {
    val context = LocalContext.current
    // A cache hit seeds the initial value, so a recycled row that already loaded
    // this icon paints it on the first frame with no IO and no recomposition wait.
    val cached = appIconCache.get(packageName)
    return produceState<ImageBitmap?>(initialValue = cached, packageName) {
        appIconCache.get(packageName)?.let { value = it; return@produceState }
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager.getApplicationIcon(packageName).toImageBitmap()
            }.getOrNull()
        }?.also { appIconCache.put(packageName, it) }
    }.value
}

private fun Drawable.toImageBitmap(size: Int = 96): ImageBitmap {
    if (this is BitmapDrawable && bitmap != null) {
        return bitmap.asImageBitmap()
    }
    val width = if (intrinsicWidth > 0) minOf(intrinsicWidth, size) else size
    val height = if (intrinsicHeight > 0) minOf(intrinsicHeight, size) else size
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    return bitmap.asImageBitmap()
}
