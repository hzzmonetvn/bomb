package com.hzzmonet.zkbomb.ui.design.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.hzzmonet.zkbomb.ui.design.BombTheme

/**
 * Renders a probed capability state.
 *
 * One place, so two screens cannot disagree about what "REQUIRES_ROOT" looks
 * like or — worse — about which states count as usable. The Freeze screen and
 * the Mode screen both showed capability state before this existed, and one of
 * them showed a hardcoded "Supported".
 */
@Composable
fun BombCapabilityBadge(state: String, modifier: Modifier = Modifier) {
    val (label, color) = capabilityLabel(state)
    BombBadge(text = label, color = color, modifier = modifier)
}

@Composable
fun capabilityLabel(state: String): Pair<String, Color> = when (state) {
    "SUPPORTED" -> "Supported" to BombTheme.colors.ok
    "REQUIRES_ROOT" -> "Needs root" to BombTheme.colors.warn
    "DECLARED_NOT_IMPLEMENTED" -> "Declared, absent" to BombTheme.colors.warn
    "UNSUPPORTED" -> "Unsupported" to BombTheme.miuix.onSurfaceVariantSummary
    // Covers NOT_PROBED and any state a newer service reports that this build
    // has never heard of. Both mean the same thing to a user: do not rely on it.
    else -> "Not probed" to BombTheme.miuix.onSurfaceVariantSummary
}
