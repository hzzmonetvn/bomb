package com.hzzmonet.zkbomb.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.ImageBitmap

/**
 * The user's own wallpaper for the app.
 *
 * Picking an image is a platform capability, so it is `expect`/`actual` and
 * reports honestly when it is unavailable instead of showing a button that
 * does nothing.
 */
@Stable
interface BackdropImageState {
    /** The chosen image, or null when none is set. */
    val image: ImageBitmap?

    /** Whether this build can open a picker at all. */
    val canPick: Boolean

    /** Why picking is unavailable, when [canPick] is false. */
    val unavailableReason: String?

    fun pick()

    fun clear()
}

@Composable
expect fun rememberBackdropImageState(): BackdropImageState
