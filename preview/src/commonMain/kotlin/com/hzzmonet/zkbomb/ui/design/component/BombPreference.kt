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
import com.hzzmonet.zkbomb.ui.design.BombIcon
import com.hzzmonet.zkbomb.ui.design.BombTheme
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.TabRow
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
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        )
    }
}

/** Segmented control — filters, sort modes, monitor presets. */
@Composable
fun BombSegmentedButton(
    options: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    TabRow(
        tabs = options,
        selectedTabIndex = selectedIndex,
        onTabSelected = onSelected,
        modifier = modifier.fillMaxWidth(),
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
