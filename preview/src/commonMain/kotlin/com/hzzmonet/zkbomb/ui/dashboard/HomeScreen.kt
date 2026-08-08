package com.hzzmonet.zkbomb.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
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
import com.hzzmonet.zkbomb.data.cpuTrend
import com.hzzmonet.zkbomb.preview.BombVersion
import com.hzzmonet.zkbomb.preview.PreviewData
import com.hzzmonet.zkbomb.preview.PreviewUiState
import com.hzzmonet.zkbomb.preview.platformInfo
import com.hzzmonet.zkbomb.ui.design.BombIcons
import com.hzzmonet.zkbomb.ui.design.BombTheme
import com.hzzmonet.zkbomb.ui.design.component.BombBadge
import com.hzzmonet.zkbomb.ui.design.component.BombCard
import com.hzzmonet.zkbomb.ui.design.component.BombHeaderBanner
import com.hzzmonet.zkbomb.ui.design.component.BombPreference
import com.hzzmonet.zkbomb.ui.design.component.BombQuickAction
import com.hzzmonet.zkbomb.ui.design.component.BombRowDivider
import com.hzzmonet.zkbomb.ui.design.component.BombSectionTitle
import com.hzzmonet.zkbomb.ui.design.component.BombSparkline
import com.hzzmonet.zkbomb.ui.design.component.BombStatCard
import com.hzzmonet.zkbomb.ui.navigation.BombNavigator
import com.hzzmonet.zkbomb.ui.navigation.BombRoute
import top.yukonga.miuix.kmp.basic.Text

fun LazyListScope.homeContent(
    state: PreviewUiState,
    navigator: BombNavigator,
    system: SystemView,
) {
    item {
        val info = platformInfo()
        BombHeaderBanner(
            appVersion = BombVersion.display,
            romName = info.romName,
            androidVersion = info.androidVersion,
            buildNumber = info.buildNumber,
            deviceName = info.deviceName,
            soc = info.soc,
            dataIsLive = info.isRealDevice,
            modeLabel = when (state.execMode) {
                PreviewData.ExecMode.ROM -> "ROM"
                PreviewData.ExecMode.ROOT -> "Root"
            },
            modeDetail = when (state.execMode) {
                PreviewData.ExecMode.ROM -> "Integrated · priv-app + bombd"
                PreviewData.ExecMode.ROOT -> "Magisk/KernelSU module"
            },
            modeColor = when (state.execMode) {
                PreviewData.ExecMode.ROM -> BombTheme.miuix.primary
                PreviewData.ExecMode.ROOT -> BombTheme.colors.accent
            },
            modeIcon = BombIcons.AppControl,
            onClick = { navigator.push(BombRoute.ExecutionMode) },
        )
    }
    item { SystemStateCard(state, system) }
    item { QuickActionRow(navigator) }

    item { BombSectionTitle("Modules") }
    item {
        BombCard {
            BombPreference(
                title = "Task Manager",
                summary = "Processes and resource usage",
                icon = BombIcons.Tasks,
                iconTint = BombTheme.colors.cpu,
                onClick = { navigator.push(BombRoute.TaskManager) },
            )
            BombRowDivider()
            BombPreference(
                title = "Freeze",
                summary = "Frozen apps and freeze policy",
                value = "${state.freezeList.size} apps",
                icon = BombIcons.Freeze,
                iconTint = BombTheme.colors.network,
                onClick = { navigator.push(BombRoute.Freeze) },
            )
            BombRowDivider()
            BombPreference(
                title = "Stats",
                summary = "System and gaming monitor",
                icon = BombIcons.Stats,
                iconTint = BombTheme.colors.gpu,
                onClick = { navigator.selectTab(BombRoute.Monitor) },
            )
            BombRowDivider()
            BombPreference(
                title = "Performance",
                summary = "Per-app performance profiles",
                value = PreviewData.DEVICE_PROFILE,
                icon = BombIcons.Performance,
                iconTint = BombTheme.colors.accent,
                onClick = { navigator.push(BombRoute.Performance) },
            )
        }
    }

    item { BombSectionTitle("System") }
    item {
        BombCard {
            BombPreference(
                title = "Execution mode",
                summary = "Privilege source and capability coverage",
                value = when (state.execMode) {
                    PreviewData.ExecMode.ROM -> "ROM"
                    PreviewData.ExecMode.ROOT -> "Root"
                },
                icon = BombIcons.AppControl,
                iconTint = BombTheme.colors.accent,
                onClick = { navigator.push(BombRoute.ExecutionMode) },
            )
            BombRowDivider()
            BombPreference(
                title = "Bomb Bridge",
                summary = "Live system events",
                icon = BombIcons.Bridge,
                iconTint = BombTheme.colors.ram,
                onClick = { navigator.push(BombRoute.Bridge) },
            )
            BombRowDivider()
            BombPreference(
                title = "Battery Lab",
                summary = "Battery and charging",
                value = system.batteryPercent?.let { "$it%" },
                icon = BombIcons.Battery,
                iconTint = BombTheme.colors.ok,
                onClick = { navigator.push(BombRoute.BatteryLab) },
            )
            BombRowDivider()
            BombPreference(
                title = "Network",
                summary = "Traffic and per-app policy",
                icon = BombIcons.Network,
                iconTint = BombTheme.colors.network,
                onClick = { navigator.push(BombRoute.Network) },
            )
            BombRowDivider()
            BombPreference(
                title = "Log Governor",
                summary = "System logging profiles",
                value = "Balanced",
                icon = BombIcons.Logs,
                iconTint = BombTheme.colors.warn,
                onClick = { navigator.push(BombRoute.LogGovernor) },
            )
            BombRowDivider()
            BombPreference(
                title = "Bomb Rules",
                summary = "Automation",
                value = "${state.ruleEnabled.count { it.value }} active",
                icon = BombIcons.Automation,
                iconTint = BombTheme.colors.accent,
                onClick = { navigator.selectTab(BombRoute.Automation) },
            )
        }
    }
}

