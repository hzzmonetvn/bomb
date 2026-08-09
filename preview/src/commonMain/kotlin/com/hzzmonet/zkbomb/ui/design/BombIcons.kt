package com.hzzmonet.zkbomb.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.foundation.Image
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp

/**
 * Bomb's icon set.
 *
 * MIUIX ships only five icons, and pulling `material-icons-extended` into a
 * system app costs ~4 MB of vectors for a handful of glyphs. These are drawn
 * once, tint with the caller's colour, and move to `:app` unchanged.
 *
 * Line icons: 24dp grid, 1.8 stroke, round caps — HyperOS' weight.
 */
object BombIcons {

    val Home: ImageVector = line("Home") {
        moveTo(3.5f, 10.5f); lineTo(12f, 3.5f); lineTo(20.5f, 10.5f)
        moveTo(5.5f, 10f); verticalLineTo(19.5f); horizontalLineTo(18.5f); verticalLineTo(10f)
        moveTo(9.8f, 19.5f); verticalLineTo(14.2f); horizontalLineTo(14.2f); verticalLineTo(19.5f)
    }

    val Apps: ImageVector = line("Apps") {
        roundedRect(3.5f, 3.5f, 7.2f, 7.2f, 1.8f)
        roundedRect(13.3f, 3.5f, 7.2f, 7.2f, 1.8f)
        roundedRect(3.5f, 13.3f, 7.2f, 7.2f, 1.8f)
        roundedRect(13.3f, 13.3f, 7.2f, 7.2f, 1.8f)
    }

    val Monitor: ImageVector = line("Monitor") {
        roundedRect(2.5f, 4.5f, 19f, 13f, 2.4f)
        moveTo(6f, 12.5f); lineTo(9f, 12.5f); lineTo(10.8f, 9f); lineTo(13.2f, 15f); lineTo(15f, 12.5f); lineTo(18f, 12.5f)
        moveTo(8.5f, 20.5f); horizontalLineTo(15.5f)
    }

    val Automation: ImageVector = line("Automation") {
        moveTo(13.2f, 2.6f); lineTo(5.2f, 13.2f); horizontalLineTo(11f); lineTo(10.2f, 21.4f); lineTo(18.4f, 10.6f); horizontalLineTo(12.5f); close()
    }

    val More: ImageVector = line("More") {
        moveTo(4.6f, 12f); horizontalLineTo(4.62f)
        moveTo(12f, 12f); horizontalLineTo(12.02f)
        moveTo(19.4f, 12f); horizontalLineTo(19.42f)
    }

    val Tasks: ImageVector = line("Tasks") {
        moveTo(4f, 6.5f); horizontalLineTo(4.02f)
        moveTo(8.5f, 6.5f); horizontalLineTo(20f)
        moveTo(4f, 12f); horizontalLineTo(4.02f)
        moveTo(8.5f, 12f); horizontalLineTo(20f)
        moveTo(4f, 17.5f); horizontalLineTo(4.02f)
        moveTo(8.5f, 17.5f); horizontalLineTo(20f)
    }

    val Freeze: ImageVector = line("Freeze") {
        moveTo(12f, 2.8f); verticalLineTo(21.2f)
        moveTo(4f, 7.4f); lineTo(20f, 16.6f)
        moveTo(20f, 7.4f); lineTo(4f, 16.6f)
        moveTo(9.2f, 5.2f); lineTo(12f, 7.4f); lineTo(14.8f, 5.2f)
        moveTo(9.2f, 18.8f); lineTo(12f, 16.6f); lineTo(14.8f, 18.8f)
    }

    val Stats: ImageVector = line("Stats") {
        moveTo(4.5f, 19.5f); verticalLineTo(12.5f)
        moveTo(9.5f, 19.5f); verticalLineTo(7.5f)
        moveTo(14.5f, 19.5f); verticalLineTo(10.5f)
        moveTo(19.5f, 19.5f); verticalLineTo(4.5f)
    }

