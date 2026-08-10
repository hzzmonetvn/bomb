package com.hzzmonet.zkbomb.data

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * The Package Inspector's component view, as the UI reads it.
 *
 * The same closed set of outcomes the rest of the app uses, so the Component
 * Explorer never has to invent a state: [Loading] before the snapshot arrives,
 * [Unsupported] when the contract is too old or the service is unreachable,
 * [Empty] when the package genuinely exposes no components, [Error] when the
 * call fails, and [Ready] with the real snapshot. No sample branch.
 */
sealed interface PackageInspectorState {
    data object Loading : PackageInspectorState
    data class Unsupported(val reason: String) : PackageInspectorState
    data object Empty : PackageInspectorState
    data class Error(val message: String) : PackageInspectorState
    data class Ready(val snapshot: BombPackageSnapshot) : PackageInspectorState
}

/** Per-component action state, so a failed toggle is shown on that row alone. */
sealed interface ComponentActionStatus {
    data object Idle : ComponentActionStatus
    data object Applying : ComponentActionStatus
    data class Failed(val message: String) : ComponentActionStatus
}

/**
 * State plus the two commands the Component Explorer needs.
 *
 * [canMutate] is deliberately conservative: it is true only when the backend
 * reports COMPONENT_CONTROL *and* the package is not a protected critical one.
 * When false, the UI shows each component's current override read-only rather
 * than offering a control that would be refused.
 */
class PackageInspectorHandle(
    val state: PackageInspectorState,
    val itemStatus: Map<String, ComponentActionStatus>,
    val canMutate: Boolean,
    /** Set a component override to a [BombComponentState] value. */
    val setComponent: (className: String, targetState: String) -> Unit,
    /** Re-fetch the snapshot (used by the Error retry). */
    val refresh: () -> Unit,
)

/**
 * Loads the Package Inspector snapshot for [packageName] when [enabled], and
 * applies component overrides through the v5 backend.
 *
 * Fetching is gated on [enabled] so the (relatively heavy) full snapshot is only
 * read when the user actually opens the component section, not on every App
 * Control visit. A successful override re-fetches so the row reflects the new
 * effective state rather than an optimistic guess.
 */
@Composable
fun rememberPackageInspector(
    service: BombServiceState,
    packageName: String,
    enabled: Boolean,
): PackageInspectorHandle {
    var state by remember(packageName) { mutableStateOf<PackageInspectorState>(PackageInspectorState.Loading) }
    val itemStatus = remember(packageName) { mutableStateMapOf<String, ComponentActionStatus>() }
    var refreshKey by remember(packageName) { mutableStateOf(0) }

    val controller = service.controller
    val apiVersion = service.apiVersion ?: 0

    LaunchedEffect(enabled, controller, apiVersion, service.connection, packageName, refreshKey) {
        if (!enabled) return@LaunchedEffect
        when {
            service.connection == BombConnection.CONNECTING -> {
                state = PackageInspectorState.Loading
                return@LaunchedEffect
            }
            controller == null -> {
                state = PackageInspectorState.Unsupported(
                    "Bomb's privileged service is not reachable, so component details are unavailable.",
                )
                return@LaunchedEffect
            }
            apiVersion < MIN_PACKAGE_VERSION -> {
                state = PackageInspectorState.Unsupported(
                    "Package Inspector needs contract v$MIN_PACKAGE_VERSION or newer; the " +
                        "connected service is v$apiVersion.",
                )
                return@LaunchedEffect
            }
        }
        state = PackageInspectorState.Loading
        val snapshot = controller.awaitPackageSnapshot(packageName)
        state = when {
            snapshot == null -> PackageInspectorState.Error("The package snapshot call failed.")
            snapshot.components.isEmpty() -> PackageInspectorState.Empty
            else -> PackageInspectorState.Ready(snapshot)
        }
    }

    val ready = state as? PackageInspectorState.Ready
    val canMutate = service.isSupported(BombCapabilityKeys.COMPONENT_CONTROL) &&
        ready?.snapshot?.protectionReason == null

    return PackageInspectorHandle(
        state = state,
        itemStatus = itemStatus,
        canMutate = canMutate,
        setComponent = { className, targetState ->
            if (controller != null) {
                itemStatus[className] = ComponentActionStatus.Applying
                controller.setComponentState(packageName, className, targetState) { result ->
                    if (result.isSuccess) {
                        itemStatus[className] = ComponentActionStatus.Idle
                        // Re-read so the row shows the real new override/effective state.
                        refreshKey += 1
                    } else {
                        itemStatus[className] = ComponentActionStatus.Failed(
                            result.message ?: result.status,
                        )
                    }
                }
            }
        },
        refresh = { refreshKey += 1 },
    )
}

private const val MIN_PACKAGE_VERSION = 5

private suspend fun BombServiceController.awaitPackageSnapshot(packageName: String): BombPackageSnapshot? =
    suspendCancellableCoroutine { continuation ->
        getPackageSnapshot(packageName) { result -> if (continuation.isActive) continuation.resume(result) }
    }
