package com.hzzmonet.zkbomb.ui.design.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hzzmonet.zkbomb.data.rememberAppIcon
import com.hzzmonet.zkbomb.ui.design.BombDimens
import com.hzzmonet.zkbomb.ui.design.BombTheme
import top.yukonga.miuix.kmp.basic.Text

/**
 * Stand-in for the launcher icon.
 *
 * Android draws the real PackageManager icon and falls back to an initial on a
 * deterministic tint when the icon cannot be loaded.
 */
@Composable
fun BombAppAvatar(
    label: String,
    tint: Color,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = BombDimens.AppIconSize,
    iconPackage: String? = null,
) {
    // Prefer the real launcher icon; keep a deterministic fallback.
    val icon = if (iconPackage != null) rememberAppIcon(iconPackage) else null
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size / 3.4f))
            .then(if (icon == null) Modifier.background(tint.copy(alpha = 0.18f)) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = label.take(1).uppercase(),
                fontSize = (size.value * 0.42f).sp,
                fontWeight = FontWeight.SemiBold,
                color = tint,
            )
        }
    }
}

/** App list row: icon, name, package, state badges, trailing value. */
@Composable
fun BombAppRow(
    name: String,
    packageName: String,
    tint: Color,
    modifier: Modifier = Modifier,
    badges: List<Pair<String, Color>> = emptyList(),
    trailing: String? = null,
    iconPackage: String? = null,
    onClick: (() -> Unit)? = null,
) {
    BombRowContainer(modifier = modifier, onClick = onClick) {
        BombAppAvatar(label = name, tint = tint, iconPackage = iconPackage)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Text(
                text = name,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = BombTheme.miuix.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.padding(top = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (badges.isEmpty()) {
                    Text(
                        text = packageName,
                        fontSize = 12.sp,
                        color = BombTheme.miuix.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    badges.forEach { (text, color) -> BombBadge(text = text, color = color) }
                }
            }
        }
        if (trailing != null) {
            Text(
                text = trailing,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = BombTheme.miuix.onSurfaceVariantActions,
            )
        }
    }
}

/** Task Manager row: identity on the left, the two live numbers on the right. */
@Composable
fun BombProcessRow(
    name: String,
    processName: String,
    pid: Int,
    // Nullable on purpose: a snapshot the framework withheld a value from shows
    // "—", never a fabricated 0.
    cpuPercent: Float?,
    memoryMb: Int?,
    tint: Color,
    modifier: Modifier = Modifier,
    state: String? = null,
    onClick: (() -> Unit)? = null,
) {
    BombRowContainer(modifier = modifier, onClick = onClick) {
        BombAppAvatar(label = name, tint = tint, size = 34.dp)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = name,
                    modifier = Modifier.weight(1f, fill = false),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = BombTheme.miuix.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (state != null) {
                    Box(modifier = Modifier.padding(start = 6.dp)) {
                        BombBadge(text = state, color = BombTheme.colors.frozen)
                    }
                }
            }
            Text(
                text = "$processName · PID $pid",
                modifier = Modifier.padding(top = 3.dp),
                fontSize = 12.sp,
                color = BombTheme.miuix.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = cpuPercent?.let { "${formatOneDecimal(it)}%" } ?: "—",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (cpuPercent != null) {
                    BombTheme.colors.cpu
                } else {
                    BombTheme.miuix.onSurfaceVariantSummary
                },
            )
            Text(
                text = memoryMb?.let { "$it MB" } ?: "—",
                modifier = Modifier.padding(top = 2.dp),
                fontSize = 12.sp,
                color = BombTheme.miuix.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun BombRowContainer(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** Kotlin/Wasm has no String.format — one decimal, done by hand. */
fun formatOneDecimal(value: Float): String {
    val scaled = (value * 10f).toInt()
    return "${scaled / 10}.${scaled % 10}"
}
