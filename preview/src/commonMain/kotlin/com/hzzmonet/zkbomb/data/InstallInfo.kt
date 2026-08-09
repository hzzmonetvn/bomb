package com.hzzmonet.zkbomb.data

import androidx.compose.runtime.Composable

/**
 * Where this copy of Bomb is installed, and therefore what it is eligible for.
 *
 * Privileged permissions are granted only to an app in a `priv-app` directory
 * **and** named in a permission allowlist on the same partition. Placement alone
 * is not enough, and neither is the allowlist — so the UI reports the placement
 * it can see and lets the capability probe report what was actually granted.
 *
 * The failure this makes visible: a sideloaded copy in `/data/app` shadows a
 * system one completely, and nothing about the resulting symptoms points at that
 * as the cause.
 */
data class BombInstallInfo(
    /** Human-readable install slot: "priv-app", "system app", "/data/app", … */
    val slot: String,
    /** The platform considers this a privileged app. */
    val privileged: Boolean,
    /** The platform considers this a system app (privileged or not). */
    val system: Boolean,
    /** Full APK path, for the case where the slot alone does not explain things. */
    val apkPath: String?,
    /**
     * The version the platform reports for the installed package.
     *
     * Read from `PackageInfo`, never from a constant in the source. A hardcoded
     * copy drifts the moment someone bumps one and not the other — which is
     * exactly what happened here: the manifest said 0.2.0 while the UI showed
     * 0.1.0, and neither had moved in six builds, so there was no way to tell
     * which build was installed.
     */
    val versionName: String?,
    val versionCode: Long?,
) {
    /** "v0.6.0 (6)" — or "unknown" when the platform did not report it. */
    val versionDisplay: String
        get() = when {
            versionName == null -> "unknown"
            versionCode == null -> "v$versionName"
            else -> "v$versionName ($versionCode)"
        }
}

@Composable
expect fun rememberInstallInfo(): BombInstallInfo
