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
import com.hzzmonet.zkbomb.api.FirewallRuleParcel
import com.hzzmonet.zkbomb.api.IBombService
import com.hzzmonet.zkbomb.api.MemoryConfig
import com.hzzmonet.zkbomb.api.SettingsAssignmentParcel
import com.hzzmonet.zkbomb.api.SettingsOverrideParcel
import com.hzzmonet.zkbomb.api.VisibilityCallerPolicyParcel

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
                        controller = AndroidBombServiceController(service, version),
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
    private val apiVersion: Int,
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

    override fun getProcessSnapshot(onResult: (BombProcessSnapshot?) -> Unit) =
        runAsync("processes", onFailure = null, onResult = onResult) {
            // The contract appended these in v3. An older service does not have
            // the transaction, so asking would land on nothing — guard, don't try.
            if (apiVersion < MIN_SNAPSHOT_VERSION) return@runAsync null
            service.processSnapshot?.let { snapshot ->
                BombProcessSnapshot(
                    sampledAtElapsedRealtimeMillis = snapshot.sampledAtElapsedRealtimeMillis,
                    truncated = snapshot.truncated,
                    processes = snapshot.processes.map { it.toCommon() },
                )
            }
        }

    override fun getSystemTelemetry(onResult: (BombSystemTelemetry?) -> Unit) =
        runAsync("telemetry", onFailure = null, onResult = onResult) {
            if (apiVersion < MIN_SNAPSHOT_VERSION) return@runAsync null
            service.systemTelemetrySnapshot?.let { it.toCommon() }
        }

    override fun getPackageSnapshot(packageName: String, onResult: (BombPackageSnapshot?) -> Unit) =
        runAsync("package", onFailure = null, onResult = onResult) {
            if (apiVersion < MIN_PACKAGE_VERSION) return@runAsync null
            service.getPackageSnapshot(packageName, currentUserId())?.toCommon()
        }

    override fun forceStopPackage(packageName: String, onResult: (BombOperationResult) -> Unit) =
        runAsync(
            name = "force-stop",
            onFailure = BombOperationResult("BACKEND_UNAVAILABLE", "Binder call failed"),
            onResult = onResult,
        ) {
            if (apiVersion < MIN_PACKAGE_VERSION) {
                BombOperationResult("UNSUPPORTED", "The connected service is older than v$MIN_PACKAGE_VERSION")
            } else {
                service.forceStopPackage(packageName, currentUserId())
                    .let { BombOperationResult(it.status.name, it.detail) }
            }
        }

    override fun setComponentState(
        packageName: String,
        className: String,
        state: String,
        onResult: (BombOperationResult) -> Unit,
    ) = runAsync(
        name = "component",
        onFailure = BombOperationResult("BACKEND_UNAVAILABLE", "Binder call failed"),
        onResult = onResult,
    ) {
        if (apiVersion < MIN_PACKAGE_VERSION) {
            BombOperationResult("UNSUPPORTED", "The connected service is older than v$MIN_PACKAGE_VERSION")
        } else {
            service.setComponentState(packageName, currentUserId(), className, state)
                .let { BombOperationResult(it.status.name, it.detail) }
        }
    }

    override fun getSelectedProcessMemory(pid: Int, onResult: (BombSelectedProcessMemory?) -> Unit) =
        runAsync("selected-memory", onFailure = null, onResult = onResult) {
            // v7 only. An older service does not have the transaction; asking would
            // land on nothing, so the caller falls back to the list snapshot values.
            if (apiVersion < MIN_SELECTED_MEMORY_VERSION) return@runAsync null
            service.getSelectedProcessMemory(pid)?.toCommon()
        }

    override fun setVisibilityPolicy(
        callingUid: Int,
        mode: String,
        packageNames: List<String>,
        onResult: (BombOperationResult) -> Unit,
    ) = runV6Operation("visibility-set", onResult) {
        service.setVisibilityPolicy(
            VisibilityCallerPolicyParcel(callingUid, currentUserId(), mode, packageNames),
        )
    }

    override fun clearVisibilityPolicy(callingUid: Int, onResult: (BombOperationResult) -> Unit) =
        runV6Operation("visibility-clear", onResult) {
            service.clearVisibilityPolicy(callingUid, currentUserId())
        }

    override fun createSettingsProfile(
        profileId: String,
        profileName: String,
        onResult: (BombOperationResult) -> Unit,
    ) = runV6Operation("settings-create", onResult) {
        service.createSettingsProfile(profileId, profileName)
    }

    override fun deleteSettingsProfile(profileId: String, onResult: (BombOperationResult) -> Unit) =
        runV6Operation("settings-delete", onResult) { service.deleteSettingsProfile(profileId) }

    override fun addSettingsOverride(
        profileId: String,
        namespace: String,
        key: String,
        valueType: String,
        value: String?,
        enabled: Boolean,
        onResult: (BombOperationResult) -> Unit,
    ) = runV6Operation("settings-override-add", onResult) {
        service.addSettingsOverride(
            SettingsOverrideParcel(profileId, namespace, key, valueType, value, enabled),
        )
    }

    override fun removeSettingsOverride(
        profileId: String,
        namespace: String,
        key: String,
        onResult: (BombOperationResult) -> Unit,
    ) = runV6Operation("settings-override-remove", onResult) {
        service.removeSettingsOverride(profileId, namespace, key)
    }

    override fun assignSettingsProfile(
        targetPackage: String,
        profileId: String,
        onResult: (BombOperationResult) -> Unit,
    ) = runV6Operation("settings-assign", onResult) {
        service.assignSettingsProfile(
            SettingsAssignmentParcel(currentUserId(), targetPackage, profileId),
        )
    }

    override fun clearSettingsAssignment(targetPackage: String, onResult: (BombOperationResult) -> Unit) =
        runV6Operation("settings-unassign", onResult) {
            service.clearSettingsAssignment(currentUserId(), targetPackage)
        }

    override fun setFirewallRule(
        uid: Int,
        wifiAccess: String,
        mobileAccess: String,
        backgroundAccess: String,
        note: String?,
        onResult: (BombOperationResult) -> Unit,
    ) = runV6Operation("firewall-set", onResult) {
        service.setFirewallRule(
            FirewallRuleParcel(uid, currentUserId(), wifiAccess, mobileAccess, backgroundAccess, note),
        )
    }

    override fun clearFirewallRule(uid: Int, onResult: (BombOperationResult) -> Unit) =
        runV6Operation("firewall-clear", onResult) {
            service.clearFirewallRule(uid, currentUserId())
        }

    override fun reloadAdBlockRules(onResult: (BombOperationResult) -> Unit) =
        runV6Operation("adblock-reload", onResult) { service.reloadAdBlockRules() }

    /**
     * Run a v6 write, guarding the contract version the same way the v5/v7 calls
     * do: a service older than v6 does not have these transactions, so asking would
     * land on nothing — return UNSUPPORTED instead of dispatching into the void.
     */
    private fun runV6Operation(
        name: String,
        onResult: (BombOperationResult) -> Unit,
        block: () -> com.hzzmonet.zkbomb.api.BombResult,
    ) = runAsync(
        name = name,
        onFailure = BombOperationResult("BACKEND_UNAVAILABLE", "Binder call failed"),
        onResult = onResult,
    ) {
        if (apiVersion < MIN_V6_VERSION) {
            BombOperationResult("UNSUPPORTED", "The connected service is older than v$MIN_V6_VERSION")
        } else {
            block().let { BombOperationResult(it.status.name, it.detail) }
        }
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

        // getProcessSnapshot / getSystemTelemetrySnapshot were appended in v3.
        const val MIN_SNAPSHOT_VERSION = 3

        // getPackageSnapshot / forceStopPackage / setComponentState were appended in v5.
        const val MIN_PACKAGE_VERSION = 5

        // Visibility / Settings / Firewall / AdBlock writes were appended in v6.
        const val MIN_V6_VERSION = 6

        // getSelectedProcessMemory was appended in v7.
        const val MIN_SELECTED_MEMORY_VERSION = 7
    }
}

