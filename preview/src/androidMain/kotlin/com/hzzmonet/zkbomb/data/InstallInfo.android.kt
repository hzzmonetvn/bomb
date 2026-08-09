package com.hzzmonet.zkbomb.data

import android.content.pm.ApplicationInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberInstallInfo(): BombInstallInfo {
    val context = LocalContext.current
    return remember(context) {
        val info = context.applicationInfo
        val path = info.sourceDir
        val pkg = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        val system = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0

        BombInstallInfo(
            slot = when {
                path == null -> "unknown"
                // The path is checked before the flags on purpose: a sideloaded
                // copy in /data/app shadows a system one completely, and calling
                // that "system app" would hide the exact thing that explains why
                // privileged permissions are missing.
                path.contains("/priv-app/") -> "priv-app"
                path.startsWith("/data/app") -> "/data/app (sideloaded)"
                path.startsWith("/system") || path.startsWith("/product") ||
                    path.startsWith("/system_ext") || path.startsWith("/vendor") -> "system app"
                else -> path.substringBeforeLast('/')
            },
            // Derived from the path, not from a flag bit.
            //
            // `ApplicationInfo.FLAG_PRIVILEGED` (1<<30) is the old constant; the
            // platform moved privileged status into `privateFlags` in Android 8,
            // so reading that bit out of `flags` today tests something that may
            // no longer mean what it did. The path is how PackageManagerService
            // itself decides — it scans the priv-app directories — so it is both
            // accurate and observable without hidden API.
            privileged = path?.contains("/priv-app/") == true,
            system = system,
            apkPath = path,
            versionName = pkg?.versionName,
            versionCode = pkg?.longVersionCode,
        )
    }
}
