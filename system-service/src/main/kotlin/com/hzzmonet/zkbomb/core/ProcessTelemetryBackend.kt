package com.hzzmonet.zkbomb.core

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import com.hzzmonet.zkbomb.api.ProcessInfo
import com.hzzmonet.zkbomb.api.ProcessSnapshot
import com.hzzmonet.zkbomb.api.SystemTelemetrySnapshot
import com.hzzmonet.zkbomb.domain.model.ProcessClassifier
import java.io.File

/**
 * Read-only Task Manager and Stats Core backend.
 *
 * Process discovery and PSS come from ActivityManager, where REAL_GET_TASKS is
 * checked by system_server. Procfs is only used for optional counters; a denial
 * turns those individual fields into null and never makes the entire snapshot
 * fail. This keeps the API truthful across kernels and SELinux policy versions.
 */
class ProcessTelemetryBackend(
    private val context: Context,
    private val activityManager: ActivityManager? =
        context.getSystemService(ActivityManager::class.java),
    private val proc: ProcStatReader = ProcStatReader(),
) {

    fun processSnapshot(): ProcessSnapshot {
        val all = runningProcesses()
        val selected = all.take(MAX_PROCESSES)
        val memoryByPid = readMemory(selected.map { it.pid })

        val processes = selected.mapNotNull { running ->
            val name = running.processName?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val stat = proc.readProcess(running.pid)
            val memory = memoryByPid[running.pid]
            val packages = running.pkgList
                ?.asSequence()
                ?.filter { it.isNotBlank() }
                ?.map { it.take(MAX_NAME_LENGTH) }
                ?.distinct()
                ?.take(MAX_PACKAGES_PER_PROCESS)
                ?.toList()
                .orEmpty()
                .ifEmpty {
                    context.packageManager.getPackagesForUid(running.uid)
                        ?.asSequence()
                        ?.map { it.take(MAX_NAME_LENGTH) }
                        ?.take(MAX_PACKAGES_PER_PROCESS)
                        ?.toList()
                        .orEmpty()
                }

            val rssKiB = runCatching {
                memory?.javaClass?.getMethod("getTotalRss")?.invoke(memory) as? Int
            }.getOrNull()

            val category = ProcessClassifier.classify(
                processName = name,
                packageNames = packages,
                uid = running.uid,
            )

            ProcessInfo(
                pid = running.pid,
                uid = running.uid,
                // UserHandle.getUserId is hidden from the public SDK. Android's
                // multi-user UID layout is a stable 100000-wide range.
                userId = running.uid / PER_USER_RANGE,
                processName = name.take(MAX_NAME_LENGTH),
                packageNames = packages,
                importance = running.importance,
                importanceReasonCode = running.importanceReasonCode,
                foreground = running.importance <=
                    ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE,
                pssBytes = memory?.totalPss
                    ?.takeIf { it > 0 }
                    ?.toLong()
                    ?.times(KIBIBYTE),
                privateDirtyBytes = memory?.totalPrivateDirty
                    ?.takeIf { it > 0 }
                    ?.toLong()
                    ?.times(KIBIBYTE),
                rssBytes = rssKiB?.takeIf { it > 0 }?.toLong()?.times(KIBIBYTE),
                cpuTimeTicks = stat?.cpuTimeTicks,
                threadCount = stat?.threadCount,
                startTimeTicks = stat?.startTimeTicks,
                categoryName = category.name,
            )
        }

        return ProcessSnapshot(
            sampledAtElapsedRealtimeMillis = SystemClock.elapsedRealtime(),
            processes = processes,
            truncated = all.size > selected.size,
        )
    }

    /** An actual visibility measurement; seeing only Bomb does not count. */
    fun hasGlobalProcessVisibility(): Boolean =
        runningProcesses().any { it.uid != Process.myUid() }

    /** Per-process CPU/thread data is supported only if another process is readable. */
    fun hasOtherProcessStatAccess(): Boolean =
        runningProcesses().asSequence()
            .filter { it.pid != Process.myPid() }
            .take(PROBE_PROCESS_LIMIT)
            .any { proc.readProcess(it.pid) != null }

    fun hasSystemCpuAccess(): Boolean = proc.readCpuTotals() != null

    fun hasThermalTelemetry(): Boolean =
        context.getSystemService(PowerManager::class.java) != null

    fun hasPowerTelemetry(): Boolean =
        context.getSystemService(BatteryManager::class.java) != null

    @Suppress("DEPRECATION")
    fun systemTelemetrySnapshot(): SystemTelemetrySnapshot {
        val now = SystemClock.elapsedRealtime()
        val memory = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memory)
        val cpu = proc.readCpuTotals()
        val power = context.getSystemService(PowerManager::class.java)
        val battery = context.getSystemService(BatteryManager::class.java)
        val batteryIntent = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        )

        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else null
        val current = battery?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            ?.takeUnless { it == Long.MIN_VALUE }

        return SystemTelemetrySnapshot(
            sampledAtElapsedRealtimeMillis = now,
            uptimeMillis = now,
            totalMemoryBytes = memory.totalMem,
            availableMemoryBytes = memory.availMem,
            lowMemoryThresholdBytes = memory.threshold,
            lowMemory = memory.lowMemory,
            cpuTotalTicks = cpu?.totalTicks,
            cpuIdleTicks = cpu?.idleTicks,
            thermalStatus = power?.currentThermalStatus,
            batteryPercent = percent,
            batteryCharging = battery?.isCharging,
            batteryTemperatureDeciCelsius = batteryIntent
                ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                ?.takeUnless { it == Int.MIN_VALUE },
            batteryVoltageMillivolts = batteryIntent
                ?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Int.MIN_VALUE)
                ?.takeUnless { it == Int.MIN_VALUE },
            batteryCurrentMicroamps = current,
            totalRxBytes = TrafficStats.getTotalRxBytes()
                .takeUnless { it == TrafficStats.UNSUPPORTED.toLong() },
            totalTxBytes = TrafficStats.getTotalTxBytes()
                .takeUnless { it == TrafficStats.UNSUPPORTED.toLong() },
        )
    }

    private fun runningProcesses(): List<ActivityManager.RunningAppProcessInfo> =
        runCatching { activityManager?.runningAppProcesses.orEmpty() }
            .getOrDefault(emptyList())
            .asSequence()
            .filter { it.pid > 0 && it.uid >= 0 }
            .distinctBy { it.pid }
            .sortedWith(compareBy({ it.importance }, { it.pid }))
            .toList()

    private fun readMemory(pids: List<Int>): Map<Int, android.os.Debug.MemoryInfo> {
        val manager = activityManager ?: return emptyMap()
        val result = LinkedHashMap<Int, android.os.Debug.MemoryInfo>()
        pids.chunked(MEMORY_BATCH_SIZE).forEach { batch ->
            val values = runCatching { manager.getProcessMemoryInfo(batch.toIntArray()) }
                .getOrNull()
                ?: return@forEach
            batch.indices.forEach { index -> values.getOrNull(index)?.let { result[batch[index]] = it } }
        }
        return result
    }

    private companion object {
        const val MAX_PROCESSES = 192
        const val MAX_PACKAGES_PER_PROCESS = 16
        const val MAX_NAME_LENGTH = 256
        const val MEMORY_BATCH_SIZE = 32
        const val PROBE_PROCESS_LIMIT = 8
        const val KIBIBYTE = 1024L
        const val PER_USER_RANGE = 100_000
    }
}

