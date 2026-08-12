package com.hzzmonet.zkbomb.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.hzzmonet.zkbomb.api.AutomationActionParcel
import com.hzzmonet.zkbomb.api.AutomationConditionParcel
import com.hzzmonet.zkbomb.api.AutomationRuleParcel
import com.hzzmonet.zkbomb.api.AutomationRulesSnapshot
import com.hzzmonet.zkbomb.api.BatteryLabProfileParcel
import com.hzzmonet.zkbomb.api.BatteryLabSnapshot
import com.hzzmonet.zkbomb.api.BombCapability
import com.hzzmonet.zkbomb.api.BombLiveEventParcel
import com.hzzmonet.zkbomb.api.BridgeEventStatusParcel
import com.hzzmonet.zkbomb.api.BridgeStatusSnapshot
import com.hzzmonet.zkbomb.api.FirewallRuleParcel
import com.hzzmonet.zkbomb.api.FrequencyLimitRequestParcel
import com.hzzmonet.zkbomb.api.FrequencyScalingSnapshot
import com.hzzmonet.zkbomb.api.FrequencyScalingTargetParcel
import com.hzzmonet.zkbomb.api.IBombService
import com.hzzmonet.zkbomb.api.MemoryConfig
import com.hzzmonet.zkbomb.api.PerformanceProfileParcel
import com.hzzmonet.zkbomb.api.PerformanceProfilesSnapshot
import com.hzzmonet.zkbomb.api.RecordingBackendStatus
import com.hzzmonet.zkbomb.api.RecordingRequestParcel
import com.hzzmonet.zkbomb.api.ThermalGuardianConfigParcel
import com.hzzmonet.zkbomb.recorder.RecordingStore
import java.util.concurrent.ConcurrentHashMap
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
                        controller = AndroidBombServiceController(service, version, context.applicationContext),
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
    private val appContext: Context,
) : BombServiceController {
    private val main = Handler(Looper.getMainLooper())

    // Platform-recording start times keyed by session id, so a finished capture
    // can be committed to the store with a real duration. Best-effort: lost across
    // a process restart, in which case the row is committed with an unknown length.
    private val recordingStartedAt = ConcurrentHashMap<String, Long>()

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

    override fun getAutomationRules(onResult: (BombAutomationSnapshot?) -> Unit) =
        runAsync("automation-rules", onFailure = null, onResult = onResult) {
            if (apiVersion < MIN_AUTOMATION_VERSION) return@runAsync null
            service.automationRules?.toCommon()
        }

    override fun upsertAutomationRule(rule: BombAutomationRule, onResult: (BombOperationResult) -> Unit) =
        runGuardedOperation(MIN_AUTOMATION_VERSION, "automation-upsert", onResult) {
            service.upsertAutomationRule(rule.toParcel())
        }

    override fun deleteAutomationRule(ruleId: String, onResult: (BombOperationResult) -> Unit) =
        runGuardedOperation(MIN_AUTOMATION_VERSION, "automation-delete", onResult) {
            service.deleteAutomationRule(ruleId)
        }

    override fun setAutomationEnabled(enabled: Boolean, onResult: (BombOperationResult) -> Unit) =
        runGuardedOperation(MIN_AUTOMATION_VERSION, "automation-enable", onResult) {
            service.setAutomationEnabled(enabled)
        }

    override fun getBatteryLabSnapshot(onResult: (BombBatteryLabSnapshot?) -> Unit) =
        runAsync("battery-lab", onFailure = null, onResult = onResult) {
            if (apiVersion < MIN_BATTERY_LAB_VERSION) return@runAsync null
            service.batteryLabSnapshot?.toCommon()
        }

    override fun setBatteryLabProfile(profile: BombBatteryLabProfile, onResult: (BombOperationResult) -> Unit) =
        runGuardedOperation(MIN_BATTERY_LAB_VERSION, "battery-set", onResult) {
            service.setBatteryLabProfile(profile.toParcel())
        }

    override fun clearBatteryLabProfile(onResult: (BombOperationResult) -> Unit) =
        runGuardedOperation(MIN_BATTERY_LAB_VERSION, "battery-clear", onResult) {
            service.clearBatteryLabProfile()
        }

    override fun getBridgeStatus(onResult: (BombBridgeStatus?) -> Unit) =
        runAsync("bridge-status", onFailure = null, onResult = onResult) {
            if (apiVersion < MIN_BRIDGE_VERSION) return@runAsync null
            service.bridgeStatus?.toCommon()
        }

    override fun publishLiveEvent(event: BombLiveEvent, onResult: (BombOperationResult) -> Unit) =
        runGuardedOperation(MIN_BRIDGE_VERSION, "bridge-publish", onResult) {
            service.publishLiveEvent(event.toParcel())
        }

    override fun dismissLiveEvent(eventId: String, onResult: (BombOperationResult) -> Unit) =
        runGuardedOperation(MIN_BRIDGE_VERSION, "bridge-dismiss", onResult) {
            service.dismissLiveEvent(eventId)
        }

    override fun getPerformanceProfiles(onResult: (BombPerformanceProfilesSnapshot?) -> Unit) =
        runAsync("performance-profiles", onFailure = null, onResult = onResult) {
            if (apiVersion < MIN_BRIDGE_VERSION) return@runAsync null
            service.performanceProfiles?.toCommon()
        }

    override fun setPerformanceProfile(profileName: String, onResult: (BombOperationResult) -> Unit) =
        runGuardedOperation(MIN_BRIDGE_VERSION, "performance-set", onResult) {
            service.setPerformanceProfile(profileName)
        }

    override fun clearPerformanceProfile(onResult: (BombOperationResult) -> Unit) =
        runGuardedOperation(MIN_BRIDGE_VERSION, "performance-clear", onResult) {
            service.clearPerformanceProfile()
        }

    override fun setThermalGuardianConfig(
        config: BombThermalGuardianConfig,
        onResult: (BombOperationResult) -> Unit,
    ) = runGuardedOperation(MIN_BRIDGE_VERSION, "thermal-guardian-set", onResult) {
        service.setThermalGuardianConfig(config.toParcel())
    }

    override fun clearThermalGuardianConfig(onResult: (BombOperationResult) -> Unit) =
        runGuardedOperation(MIN_BRIDGE_VERSION, "thermal-guardian-clear", onResult) {
            service.clearThermalGuardianConfig()
        }

    override fun getRecordingBackendStatus(onResult: (BombRecordingBackendStatus?) -> Unit) =
        runAsync("recording-status", onFailure = null, onResult = onResult) {
            if (apiVersion < MIN_RECORDING_VERSION) return@runAsync null
            service.recordingBackendStatus?.also(::finalizeEndedRecording)?.toCommon()
        }

    override fun startCallRecording(kind: String, onResult: (BombOperationResult) -> Unit) =
        runAsync(
            name = "call-record-start",
            onFailure = BombOperationResult("BACKEND_UNAVAILABLE", "Binder call failed"),
            onResult = onResult,
        ) {
            if (apiVersion < MIN_RECORDING_VERSION) {
                return@runAsync BombOperationResult(
                    "UNSUPPORTED",
                    "The connected service is older than v$MIN_RECORDING_VERSION",
                )
            }
            // The backend records into a caller-owned fd only — never a path. Allocate
            // the output in the shared recordings store so a finished capture lists and
            // is subject to the same retention as VoIP recordings.
            val store = RecordingStore(appContext)
            val allocation = store.allocate()
            val result = try {
                ParcelFileDescriptor.open(
                    allocation.file,
                    ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE,
                ).use { fd ->
                    // AAC/m4a is the only format the platform backend accepts; the session
                    // id doubles as the store id so Stop can commit the same file.
                    service.startCallRecording(
                        RecordingRequestParcel(allocation.id, kind, "AAC_M4A"),
                        fd,
                    )
                }
            } catch (failure: Throwable) {
                store.delete(allocation.id)
                throw failure
            }
            val mapped = BombOperationResult(result.status.name, result.detail)
            if (mapped.isSuccess) {
                recordingStartedAt[allocation.id] = SystemClock.elapsedRealtime()
            } else {
                // Nothing was captured — do not leave an orphan file behind.
                store.delete(allocation.id)
            }
            mapped
        }

    override fun stopCallRecording(sessionId: String, onResult: (BombOperationResult) -> Unit) =
        runAsync(
            name = "call-record-stop",
            onFailure = BombOperationResult("BACKEND_UNAVAILABLE", "Binder call failed"),
            onResult = onResult,
        ) {
            if (apiVersion < MIN_RECORDING_VERSION) {
                return@runAsync BombOperationResult(
                    "UNSUPPORTED",
                    "The connected service is older than v$MIN_RECORDING_VERSION",
                )
            }
            val result = service.stopCallRecording(sessionId)
            val mapped = BombOperationResult(result.status.name, result.detail)
            // The backend deliberately returns FAILED for a completed-but-silent
            // capture. Re-read status so both successful and silent terminal files
            // receive metadata, while genuine failures are removed.
            service.recordingBackendStatus?.also(::finalizeEndedRecording)
            mapped
        }

    private fun finalizeEndedRecording(status: RecordingBackendStatus) {
        if (status.state != "IDLE" || recordingStartedAt.isEmpty()) return
        val entry = recordingStartedAt.entries.firstOrNull() ?: return
        if (!recordingStartedAt.remove(entry.key, entry.value)) return

        val durationMillis = (SystemClock.elapsedRealtime() - entry.value).coerceAtLeast(0)
        val store = RecordingStore(appContext)
        when (status.lastOutcome) {
            "COMPLETED", "SILENT" -> store.commit(
                id = entry.key,
                packageName = "Platform call recording",
                startedAtEpochMillis = System.currentTimeMillis() - durationMillis,
                durationMillis = durationMillis,
                silent = status.lastOutcome == "SILENT",
            )
            else -> store.delete(entry.key)
        }
    }

    override fun getFrequencyScalingSnapshot(onResult: (BombClockSnapshot?) -> Unit) =
        runAsync("frequency-snapshot", onFailure = null, onResult = onResult) {
            if (apiVersion < MIN_CLOCK_VERSION) return@runAsync null
            service.frequencyScalingSnapshot
                ?.takeIf { it.isStructurallyValid() }
                ?.toCommon()
        }

    override fun setFrequencyLimits(
        targetId: String,
        kind: String,
        minMHz: Int,
        maxMHz: Int,
        onResult: (BombOperationResult) -> Unit,
    ) = runGuardedOperation(MIN_CLOCK_VERSION, "frequency-set", onResult) {
        service.setFrequencyLimits(FrequencyLimitRequestParcel(targetId, kind, minMHz, maxMHz))
    }

    override fun resetFrequencyLimits(
        targetId: String,
        kind: String,
        onResult: (BombOperationResult) -> Unit,
    ) =
        runAsync(
            name = "frequency-reset",
            onFailure = BombOperationResult("BACKEND_UNAVAILABLE", "Binder call failed"),
            onResult = onResult,
        ) {
            if (apiVersion < MIN_CLOCK_VERSION) {
                return@runAsync BombOperationResult(
                    "UNSUPPORTED",
                    "The connected service is older than v$MIN_CLOCK_VERSION",
                )
            }
            val snapshot = service.frequencyScalingSnapshot
                ?.takeIf { it.isStructurallyValid() }
                ?: return@runAsync BombOperationResult(
                    "BACKEND_UNAVAILABLE",
                    "A fresh frequency snapshot is unavailable",
                )
            val target = snapshot.targets.firstOrNull { it.id == targetId && it.kind == kind }
                ?: return@runAsync BombOperationResult("UNSUPPORTED", "Frequency target is absent")
            val floor = target.availableMHz.firstOrNull()
            val ceiling = target.availableMHz.lastOrNull()
            if (floor == null || ceiling == null) {
                return@runAsync BombOperationResult("BACKEND_UNAVAILABLE", "Frequency ladder is empty")
            }
            service.setFrequencyLimits(
                FrequencyLimitRequestParcel(targetId, kind, floor, ceiling),
            ).let { BombOperationResult(it.status.name, it.detail) }
        }

    /**
     * Run a write guarded on a minimum contract version: an older service does not
     * have the transaction, so asking would land on nothing — return UNSUPPORTED
     * instead of dispatching into the void.
     */
    private fun runGuardedOperation(
        minVersion: Int,
        name: String,
        onResult: (BombOperationResult) -> Unit,
        block: () -> com.hzzmonet.zkbomb.api.BombResult,
    ) = runAsync(
        name = name,
        onFailure = BombOperationResult("BACKEND_UNAVAILABLE", "Binder call failed"),
        onResult = onResult,
    ) {
        if (apiVersion < minVersion) {
            BombOperationResult("UNSUPPORTED", "The connected service is older than v$minVersion")
        } else {
            block().let { BombOperationResult(it.status.name, it.detail) }
        }
    }

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

        // Automation (getAutomationRules/upsert/delete/setEnabled) was appended in v8.
        const val MIN_AUTOMATION_VERSION = 8

        // Battery Lab (getBatteryLabSnapshot/set/clear) was appended in v9.
        const val MIN_BATTERY_LAB_VERSION = 9

        // Bridge + Performance Profiles + Thermal Guardian were appended in v10.
        const val MIN_BRIDGE_VERSION = 10

        // Call / VoIP platform recording (status/start/stop) was appended in v11.
        const val MIN_RECORDING_VERSION = 11

        // CPU/GPU dynamic frequency snapshot and min/max limits — v12.
        const val MIN_CLOCK_VERSION = 12
    }
}

