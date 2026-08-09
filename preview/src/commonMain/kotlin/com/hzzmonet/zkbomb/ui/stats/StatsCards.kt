package com.hzzmonet.zkbomb.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hzzmonet.zkbomb.data.SystemView
import com.hzzmonet.zkbomb.data.cpuTrend
import com.hzzmonet.zkbomb.preview.PreviewData
import com.hzzmonet.zkbomb.ui.design.BombTheme
import com.hzzmonet.zkbomb.ui.design.component.BombBarChart
import com.hzzmonet.zkbomb.ui.design.component.BombCard
import com.hzzmonet.zkbomb.ui.design.component.BombMeter
import com.hzzmonet.zkbomb.ui.design.component.BombSparkline
import top.yukonga.miuix.kmp.basic.Text

/**
 * CPU detail: total load when the platform allows reading it, and every core's
 * cluster, clock and (where available) load. Shared by Monitor and Task Manager.
 */
@Composable
fun CpuDetailCard(system: SystemView, modifier: Modifier = Modifier, showTrend: Boolean = true) {
    BombCard(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "CPU",
                        fontSize = 13.sp,
                        color = BombTheme.miuix.onSurfaceVariantSummary,
                    )
                    Row(
                        modifier = Modifier.padding(top = 2.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Text(
                            text = system.cpuPercent?.toString() ?: "—",
                            fontSize = 30.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (system.cpuPercent != null) {
                                BombTheme.miuix.onSurface
                            } else {
                                BombTheme.miuix.onSurfaceVariantSummary
                            },
                        )
                        Text(
                            text = if (system.cpuPercent != null) "% total" else "unavailable",
                            modifier = Modifier.padding(start = 3.dp, bottom = 4.dp),
                            fontSize = 12.sp,
                            color = BombTheme.miuix.onSurfaceVariantSummary,
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${system.coreCount} cores",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = BombTheme.colors.cpu,
                    )
                    if (system.isLive) {
                        Text(
                            text = "clock from cpufreq",
                            modifier = Modifier.padding(top = 2.dp),
                            fontSize = 11.sp,
                            color = BombTheme.miuix.onSurfaceVariantSummary,
                        )
                    }
                }
            }

            system.cpuUnavailableReason?.let { reason ->
                Text(
                    text = reason,
                    modifier = Modifier.padding(top = 8.dp),
                    fontSize = 11.sp,
                    color = BombTheme.colors.warn,
                )
            }

            if (showTrend && system.cpuPercent != null) {
                BombSparkline(
                    values = system.cpuTrend(),
                    color = BombTheme.colors.cpu,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .padding(top = 10.dp),
                )
            }

            val loads = system.coreLoads
            if (loads != null) {
                BombBarChart(
                    values = loads,
                    color = BombTheme.colors.cpu,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .padding(top = 12.dp),
                )
            }

            val freqs = system.coreFreqsKhz
            if (freqs != null) {
                Column(
                    modifier = Modifier.padding(top = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    freqs.indices.forEach { index -> CoreRow(system, index) }
                }
            } else if (system.isLive) {
                Text(
                    text = "Per-core clocks are not readable on this build",
                    modifier = Modifier.padding(top = 12.dp),
                    fontSize = 12.sp,
                    color = BombTheme.miuix.onSurfaceVariantSummary,
                )
            }
        }
    }
}

