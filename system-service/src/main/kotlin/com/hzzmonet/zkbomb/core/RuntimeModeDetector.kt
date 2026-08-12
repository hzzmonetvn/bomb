package com.hzzmonet.zkbomb.core

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Process
import com.hzzmonet.zkbomb.api.BombRuntimeMode
import java.io.File

/**
 * Works out how Bomb is deployed.
 *
 * Order matters, and it is deliberate:
 *
 * 1. **The ROM's own declaration** (`ro.bomb.integrated=1`) wins. Only the
 *    image builder knows whether the priv-app placement, the permission
 *    allowlist and the SELinux policy were integrated — an app cannot see any of
 *    that from the inside, and inferring it from symptoms would be guessing.
 * 2. **A root backend**, detected by the module's own directory existing.
 * 3. Otherwise **NORMAL**.
 *
 * What this class does *not* do is decide capabilities. A mode says which
 * backend is supposed to be there; [CapabilityProbe] says what actually works.
 * Keeping those separate is what stops a stray property from making Bomb claim
 * features it does not have.
 */
class RuntimeModeDetector(
    private val context: Context,
    private val properties: SystemPropertyReader,
    private val root: File = File("/"),
) {

    /**
     * An updated priv-app keeps its system identity even though its active APK
     * moves to `/data/app`; that measured identity prevents a sideload update
     * from demoting the integrated backend to NORMAL.
     */
    fun detect(): BombRuntimeMode = classifyRuntimeMode(
        romDeclared = romDeclared(),
        privilegedInstall = privAppPlacement(),
        rootBackendPresent = rootBackendPresent(),
    )

    /**
     * The ROM set the marker to exactly `"1"`.
     *
     * Note this is checked even when properties are otherwise unreadable through
     * reflection — [SystemPropertyReader] reports that honestly, and a device
     * where the marker cannot be read can still be identified from PackageManager's
     * preserved privileged-system identity; otherwise it under-claims.
     */
    fun romDeclared(): Boolean =
        properties.get(BombRuntimeMode.ROM_MARKER_PROPERTY) == BombRuntimeMode.ROM_MARKER_VALUE ||
            properties.get(BombRuntimeMode.LEGACY_ROM_MARKER_PROPERTY) ==
            BombRuntimeMode.ROM_MARKER_VALUE

    /**
     * The root module's data directory exists.
     *
     * Deliberately not "is `su` on PATH": the presence of a root manager says
     * nothing about whether Bomb's own backend was installed, and a device with
     * Magisk but no Bomb module would report ROOT and then fail every call.
     */
    private fun rootBackendPresent(): Boolean =
        File(root, "data/adb/modules/bomb_backend").isDirectory

    /**
     * Whether Bomb is installed as a privileged app.
     *
     * The naive check is the APK path — `PackageManagerService` decides priv-app
     * status from where the base APK lives. But that alone is wrong for an
     * **updated** system app: once a priv-app baked into `/system/priv-app` is
     * updated through `/data/app`, its active `sourceDir` points at `/data/app`,
     * even though PackageManager keeps it a system app and preserves its
     * privileged private flag. Deciding on `sourceDir` alone would then demote a
     * still-privileged install to "sideloaded".
     *
     * So the check is layered:
     *  1. the active or public APK path is under `/priv-app/` (a clean priv-app
     *     with no update), or
     *  2. the app is a system / updated-system app **and** carries the privileged
     *     private flag — which PackageManager preserves across the `/data/app`
     *     update.
     *
     * Reflection reads the hidden `privateFlags`. If hidden-API access is blocked,
     * a real grant from Bomb's privapp XML is accepted as equivalent evidence,
     * but only together with the system/updated-system PackageManager flag.
     */
    fun privAppPlacement(): Boolean {
        val info = context.applicationInfo
        return isPrivilegedInstall(
            sourceDir = info.sourceDir,
            publicSourceDir = info.publicSourceDir,
            applicationFlags = info.flags,
            privateFlags = privateFlags(info),
            allowlistedPermissionGranted = hasPrivilegedAllowlistGrant(),
        )
    }

    /**
     * The hidden `ApplicationInfo.PRIVATE_FLAG_PRIVILEGED`, read reflectively.
     *
     * PackageManager sets this for apps in a `priv-app` directory and keeps it set
     * when such an app is updated to `/data/app`, so it survives exactly the case
     * the APK path loses. A missing field falls back to the measured privapp grant.
     */
    private fun privateFlags(info: ApplicationInfo): Int? = runCatching {
        ApplicationInfo::class.java.getField("privateFlags").getInt(info)
    }.getOrNull()

    /** A runtime check of grants originating from Bomb's partition-matched privapp XML. */
    private fun hasPrivilegedAllowlistGrant(): Boolean = PRIVILEGED_ALLOWLIST_PERMISSIONS.any {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Whether this process can see other processes in `/proc`.
     *
     * An actual measurement, not an inference from mode or permissions. Android
     * mounts `/proc` with `hidepid` so an ordinary app sees only itself; whether
     * a privileged one sees more depends on the mount options this specific ROM
     * used, which is not knowable any other way.
     *
     * The threshold is deliberately not `> 0`: the caller's own pid is always
     * visible, and a couple of transient entries would make `> 0` true on a
     * device that in fact hides everything.
     */
    fun canSeeOtherProcesses(): Boolean {
        val mine = Process.myPid().toString()
        val entries = File(root, "proc").list() ?: return false
        return entries.count { it != mine && it.toIntOrNull() != null } > VISIBLE_PROCESS_THRESHOLD
    }

    private companion object {
        const val VISIBLE_PROCESS_THRESHOLD = 3
        val PRIVILEGED_ALLOWLIST_PERMISSIONS = listOf(
            "android.permission.FORCE_STOP_PACKAGES",
            "android.permission.SUSPEND_APPS",
            "android.permission.CHANGE_COMPONENT_ENABLED_STATE",
            "android.permission.REAL_GET_TASKS",
            "android.permission.CAPTURE_AUDIO_OUTPUT",
        )
    }
}

/** Pure classifier kept outside Android objects so updated-system edge cases are unit-testable. */
internal fun isPrivilegedInstall(
    sourceDir: String?,
    publicSourceDir: String?,
    applicationFlags: Int,
    privateFlags: Int?,
    allowlistedPermissionGranted: Boolean = false,
): Boolean {
    if (sourceDir?.contains("/priv-app/") == true) return true
    if (publicSourceDir?.contains("/priv-app/") == true) return true
    val systemIdentity = applicationFlags and ApplicationInfo.FLAG_SYSTEM != 0 ||
        applicationFlags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
    val privilegedPrivateFlag = privateFlags != null &&
        privateFlags and PRIVATE_FLAG_PRIVILEGED != 0
    return systemIdentity && (privilegedPrivateFlag || allowlistedPermissionGranted)
}

// ApplicationInfo.PRIVATE_FLAG_PRIVILEGED is @hide but stable: 1 shl 3.
internal const val PRIVATE_FLAG_PRIVILEGED = 1 shl 3

internal fun classifyRuntimeMode(
    romDeclared: Boolean,
    privilegedInstall: Boolean,
    rootBackendPresent: Boolean,
): BombRuntimeMode = when {
    romDeclared || privilegedInstall -> BombRuntimeMode.ROM
    rootBackendPresent -> BombRuntimeMode.ROOT
    else -> BombRuntimeMode.NORMAL
}