private fun FrequencyScalingSnapshot.toCommon(): BombClockSnapshot =
    BombClockSnapshot(targets = targets.map { it.toCommon() })

private fun FrequencyScalingTargetParcel.toCommon(): BombClockDomain = BombClockDomain(
    id = id,
    kind = kind,
    availableMHz = availableMHz,
    boostMHz = boostMHz,
    currentMinMHz = currentMinMHz,
    currentMaxMHz = currentMaxMHz,
    writable = writable,
)

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

private fun AutomationConditionParcel.toCommon(): BombAutomationCondition =
    BombAutomationCondition(type = type, value = value)

private fun AutomationActionParcel.toCommon(): BombAutomationAction =
    BombAutomationAction(type = type, value = value, packageName = packageName, userId = userId)

private fun AutomationRuleParcel.toCommon(): BombAutomationRule = BombAutomationRule(
    id = id,
    name = name,
    enabled = enabled,
    trigger = trigger,
    triggerPackageName = triggerPackageName,
    conditions = conditions.map { it.toCommon() },
    actions = actions.map { it.toCommon() },
    scope = scope,
    priority = priority,
    cooldownMillis = cooldownMillis,
    debounceMillis = debounceMillis,
    restorePolicy = restorePolicy,
)

private fun AutomationRulesSnapshot.toCommon(): BombAutomationSnapshot = BombAutomationSnapshot(
    enabled = enabled,
    observerRunning = observerRunning,
    rules = rules.map { it.toCommon() },
    lastTrigger = lastTrigger,
    lastTriggeredAtMillis = lastTriggeredAtMillis,
)