@Composable
private fun SystemStateCard(state: PreviewUiState, system: SystemView) {
    BombCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "System state",
                    modifier = Modifier.weight(1f),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = BombTheme.miuix.onSurface,
                )
                if (state.execMode == PreviewData.ExecMode.ROOT) {
                    BombBadge(
                        text = "Root",
                        color = BombTheme.colors.accent,
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
                // Thermal *status* on a device, sensor value only in the sample.
                Text(
                    text = system.socTempC?.let { "$it°C" }
                        ?: system.thermalStatus?.let { "Thermal: $it" }
                        ?: "—",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = BombTheme.colors.thermal,
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BombStatCard(
                    label = "CPU",
                    value = system.cpuPercent?.toString() ?: "—",
                    unit = if (system.cpuPercent != null) "%" else "",
                    color = BombTheme.colors.cpu,
                    icon = BombIcons.Cpu,
                    progress = system.cpuPercent?.let { it / 100f },
                    modifier = Modifier.weight(1f),
                )
                BombStatCard(
                    label = "RAM",
                    value = system.ramUsedGb ?: "—",
                    unit = "GB",
                    color = BombTheme.colors.ram,
                    icon = BombIcons.Ram,
                    progress = system.ramPercent?.let { it / 100f },
                    modifier = Modifier.weight(1f),
                )
                BombStatCard(
                    label = "Battery",
                    value = system.batteryPercent?.toString() ?: "—",
                    unit = "%",
                    color = BombTheme.colors.ok,
                    icon = BombIcons.Battery,
                    progress = system.batteryPercent?.let { it / 100f },
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (system.cpuPercent != null) {
                        "CPU · last ${system.cpuTrend().size} samples"
                    } else {
                        "Uptime ${system.uptime ?: "—"}"
                    },
                    modifier = Modifier.weight(1f),
                    fontSize = 12.sp,
                    color = BombTheme.miuix.onSurfaceVariantSummary,
                )
                Text(
                    text = system.powerWatts?.let { "$it W" } ?: "",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = BombTheme.colors.power,
                )
            }
            if (system.cpuPercent != null) {
                BombSparkline(
                    values = system.cpuTrend(),
                    color = BombTheme.colors.cpu,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun QuickActionRow(navigator: BombNavigator) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BombQuickAction(
            title = "Tasks",
            icon = BombIcons.Tasks,
            tint = BombTheme.colors.cpu,
            onClick = { navigator.push(BombRoute.TaskManager) },
            modifier = Modifier.weight(1f),
        )
        BombQuickAction(
            title = "Freeze",
            icon = BombIcons.Freeze,
            tint = BombTheme.colors.network,
            onClick = { navigator.push(BombRoute.Freeze) },
            modifier = Modifier.weight(1f),
        )
        BombQuickAction(
            title = "Stats",
            icon = BombIcons.Stats,
            tint = BombTheme.colors.gpu,
            onClick = { navigator.selectTab(BombRoute.Monitor) },
            modifier = Modifier.weight(1f),
        )
        BombQuickAction(
            title = "Profiles",
            icon = BombIcons.Performance,
            tint = BombTheme.colors.accent,
            onClick = { navigator.push(BombRoute.Performance) },
            modifier = Modifier.weight(1f),
        )
    }
}
