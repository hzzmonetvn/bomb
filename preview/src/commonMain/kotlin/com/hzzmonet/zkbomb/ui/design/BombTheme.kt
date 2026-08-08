package com.hzzmonet.zkbomb.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * Bomb's colour roles that MIUIX does not define.
 *
 * MIUIX owns the surface/text/control palette so Bomb keeps looking like HyperOS.
 * Bomb owns three things on top of it:
 *
 *  - [accent]: the Bomb identity colour. Used sparingly — branding and the
 *    "monitoring is live" state. Never as a general button colour.
 *  - status roles ([ok]/[warn]/[critical]): thermal and impact states.
 *  - telemetry series roles: one stable colour per metric, so CPU is the same
 *    colour in a stat card, a chart and the overlay.
 */
@Immutable
data class BombColors(
    val accent: Color,
    val ok: Color,
    val warn: Color,
    val critical: Color,
    val cpu: Color,
    val gpu: Color,
    val ram: Color,
    val thermal: Color,
    val power: Color,
    val network: Color,
    val fps: Color,
    val frozen: Color,
    val chartGrid: Color,
    val isDark: Boolean,
)

fun bombLightColors(): BombColors = BombColors(
    accent = Color(0xFFFF6A2B),
    ok = Color(0xFF17A67B),
    warn = Color(0xFFE08700),
    critical = Color(0xFFE94634),
    cpu = Color(0xFF3482FF),
    gpu = Color(0xFF8B5CF6),
    ram = Color(0xFF0EA5A5),
    thermal = Color(0xFFE08700),
    power = Color(0xFF17A67B),
    network = Color(0xFF0284C7),
    fps = Color(0xFFFF6A2B),
    frozen = Color(0xFF64748B),
    chartGrid = Color(0x14000000),
    isDark = false,
)

fun bombDarkColors(): BombColors = BombColors(
    accent = Color(0xFFFF8A54),
    ok = Color(0xFF34D399),
    warn = Color(0xFFFBBF24),
    critical = Color(0xFFFF6B5A),
    cpu = Color(0xFF5B9DFF),
    gpu = Color(0xFFA78BFA),
    ram = Color(0xFF2DD4BF),
    thermal = Color(0xFFFBBF24),
    power = Color(0xFF34D399),
    network = Color(0xFF38BDF8),
    fps = Color(0xFFFF8A54),
    frozen = Color(0xFF94A3B8),
    chartGrid = Color(0x1AFFFFFF),
    isDark = true,
)

val LocalBombColors = staticCompositionLocalOf { bombLightColors() }

/** HyperOS-like spacing scale. Every Bomb component measures in these. */
object BombDimens {
    val PagePadding: Dp = 12.dp
    val CardSpacing: Dp = 12.dp
    val ItemSpacing: Dp = 8.dp
    val CardCorner: Dp = 18.dp
    val RowHeight: Dp = 56.dp
    val IconSize: Dp = 22.dp
    val AppIconSize: Dp = 40.dp
    val SectionTop: Dp = 16.dp
}

@Composable
fun BombTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    val bombColors = if (darkTheme) bombDarkColors() else bombLightColors()
    val miuixColors = if (darkTheme) darkColorScheme() else lightColorScheme()
    androidx.compose.runtime.CompositionLocalProvider(LocalBombColors provides bombColors) {
        MiuixTheme(colors = miuixColors, content = content)
    }
}

object BombTheme {
    val colors: BombColors
        @Composable @ReadOnlyComposable
        get() = LocalBombColors.current

    /** MIUIX palette, re-exported so feature code never imports MIUIX directly. */
    val miuix: top.yukonga.miuix.kmp.theme.Colors
        @Composable @ReadOnlyComposable
        get() = MiuixTheme.colorScheme
}