    val Performance: ImageVector = line("Performance") {
        moveTo(3.4f, 17.5f)
        arcTo(9.6f, 9.6f, 0f, isMoreThanHalf = false, isPositiveArc = true, 20.6f, 17.5f)
        moveTo(12f, 16f); lineTo(16.2f, 8.8f)
    }

    val Battery: ImageVector = line("Battery") {
        roundedRect(2.5f, 7f, 16.5f, 10f, 2.6f)
        moveTo(21.2f, 10.4f); verticalLineTo(13.6f)
        moveTo(5.6f, 10f); verticalLineTo(14f)
        moveTo(9f, 10f); verticalLineTo(14f)
        moveTo(12.4f, 10f); verticalLineTo(14f)
    }

    val Network: ImageVector = line("Network") {
        moveTo(7f, 20f); verticalLineTo(5.6f)
        moveTo(3.4f, 9f); lineTo(7f, 5.2f); lineTo(10.6f, 9f)
        moveTo(17f, 4f); verticalLineTo(18.4f)
        moveTo(13.4f, 15f); lineTo(17f, 18.8f); lineTo(20.6f, 15f)
    }

    val Logs: ImageVector = line("Logs") {
        moveTo(5.5f, 3.5f); horizontalLineTo(14f); lineTo(18.5f, 8f); verticalLineTo(20.5f); horizontalLineTo(5.5f); close()
        moveTo(13.8f, 3.6f); verticalLineTo(8.2f); horizontalLineTo(18.4f)
        moveTo(8.6f, 12.4f); horizontalLineTo(15.4f)
        moveTo(8.6f, 16.2f); horizontalLineTo(13f)
    }

    val Bridge: ImageVector = line("Bridge") {
        roundedRect(2.6f, 8.4f, 18.8f, 7.2f, 3.6f)
        moveTo(7.4f, 12f); horizontalLineTo(7.42f)
        moveTo(16.6f, 12f); horizontalLineTo(16.62f)
    }

    val Recorder: ImageVector = line("Recorder") {
        roundedRect(9f, 2.6f, 6f, 11f, 3f)
        moveTo(5.4f, 11.4f)
        arcTo(6.6f, 6.6f, 0f, isMoreThanHalf = false, isPositiveArc = false, 18.6f, 11.4f)
        moveTo(12f, 18f); verticalLineTo(21.4f)
    }

    val AppControl: ImageVector = line("AppControl") {
        roundedRect(3.4f, 3.4f, 17.2f, 17.2f, 4.2f)
        moveTo(8.2f, 9.6f); horizontalLineTo(15.8f)
        moveTo(8.2f, 14.4f); horizontalLineTo(12.4f)
    }

    val Chevron: ImageVector = line("Chevron") {
        moveTo(9.5f, 5f); lineTo(16f, 12f); lineTo(9.5f, 19f)
    }

    val Back: ImageVector = line("Back") {
        moveTo(14.5f, 5f); lineTo(8f, 12f); lineTo(14.5f, 19f)
    }

    val Search: ImageVector = line("Search") {
        circle(10.8f, 10.8f, 6.6f)
        moveTo(15.6f, 15.6f); lineTo(20.4f, 20.4f)
    }

    val Sun: ImageVector = line("Sun") {
        circle(12f, 12f, 4.4f)
        moveTo(12f, 2.6f); verticalLineTo(4.6f)
        moveTo(12f, 19.4f); verticalLineTo(21.4f)
        moveTo(2.6f, 12f); horizontalLineTo(4.6f)
        moveTo(19.4f, 12f); horizontalLineTo(21.4f)
        moveTo(5.4f, 5.4f); lineTo(6.8f, 6.8f)
        moveTo(17.2f, 17.2f); lineTo(18.6f, 18.6f)
        moveTo(18.6f, 5.4f); lineTo(17.2f, 6.8f)
        moveTo(6.8f, 17.2f); lineTo(5.4f, 18.6f)
    }

