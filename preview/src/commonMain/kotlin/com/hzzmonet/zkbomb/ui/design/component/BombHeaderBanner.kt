package com.hzzmonet.zkbomb.ui.design.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hzzmonet.zkbomb.ui.design.BombIcon
import com.hzzmonet.zkbomb.ui.design.BombIcons
import com.hzzmonet.zkbomb.ui.design.BombTheme
import com.hzzmonet.zkbomb.ui.design.LocalBombSurfaceAlpha
import top.yukonga.miuix.kmp.basic.Text

/**
 * The status banner at the top of Home.
 *
 * Answers the three things worth knowing at a glance — what ROM this is, what
 * build of Bomb is running, and how Bomb currently gets its privilege — and is
 * itself the control for the third: tapping it opens the execution-mode screen.
 *
 * It is the one deliberately loud surface in the app. Everything else stays
 * quiet HyperOS rows, so the banner carries the accent gradient alone.
 */
@Composable
fun BombHeaderBanner(
    appVersion: String,
    romName: String,
    androidVersion: String,
    buildNumber: String,
    deviceName: String,
    soc: String,
    modeLabel: String,
    modeDetail: String,
    modeColor: Color,
    modeIcon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dataIsLive: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val alpha = LocalBombSurfaceAlpha.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(BombTheme.miuix.surfaceContainer.copy(alpha = alpha))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        modeColor.copy(alpha = 0.26f),
                        modeColor.copy(alpha = 0.06f),
                        Color.Transparent,
                    ),
                ),
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(18.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(modeColor.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    BombIcon(icon = modeIcon, tint = modeColor, modifier = Modifier.size(23.dp))
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 13.dp),
                ) {
                    Text(
                        text = "Bomb",
                        fontSize = 21.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = BombTheme.miuix.onSurface,
                    )
                    Text(
                        text = appVersion,
                        modifier = Modifier.padding(top = 1.dp),
                        fontSize = 12.sp,
                        color = BombTheme.miuix.onSurfaceVariantSummary,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(modeColor),
                        )
                        Text(
                            text = modeLabel,
                            modifier = Modifier.padding(start = 6.dp),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = modeColor,
                        )
                    }
                    Row(
                        modifier = Modifier.padding(top = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Change",
                            fontSize = 11.sp,
                            color = BombTheme.miuix.onSurfaceVariantActions,
                        )
                        BombIcon(
                            icon = BombIcons.Chevron,
                            tint = BombTheme.miuix.onSurfaceVariantActions,
                            modifier = Modifier
                                .padding(start = 2.dp)
                                .size(11.dp),
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 15.dp, bottom = 13.dp)
                    .height(0.75.dp)
                    .background(BombTheme.miuix.dividerLine),
            )

            BannerLine("ROM", "$romName · $androidVersion")
            BannerLine("Build", buildNumber)
            BannerLine("Device", "$deviceName · $soc")
            BannerLine("Mode", modeDetail, valueColor = modeColor)

            if (!dataIsLive) {
                Text(
                    text = "Sample values — a device build reads these from Build.*",
                    modifier = Modifier.padding(top = 10.dp),
                    fontSize = 11.sp,
                    color = BombTheme.colors.accent,
                )
            }
        }
    }
}

@Composable
private fun BannerLine(
    label: String,
    value: String,
    valueColor: Color = BombTheme.miuix.onSurface,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(0.28f),
            fontSize = 12.sp,
            color = BombTheme.miuix.onSurfaceVariantSummary,
        )
        Text(
            text = value,
            modifier = Modifier.weight(0.72f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = valueColor,
        )
    }
}
