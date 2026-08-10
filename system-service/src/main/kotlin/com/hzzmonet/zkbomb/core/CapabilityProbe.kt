package com.hzzmonet.zkbomb.core

import android.content.Context
import android.content.pm.PackageManager
import com.hzzmonet.zkbomb.api.BombCapabilities
import com.hzzmonet.zkbomb.api.BombCapability
import com.hzzmonet.zkbomb.api.BombResult
import com.hzzmonet.zkbomb.api.CapabilityState

/**
 * Answers, by probing, what this device and this installation can actually do.
 *
 * Two rules govern every line below:
 *
 * 1. **Nothing defaults to supported.** A capability this class forgets to set
 *    reads as `NOT_PROBED`, and a UI treats that as unusable. The failure mode
 *    of an omission is therefore a hidden control, not a control that throws
 *    when tapped.
 * 2. **Holding a permission is not the same as the operation working.** Where a
 *    permission is genuinely sufficient it is checked; where it is not — call
 *    recording is the clear case — the honest answer is `UNSUPPORTED` until a
 *    real capture probe has run, and that probe does not exist yet.
 */
class CapabilityProbe(
    private val context: Context,
    private val zram: ZramReader = ZramReader(),
    private val properties: SystemPropertyReader = SystemPropertyReader(),
    private val mode: RuntimeModeDetector = RuntimeModeDetector(context, properties),
    private val controlWriter: RomControlPropertyWriter = RomControlPropertyWriter(),
    private val freeze: FreezeBackend = FreezeBackend(context),
    private val processTelemetry: ProcessTelemetryBackend = ProcessTelemetryBackend(context),
    private val packageControl: PackageControlBackend = PackageControlBackend(context, freeze),
) {

    fun probe(): BombCapabilities {
        val builder = BombCapabilities.Builder()
        val romMode = mode.romDeclared()

        // ---- Task Manager and telemetry --------------------------------------
        // Measured, not inferred. Android mounts /proc with hidepid so an
        // ordinary app sees only itself; whether a privileged one sees more
        // depends on the mount options this ROM used, and no permission or mode
        // flag answers that. So the probe counts what is actually visible.
        val seesProcesses = processTelemetry.hasGlobalProcessVisibility()
        val processState = when {
            seesProcesses -> CapabilityState.SUPPORTED
            // ROM mode was declared but /proc is still hidden: the integration
            // did not include the mount change. Reported as unsupported rather
            // than requires-root, because root would not help either — it is a
            // property of how /proc was mounted at boot.
            romMode -> CapabilityState.UNSUPPORTED
            else -> CapabilityState.REQUIRES_ROOT
        }
        builder.set(BombCapability.TASK_MANAGER, processState)
        val readsOtherProcessStat = seesProcesses && processTelemetry.hasOtherProcessStatAccess()
        builder.set(
            BombCapability.PROCESS_CPU_TELEMETRY,
            if (readsOtherProcessStat) CapabilityState.SUPPORTED else CapabilityState.UNSUPPORTED,
        )
        builder.set(
            BombCapability.THREAD_TELEMETRY,
            if (readsOtherProcessStat) CapabilityState.SUPPORTED else CapabilityState.UNSUPPORTED,
        )
        builder.set(
            BombCapability.PROCESS_PSS_TELEMETRY,
            if (processTelemetry.hasSelectedProcessMemoryAccess()) {
                CapabilityState.SUPPORTED
            } else {
                CapabilityState.UNSUPPORTED
            },
        )
        builder.set(
            BombCapability.SYSTEM_CPU_TELEMETRY,
            if (processTelemetry.hasSystemCpuAccess()) {
                CapabilityState.SUPPORTED
            } else {
                CapabilityState.UNSUPPORTED
            },
        )
        // Vendor GPU and thermal nodes differ per SoC and none has been probed.
        builder.set(BombCapability.GPU_TELEMETRY, CapabilityState.NOT_PROBED)
        builder.set(
            BombCapability.THERMAL_TELEMETRY,
            if (processTelemetry.hasThermalTelemetry()) {
                CapabilityState.SUPPORTED
            } else {
                CapabilityState.UNSUPPORTED
            },
        )
        builder.set(
            BombCapability.POWER_TELEMETRY,
            if (processTelemetry.hasPowerTelemetry()) {
                CapabilityState.SUPPORTED
            } else {
                CapabilityState.UNSUPPORTED
            },
        )
        // Frame timing for Bomb's own window is public API and genuinely works.
        builder.set(BombCapability.FPS_TELEMETRY, CapabilityState.SUPPORTED)

        // ---- Freeze -----------------------------------------------------------
        val freezeCapabilities = freeze.capabilities()
        builder.set(
            BombCapability.DEEP_FREEZE,
            if (freezeCapabilities.suspend) CapabilityState.SUPPORTED else CapabilityState.REQUIRES_ROOT,
        )
        // The platform freezer is driven by the system's own cached-app
        // optimizer; an unprivileged app cannot express intent to it at all.
        builder.set(BombCapability.SOFT_FREEZE, CapabilityState.REQUIRES_ROOT)
        builder.set(
            BombCapability.COMPONENT_CONTROL,
            if (packageControl.canControlComponents()) {
                CapabilityState.SUPPORTED
            } else {
                CapabilityState.REQUIRES_ROOT
            },
        )
        builder.set(BombCapability.FRAMEWORK_PROCESS_CONTROL, CapabilityState.REQUIRES_ROOT)
        builder.set(
            BombCapability.PACKAGE_FORCE_STOP,
            if (freeze.canForceStop()) CapabilityState.SUPPORTED else CapabilityState.REQUIRES_ROOT,
        )

        // ---- Framework patches ------------------------------------------------
        // ROM-mode only by decision D6: these are patches inside system_server,
        // so no amount of app-side privilege reaches them.
        builder.set(
            BombCapability.PACKAGE_VISIBILITY_VIRTUALIZATION,
            frameworkPatchState(romMode, "ro.bomb.framework.visibility"),
        )
        builder.set(
            BombCapability.SETTINGS_VIRTUALIZATION,
            frameworkPatchState(romMode, "ro.bomb.framework.settings"),
        )

        // ---- Network ----------------------------------------------------------
        builder.set(BombCapability.AD_BLOCK, CapabilityState.NOT_PROBED)
        builder.set(BombCapability.DNS_CONTROL, CapabilityState.REQUIRES_ROOT)
        builder.set(BombCapability.FIREWALL, CapabilityState.REQUIRES_ROOT)
        builder.set(BombCapability.PROXY_GATEWAY, CapabilityState.REQUIRES_ROOT)

        // ---- Device control ---------------------------------------------------
        val zramCaps = zram.probe()
        builder.set(
            BombCapability.ZRAM_CONTROL,
            when {
                !zramCaps.present -> CapabilityState.UNSUPPORTED
                romMode && controlWriter.available -> CapabilityState.SUPPORTED
                else -> CapabilityState.REQUIRES_ROOT
            },
        )
        builder.set(BombCapability.PERFORMANCE_CONTROL, CapabilityState.REQUIRES_ROOT)
        builder.set(BombCapability.CHARGE_CONTROL, CapabilityState.NOT_PROBED)
        builder.set(
            BombCapability.AUTOMATION_RULES,
            if (context.getSystemService(android.app.usage.UsageStatsManager::class.java) != null) {
                CapabilityState.SUPPORTED
            } else {
                CapabilityState.UNSUPPORTED
            },
        )

        // ---- Logging ----------------------------------------------------------
        builder.set(
            BombCapability.LOG_REDUCE,
            when {
                !properties.available -> CapabilityState.UNSUPPORTED
                // A ROM that declares Bomb integrated ships the init trigger the
                // REDUCED tier is driven by; that trigger is part of the same
                // integration the flag asserts.
                romMode && controlWriter.available -> CapabilityState.SUPPORTED
                else -> CapabilityState.REQUIRES_ROOT
            },
        )
        builder.set(
            BombCapability.LOG_DISABLE,
            when {
                romMode && controlWriter.available -> CapabilityState.SUPPORTED
                else -> CapabilityState.REQUIRES_ROOT
            },
        )

        // ---- Bridge -----------------------------------------------------------
        // Posting a notification is public API and is the floor renderer.
        builder.set(
            BombCapability.LIVE_UPDATE_BRIDGE,
            if (holds("android.permission.POST_NOTIFICATIONS")) {
                CapabilityState.SUPPORTED
            } else {
                CapabilityState.UNSUPPORTED
            },
        )
        // HyperIsland needs three separate signals, none of them checked yet.
        builder.set(BombCapability.HYPER_ISLAND_BRIDGE, CapabilityState.NOT_PROBED)

        // ---- Recording --------------------------------------------------------
        // Deliberately not derived from permissions. Holding CAPTURE_AUDIO_OUTPUT
        // says nothing about whether VOICE_CALL yields non-silent frames on this
        // ROM, and only a real capture answers that.
        builder.set(BombCapability.PHONE_RECORDING, CapabilityState.NOT_PROBED)
        builder.set(BombCapability.VOIP_RECORDING, CapabilityState.UNSUPPORTED)

        // ---- AirDrop interop ---------------------------------------------------
        builder.set(BombCapability.AIRDROP_INTEROP, airDropState())

        return builder.build()
    }

    /**
     * The target ROM declares the mosey HAL in its VINTF matrix and whitelists
     * the package, but ships no `mosey_server` and no app — so neither
     * "supported" nor "unsupported" is true, and the state that says exactly
     * that is the right answer.
     */
    private fun airDropState(): CapabilityState = when {
        installed(MOSEY_PACKAGE) -> CapabilityState.SUPPORTED
        properties.get("ro.vendor.mosey.support") != null -> CapabilityState.DECLARED_NOT_IMPLEMENTED
        else -> CapabilityState.UNSUPPORTED
    }

    private fun holds(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun frameworkPatchState(romMode: Boolean, marker: String): CapabilityState =
        FrameworkBridgeGate.markerOnlyCapabilityState(
            romDeclared = romMode,
            patchMarkerDeclared = properties.get(marker) == "1",
        )

    private fun installed(packageName: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    }.getOrDefault(false)

    private companion object {
        const val MOSEY_PACKAGE = "com.google.android.mosey"
    }
}

/**
 * Fail-closed boundary for framework-owned features.
 *
 * A ROM/property marker describes image intent only. Until system_server exposes
 * a real bridge handshake and acknowledges the applied policy revision, the
 * service has no evidence that either framework hook can consume its state.
 */
internal object FrameworkBridgeGate {
    fun markerOnlyCapabilityState(
        romDeclared: Boolean,
        patchMarkerDeclared: Boolean,
    ): CapabilityState {
        if (!romDeclared || !patchMarkerDeclared) return CapabilityState.UNSUPPORTED
        return CapabilityState.UNSUPPORTED
    }

    fun executeWrite(
        capabilityState: CapabilityState,
        unavailableDetail: String,
        mutation: () -> BombResult,
    ): BombResult = if (capabilityState == CapabilityState.SUPPORTED) {
        mutation()
    } else {
        BombResult.unsupported(unavailableDetail)
    }
}
