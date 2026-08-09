package com.hzzmonet.zkbomb.core

import android.content.Context
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

    fun detect(): BombRuntimeMode {
        if (romDeclared()) return BombRuntimeMode.ROM
        if (rootBackendPresent()) return BombRuntimeMode.ROOT
        return BombRuntimeMode.NORMAL
    }

    /**
     * The ROM set the marker to exactly `"1"`.
     *
     * Note this is checked even when properties are otherwise unreadable through
     * reflection — [SystemPropertyReader] reports that honestly, and a device
     * where the marker cannot be read is reported as NORMAL rather than as ROM,
     * which is the safe direction: it under-claims.
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
     * Whether Bomb is placed in a priv-app directory.
     *
     * Derived from the APK path, which is how `PackageManagerService` decides.
     * Reported alongside the mode because the two can disagree — a ROM that sets
     * the marker but ships the APK to the wrong partition is a real mistake, and
     * one that is otherwise very hard to see.
     */
    fun privAppPlacement(): Boolean =
        context.applicationInfo.sourceDir?.contains("/priv-app/") == true

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
    }
}
