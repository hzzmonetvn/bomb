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
import com.hzzmonet.zkbomb.api.SelectedProcessMemory
import com.hzzmonet.zkbomb.api.SelectedProcessMemoryStatus
import com.hzzmonet.zkbomb.api.SystemTelemetrySnapshot
import com.hzzmonet.zkbomb.domain.model.ProcessClassifier
import java.io.File

/**
 * Read-only Task Manager and Stats Core backend.
 *
 * Process discovery comes from ActivityManager. The process list uses only
 * optional procfs counters; expensive PSS/private-dirty sampling is isolated in
 * [selectedProcessMemory] and always targets exactly one validated PID.
 */
class ProcessTelemetryBackend(
    private val context: Context,
    private val activityManager: ActivityManager? =
        context.getSystemService(ActivityManager::class.java),
) {
    private val proc = ProcStatReader()
    private val processSnapshots = ProcessSnapshotBackend(
        inventory = AndroidProcessInventory(context, activityManager),
        memorySource = AndroidSelectedProcessMemorySource(activityManager),
        proc = proc,
        elapsedRealtime = SystemClock::elapsedRealtime,
    )

    fun processSnapshot(): ProcessSnapshot = processSnapshots.processSnapshot()

    fun selectedProcessMemory(pid: Int): SelectedProcessMemory =
        processSnapshots.selectedProcessMemory(pid)

    /** An actual visibility measurement; seeing only Bomb does not count. */
    fun hasGlobalProcessVisibility(): Boolean =
        processSnapshots.hasGlobalProcessVisibility(Process.myUid())

    /** Per-process CPU/thread data is supported only if another process is readable. */
    fun hasOtherProcessStatAccess(): Boolean =
        processSnapshots.hasOtherProcessStatAccess(Process.myPid())

    /** Real one-PID probe; a failed/disappeared candidate is never replaced. */
    fun hasSelectedProcessMemoryAccess(): Boolean =
        processSnapshots.probeSelectedProcessMemory(excludedPid = Process.myPid())

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

}

internal data class ObservedProcess(
    val pid: Int,
    val uid: Int,
    val processName: String?,
    val packageNames: List<String>,
    val importance: Int,
    val importanceReasonCode: Int,
    val foreground: Boolean,
)

internal fun interface ProcessInventory {
    fun runningProcesses(): List<ObservedProcess>
}

internal data class ProcessMemoryReading(
    val pssBytes: Long?,
    val privateDirtyBytes: Long?,
    val rssBytes: Long?,
) {
    val hasAnyMetric: Boolean
        get() = pssBytes != null || privateDirtyBytes != null || rssBytes != null
}

internal fun interface SelectedProcessMemorySource {
    /** Samples [pid] only. Implementations must never substitute or batch another PID. */
    fun read(pid: Int): ProcessMemoryReading?
}

internal interface ProcessProcReader {
    fun readProcess(pid: Int): ProcProcessStat?
    fun readRssBytes(pid: Int): Long?
    fun readCpuTotals(): CpuTotals?
}

internal class ProcessSnapshotBackend(
    private val inventory: ProcessInventory,
    private val memorySource: SelectedProcessMemorySource,
    private val proc: ProcessProcReader,
    private val elapsedRealtime: () -> Long,
) {
    fun processSnapshot(): ProcessSnapshot {
        val all = inventorySnapshot()
        val selected = all.take(MAX_PROCESSES)
        val processes = selected.mapNotNull { running ->
            val name = running.processName?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val stat = runCatching { proc.readProcess(running.pid) }.getOrNull()
            val packages = running.packageNames.asSequence()
                .filter { it.isNotBlank() }
                .map { it.take(MAX_NAME_LENGTH) }
                .distinct()
                .take(MAX_PACKAGES_PER_PROCESS)
                .toList()
            val category = ProcessClassifier.classify(name, packages, running.uid)

            ProcessInfo(
                pid = running.pid,
                uid = running.uid,
                userId = running.uid / PER_USER_RANGE,
                processName = name.take(MAX_NAME_LENGTH),
                packageNames = packages,
                importance = running.importance,
                importanceReasonCode = running.importanceReasonCode,
                foreground = running.foreground,
                pssBytes = null,
                privateDirtyBytes = null,
                rssBytes = runCatching { proc.readRssBytes(running.pid) }.getOrNull(),
                cpuTimeTicks = stat?.cpuTimeTicks,
                threadCount = stat?.threadCount,
                startTimeTicks = stat?.startTimeTicks,
                categoryName = category.name,
            )
        }
        return ProcessSnapshot(
            sampledAtElapsedRealtimeMillis = elapsedRealtime(),
            processes = processes,
            truncated = all.size > selected.size,
        )
    }

    fun selectedProcessMemory(pid: Int): SelectedProcessMemory {
        if (pid <= 0) return unavailable(pid, SelectedProcessMemoryStatus.INVALID_PID)
        val selected = visibleProcesses().firstOrNull { it.pid == pid }
        if (selected == null) {
            return unavailable(pid, SelectedProcessMemoryStatus.NOT_VISIBLE)
        }

        val reading = runCatching { memorySource.read(pid) }.getOrNull()
        val observedAfterSample = visibleProcesses().firstOrNull { it.pid == pid }
        if (
            observedAfterSample == null ||
            observedAfterSample.uid != selected.uid ||
            observedAfterSample.processName != selected.processName
        ) {
            return unavailable(pid, SelectedProcessMemoryStatus.DISAPPEARED)
        }
        if (reading == null || !reading.hasAnyMetric) {
            return unavailable(pid, SelectedProcessMemoryStatus.UNAVAILABLE)
        }
        return SelectedProcessMemory(
            pid = pid,
            sampledAtElapsedRealtimeMillis = elapsedRealtime(),
            status = SelectedProcessMemoryStatus.AVAILABLE.name,
            pssBytes = reading.pssBytes,
            privateDirtyBytes = reading.privateDirtyBytes,
            rssBytes = reading.rssBytes,
        )
    }

    fun probeSelectedProcessMemory(excludedPid: Int = -1): Boolean {
        val candidate = visibleProcesses().firstOrNull { it.pid != excludedPid } ?: return false
        return selectedProcessMemory(candidate.pid).available
    }

    fun hasGlobalProcessVisibility(selfUid: Int): Boolean =
        inventorySnapshot().any { it.uid != selfUid }

    fun hasOtherProcessStatAccess(selfPid: Int): Boolean =
        inventorySnapshot().asSequence()
            .filter { it.pid != selfPid }
            .take(PROBE_PROCESS_LIMIT)
            .any { runCatching { proc.readProcess(it.pid) }.getOrNull() != null }

    private fun visibleProcesses(): List<ObservedProcess> =
        inventorySnapshot().take(MAX_PROCESSES)

    private fun inventorySnapshot(): List<ObservedProcess> =
        runCatching { inventory.runningProcesses() }
            .getOrDefault(emptyList())
            .asSequence()
            .filter { it.pid > 0 && it.uid >= 0 && !it.processName.isNullOrBlank() }
            .distinctBy { it.pid }
            .sortedWith(compareBy({ it.importance }, { it.pid }))
            .toList()

    private fun unavailable(pid: Int, status: SelectedProcessMemoryStatus): SelectedProcessMemory =
        SelectedProcessMemory.unavailable(pid, elapsedRealtime(), status)

    private companion object {
        const val MAX_PROCESSES = 192
        const val MAX_PACKAGES_PER_PROCESS = 16
        const val MAX_NAME_LENGTH = 256
        const val PROBE_PROCESS_LIMIT = 8
        const val PER_USER_RANGE = 100_000
    }
}

