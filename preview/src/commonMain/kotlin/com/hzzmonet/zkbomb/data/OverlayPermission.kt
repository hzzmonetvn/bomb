package com.hzzmonet.zkbomb.data

import androidx.compose.runtime.Composable

/**
 * Whether Bomb may draw the stats overlay over other apps, and how to ask.
 *
 * `SYSTEM_ALERT_WINDOW` is a special permission: it is never granted by a
 * runtime dialog, only by the user toggling it in Settings after being sent
 * there. So this is a state plus an action, not a `requestPermissions` call.
 *
 * The Monitor screen previously showed a preview of the overlay, a preset
 * picker and a row of switches with nothing behind them — the permission was
 * not in the manifest and was never requested, so no overlay could appear on any
 * device. Controls that cannot do anything are worse than absent ones: they read
 * as broken rather than as unbuilt.
 */
data class OverlayPermissionState(
    /** True when the overlay can actually be shown right now. */
    val granted: Boolean,
    /**
     * Whether this platform has such a permission at all.
     *
     * False on non-Android targets, where the whole card is hidden rather than
     * shown as permanently denied.
     */
    val applicable: Boolean,
    /** Sends the user to the system screen where the permission is granted. */
    val request: () -> Unit,
)

@Composable
expect fun rememberOverlayPermission(): OverlayPermissionState