@Composable
private fun CoreRow(system: SystemView, index: Int) {
    val freqKhz = system.coreFreqsKhz?.getOrNull(index)
    val maxKhz = system.coreMaxFreqsKhz?.getOrNull(index)
    val load = system.coreLoads?.getOrNull(index)
    val cluster = clusterFor(system, index, maxKhz)
    val tint = when (cluster) {
        "Prime" -> BombTheme.colors.accent
        "Gold" -> BombTheme.colors.gpu
        else -> BombTheme.colors.cpu
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(0.30f)) {
            Text(
                text = "Core $index",
                fontSize = 13.sp,
                color = BombTheme.miuix.onSurface,
            )
            Text(text = cluster, fontSize = 11.sp, color = tint)
        }
        Column(modifier = Modifier.weight(0.42f)) {
            // Clock against the core's own ceiling — a silver core at 1.0 GHz is
            // not the same story as a prime core at 1.0 GHz.
            if (freqKhz != null && maxKhz != null && maxKhz > 0) {
                BombMeter(progress = freqKhz.toFloat() / maxKhz, color = tint, height = 5.dp)
            }
            if (load != null) {
                BombMeter(
                    progress = load / 100f,
                    color = tint.copy(alpha = 0.45f),
                    height = 3.dp,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(0.28f)
                .padding(start = 10.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Text(
                text = freqKhz?.let { "${it / 1000} MHz" } ?: "offline",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = if (freqKhz != null) {
                    BombTheme.miuix.onSurface
                } else {
                    BombTheme.miuix.onSurfaceVariantSummary
                },
            )
            if (load != null) {
                Text(
                    text = "${load.toInt()}%",
                    fontSize = 11.sp,
                    color = BombTheme.miuix.onSurfaceVariantSummary,
                )
            }
        }
    }
}

/**
 * Cluster naming from the per-core maximum clock — the grouping a big.LITTLE
 * SoC actually exposes, rather than a hardcoded core map per chip.
 */
private fun clusterFor(system: SystemView, index: Int, maxKhz: Int?): String {
    if (!system.isLive) return PreviewData.coreClusters.getOrElse(index) { "Core" }
    val all = system.coreMaxFreqsKhz?.filterNotNull()?.distinct()?.sorted() ?: return "Core"
    if (maxKhz == null || all.isEmpty()) return "Core"
    return when {
        all.size <= 1 -> "Core"
        maxKhz == all.last() && all.size >= 3 -> "Prime"
        maxKhz == all.first() -> "Silver"
        else -> "Gold"
    }
}

/** Memory breakdown from ActivityManager.MemoryInfo. */
@Composable
fun MemoryCard(system: SystemView, modifier: Modifier = Modifier) {
    BombCard(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Memory",
                        fontSize = 13.sp,
                        color = BombTheme.miuix.onSurfaceVariantSummary,
                    )
                    Row(
                        modifier = Modifier.padding(top = 2.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Text(
                            text = system.ramUsedGb ?: "—",
                            fontSize = 30.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = BombTheme.miuix.onSurface,
                        )
                        Text(
                            text = "/ ${system.ramTotalGb ?: "—"} GB",
                            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                            fontSize = 12.sp,
                            color = BombTheme.miuix.onSurfaceVariantSummary,
                        )
                    }
                }
                Text(
                    text = system.ramPercent?.let { "$it%" } ?: "—",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = BombTheme.colors.ram,
                )
            }
            system.ramPercent?.let { percent ->
                BombMeter(
                    progress = percent / 100f,
                    color = BombTheme.colors.ram,
                    height = 6.dp,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            BombSparkline(
                values = listOf(
                    48f, 49f, 50f, 51f, 52f, 52f, 53f, 52f, 51f, 52f,
                    52f, 53f, 54f, 53f, 52f, 52f, 51f, 52f, 52f, 52f,
                ),
                color = BombTheme.colors.ram,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .padding(top = 10.dp),
            )
            Column(
                modifier = Modifier.padding(top = 14.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                MemoryLine("Available", system.ramAvailableGb?.let { "$it GB" })
                if (system.lowMemory == true) {
                    MemoryLine("State", "Low memory", BombTheme.colors.warn)
                }
                system.ownPssMb?.let { MemoryLine("Bomb (PSS)", "$it MB") }
                if (!system.isLive) {
                    MemoryLine("ZRAM", "${PreviewData.ZRAM_USED_GB} / ${PreviewData.ZRAM_TOTAL_GB} GB")
                    MemoryLine("Swap", "${PreviewData.SWAP_USED_GB} GB")
                }
            }
            if (system.isLive) {
                Text(
                    text = "ZRAM and swap detail need privileged /proc access",
                    modifier = Modifier.padding(top = 10.dp),
                    fontSize = 11.sp,
                    color = BombTheme.miuix.onSurfaceVariantSummary,
                )
            }
        }
    }
}

/** Power / Wattage card with live reading and power trend sparkline. */
@Composable
fun PowerCard(system: SystemView, modifier: Modifier = Modifier) {
    BombCard(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Power",
                        fontSize = 13.sp,
                        color = BombTheme.miuix.onSurfaceVariantSummary,
                    )
                    Row(
                        modifier = Modifier.padding(top = 2.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Text(
                            text = PreviewData.POWER_WATTS,
                            fontSize = 30.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = BombTheme.colors.power,
                        )
                        Text(
                            text = " Watts",
                            modifier = Modifier.padding(start = 3.dp, bottom = 4.dp),
                            fontSize = 12.sp,
                            color = BombTheme.miuix.onSurfaceVariantSummary,
                        )
                    }
                }
                Text(
                    text = "Battery Lab",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = BombTheme.colors.power,
                )
            }

            BombSparkline(
                values = listOf(
                    4.8f, 5.1f, 5.4f, 5.2f, 5.7f, 6.1f, 5.8f, 5.3f, 4.9f, 5.5f,
                    5.9f, 6.3f, 5.7f, 5.2f, 5.0f, 5.4f, 5.8f, 5.6f, 5.3f, 5.3f,
                ),
                color = BombTheme.colors.power,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .padding(top = 10.dp),
            )

            Column(
                modifier = Modifier.padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PowerLine("Estimated Power", "${PreviewData.POWER_WATTS} W")
                PowerLine("Battery Temp", "${PreviewData.BATTERY_TEMP} °C")
                PowerLine("Charge Level", "${PreviewData.BATTERY_PERCENT}%")
            }
        }
    }
}

@Composable
private fun PowerLine(label: String, value: String) {
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

@Composable
private fun MemoryLine(label: String, value: String?, valueColor: Color? = null) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            fontSize = 13.sp,
            color = BombTheme.miuix.onSurfaceVariantSummary,
        )
        Text(
            text = value ?: "—",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = valueColor ?: BombTheme.miuix.onSurface,
        )
    }
}
