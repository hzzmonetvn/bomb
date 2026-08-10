package com.hzzmonet.zkbomb.data

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * The Task Manager's process list, as the UI reads it.
 *
 * A closed set of outcomes so no screen has to invent one: [Loading] before the
 * first sample, [Unsupported] when the backend cannot provide the list at all
 * (contract too old, service unreachable, capability not held), [Empty] when the
 * sample genuinely contained nothing, [Error] when a call failed, and [Ready]
 * with real rows. There is no "sample" branch — a device that cannot show
 * processes says so.
 */
sealed interface ProcessListState {
    data object Loading : ProcessListState
    data class Unsupported(val reason: String) : ProcessListState
    data object Empty : ProcessListState
    data class Error(val message: String) : ProcessListState
    data class Ready(
        val processes: List<ProcessRow>,
        val truncated: Boolean,
        val sampledAtMillis: Long,
    ) : ProcessListState
}

/**
 * One process, resolved for display. CPU is a rate computed from two snapshots,
 * so it is null on the first sample and whenever the ticks needed to compute it
 * were withheld — never defaulted to zero.
 */
data class ProcessRow(
    val pid: Int,
    val uid: Int,
    val userId: Int,
    val processName: String,
    val packageName: String?,
    val isSystem: Boolean,
    val foreground: Boolean,
    val importance: Int,
    val category: String?,
    /** Share of total CPU capacity across all cores, 0..100, or null. */
    val cpuPercent: Float?,
    val pssBytes: Long?,
    val rssBytes: Long?,
    val privateDirtyBytes: Long?,
    val threadCount: Int?,
    val cpuTimeTicks: Long?,
    val startTimeTicks: Long?,
) {
    /** PSS in whole MB, falling back to RSS; null when neither is known. */
    val memoryMb: Int?
        get() = (pssBytes ?: rssBytes)?.let { (it / (1024 * 1024)).toInt() }
}

/** The Monitor screen's system-wide telemetry state. */
sealed interface SystemTelemetryState {
    data object Loading : SystemTelemetryState
    data class Unsupported(val reason: String) : SystemTelemetryState
    data class Error(val message: String) : SystemTelemetryState
    data class Ready(
        val telemetry: BombSystemTelemetry,
        /** Total CPU busy percentage from successive deltas, or null on the first sample. */
        val cpuPercent: Int?,
    ) : SystemTelemetryState
}

/**
 * The delta arithmetic, kept pure so it can be reasoned about and tested without
 * a device or a Binder.
 *
 * Both CPU figures are ratios of counter deltas, which is the only correct way to
 * read jiffie counters: a single sample is a running total since boot and says
 * nothing about *now*.
 */
object TelemetryMath {

    /**
     * System-wide busy percentage between two `/proc/stat` samples.
     *
     * `busy = Δtotal − Δidle`, as a percentage of `Δtotal`. Returns null when any
     * input is missing or the counters did not advance (so the caller shows
     * "unavailable" rather than a divide-by-zero or a frozen 0).
     */
    fun systemCpuPercent(prevTotal: Long?, prevIdle: Long?, total: Long?, idle: Long?): Int? {
        if (prevTotal == null || prevIdle == null || total == null || idle == null) return null
        val deltaTotal = total - prevTotal
        val deltaIdle = idle - prevIdle
        if (deltaTotal <= 0L) return null
        val busy = (deltaTotal - deltaIdle).coerceIn(0L, deltaTotal)
        return ((busy * 100L) / deltaTotal).toInt()
    }

    /**
     * A process's share of total CPU capacity between two snapshots.
     *
     * `Δprocess_ticks / Δtotal_ticks × 100`, in the same jiffie unit so the clock
     * rate cancels. Null when the process ticks were withheld, when there is no
     * previous sample, or when the total did not advance. A negative process
     * delta (pid reused) is treated as unknown, not as a spike.
     */
    fun processCpuPercent(prevTicks: Long?, ticks: Long?, deltaTotalTicks: Long): Float? {
        if (prevTicks == null || ticks == null || deltaTotalTicks <= 0L) return null
        val delta = ticks - prevTicks
        if (delta < 0L) return null
        return ((delta.toDouble() / deltaTotalTicks.toDouble()) * 100.0).toFloat().coerceIn(0f, 100f)
    }

    /** Android app UIDs start at 10000 within each user block; below that is system. */
    fun isSystemUid(uid: Int): Boolean = (uid % 100_000) < 10_000
}

/**
 * Polls the privileged process snapshot while [active], and stops the moment it
 * is not (route left, or the whole app leaves composition).
 *
 * Per-process CPU comes from consecutive snapshots: the previous sample's ticks
 * are kept and each new sample's rate is `Δprocess_ticks / Δtotal_ticks`, the
 * total taken from the system telemetry sampled in the same tick.
 */
