package com.hzzmonet.zkbomb.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.hzzmonet.zkbomb.api.BombCapability
import com.hzzmonet.zkbomb.api.IBombService
import com.hzzmonet.zkbomb.api.MemoryConfig

@Composable
actual fun rememberBombService(): BombServiceState {
    val context = LocalContext.current
    var state by remember { mutableStateOf(BombServiceState.NOT_CONNECTED) }

    DisposableEffect(context) {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val service = IBombService.Stub.asInterface(binder)
                if (service == null) {
                    state = BombServiceState(
                        connection = BombConnection.UNAVAILABLE,
                        apiVersion = null,
                        capabilities = emptyMap(),
                        error = "Bound, but the binder did not implement IBombService",
                    )
                    return
                }
                // A RemoteException here is not hypothetical even in-process:
                // the service refuses callers it does not trust, and a refused
                // getCapabilities returns an empty set rather than throwing —
                // which is exactly the "no capabilities" case below.
                state = runCatching {
                    val probed = service.capabilities.probed()
                    val version = service.apiVersion
                    val logStatus = runCatching { service.logStatus }.getOrNull()
                    BombServiceState(
                        connection = BombConnection.CONNECTED,
                        apiVersion = version,
                        capabilities = probed.entries.associate { (k, v) -> k.name to v.name },
                        // Guarded on the contract version rather than caught as
                        // an exception: a service older than this client simply
                        // does not have transaction 8, and asking anyway would
                        // land on nothing. Version 2 is where it was appended.
                        runtimeMode = if (version >= 2) service.runtimeMode else "NORMAL",
                        logLevel = logStatus?.effectiveLevel,
                        controller = AndroidBombServiceController(service),
                    )
                }.getOrElse { error ->
                    BombServiceState(
                        connection = BombConnection.UNAVAILABLE,
                        apiVersion = null,
                        capabilities = emptyMap(),
                        error = error.message ?: error::class.simpleName,
                    )
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                // The process hosting the service died. Capabilities are dropped
                // rather than kept: a stale "supported" would enable a control
                // that now has nothing behind it.
                state = BombServiceState(
                    connection = BombConnection.UNAVAILABLE,
                    apiVersion = null,
                    capabilities = emptyMap(),
                    error = "Service disconnected",
                )
            }

            override fun onNullBinding(name: ComponentName?) {
                state = BombServiceState(
                    connection = BombConnection.UNAVAILABLE,
                    apiVersion = null,
                    capabilities = emptyMap(),
                    error = "Service returned no binder",
                )
            }
        }

        val intent = Intent().setClassName(context.packageName, SERVICE_CLASS)
        val bound = runCatching {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)

        if (!bound) {
            state = BombServiceState(
                connection = BombConnection.UNAVAILABLE,
                apiVersion = null,
                capabilities = emptyMap(),
                error = "bindService refused",
            )
        }

        onDispose {
            if (bound) runCatching { context.unbindService(connection) }
        }
    }

    return state
}

private class AndroidBombServiceController(
    private val service: IBombService,
) : BombServiceController {
    private val main = Handler(Looper.getMainLooper())

    override fun getFreezeStatus(
        packageName: String,
        onResult: (BombFreezeStatus?) -> Unit,
    ) = runAsync("freeze-status", onFailure = null, onResult = onResult) {
        service.getFreezeStatus(packageName, currentUserId())?.let {
            BombFreezeStatus(
                mode = it.mode,
                hasUnfrozenProcesses = it.hasUnfrozenProcesses,
                availableModes = it.availableModes,
                exclusionReason = it.exclusionReason,
            )
        }
    }

    override fun setFreezeMode(
        packageName: String,
        mode: String,
        onResult: (BombOperationResult) -> Unit,
    ) = runOperation("freeze", onResult) {
        service.setFreezeMode(packageName, currentUserId(), mode)
    }

    override fun setLogLevel(
        level: String,
        auditRatePerSecond: Int,
        onResult: (BombOperationResult) -> Unit,
    ) = runOperation("log", onResult) { service.setLogLevel(level, auditRatePerSecond) }

    override fun getMemoryStatus(onResult: (BombMemoryStatus?) -> Unit) =
        runAsync("memory-status", onFailure = null, onResult = onResult) {
            service.memoryStatus?.let {
                BombMemoryStatus(
                    zramPresent = it.zramPresent,
                    disksizeBytes = it.disksizeBytes,
                    compressionRatio = it.compressionRatio,
                    ramEfficiency = it.ramEfficiency,
                    currentAlgorithm = it.currentAlgorithm,
                    globalSwappiness = it.globalSwappiness,
                )
            }
        }

    override fun setMemoryConfig(
        swappiness: Int?,
        pageCluster: Int?,
        onResult: (BombOperationResult) -> Unit,
    ) = runOperation("memory", onResult) {
        service.setMemoryConfig(
            MemoryConfig(swappiness = swappiness, pageCluster = pageCluster),
        )
    }

    private fun runOperation(
        name: String,
        onResult: (BombOperationResult) -> Unit,
        block: () -> com.hzzmonet.zkbomb.api.BombResult,
    ) = runAsync(
        name = name,
        onFailure = BombOperationResult("BACKEND_UNAVAILABLE", "Binder call failed"),
        onResult = onResult,
    ) {
        block().let { BombOperationResult(it.status.name, it.detail) }
    }

    private fun <T> runAsync(
        name: String,
        onFailure: T,
        onResult: (T) -> Unit,
        block: () -> T,
    ) {
        Thread({
            val result = runCatching(block).getOrElse { error ->
                @Suppress("UNCHECKED_CAST")
                when (onFailure) {
                    is BombOperationResult -> BombOperationResult(
                        "BACKEND_UNAVAILABLE",
                        error.message ?: error.javaClass.simpleName,
                    ) as T
                    else -> onFailure
                }
            }
            main.post { onResult(result) }
        }, "BombBinder-$name").start()
    }

    private fun currentUserId(): Int =
        android.os.Process.myUid() / PER_USER_RANGE

    private companion object {
        // Android assigns each user a block of 100,000 UIDs. Public SDK does
        // not expose UserHandle.myUserId(), so derive the current user from the
        // app UID without reflecting into a hidden API.
        const val PER_USER_RANGE = 100_000
    }
}

/**
 * Resolved by name rather than by class literal.
 *
 * `:preview` is the shared UI module and deliberately does not depend on
 * `:system-service` — the UI must not be able to reach a privileged
 * implementation directly, only the AIDL surface in `:core-api`. Naming the
 * class keeps that boundary while still letting the bind resolve at runtime; if
 * the service is not in the APK, `bindService` simply fails and the UI reports
 * `UNAVAILABLE`, which is the honest outcome.
 */
private const val SERVICE_CLASS = "com.hzzmonet.zkbomb.core.BombCoreService"
