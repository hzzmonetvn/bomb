package com.hzzmonet.zkbomb.ui.design.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hzzmonet.zkbomb.ui.design.BombIcon
import com.hzzmonet.zkbomb.ui.design.BombTheme
import com.hzzmonet.zkbomb.ui.design.LocalBombSurfaceAlpha
import top.yukonga.miuix.kmp.basic.Text

/**
 * One metric, one colour, everywhere.
 *
 * The series colour comes from [com.hzzmonet.zkbomb.ui.design.BombColors] so a
 * metric reads identically in a stat tile, a chart and the overlay.
 */
@Composable
fun BombStatCard(
    label: String,
    value: String,
    unit: String,
    color: Color,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    progress: Float? = null,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(BombTheme.miuix.surfaceContainerHigh.copy(alpha = LocalBombSurfaceAlpha.current))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                BombIcon(icon = icon, tint = color, modifier = Modifier.size(14.dp))
            }
            Text(
                text = label,
                modifier = Modifier.padding(start = if (icon != null) 6.dp else 0.dp),
                fontSize = 12.sp,
                color = BombTheme.miuix.onSurfaceVariantSummary,
            )
        }
        Row(
            modifier = Modifier.padding(top = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = value,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                color = BombTheme.miuix.onSurface,
            )
            Text(
                text = unit,
                modifier = Modifier.padding(start = 2.dp, bottom = 2.dp),
                fontSize = 12.sp,
                color = BombTheme.miuix.onSurfaceVariantSummary,
            )
        }
        if (progress != null) {
            BombMeter(
                progress = progress,
                color = color,
                modifier = Modifier.padding(top = 9.dp),
            )
        }
    }
}

/** Thin capsule meter. Used under stat tiles and for per-core load. */
@Composable
fun BombMeter(
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 4.dp,
) {
    val clamped = progress.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(color.copy(alpha = 0.16f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(clamped)
                .height(height)
                .clip(RoundedCornerShape(height / 2))
                .background(color),
        )
    }
}

/**
 * Telemetry sparkline: filled area + line, drawn from a fixed-size ring of
 * samples. Axis-free by design — it reads as a trend next to the exact value,
 * not as a chart to measure against.
 */
@Composable
fun BombSparkline(
    values: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
    maxValue: Float? = null,
) {
    val grid = BombTheme.colors.chartGrid
    Canvas(modifier = modifier) {
        if (values.size < 2) return@Canvas
        val w = size.width
        val h = size.height

        // A trend line pinned to 0..100 flattens against the floor for a metric
        // that lives at 15-30%. Auto-scale to the window's own range, with
        // headroom, unless the caller pins a scale on purpose.
        val ceiling = maxValue ?: run {
            val lo = values.min()
            val hi = values.max()
            val span = (hi - lo).coerceAtLeast(1f)
            hi + span * 0.35f
        }
        val floor = maxValue?.let { 0f } ?: run {
            val lo = values.min()
            val hi = values.max()
            val span = (hi - lo).coerceAtLeast(1f)
            (lo - span * 0.35f).coerceAtLeast(0f)
        }
        val range = (ceiling - floor).coerceAtLeast(1f)

        for (i in 1..2) {
            val y = h * i / 3f
            drawLine(
                color = grid,
                start = Offset(0f, y),
                end = Offset(w, y),
                strokeWidth = 1f,
            )
        }

        val stepX = w / (values.size - 1)
        fun pointY(v: Float): Float {
            val fraction = ((v - floor) / range).coerceIn(0f, 1f)
            return h - fraction * h * 0.90f - h * 0.05f
        }

        val linePath = Path()
        val fillPath = Path()
        values.forEachIndexed { index, value ->
            val x = stepX * index
            val y = pointY(value)
            if (index == 0) {
                linePath.moveTo(x, y)
                fillPath.moveTo(x, h)
                fillPath.lineTo(x, y)
            } else {
                linePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        fillPath.lineTo(w, h)
        fillPath.close()

        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(color.copy(alpha = 0.26f), color.copy(alpha = 0f)),
                startY = 0f,
                endY = h,
            ),
        )
        drawPath(
            path = linePath,
            color = color,
            style = Stroke(width = 2.2f, cap = StrokeCap.Round),
        )
    }
}

/** Per-core / per-bucket bars. */
@Composable
fun BombBarChart(
    values: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
    maxValue: Float = 100f,
) {
    Canvas(modifier = modifier) {
        if (values.isEmpty()) return@Canvas
        val gap = 4f
        val barWidth = (size.width - gap * (values.size - 1)) / values.size
        // Cap the radius: scaling it with bar width turns wide bars into capsules
        // and hides the height difference the chart exists to show.
        val radiusPx = minOf(barWidth / 3f, 5.dp.toPx())
        val radius = androidx.compose.ui.geometry.CornerRadius(radiusPx, radiusPx)
        values.forEachIndexed { index, value ->
            val fraction = (value.coerceIn(0f, maxValue) / maxValue)
            val barHeight = (size.height * fraction).coerceAtLeast(radiusPx * 2f)
            val x = index * (barWidth + gap)
            drawRoundRect(
                color = color.copy(alpha = 0.15f),
                topLeft = Offset(x, 0f),
                size = Size(barWidth, size.height),
                cornerRadius = radius,
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(x, size.height - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = radius,
            )
        }
    }
}

/** Labelled horizontal metric line: "Core 3   1804 MHz  ▇▇▇▁▁" */
@Composable
fun BombMetricLine(
    label: String,
    valueText: String,
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(0.30f),
            fontSize = 13.sp,
            color = BombTheme.miuix.onSurfaceVariantSummary,
        )
        BombMeter(
            progress = progress,
            color = color,
            modifier = Modifier.weight(0.46f),
            height = 5.dp,
        )
        Text(
            text = valueText,
            modifier = Modifier
                .weight(0.24f)
                .padding(start = 10.dp),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = BombTheme.miuix.onSurface,
        )
    }
}

/**
 * The Stats overlay, rendered inline so overlay presets can be compared without
 * a device. Same composable the real overlay window hosts.
 */
@Composable
fun BombMonitorOverlay(
    entries: List<OverlayEntry>,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    opacity: Float = 0.82f,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(if (compact) 999.dp else 12.dp))
            .background(Color.Black.copy(alpha = opacity))
            .padding(horizontal = if (compact) 12.dp else 11.dp, vertical = if (compact) 6.dp else 9.dp),
    ) {
        if (compact) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                entries.forEach { entry ->
                    Text(
                        text = entry.value,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = entry.color,
                    )
                }
            }
        } else {
            // Intrinsic width keeps the overlay wrap-content while still letting
            // the label/value columns align — a real overlay never spans the screen.
            Column(
                modifier = Modifier.width(IntrinsicSize.Max),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                entries.forEach { entry ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = entry.label,
                            modifier = Modifier.weight(1f),
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.62f),
                        )
                        Text(
                            text = entry.value,
                            modifier = Modifier.padding(start = 14.dp),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = entry.color,
                        )
                    }
                }
            }
        }
    }
}

data class OverlayEntry(
    val label: String,
    val value: String,
    val color: Color,
)