private fun BombAutomationCondition.toParcel(): AutomationConditionParcel =
    AutomationConditionParcel(type = type, value = value)

private fun BombAutomationAction.toParcel(): AutomationActionParcel =
    AutomationActionParcel(type = type, value = value, packageName = packageName, userId = userId)

private fun BombAutomationRule.toParcel(): AutomationRuleParcel = AutomationRuleParcel(
    id = id,
    name = name,
    enabled = enabled,
    trigger = trigger,
    triggerPackageName = triggerPackageName,
    conditions = conditions.map { it.toParcel() },
    actions = actions.map { it.toParcel() },
    scope = scope,
    priority = priority,
    cooldownMillis = cooldownMillis,
    debounceMillis = debounceMillis,
    restorePolicy = restorePolicy,
)

private fun BatteryLabProfileParcel.toCommon(): BombBatteryLabProfile = BombBatteryLabProfile(
    chargeLimitPercent = chargeLimitPercent,
    maxTemperatureDeciCelsius = maxTemperatureDeciCelsius,
    capacityResumeHysteresisPercent = capacityResumeHysteresisPercent,
    temperatureResumeHysteresisDeciCelsius = temperatureResumeHysteresisDeciCelsius,
    maxChargeCurrentMicroamps = maxChargeCurrentMicroamps,
)