@Composable
fun rememberProcessList(
    service: BombServiceState,
    active: Boolean,
    intervalMillis: Long,
): ProcessListState {
    var state by remember { mutableStateOf<ProcessListState>(ProcessListState.Loading) }
    val controller = service.controller
    val apiVersion = service.apiVersion ?: 0
    val supported = service.isSupported(BombCapabilityKeys.TASK_MANAGER)

    LaunchedEffect(active, controller, apiVersion, supported, service.connection, intervalMillis) {
        if (!active) return@LaunchedEffect
        when {
            service.connection == BombConnection.CONNECTING -> {
                state = ProcessListState.Loading
                return@LaunchedEffect
            }
            controller == null -> {
                state = ProcessListState.Unsupported(
                    "Bomb's privileged service is not reachable on this build, so the " +
                        "system-wide process list is unavailable.",
                )
                return@LaunchedEffect
            }
            apiVersion < MIN_SNAPSHOT_VERSION -> {
                state = ProcessListState.Unsupported(
                    "The connected service implements contract v$apiVersion; the process " +
                        "snapshot needs v$MIN_SNAPSHOT_VERSION or newer.",
                )
                return@LaunchedEffect
            }
            !supported -> {
                state = ProcessListState.Unsupported(
                    "This build does not expose global process visibility " +
                        "(ActivityManager). A system-wide list needs the ROM or root backend.",
                )
                return@LaunchedEffect
            }
        }

        var previous: Map<Int, Long?> = emptyMap()
        var previousTotalTicks: Long? = null
        while (isActive) {
            val snapshot = controller.awaitProcessSnapshot()
            if (snapshot == null) {
                state = ProcessListState.Error("The process snapshot call failed.")
            } else {
                val totalTicks = controller.awaitSystemTelemetry()?.cpuTotalTicks
                val lastTotalTicks = previousTotalTicks
                val deltaTotal = if (lastTotalTicks != null && totalTicks != null) {
                    totalTicks - lastTotalTicks
                } else {
                    0L
                }
                val rows = snapshot.processes.map { it.toRow(previous[it.pid], deltaTotal) }
                state = if (rows.isEmpty()) {
                    ProcessListState.Empty
                } else {
                    ProcessListState.Ready(rows, snapshot.truncated, snapshot.sampledAtElapsedRealtimeMillis)
                }
                previous = snapshot.processes.associate { it.pid to it.cpuTimeTicks }
                if (totalTicks != null) previousTotalTicks = totalTicks
            }
            delay(intervalMillis)
        }
    }
    return state
}

/**
 * Polls system telemetry while [active], computing total CPU% from successive
 * total/idle deltas. Stops when [active] goes false or the caller leaves
 * composition.
 */
@Composable
fun rememberSystemTelemetry(
    service: BombServiceState,
    active: Boolean,
    intervalMillis: Long,
): SystemTelemetryState {
    var state by remember { mutableStateOf<SystemTelemetryState>(SystemTelemetryState.Loading) }
    val controller = service.controller
    val apiVersion = service.apiVersion ?: 0

    LaunchedEffect(active, controller, apiVersion, service.connection, intervalMillis) {
        if (!active) return@LaunchedEffect
        when {
            service.connection == BombConnection.CONNECTING -> {
                state = SystemTelemetryState.Loading
                return@LaunchedEffect
            }
            controller == null -> {
                state = SystemTelemetryState.Unsupported(
                    "Bomb's privileged service is not reachable, so system telemetry " +
                        "is unavailable.",
                )
                return@LaunchedEffect
            }
            apiVersion < MIN_SNAPSHOT_VERSION -> {
                state = SystemTelemetryState.Unsupported(
                    "The connected service implements contract v$apiVersion; system " +
                        "telemetry needs v$MIN_SNAPSHOT_VERSION or newer.",
                )
                return@LaunchedEffect
            }
        }

        var previousTotal: Long? = null
        var previousIdle: Long? = null
        while (isActive) {
            val telemetry = controller.awaitSystemTelemetry()
            if (telemetry == null) {
                state = SystemTelemetryState.Error("The telemetry call failed.")
            } else {
                val cpu = TelemetryMath.systemCpuPercent(
                    prevTotal = previousTotal,
                    prevIdle = previousIdle,
                    total = telemetry.cpuTotalTicks,
                    idle = telemetry.cpuIdleTicks,
                )
                state = SystemTelemetryState.Ready(telemetry, cpu)
                previousTotal = telemetry.cpuTotalTicks
                previousIdle = telemetry.cpuIdleTicks
            }
            delay(intervalMillis)
        }
    }
    return state
}

private const val MIN_SNAPSHOT_VERSION = 3

private fun BombProcessInfo.toRow(previousTicks: Long?, deltaTotalTicks: Long): ProcessRow =
    ProcessRow(
        pid = pid,
        uid = uid,
        userId = userId,
        processName = processName,
        packageName = packageNames.firstOrNull(),
        isSystem = TelemetryMath.isSystemUid(uid),
        foreground = foreground,
        importance = importance,
        category = categoryName,
        cpuPercent = TelemetryMath.processCpuPercent(previousTicks, cpuTimeTicks, deltaTotalTicks),
        pssBytes = pssBytes,
        rssBytes = rssBytes,
        privateDirtyBytes = privateDirtyBytes,
        threadCount = threadCount,
        cpuTimeTicks = cpuTimeTicks,
        startTimeTicks = startTimeTicks,
    )

private suspend fun BombServiceController.awaitProcessSnapshot(): BombProcessSnapshot? =
    suspendCancellableCoroutine { continuation ->
        getProcessSnapshot { result -> if (continuation.isActive) continuation.resume(result) }
    }

private suspend fun BombServiceController.awaitSystemTelemetry(): BombSystemTelemetry? =
    suspendCancellableCoroutine { continuation ->
        getSystemTelemetry { result -> if (continuation.isActive) continuation.resume(result) }
    }