private class AndroidProcessInventory(
    private val context: Context,
    private val activityManager: ActivityManager?,
) : ProcessInventory {
    override fun runningProcesses(): List<ObservedProcess> =
        activityManager?.runningAppProcesses.orEmpty().map { running ->
            val packages = running.pkgList
                ?.asSequence()
                ?.filter { it.isNotBlank() }
                ?.toList()
                .orEmpty()
                .ifEmpty {
                    runCatching { context.packageManager.getPackagesForUid(running.uid).orEmpty().toList() }
                        .getOrDefault(emptyList())
                }
            ObservedProcess(
                pid = running.pid,
                uid = running.uid,
                processName = running.processName,
                packageNames = packages,
                importance = running.importance,
                importanceReasonCode = running.importanceReasonCode,
                foreground = running.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE,
            )
        }
}

private class AndroidSelectedProcessMemorySource(
    private val activityManager: ActivityManager?,
) : SelectedProcessMemorySource {
    override fun read(pid: Int): ProcessMemoryReading? {
        val manager = activityManager ?: return null
        val memory = manager.getProcessMemoryInfo(intArrayOf(pid)).singleOrNull() ?: return null
        val rssKiB = runCatching {
            memory.javaClass.getMethod("getTotalRss").invoke(memory) as? Int
        }.getOrNull()
        return ProcessMemoryReading(
            pssBytes = memory.totalPss.positiveKiBToBytes(),
            privateDirtyBytes = memory.totalPrivateDirty.positiveKiBToBytes(),
            rssBytes = rssKiB.positiveKiBToBytes(),
        )
    }

    private fun Int?.positiveKiBToBytes(): Long? =
        this?.takeIf { it > 0 }?.toLong()?.times(KIBIBYTE)

    private companion object {
        const val KIBIBYTE = 1024L
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

    fun parseRssBytes(status: String): Long? {
        val rssLine = status.lineSequence().firstOrNull { it.startsWith("VmRSS:") } ?: return null
        val fields = rssLine.trim().split(WHITESPACE)
        if (fields.size != 3 || fields[0] != "VmRSS:" || fields[2] != "kB") return null
        val rssKiB = fields[1].toLongOrNull()?.takeIf { it > 0 } ?: return null
        if (rssKiB > Long.MAX_VALUE / KIBIBYTE) return null
        return rssKiB * KIBIBYTE
    }

    private val WHITESPACE = Regex("\\s+")
    private const val USER_TIME_INDEX = 11
    private const val SYSTEM_TIME_INDEX = 12
    private const val THREAD_COUNT_INDEX = 17
    private const val START_TIME_INDEX = 19
    private const val IDLE_INDEX = 3
    private const val IOWAIT_INDEX = 4
    private const val KIBIBYTE = 1024L
}

internal class ProcStatReader(private val root: File = File("/proc")) : ProcessProcReader {
    override fun readProcess(pid: Int): ProcProcessStat? {
        if (pid <= 0) return null
        return runCatching {
            ProcStatParser.parseProcess(File(root, "$pid/stat").readText())
        }.getOrNull()
    }

    override fun readRssBytes(pid: Int): Long? {
        if (pid <= 0) return null
        return runCatching {
            ProcStatParser.parseRssBytes(File(root, "$pid/status").readText())
        }.getOrNull()
    }

    override fun readCpuTotals(): CpuTotals? = runCatching {
        val first = File(root, "stat").bufferedReader().use { it.readLine() }
        ProcStatParser.parseCpuTotals(first)
    }.getOrNull()
}
