package com.hzzmonet.zkbomb.data

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * The state of the expensive memory sample for one *selected* process.
 *
 * A closed set of honest outcomes: [Loading] while the single on-demand call is
 * in flight, [Unsupported] when the contract is older than v7 (the caller then
 * falls back to the list snapshot), [Disappeared] when the process is gone or its
 * PID was reused by a different process, [Unavailable] when the service can see
 * the process but cannot read its memory, [Error] on a failed call, and [Ready]
 * with the sample. Metric fields inside [Ready] stay nullable and render "—".
 */
sealed interface SelectedProcessMemoryState {
    data object Loading : SelectedProcessMemoryState
    data object Unsupported : SelectedProcessMemoryState
    data object Disappeared : SelectedProcessMemoryState
    data class Unavailable(val reason: String) : SelectedProcessMemoryState
    data class Error(val message: String) : SelectedProcessMemoryState
    data class Ready(val memory: BombSelectedProcessMemory) : SelectedProcessMemoryState
}

/**
 * Turns a raw v7 memory result into a UI state, purely.
 *
 * The one subtle rule is the identity guard, which defends against PID reuse: a
 * PID freed by process A and reused by process B would have the backend sample
 * B's memory for A's PID. The resolver refuses that by comparing the process
 * *generation* — its start time — captured when the user selected it against the
 * generation currently holding the PID. A mismatch is [Disappeared], even when
 * the backend reported the sample as available.
 *
 * The "PID no longer present at all" case is handled by the caller (it stops
 * finding a row for the PID and never calls this), so a null current start time
 * here only means the start tick was withheld, not that the process vanished.
 */
object SelectedMemoryResolver {

    const val MIN_VERSION = 7

    fun resolve(
        apiVersion: Int,
        result: BombSelectedProcessMemory?,
        expectedStartTimeTicks: Long?,
        currentStartTimeTicks: Long?,
    ): SelectedProcessMemoryState {
        if (apiVersion < MIN_VERSION) return SelectedProcessMemoryState.Unsupported
        if (result == null) return SelectedProcessMemoryState.Error("The memory sample call failed.")

        // Identity first: a reused PID must never show another process's memory,
        // regardless of what the backend returned for it.
        if (isReusedGeneration(expectedStartTimeTicks, currentStartTimeTicks)) {
            return SelectedProcessMemoryState.Disappeared
        }

        return when (result.status) {
            BombSelectedMemoryStatus.AVAILABLE ->
                SelectedProcessMemoryState.Ready(result)
            BombSelectedMemoryStatus.INVALID_PID,
            BombSelectedMemoryStatus.DISAPPEARED ->
                SelectedProcessMemoryState.Disappeared
            BombSelectedMemoryStatus.NOT_VISIBLE ->
                SelectedProcessMemoryState.Unavailable("The service cannot see this process.")
            BombSelectedMemoryStatus.UNAVAILABLE ->
                SelectedProcessMemoryState.Unavailable("Memory counters are unavailable for this process.")
            BombSelectedMemoryStatus.PERMISSION_DENIED ->
                SelectedProcessMemoryState.Unavailable("Bomb lacks permission to read this process's memory.")
        }
    }

    /**
     * True only when both generations are known and differ — i.e. the PID is now
     * held by a different process than the one selected. When either start tick
     * was withheld, reuse cannot be proven and is not assumed.
     */
    private fun isReusedGeneration(expected: Long?, current: Long?): Boolean =
        expected != null && current != null && expected != current
}

/**
 * Fetches the selected process's memory once, on demand, and re-derives the state
 * as the snapshot moves under it.
 *
 * Lifecycle: the call fires from a [LaunchedEffect] keyed on [pid] (and the
 * service/contract). Changing [pid] or leaving composition — closing the sheet —
 * cancels the in-flight coroutine and drops its result, so a stale PID never
 * overwrites the state. There is no loop: one call per open/selection, plus an
 * explicit [refreshKey] bump if the caller offers a manual re-sample.
 *
 * The final state is computed each recomposition from the last raw result and the
 * *live* [currentStartTimeTicks], so if the process is replaced after the sample
 * arrives the view flips to [SelectedProcessMemoryState.Disappeared] immediately.
 */
@Composable
fun rememberSelectedProcessMemory(
    service: BombServiceState,
    pid: Int,
    expectedStartTimeTicks: Long?,
    currentStartTimeTicks: Long?,
    refreshKey: Int = 0,
): SelectedProcessMemoryState {
    val controller = service.controller
    val apiVersion = service.apiVersion ?: 0

    var loaded by remember(pid) { mutableStateOf(false) }
    var raw by remember(pid) { mutableStateOf<BombSelectedProcessMemory?>(null) }

    LaunchedEffect(pid, apiVersion, controller, refreshKey) {
        loaded = false
        raw = null
        if (apiVersion < SelectedMemoryResolver.MIN_VERSION || controller == null) return@LaunchedEffect
        raw = controller.awaitSelectedMemory(pid)
        loaded = true
    }

    return when {
        apiVersion < SelectedMemoryResolver.MIN_VERSION -> SelectedProcessMemoryState.Unsupported
        controller == null -> SelectedProcessMemoryState.Error("Bomb's privileged service is not reachable.")
        !loaded -> SelectedProcessMemoryState.Loading
        else -> SelectedMemoryResolver.resolve(apiVersion, raw, expectedStartTimeTicks, currentStartTimeTicks)
    }
}

private suspend fun BombServiceController.awaitSelectedMemory(pid: Int): BombSelectedProcessMemory? =
    suspendCancellableCoroutine { continuation ->
        getSelectedProcessMemory(pid) { result -> if (continuation.isActive) continuation.resume(result) }
    }
