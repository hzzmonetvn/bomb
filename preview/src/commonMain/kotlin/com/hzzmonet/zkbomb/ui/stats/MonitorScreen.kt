package com.hzzmonet.zkbomb.ui.stats

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hzzmonet.zkbomb.data.SystemView
import com.hzzmonet.zkbomb.data.temperatureTrend
import com.hzzmonet.zkbomb.preview.PreviewData
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
import top.yukonga.miuix.kmp.basic.Text

private val presets = listOf("Classic", "Mini", "Gaming")

fun LazyListScope.monitorContent(state: PreviewUiState, system: SystemView) {
    item { OverlayPreviewCard(state) }

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

    item { BombSectionTitle("Live") }
    item { CpuDetailCard(system) }
    item { MemoryCard(system) }
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

@Composable
private fun OverlayPreviewCard(state: PreviewUiState) {
    BombCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Overlay preview",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = BombTheme.miuix.onSurface,
            )
            Text(
                text = "Drag, snap and per-app placement are device-side behaviours",
                modifier = Modifier.padding(top = 3.dp),
                fontSize = 12.sp,
                color = BombTheme.miuix.onSurfaceVariantSummary,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                when (state.overlayPreset) {
                    1 -> BombMonitorOverlay(
                        compact = true,
                        opacity = state.overlayOpacity,
                        entries = listOf(
                            OverlayEntry("FPS", "${PreviewData.FPS}", BombTheme.colors.fps),
                            OverlayEntry("TEMP", "${PreviewData.SOC_TEMP}°", BombTheme.colors.thermal),
                            OverlayEntry("PWR", "${PreviewData.POWER_WATTS}W", BombTheme.colors.power),
                        ),
                    )

                    2 -> BombMonitorOverlay(
                        opacity = state.overlayOpacity,
                        entries = listOf(
                            OverlayEntry("FPS", "${PreviewData.FPS}", BombTheme.colors.fps),
                            OverlayEntry("FRAME", "8.4 ms", BombTheme.colors.fps),
                            OverlayEntry("GPU", "${PreviewData.GPU_PERCENT}%", BombTheme.colors.gpu),
                            OverlayEntry("TEMP", "${PreviewData.SOC_TEMP}°C", BombTheme.colors.thermal),
                            OverlayEntry("POWER", "${PreviewData.POWER_WATTS} W", BombTheme.colors.power),
                        ),
                    )

                    else -> BombMonitorOverlay(
                        opacity = state.overlayOpacity,
                        entries = listOf(
                            OverlayEntry("CPU", "${PreviewData.CPU_PERCENT}%", BombTheme.colors.cpu),
                            OverlayEntry("GPU", "${PreviewData.GPU_PERCENT}%", BombTheme.colors.gpu),
                            OverlayEntry("RAM", "${PreviewData.RAM_USED_GB} GB", BombTheme.colors.ram),
                            OverlayEntry("FPS", "${PreviewData.FPS}", BombTheme.colors.fps),
                            OverlayEntry("TEMP", "${PreviewData.SOC_TEMP}°C", BombTheme.colors.thermal),
                            OverlayEntry("POWER", "${PreviewData.POWER_WATTS} W", BombTheme.colors.power),
                        ),
                    )
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
            if (system.gpuPercent != null) {
                BombSparkline(
                    values = PreviewData.gpuHistory,
                    color = BombTheme.colors.gpu,
                    modifier = Modifier.fillMaxWidth().height(48.dp).padding(top = 10.dp),
                )
            } else {
                Text(
                    text = "GPU load lives in vendor sysfs nodes that an unprivileged " +
                        "app cannot open. Available in ROM and root modes.",
                    modifier = Modifier.padding(top = 6.dp),
                    fontSize = 12.sp,
                    color = BombTheme.miuix.onSurfaceVariantSummary,
                )
            }

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