private fun BombBatteryLabProfile.toParcel(): BatteryLabProfileParcel = BatteryLabProfileParcel(
    chargeLimitPercent = chargeLimitPercent,
    maxTemperatureDeciCelsius = maxTemperatureDeciCelsius,
    capacityResumeHysteresisPercent = capacityResumeHysteresisPercent,
    temperatureResumeHysteresisDeciCelsius = temperatureResumeHysteresisDeciCelsius,
    maxChargeCurrentMicroamps = maxChargeCurrentMicroamps,
)

private fun BatteryLabSnapshot.toCommon(): BombBatteryLabSnapshot = BombBatteryLabSnapshot(
    sampledAtElapsedRealtimeMillis = sampledAtElapsedRealtimeMillis,
    backendStatus = BombBatteryLabBackendStatus.fromName(backendStatus),
    powerSupplyName = powerSupplyName,
    status = status,
    capacityPercent = capacityPercent,
    temperatureDeciCelsius = temperatureDeciCelsius,
    voltageMicrovolts = voltageMicrovolts,
    currentMicroamps = currentMicroamps,
    chargeCounterMicroampHours = chargeCounterMicroampHours,
    cycleCount = cycleCount,
    chargeFullMicroampHours = chargeFullMicroampHours,
    chargeFullDesignMicroampHours = chargeFullDesignMicroampHours,
    externalPowerPresent = externalPowerPresent,
    chargeControlKind = chargeControlKind,
    chargeLimitControlSupported = chargeLimitControlSupported,
    thermalChargeControlSupported = thermalChargeControlSupported,
    activeProfile = activeProfile?.toCommon(),
    chargingSuspendedByBomb = chargingSuspendedByBomb,
    lastDecisionReason = lastDecisionReason,
    chargeCurrentControlSupported = chargeCurrentControlSupported,
    maxSupportedChargeCurrentMicroamps = maxSupportedChargeCurrentMicroamps,
)

