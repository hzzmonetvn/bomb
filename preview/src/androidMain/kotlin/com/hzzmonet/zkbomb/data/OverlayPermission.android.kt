package com.hzzmonet.zkbomb.data

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri

@Composable
actual fun rememberOverlayPermission(): OverlayPermissionState {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }

    // ACTION_MANAGE_OVERLAY_PERMISSION returns no result, but launching it for
    // one still gives a callback the moment the user comes back — which is the
    // only moment this value can have changed. Cheaper and more accurate than
    // polling, and it needs nothing beyond activity-compose, which is already a
    // dependency.
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        granted = Settings.canDrawOverlays(context)
    }

    return OverlayPermissionState(
        granted = granted,
        applicable = true,
        request = {
            val perApp = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:${context.packageName}".toUri(),
            )
            runCatching { launcher.launch(perApp) }.onFailure {
                // Some builds hide the per-app screen. The all-apps list is the
                // documented fallback and always resolves.
                runCatching {
                    launcher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                }
            }
        },
    )
}
