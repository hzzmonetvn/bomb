package com.hzzmonet.zkbomb.ui.stats

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hzzmonet.zkbomb.data.SystemTelemetryState
import com.hzzmonet.zkbomb.data.SystemView
import com.hzzmonet.zkbomb.data.formatBytesGb
import com.hzzmonet.zkbomb.data.rememberOverlayPermission
import com.hzzmonet.zkbomb.data.temperatureTrend
import com.hzzmonet.zkbomb.preview.PreviewUiState
import com.hzzmonet.zkbomb.ui.design.BombTheme
import com.hzzmonet.zkbomb.ui.design.component.BombCard
import com.hzzmonet.zkbomb.ui.design.component.BombMonitorOverlay
import com.hzzmonet.zkbomb.ui.design.component.BombRowDivider
import com.hzzmonet.zkbomb.ui.design.component.BombSectionTitle
import com.hzzmonet.zkbomb.ui.design.component.BombSegmentedButton
import com.hzzmonet.zkbomb.ui.design.component.BombSliderPreference
import com.hzzmonet.zkbomb.ui.design.component.BombSparkline
import com.hzzmonet.zkbomb.ui.design.component.BombSwitchPreference
import com.hzzmonet.zkbomb.ui.design.component.BombUnsupportedState
import com.hzzmonet.zkbomb.ui.design.component.OverlayEntry
import com.hzzmonet.zkbomb.ui.design.component.formatOneDecimal
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import kotlin.math.roundToInt

private val presets = listOf("Classic", "Mini", "Gaming")

fun LazyListScope.monitorContent(
    state: PreviewUiState,
    system: SystemView,
    telemetry: SystemTelemetryState,
) {
    item { OverlayPermissionCard() }
    item { OverlayPreviewCard(state, system) }

    item {
        BombSegmentedButton(
            options = presets,
            selectedIndex = state.overlayPreset,
            onSelected = { state.overlayPreset = it },
        )
    }

    item { BombSectionTitle("Monitors") }
    item {
        BombCard {
            state.monitors.keys.sorted().forEachIndexed { index, name ->
                if (index > 0) BombRowDivider()
                BombSwitchPreference(
                    title = name,
                    summary = descriptionOf(name),
                    checked = state.monitors[name] == true,
                    onCheckedChange = { state.monitors[name] = it },
                )
            }
        }
    }

    item { BombSectionTitle("Overlay") }
    item {
        BombCard {
            BombSliderPreference(
                title = "Opacity",
                value = state.overlayOpacity,
                onValueChange = { state.overlayOpacity = it },
                valueLabel = "${(state.overlayOpacity * 100).toInt()}%",
                valueRange = 0.3f..1f,
            )
            BombRowDivider()
            BombSliderPreference(
                title = "Scale",
                value = state.overlayScale,
                onValueChange = { state.overlayScale = it },
                valueLabel = "${formatOneDecimal(state.overlayScale)}×",
                valueRange = 0.8f..1.6f,
            )
        }
    }

    // Privileged system telemetry: the CPU figure here is computed from
    // successive /proc/stat total/idle deltas by the service, so it is present
    // even when an unprivileged /proc/stat read (the SystemView cards below)
    // returns nothing.
    item { BombSectionTitle("System telemetry") }
    item { SystemTelemetryCard(telemetry) }

    item { BombSectionTitle("Live") }
    item { CpuDetailCard(system) }
    item { MemoryCard(system) }
    item { PowerCard(system) }
    item { GpuThermalCard(system) }

    item {
        BombCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Frame rate",
                        modifier = Modifier.weight(1f),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = BombTheme.miuix.onSurface,
                    )
                    Text(
                        text = system.fps?.let { "$it FPS" } ?: "—",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = BombTheme.colors.fps,
                    )
                }
                Text(
                    text = if (system.isLive) {
                        "Measured from Choreographer — this is Bomb's own draw rate. " +
                            "A game's FPS needs dumpsys gfxinfo and the DUMP permission."
                    } else {
                        "Sample value"
                    },
                    modifier = Modifier.padding(top = 6.dp),
                    fontSize = 12.sp,
                    color = BombTheme.miuix.onSurfaceVariantSummary,
                )
            }
        }
    }

    item {
        BombUnsupportedState(
            title = "Per-thread CPU",
            reason = "Thread Monitor needs bombd for per-TID sampling. The daemon is " +
                "not present on this build, so Bomb reports the metric as unavailable " +
                "instead of estimating it.",
        )
    }
}