data class ProcProcessStat(
    val cpuTimeTicks: Long,
    val threadCount: Int,
    val startTimeTicks: Long,
)

data class CpuTotals(val totalTicks: Long, val idleTicks: Long)

/** Parser kept separate so malformed or racing proc entries are harmless. */
internal object ProcStatParser {
    fun parseProcess(line: String): ProcProcessStat? {
        val open = line.indexOf('(')
        val close = line.lastIndexOf(')')
        if (open <= 0 || close <= open || line.substring(0, open).trim().toIntOrNull() == null) return null
        val fields = line.substring(close + 1).trim().split(WHITESPACE)
        if (fields.size <= START_TIME_INDEX) return null
        val userTicks = fields[USER_TIME_INDEX].toLongOrNull() ?: return null
        val systemTicks = fields[SYSTEM_TIME_INDEX].toLongOrNull() ?: return null
        val threads = fields[THREAD_COUNT_INDEX].toIntOrNull()?.takeIf { it >= 0 } ?: return null
        val start = fields[START_TIME_INDEX].toLongOrNull()?.takeIf { it >= 0 } ?: return null
        return ProcProcessStat(
            cpuTimeTicks = userTicks + systemTicks,
            threadCount = threads,
            startTimeTicks = start,
        )
    }

    fun parseCpuTotals(line: String): CpuTotals? {
        val fields = line.trim().split(WHITESPACE)
        if (fields.firstOrNull() != "cpu" || fields.size < 5) return null
        val counters = fields.drop(1).map { it.toLongOrNull() ?: return null }
        return CpuTotals(
            totalTicks = counters.fold(0L) { total, value -> total + value },
            idleTicks = counters[IDLE_INDEX] + counters.getOrElse(IOWAIT_INDEX) { 0L },
        )
    }

    private val WHITESPACE = Regex("\\s+")
    private const val USER_TIME_INDEX = 11
    private const val SYSTEM_TIME_INDEX = 12
    private const val THREAD_COUNT_INDEX = 17
    private const val START_TIME_INDEX = 19
    private const val IDLE_INDEX = 3
    private const val IOWAIT_INDEX = 4
}

class ProcStatReader(private val root: File = File("/proc")) {
    fun readProcess(pid: Int): ProcProcessStat? {
        if (pid <= 0) return null
        return runCatching {
            ProcStatParser.parseProcess(File(root, "$pid/stat").readText())
        }.getOrNull()
    }

    fun readCpuTotals(): CpuTotals? = runCatching {
        val first = File(root, "stat").bufferedReader().use { it.readLine() }
        ProcStatParser.parseCpuTotals(first)
    }.getOrNull()
}