private fun BridgeEventStatusParcel.toCommon(): BombBridgeEventStatus =
    BombBridgeEventStatus(eventId = eventId, renderer = renderer, state = state, updatedAtMillis = updatedAtMillis)

private fun BridgeStatusSnapshot.toCommon(): BombBridgeStatus = BombBridgeStatus(
    notificationAvailable = notificationAvailable,
    liveUpdateAvailable = liveUpdateAvailable,
    hyperIslandFeaturePresent = hyperIslandFeaturePresent,
    hyperIslandProtocolVersion = hyperIslandProtocolVersion,
    hyperIslandPermitted = hyperIslandPermitted,
    hyperIslandPayloadAdapterAvailable = hyperIslandPayloadAdapterAvailable,
    activeEvents = activeEvents.map { it.toCommon() },
)

private fun BombLiveEvent.toParcel(): BombLiveEventParcel = BombLiveEventParcel(
    id = id,
    sourcePackage = sourcePackage,
    type = type,
    title = title,
    subtitle = subtitle,
    compactText = compactText,
    progressFraction = progressFraction,
    progressIndeterminate = progressIndeterminate,
    state = state,
    // Common UI code has no wall clock; stamp it here (the only side that does)
    // when the caller left it unset, so the event carries a real publish time.
    timestampMillis = if (timestampMillis > 0L) timestampMillis else System.currentTimeMillis(),
)

private fun PerformanceProfileParcel.toCommon(): BombPerformanceProfileDef = BombPerformanceProfileDef(
    name = name,
    swappiness = swappiness,
    pageCluster = pageCluster,
    refreshRateHz = refreshRateHz,
    cpuStrategy = cpuStrategy,
    gpuStrategy = gpuStrategy,
    thermalStrategy = thermalStrategy,
    monitorPreset = monitorPreset,
)

private fun ThermalGuardianConfigParcel.toCommon(): BombThermalGuardianConfig = BombThermalGuardianConfig(
    sustainableAtDeciCelsius = sustainableAtDeciCelsius,
    ecoAtDeciCelsius = ecoAtDeciCelsius,
    restoreAtDeciCelsius = restoreAtDeciCelsius,
    cooldownMillis = cooldownMillis,
)

private fun BombThermalGuardianConfig.toParcel(): ThermalGuardianConfigParcel = ThermalGuardianConfigParcel(
    sustainableAtDeciCelsius = sustainableAtDeciCelsius,
    ecoAtDeciCelsius = ecoAtDeciCelsius,
    restoreAtDeciCelsius = restoreAtDeciCelsius,
    cooldownMillis = cooldownMillis,
)

private fun PerformanceProfilesSnapshot.toCommon(): BombPerformanceProfilesSnapshot =
    BombPerformanceProfilesSnapshot(
        profiles = profiles.map { it.toCommon() },
        activeProfile = activeProfile,
        memoryControlAvailable = memoryControlAvailable,
        appliedFields = appliedFields,
        thermalGuardianConfig = thermalGuardianConfig?.toCommon(),
        thermalGuardianActiveProfile = thermalGuardianActiveProfile,
    )

private fun RecordingBackendStatus.toCommon(): BombRecordingBackendStatus = BombRecordingBackendStatus(
    state = state,
    activeSessionId = activeSessionId,
    activeKind = activeKind,
    startedAtElapsedRealtimeMillis = startedAtElapsedRealtimeMillis,
    cellularSupport = VoipCaptureSupport.fromName(cellularSupport),
    voipSupport = VoipCaptureSupport.fromName(voipSupport),
    capturePermissionHeld = capturePermissionHeld,
    activeClientSilenced = activeClientSilenced,
    lastOutcome = lastOutcome,
    lastPeakAmplitude = lastPeakAmplitude,
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
