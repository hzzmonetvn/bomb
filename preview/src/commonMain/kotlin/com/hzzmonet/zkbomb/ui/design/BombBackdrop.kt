package com.hzzmonet.zkbomb.ui.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The app backdrop.
 *
 * A wallpaper behind translucent cards, drawn rather than shipped as an image:
 * a bitmap large enough for a modern display costs a few MB in an APK that is
 * meant to stay light, and a drawn backdrop re-tints itself for light/dark and
 * for each preset without a second asset.
 *
 * Cards float over it — see [LocalBombSurfaceAlpha].
 */
enum class BombBackdropStyle(val label: String) {
    None("Off"),
    Ember("Ember"),
    Ocean("Ocean"),
    Void("Void"),

    /** The user's own picture, chosen with the system photo picker. */
    Custom("Image"),
}

/**
 * How opaque Bomb's surfaces are. 1f when no backdrop is showing; lowered so
 * the backdrop reads through the cards when one is.
 */
val LocalBombSurfaceAlpha = staticCompositionLocalOf { 1f }

@Composable
fun BombBackdrop(
    style: BombBackdropStyle,
    darkTheme: Boolean,
    modifier: Modifier = Modifier,
    image: ImageBitmap? = null,
    dim: Float = 0.35f,
    blurRadius: Dp = 0.dp,
) {
    if (style == BombBackdropStyle.None) return

    if (style == BombBackdropStyle.Custom) {
        if (image == null) return
        Box(modifier = modifier.fillMaxSize()) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (blurRadius > 0.dp) Modifier.blur(blurRadius) else Modifier),
            )
            // Scrim in the direction the text needs: darken under light text,
            // lighten under dark text. Without it a busy photo eats the UI.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        (if (darkTheme) Color.Black else Color.White).copy(alpha = dim),
                    ),
            )
        }
        return
    }

    val base: Color
    val blobs: List<Pair<Color, Offset>>
    when (style) {
        BombBackdropStyle.Ember -> {
            base = if (darkTheme) Color(0xFF0D0B0A) else Color(0xFFFDF4EE)
            blobs = listOf(
                Color(0xFFFF6A2B) to Offset(0.16f, 0.08f),
                Color(0xFFE94634) to Offset(0.92f, 0.30f),
                Color(0xFF8B5CF6) to Offset(0.30f, 0.86f),
            )
        }

        BombBackdropStyle.Ocean -> {
            base = if (darkTheme) Color(0xFF070F17) else Color(0xFFEDF5FB)
            blobs = listOf(
                Color(0xFF3482FF) to Offset(0.14f, 0.12f),
                Color(0xFF0EA5A5) to Offset(0.88f, 0.34f),
                Color(0xFF8B5CF6) to Offset(0.42f, 0.90f),
            )
        }

        BombBackdropStyle.Void -> {
            base = if (darkTheme) Color(0xFF08090B) else Color(0xFFF2F3F6)
            blobs = listOf(
                Color(0xFF64748B) to Offset(0.20f, 0.10f),
                Color(0xFF94A3B8) to Offset(0.86f, 0.42f),
                Color(0xFF334155) to Offset(0.36f, 0.88f),
            )
        }

        BombBackdropStyle.None, BombBackdropStyle.Custom -> return
    }

    // Stronger on dark, where the glow reads; restrained on light, where it
    // would otherwise fight the text.
    val blobAlpha = if (darkTheme) 0.42f else 0.30f

    Canvas(modifier = modifier.fillMaxSize()) {
        drawRect(color = base)
        blobs.forEachIndexed { index, (color, position) ->
            val radius = size.minDimension * (0.85f - index * 0.12f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = blobAlpha), color.copy(alpha = 0f)),
                    center = Offset(size.width * position.x, size.height * position.y),
                    radius = radius,
                ),
                radius = radius,
                center = Offset(size.width * position.x, size.height * position.y),
            )
        }
        // Settle the lower half so the bottom bar and long lists stay readable.
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(base.copy(alpha = 0f), base.copy(alpha = if (darkTheme) 0.55f else 0.35f)),
                startY = size.height * 0.35f,
                endY = size.height,
            ),
        )
    }
}