    val Moon: ImageVector = line("Moon") {
        moveTo(20.4f, 14.2f)
        arcTo(8.8f, 8.8f, 0f, isMoreThanHalf = true, isPositiveArc = true, 9.8f, 3.6f)
        arcTo(6.9f, 6.9f, 0f, isMoreThanHalf = false, isPositiveArc = false, 20.4f, 14.2f)
        close()
    }

    val Thermal: ImageVector = line("Thermal") {
        moveTo(10f, 13.6f); verticalLineTo(5.4f)
        arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 14f, 5.4f)
        verticalLineTo(13.6f)
        moveTo(9.2f, 14.2f)
        arcTo(3.6f, 3.6f, 0f, isMoreThanHalf = true, isPositiveArc = false, 14.8f, 14.2f)
    }

    val Cpu: ImageVector = line("Cpu") {
        roundedRect(6.4f, 6.4f, 11.2f, 11.2f, 2.2f)
        roundedRect(9.6f, 9.6f, 4.8f, 4.8f, 1f)
        moveTo(9.4f, 3f); verticalLineTo(6.2f)
        moveTo(14.6f, 3f); verticalLineTo(6.2f)
        moveTo(9.4f, 17.8f); verticalLineTo(21f)
        moveTo(14.6f, 17.8f); verticalLineTo(21f)
        moveTo(3f, 9.4f); horizontalLineTo(6.2f)
        moveTo(3f, 14.6f); horizontalLineTo(6.2f)
        moveTo(17.8f, 9.4f); horizontalLineTo(21f)
        moveTo(17.8f, 14.6f); horizontalLineTo(21f)
    }

    val Ram: ImageVector = line("Ram") {
        roundedRect(2.6f, 7.4f, 18.8f, 9.2f, 2.2f)
        moveTo(7f, 16.6f); verticalLineTo(19.4f)
        moveTo(12f, 16.6f); verticalLineTo(19.4f)
        moveTo(17f, 16.6f); verticalLineTo(19.4f)
    }

    val Power: ImageVector = line("Power") {
        moveTo(12f, 3.6f); verticalLineTo(11.4f)
        moveTo(6.6f, 6.4f)
        arcTo(7.6f, 7.6f, 0f, isMoreThanHalf = true, isPositiveArc = false, 17.4f, 6.4f)
    }

    val Play: ImageVector = line("Play") {
        moveTo(7.5f, 4.8f); lineTo(18.5f, 12f); lineTo(7.5f, 19.2f); close()
    }

    val Stop: ImageVector = line("Stop") {
        roundedRect(6f, 6f, 12f, 12f, 2.4f)
    }
}

/** Renders a Bomb icon tinted with [tint]. */
@Composable
fun BombIcon(
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    Image(
        painter = rememberVectorPainter(icon),
        contentDescription = contentDescription,
        modifier = modifier,
        colorFilter = ColorFilter.tint(tint),
    )
}

// ---------------------------------------------------------------- builders

private fun line(name: String, block: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        )
    }.build()

private fun PathBuilder.roundedRect(x: Float, y: Float, w: Float, h: Float, r: Float) {
    moveTo(x + r, y)
    horizontalLineTo(x + w - r)
    arcToRelative(r, r, 0f, isMoreThanHalf = false, isPositiveArc = true, r, r)
    verticalLineTo(y + h - r)
    arcToRelative(r, r, 0f, isMoreThanHalf = false, isPositiveArc = true, -r, r)
    horizontalLineTo(x + r)
    arcToRelative(r, r, 0f, isMoreThanHalf = false, isPositiveArc = true, -r, -r)
    verticalLineTo(y + r)
    arcToRelative(r, r, 0f, isMoreThanHalf = false, isPositiveArc = true, r, -r)
    close()
}

private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
    moveTo(cx - r, cy)
    arcToRelative(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, 2 * r, 0f)
    arcToRelative(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, -2 * r, 0f)
    close()
}