private fun com.hzzmonet.zkbomb.api.SelectedProcessMemory.toCommon(): BombSelectedProcessMemory =
    BombSelectedProcessMemory(
        pid = pid,
        sampledAtElapsedRealtimeMillis = sampledAtElapsedRealtimeMillis,
        status = BombSelectedMemoryStatus.fromName(status),
        pssBytes = pssBytes,
        privateDirtyBytes = privateDirtyBytes,
        rssBytes = rssBytes,
    )

private fun com.hzzmonet.zkbomb.api.PackageComponentInfo.toCommon(): BombPackageComponent =
    BombPackageComponent(
        kind = kind,
        className = className,
        processName = processName,
        manifestEnabled = manifestEnabled,
        effectiveEnabled = effectiveEnabled,
        exported = exported,
        permission = permission,
        overrideState = overrideState,
        authorities = authorities,
    )

private fun com.hzzmonet.zkbomb.api.PackageSnapshot.toCommon(): BombPackageSnapshot =
    BombPackageSnapshot(
        packageName = packageName,
        userId = userId,
        uid = uid,
        label = label,
        versionName = versionName,
        longVersionCode = longVersionCode,
        targetSdkVersion = targetSdkVersion,
        minSdkVersion = minSdkVersion,
        enabled = enabled,
        stopped = stopped,
        suspended = suspended,
        systemApp = systemApp,
        debuggable = debuggable,
        installerPackageName = installerPackageName,
        requestedPermissions = requestedPermissions,
        signingCertificateSha256 = signingCertificateSha256,
        processNames = processNames,
        components = components.map { it.toCommon() },
        totalComponentCount = totalComponentCount,
        componentsTruncated = componentsTruncated,
        protectionReason = protectionReason,
    )

private fun com.hzzmonet.zkbomb.api.ProcessInfo.toCommon(): BombProcessInfo = BombProcessInfo(
    pid = pid,
    uid = uid,
    userId = userId,
    processName = processName,
    packageNames = packageNames,
    importance = importance,
    foreground = foreground,
    pssBytes = pssBytes,
    privateDirtyBytes = privateDirtyBytes,
    rssBytes = rssBytes,
    cpuTimeTicks = cpuTimeTicks,
    threadCount = threadCount,
    startTimeTicks = startTimeTicks,
    categoryName = categoryName,
)

private fun com.hzzmonet.zkbomb.api.SystemTelemetrySnapshot.toCommon(): BombSystemTelemetry =
    BombSystemTelemetry(
        sampledAtElapsedRealtimeMillis = sampledAtElapsedRealtimeMillis,
        uptimeMillis = uptimeMillis,
        totalMemoryBytes = totalMemoryBytes,
        availableMemoryBytes = availableMemoryBytes,
        lowMemoryThresholdBytes = lowMemoryThresholdBytes,
        lowMemory = lowMemory,
        cpuTotalTicks = cpuTotalTicks,
        cpuIdleTicks = cpuIdleTicks,
        thermalStatus = thermalStatus,
        batteryPercent = batteryPercent,
        batteryCharging = batteryCharging,
        batteryTemperatureDeciCelsius = batteryTemperatureDeciCelsius,
        batteryVoltageMillivolts = batteryVoltageMillivolts,
        batteryCurrentMicroamps = batteryCurrentMicroamps,
        totalRxBytes = totalRxBytes,
        totalTxBytes = totalTxBytes,
    )

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
