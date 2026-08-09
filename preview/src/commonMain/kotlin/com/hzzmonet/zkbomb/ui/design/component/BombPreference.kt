package com.hzzmonet.zkbomb.ui.design.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hzzmonet.zkbomb.ui.design.BombDimens
import com.hzzmonet.zkbomb.ui.design.BombIcon
import com.hzzmonet.zkbomb.ui.design.BombTheme
import com.hzzmonet.zkbomb.ui.design.LocalBombSurfaceAlpha
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.TabRowDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.extra.SuperArrow
import top.yukonga.miuix.kmp.extra.SuperSwitch

/**
 * Navigating preference row. [value] renders as the trailing summary value the
 * way HyperOS settings rows do ("Freeze — Smart").
 */
@Composable
fun BombPreference(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    value: String? = null,
    icon: ImageVector? = null,
    iconTint: Color? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    SuperArrow(
        title = title,
        summary = summary,
        modifier = modifier,
        enabled = enabled,
        startAction = icon?.let {
            {
                BombLeadingIcon(icon = it, tint = iconTint ?: BombTheme.miuix.primary)
            }
        },
        endActions = {
            if (value != null) {
                Text(
                    text = value,
                    fontSize = 14.sp,
                    color = BombTheme.miuix.onSurfaceVariantActions,
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
        },
        onClick = onClick,
    )
}

@Composable
fun BombSwitchPreference(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    icon: ImageVector? = null,
    iconTint: Color? = null,
    enabled: Boolean = true,
) {
    SuperSwitch(
        title = title,
        summary = summary,
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        startAction = icon?.let {
            {
                BombLeadingIcon(icon = it, tint = iconTint ?: BombTheme.miuix.primary)
            }
        },
    )
}

/** Slider row with a live value label — monitor intervals, opacity, scale. */
@Composable
fun BombSliderPreference(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueLabel: String,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    enabled: Boolean = true,
) {
    Column(modifier = modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                color = BombTheme.miuix.onSurface,
            )
            Text(
                text = valueLabel,
                fontSize = 14.sp,
                color = BombTheme.miuix.onSurfaceVariantActions,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        )
    }
}

/**
 * Segmented control — filters, sort modes, monitor presets.
 *
 * Two things this does that a bare MIUIX `TabRow` does not:
 *
 *  - **rounds the outer corners.** Unselected tabs each paint their own
 *    rectangle, and adjacent rectangles merge into one bar with square outer
 *    corners — which reads as a black slab against Bomb's rounded cards. The
 *    clip gives the whole row the same corner radius as [BombCard].
 *  - **respects [LocalBombSurfaceAlpha].** `TabRow` paints an opaque background
 *    of its own, so with a backdrop showing it stayed solid while every card
 *    around it was translucent. Passing the alpha through is what puts it back
 *    in the same visual plane as the rest of the page.
 */
@Composable
fun BombSegmentedButton(
    options: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val alpha = LocalBombSurfaceAlpha.current
    TabRow(
        tabs = options,
        selectedTabIndex = selectedIndex,
        onTabSelected = onSelected,
        colors = TabRowDefaults.tabRowColors(
            backgroundColor = BombTheme.miuix.surfaceContainer.copy(alpha = alpha * 0.55f),
            contentColor = BombTheme.miuix.onSurfaceVariantSummary,
            // The selected pill stays the most opaque thing in the row so the
            // selection is still obvious over a busy wallpaper.
            selectedBackgroundColor = BombTheme.miuix.surfaceContainerHigh.copy(alpha = alpha),
            selectedContentColor = BombTheme.miuix.onSurface,
        ),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(BombDimens.CardCorner)),
    )
}

@Composable
internal fun BombLeadingIcon(
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .padding(end = 14.dp)
            .size(30.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(tint.copy(alpha = 0.13f)),
        contentAlignment = Alignment.Center,
    ) {
        BombIcon(icon = icon, tint = tint, modifier = Modifier.size(18.dp))
    }
}

/** Compact tile used by the Home quick-action grid. */
@Composable
fun BombQuickAction(
    title: String,
    icon: ImageVector,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    top.yukonga.miuix.kmp.basic.Card(
        modifier = modifier,
        cornerRadius = 16.dp,
        insideMargin = PaddingValues(14.dp),
        // Without this the four Home tiles were the only opaque surfaces on the
        // page — every other card already went through BombCard, which applies
        // the alpha. They read as black slabs sitting on top of the backdrop
        // rather than as part of it.
        colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(
            color = BombTheme.miuix.surfaceContainer.copy(alpha = LocalBombSurfaceAlpha.current),
        ),
        onClick = onClick,
        pressFeedbackType = top.yukonga.miuix.kmp.utils.PressFeedbackType.Sink,
        showIndication = true,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(tint.copy(alpha = 0.13f)),
            contentAlignment = Alignment.Center,
        ) {
            BombIcon(icon = icon, tint = tint, modifier = Modifier.size(19.dp))
        }
        Text(
            text = title,
            modifier = Modifier.padding(top = 10.dp),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = BombTheme.miuix.onSurfaceContainer,
        )
    }
}