private fun descriptionOf(name: String): String = when (name) {
    "Classic Monitor" -> "FPS, CPU, GPU, RAM, temperature, power"
    "Processes Monitor" -> "Per-process CPU and memory"
    "Thread Monitor" -> "Per-thread utilisation"
    "Mini Monitor" -> "Compact single-line overlay"
    "FPS Stats" -> "Frame time, 1% low, jank"
    else -> "CPU, GPU, battery and skin sensors"
}

/**
 * The privileged system telemetry, or the honest reason it is not here.
 *
 * CPU is null on the very first sample (a rate needs two), which reads as
 * "collecting…" rather than a fake 0. Memory, battery and thermal come straight
 * from the snapshot; a withheld field shows "—".
 */
@Composable
private fun SystemTelemetryCard(state: SystemTelemetryState) {
    when (state) {
        SystemTelemetryState.Loading -> BombCard {
            Text(
                text = "Reading system telemetry from the privileged service…",
                modifier = Modifier.padding(16.dp),
                fontSize = 13.sp,
                color = BombTheme.miuix.onSurfaceVariantSummary,
            )
        }

        is SystemTelemetryState.Unsupported ->
            BombUnsupportedState(title = "System telemetry", reason = state.reason)

        is SystemTelemetryState.Error -> BombCard {
            Text(
                text = state.message,
                modifier = Modifier.padding(16.dp),
                fontSize = 13.sp,
                color = BombTheme.colors.critical,
            )
        }

        is SystemTelemetryState.Ready -> {
            val t = state.telemetry
            BombCard {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(9.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = state.cpuPercent?.toString() ?: "—",
                            fontSize = 30.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (state.cpuPercent != null) BombTheme.miuix.onSurface else BombTheme.miuix.onSurfaceVariantSummary,
                        )
                        Text(
                            text = if (state.cpuPercent != null) "% CPU (all cores)" else "collecting…",
                            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                            fontSize = 12.sp,
                            color = BombTheme.miuix.onSurfaceVariantSummary,
                        )
                    }
                    TelemetryLine("Memory used", memoryUsed(t))
                    TelemetryLine("Low memory", if (t.lowMemory) "Yes" else "No")
                    TelemetryLine("Thermal", thermalLabel(t.thermalStatus))
                    TelemetryLine("Battery", t.batteryPercent?.let { "$it%" } ?: "—")
                    TelemetryLine(
                        "Battery temperature",
                        t.batteryTemperatureDeciCelsius?.let { "${formatOneDecimal(it / 10f)}°C" } ?: "—",
                    )
                }
            }
        }
    }
}

private fun memoryUsed(t: com.hzzmonet.zkbomb.data.BombSystemTelemetry): String {
    val used = t.totalMemoryBytes - t.availableMemoryBytes
    val usedGb = formatBytesGb(used) ?: return "—"
    val totalGb = formatBytesGb(t.totalMemoryBytes) ?: return "$usedGb GB"
    return "$usedGb / $totalGb GB"
}

/** PowerManager thermal status codes → label, or "—" when the service withheld it. */
private fun thermalLabel(status: Int?): String = when (status) {
    null -> "—"
    0 -> "None"
    1 -> "Light"
    2 -> "Moderate"
    3 -> "Severe"
    4 -> "Critical"
    5 -> "Emergency"
    6 -> "Shutdown"
    else -> "Unknown"
}

@Composable
private fun TelemetryLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            fontSize = 13.sp,
            color = BombTheme.miuix.onSurfaceVariantSummary,
        )
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = BombTheme.miuix.onSurface,
        )
    }
}

/**
 * Asks for the draw-over-other-apps permission, and says plainly what happens
 * without it.
 */
@Composable
private fun OverlayPermissionCard() {
    val permission = rememberOverlayPermission()
    if (!permission.applicable || permission.granted) return

    BombCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Overlay permission needed",
                fontWeight = FontWeight.SemiBold,
                color = BombTheme.miuix.onSurface,
            )
            Text(
                text = "The monitors below are saved, but nothing can be drawn over " +
                    "other apps until Bomb is allowed to. Android grants this only " +
                    "from its own settings screen.",
                modifier = Modifier.padding(top = 6.dp),
                fontSize = 13.sp,
                color = BombTheme.miuix.onSurfaceVariantSummary,
            )
            Button(
                onClick = permission.request,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(text = "Open permission settings")
            }
        }
    }
}

