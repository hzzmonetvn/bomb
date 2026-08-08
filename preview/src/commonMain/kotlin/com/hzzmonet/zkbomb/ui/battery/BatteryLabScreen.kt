package com.hzzmonet.zkbomb.ui.battery

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
import com.hzzmonet.zkbomb.data.temperatureTrend
import com.hzzmonet.zkbomb.preview.PreviewData
import com.hzzmonet.zkbomb.preview.PreviewUiState
import com.hzzmonet.zkbomb.ui.design.BombIcons
import com.hzzmonet.zkbomb.ui.design.BombTheme
import com.hzzmonet.zkbomb.ui.design.component.BombCard
import com.hzzmonet.zkbomb.ui.design.component.BombPreference
import com.hzzmonet.zkbomb.ui.design.component.BombRowDivider
import com.hzzmonet.zkbomb.ui.design.component.BombSectionTitle
import com.hzzmonet.zkbomb.ui.design.component.BombSliderPreference
import com.hzzmonet.zkbomb.ui.design.component.BombSparkline
import com.hzzmonet.zkbomb.ui.design.component.BombStatCard
import com.hzzmonet.zkbomb.ui.design.component.BombSwitchPreference
import com.hzzmonet.zkbomb.ui.design.component.BombUnsupportedState
import top.yukonga.miuix.kmp.basic.Text

fun LazyListScope.batteryLabContent(state: PreviewUiState, system: SystemView) {
    item { BatteryHeader(system) }

    item { BombSectionTitle("Health") }
    item {
        BombCard {
            BombPreference(
                title = "Cycle count",
                summary = if (system.isLive && system.batteryCycleCount == null) {
                    "Not reported by this ROM"
                } else {
                    null
                },
                value = system.batteryCycleCount?.toString() ?: "—",
                onClick = {},
            )
            BombRowDivider()
            BombPreference(
                title = "Charge counter",
                value = system.chargeCounterMah?.let { "$it mAh" } ?: "—",
                onClick = {},
            )
            BombRowDivider()
            BombPreference(title = "Health", value = system.batteryHealth ?: "—", onClick = {})
            BombRowDivider()
            BombPreference(title = "Technology", value = system.batteryTechnology ?: "—", onClick = {})
            BombRowDivider()
            BombPreference(title = "Thermal status", value = system.thermalStatus ?: "—", onClick = {})
        }
    }

    item { BombSectionTitle("Charging") }
    item {
        BombCard {
            BombSwitchPreference(
                title = "Charge limit",
                summary = "Stop charging at a level to reduce wear",
                checked = state.chargeLimit,
                onCheckedChange = { state.chargeLimit = it },
            )
            if (state.chargeLimit) {
                BombRowDivider()
                BombSliderPreference(
                    title = "Limit",
                    value = state.chargeLimitLevel,
                    onValueChange = { state.chargeLimitLevel = it },
                    valueLabel = "${state.chargeLimitLevel.toInt()}%",
                    valueRange = 60f..95f,
                    steps = 6,
                )
            }
        }
    }

    item {
        BombUnsupportedState(
            title = "Charging current strategy",
            reason = "This device exposes a charge-limit node but no writable current " +
                "control. Bomb enables the controls it probed successfully and marks " +
                "the rest unavailable — it does not guess vendor paths.",
        )
    }
}

@Composable
private fun BatteryHeader(system: SystemView) {
    BombCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Battery",
                    modifier = Modifier.weight(1f),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = BombTheme.miuix.onSurface,
                )
                Text(
                    text = system.batteryStatus ?: "—",
                    fontSize = 13.sp,
                    color = BombTheme.miuix.onSurfaceVariantSummary,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BombStatCard(
                    label = "Level",
                    value = system.batteryPercent?.toString() ?: "—",
                    unit = "%",
                    color = BombTheme.colors.ok,
                    icon = BombIcons.Battery,
                    progress = system.batteryPercent?.let { it / 100f },
                    modifier = Modifier.weight(1f),
                )
                BombStatCard(
                    label = "Temp",
                    value = system.batteryTempC ?: "—",
                    unit = "°C",
                    color = BombTheme.colors.thermal,
                    icon = BombIcons.Thermal,
                    modifier = Modifier.weight(1f),
                )
                BombStatCard(
                    label = "Power",
                    value = system.powerWatts ?: "—",
                    unit = "W",
                    color = BombTheme.colors.power,
                    icon = BombIcons.Power,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp),
            ) {
                Text(
                    text = "Voltage ${system.batteryVoltageV ?: "—"} V",
                    modifier = Modifier.weight(1f),
                    fontSize = 12.sp,
                    color = BombTheme.miuix.onSurfaceVariantSummary,
                )
                Text(
                    text = "Current ${system.batteryCurrentMa?.toString() ?: "—"} mA",
                    fontSize = 12.sp,
                    color = BombTheme.miuix.onSurfaceVariantSummary,
                )
            }
            BombSparkline(
                values = system.temperatureTrend(),
                color = BombTheme.colors.thermal,
                maxValue = 60f,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(top = 10.dp),
            )
        }
    }
}