/**
 * A live preview of the floating overlay, fed from the same real measurements the
 * rest of the screen uses — a metric the device cannot report shows "—", never a
 * fabricated number.
 */
@Composable
private fun OverlayPreviewCard(state: PreviewUiState, system: SystemView) {
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    val cpu = system.cpuPercent?.toString() ?: "—"
    val gpu = system.gpuPercent?.toString() ?: "—"
    val ram = system.ramUsedGb ?: "—"
    val fps = system.fps?.toString() ?: "—"
    val temp = system.socTempC ?: "—"
    val power = system.powerWatts ?: "—"

    BombCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Floating Overlay Preview",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = BombTheme.miuix.onSurface,
            )
            Text(
                text = "Drag the overlay box below to adjust position. Values are live; " +
                    "a metric this build cannot read shows as —.",
                modifier = Modifier.padding(top = 3.dp),
                fontSize = 12.sp,
                color = BombTheme.miuix.onSurfaceVariantSummary,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .padding(top = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                offsetX += dragAmount.x
                                offsetY += dragAmount.y
                            }
                        }
                        .graphicsLayer {
                            scaleX = state.overlayScale
                            scaleY = state.overlayScale
                        },
                ) {
                    when (state.overlayPreset) {
                        1 -> BombMonitorOverlay(
                            compact = true,
                            opacity = state.overlayOpacity,
                            entries = listOf(
                                OverlayEntry("FPS", fps, BombTheme.colors.fps),
                                OverlayEntry("TEMP", "$temp°", BombTheme.colors.thermal),
                                OverlayEntry("PWR", "${power}W", BombTheme.colors.power),
                            ),
                        )

                        2 -> BombMonitorOverlay(
                            opacity = state.overlayOpacity,
                            entries = listOf(
                                OverlayEntry("FPS", fps, BombTheme.colors.fps),
                                OverlayEntry("GPU", "$gpu%", BombTheme.colors.gpu),
                                OverlayEntry("TEMP", "$temp°C", BombTheme.colors.thermal),
                                OverlayEntry("POWER", "$power W", BombTheme.colors.power),
                            ),
                        )

                        else -> BombMonitorOverlay(
                            opacity = state.overlayOpacity,
                            entries = listOf(
                                OverlayEntry("CPU", "$cpu%", BombTheme.colors.cpu),
                                OverlayEntry("GPU", "$gpu%", BombTheme.colors.gpu),
                                OverlayEntry("RAM", "$ram GB", BombTheme.colors.ram),
                                OverlayEntry("FPS", fps, BombTheme.colors.fps),
                                OverlayEntry("TEMP", "$temp°C", BombTheme.colors.thermal),
                                OverlayEntry("POWER", "$power W", BombTheme.colors.power),
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GpuThermalCard(system: SystemView) {
    BombCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "GPU",
                    modifier = Modifier.weight(1f),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = BombTheme.miuix.onSurface,
                )
                Text(
                    text = system.gpuPercent?.let { "$it%" } ?: "unavailable",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (system.gpuPercent != null) {
                        BombTheme.colors.gpu
                    } else {
                        BombTheme.miuix.onSurfaceVariantSummary
                    },
                )
            }
            Text(
                text = "GPU load lives in vendor sysfs nodes that an unprivileged " +
                    "app cannot open. Available in ROM and root modes.",
                modifier = Modifier.padding(top = 6.dp),
                fontSize = 12.sp,
                color = BombTheme.miuix.onSurfaceVariantSummary,
            )

            Row(
                modifier = Modifier.padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Thermal",
                    modifier = Modifier.weight(1f),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = BombTheme.miuix.onSurface,
                )
                Text(
                    text = system.socTempC?.let { "$it°C" }
                        ?: system.thermalStatus
                        ?: "—",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = BombTheme.colors.thermal,
                )
            }
            Text(
                text = if (system.isLive) {
                    "Framework thermal status. Per-sensor temperatures need privileged " +
                        "access to thermal_zone; battery temperature is in Battery Lab."
                } else {
                    "SoC sensor sample"
                },
                modifier = Modifier.padding(top = 6.dp),
                fontSize = 12.sp,
                color = BombTheme.miuix.onSurfaceVariantSummary,
            )
            BombSparkline(
                values = system.temperatureTrend(),
                color = BombTheme.colors.thermal,
                maxValue = 60f,
                modifier = Modifier.fillMaxWidth().height(48.dp).padding(top = 10.dp),
            )
        }
    }
}
