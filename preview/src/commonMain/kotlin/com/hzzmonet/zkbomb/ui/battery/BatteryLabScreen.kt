package com.hzzmonet.zkbomb.ui.battery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hzzmonet.zkbomb.data.BombBatteryBounds
import com.hzzmonet.zkbomb.data.BombBatteryLabProfile
import com.hzzmonet.zkbomb.data.BombBatteryLabSnapshot
import com.hzzmonet.zkbomb.data.BombBatteryLabBackendStatus
import com.hzzmonet.zkbomb.data.BombOperationResult
import com.hzzmonet.zkbomb.data.BombServiceState
import com.hzzmonet.zkbomb.data.BatteryLabState
import com.hzzmonet.zkbomb.data.SystemView
import com.hzzmonet.zkbomb.data.V6ApplyState
import com.hzzmonet.zkbomb.data.rememberBatteryLab
import com.hzzmonet.zkbomb.data.temperatureTrend
import com.hzzmonet.zkbomb.preview.PreviewUiState
import com.hzzmonet.zkbomb.ui.common.V6ApplyStatusLine
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
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text

/**
 * Battery Lab (BOMB_PLAN.md §14) — battery health, charging and a bounded
 * charge/thermal profile.
 *
 * The framework header is the unprivileged battery view; the "Battery Lab" section
 * below is the privileged v9 `/sys/class/power_supply` snapshot with the active
 * profile read back from the service. The profile console only enables the
 * controls the backend probed as writable and shows the service's own result.
 */
fun LazyListScope.batteryLabContent(
    state: PreviewUiState,
    system: SystemView,
    service: BombServiceState,
) {
    item { BatteryHeader(system) }
    item { BombSectionTitle("Battery Lab") }
    item { BatteryLabSection(service, state.samplingIntervalMillis) }
}

@Composable
private fun BatteryLabSection(service: BombServiceState, intervalMillis: Long) {
    var refreshKey by remember { mutableIntStateOf(0) }
    val labState = rememberBatteryLab(
        service = service,
        active = true, // composed only on the Battery Lab route, so this is route-gated
        intervalMillis = intervalMillis,
        refreshKey = refreshKey,
    )

    when (labState) {
        BatteryLabState.Loading ->
            InfoCard("Reading the power-supply snapshot from the privileged service…")

        is BatteryLabState.Unsupported ->
            BombUnsupportedState(title = "Battery Lab", reason = labState.reason)

        is BatteryLabState.Error ->
            InfoCard(labState.message, error = true)

        is BatteryLabState.Ready -> {
            val snapshot = labState.snapshot
            when (snapshot.backendStatus) {
                BombBatteryLabBackendStatus.AVAILABLE ->
                    BatteryLabReady(service, snapshot) { refreshKey++ }
                BombBatteryLabBackendStatus.UNSUPPORTED ->
                    BombUnsupportedState(
                        title = "Battery Lab unavailable",
                        reason = "This device does not expose a charge- or thermal-control node " +
                            "Bomb can drive. Health metrics above still come from the framework.",
                    )
                BombBatteryLabBackendStatus.UNAVAILABLE ->
                    InfoCard("The power-supply backend did not answer on this build.", error = true)
            }
        }
    }
}

@Composable
private fun BatteryLabReady(
    service: BombServiceState,
    snapshot: BombBatteryLabSnapshot,
    onApplied: () -> Unit,
) {
    HealthCard(snapshot)
    ChargeControlCard(service, snapshot, onApplied)
}

@Composable
private fun HealthCard(snapshot: BombBatteryLabSnapshot) {
    BombCard {
        BombPreference(
            title = "Power supply",
            value = snapshot.powerSupplyName ?: "—",
            enabled = false,
            onClick = { },
        )
        BombRowDivider()
        BombPreference(title = "Status", value = snapshot.status ?: "—", enabled = false, onClick = { })
        BombRowDivider()
        BombPreference(
            title = "Cycle count",
            value = snapshot.cycleCount?.toString() ?: "—",
            enabled = false,
            onClick = { },
        )
        BombRowDivider()
        BombPreference(
            title = "Battery health",
            summary = "Full charge relative to design capacity",
            value = snapshot.healthPercent?.let { "$it%" } ?: "—",
            enabled = false,
            onClick = { },
        )
        BombRowDivider()
        BombPreference(
            title = "Temperature",
            value = deciToC(snapshot.temperatureDeciCelsius),
            enabled = false,
            onClick = { },
        )
        BombRowDivider()
        BombPreference(
            title = "Charge control",
            summary = "Strategy Bomb probed on this device",
            value = snapshot.chargeControlKind ?: "none",
            enabled = false,
            onClick = { },
        )
    }
}

@Composable
private fun ChargeControlCard(
    service: BombServiceState,
    snapshot: BombBatteryLabSnapshot,
    onApplied: () -> Unit,
) {
    val controller = service.controller
    val canCharge = snapshot.chargeLimitControlSupported
    val canThermal = snapshot.thermalChargeControlSupported
    val canCurrent = snapshot.chargeCurrentControlSupported

    if (!canCharge && !canThermal && !canCurrent) {
        BombUnsupportedState(
            title = "Charge policy",
            reason = "No writable charge-limit, thermal-control or charge-current node was probed on " +
                "this device. Bomb enables only controls it verified — it does not guess vendor paths.",
        )
        return
    }

    // The charge-current ceiling the slider tops out at: the device's advertised
    // maximum when known, otherwise a conservative fallback.
    val currentCeilingMicroamps = snapshot.maxSupportedChargeCurrentMicroamps
        ?: BombBatteryBounds.FALLBACK_CEILING_CHARGE_CURRENT_MICROAMPS

    // Composed desired policy. The active policy is shown separately from the
    // snapshot, so this is what the user is about to apply — not a live readout.
    var chargeOn by remember { mutableStateOf(snapshot.activeProfile?.chargeLimitPercent != null && canCharge) }
    var chargePercent by remember {
        mutableIntStateOf(snapshot.activeProfile?.chargeLimitPercent ?: 80)
    }
    var thermalOn by remember { mutableStateOf(snapshot.activeProfile?.maxTemperatureDeciCelsius != null && canThermal) }
    var maxTempDeci by remember {
        mutableIntStateOf(snapshot.activeProfile?.maxTemperatureDeciCelsius ?: 450)
    }
    var currentOn by remember {
        mutableStateOf(snapshot.activeProfile?.maxChargeCurrentMicroamps != null && canCurrent)
    }
    var maxCurrentMicroamps by remember {
        mutableIntStateOf(
            snapshot.activeProfile?.maxChargeCurrentMicroamps
                ?: BombBatteryBounds.DEFAULT_CHARGE_CURRENT_MICROAMPS.coerceAtMost(currentCeilingMicroamps),
        )
    }
    var status by remember { mutableStateOf<V6ApplyState>(V6ApplyState.Idle) }
    val applying = status is V6ApplyState.Applying

    val draft = BombBatteryLabProfile(
        chargeLimitPercent = if (chargeOn) chargePercent else null,
        maxTemperatureDeciCelsius = if (thermalOn) maxTempDeci else null,
        capacityResumeHysteresisPercent = BombBatteryBounds.DEFAULT_CAPACITY_HYSTERESIS_PERCENT,
        temperatureResumeHysteresisDeciCelsius = BombBatteryBounds.DEFAULT_TEMPERATURE_HYSTERESIS_DECI_CELSIUS,
        maxChargeCurrentMicroamps = if (currentOn) maxCurrentMicroamps else null,
    )
    val violation = BombBatteryBounds.violation(draft)

    BombCard {
        BombSwitchPreference(
            title = "Charge limit",
            summary = if (canCharge) "Stop charging at a level to reduce wear" else "No charge-limit node on this device",
            checked = chargeOn,
            onCheckedChange = { chargeOn = it },
            enabled = canCharge && !applying,
        )
        if (chargeOn) {
            BombRowDivider()
            BombSliderPreference(
                title = "Limit",
                value = chargePercent.toFloat(),
                onValueChange = { chargePercent = it.toInt() },
                valueLabel = "$chargePercent%",
                valueRange = BombBatteryBounds.MIN_CHARGE_LIMIT_PERCENT.toFloat()..
                    BombBatteryBounds.MAX_CHARGE_LIMIT_PERCENT.toFloat(),
                steps = 0,
                enabled = canCharge && !applying,
            )
        }
        BombRowDivider()
        BombSwitchPreference(
            title = "Temperature limit",
            summary = if (canThermal) "Pause charging above a battery temperature" else "No thermal-control node on this device",
            checked = thermalOn,
            onCheckedChange = { thermalOn = it },
            enabled = canThermal && !applying,
        )
        if (thermalOn) {
            BombRowDivider()
            BombSliderPreference(
                title = "Max temperature",
                value = (maxTempDeci / 10f),
                onValueChange = { maxTempDeci = (it * 10).toInt() },
                valueLabel = deciToC(maxTempDeci),
                valueRange = (BombBatteryBounds.MIN_TEMPERATURE_DECI_CELSIUS / 10f)..
                    (BombBatteryBounds.MAX_TEMPERATURE_DECI_CELSIUS / 10f),
                steps = 0,
                enabled = canThermal && !applying,
            )
        }
        BombRowDivider()
        BombSwitchPreference(
            title = "Charge current limit",
            summary = if (canCurrent) {
                "Cap the charging current to run cooler and slow wear"
            } else {
                "No charge-current node on this device"
            },
            checked = currentOn,
            onCheckedChange = { currentOn = it },
            enabled = canCurrent && !applying,
        )
        if (currentOn) {
            BombRowDivider()
            BombSliderPreference(
                title = "Max current",
                // The slider works in mA (µA / 1000) — the unit the user reads — and
                // the draft stores µA, matching the snapshot's currentMicroamps.
                value = (maxCurrentMicroamps / 1000f),
                onValueChange = { maxCurrentMicroamps = (it.toInt()) * 1000 },
                valueLabel = "${maxCurrentMicroamps / 1000} mA",
                valueRange = (BombBatteryBounds.MIN_CHARGE_CURRENT_MICROAMPS / 1000f)..
                    (currentCeilingMicroamps / 1000f),
                steps = 0,
                enabled = canCurrent && !applying,
            )
        }
    }

    BombCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ActiveLine("Active profile", describeProfile(snapshot.activeProfile))
            ActiveLine(
                "Charging suspended by Bomb",
                if (snapshot.chargingSuspendedByBomb) "Yes" else "No",
            )
            snapshot.lastDecisionReason?.let { ActiveLine("Last decision", it) }

            if (violation != null) {
                Text(text = violation, fontSize = 12.sp, color = BombTheme.colors.warn)
            }
            V6ApplyStatusLine(status)

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        status = V6ApplyState.Applying
                        if (controller == null) {
                            status = V6ApplyState.Done(NOT_CONNECTED)
                        } else {
                            controller.setBatteryLabProfile(draft) { result ->
                                status = V6ApplyState.Done(result)
                                if (result.isSuccess) onApplied()
                            }
                        }
                    },
                    enabled = controller != null && !applying && violation == null,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(text = if (applying) "Applying…" else "Apply profile")
                }
                Button(
                    onClick = {
                        status = V6ApplyState.Applying
                        if (controller == null) {
                            status = V6ApplyState.Done(NOT_CONNECTED)
                        } else {
                            controller.clearBatteryLabProfile { result ->
                                status = V6ApplyState.Done(result)
                                if (result.isSuccess) onApplied()
                            }
                        }
                    },
                    enabled = controller != null && !applying && snapshot.activeProfile != null,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(),
                ) {
                    Text(text = "Clear profile")
                }
            }
        }
    }
}

@Composable
private fun ActiveLine(label: String, value: String) {
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

private fun describeProfile(profile: BombBatteryLabProfile?): String {
    if (profile == null) return "None"
    val parts = buildList {
        profile.chargeLimitPercent?.let { add("charge ≤ $it%") }
        profile.maxTemperatureDeciCelsius?.let { add("temp ≤ ${deciToC(it)}") }
        profile.maxChargeCurrentMicroamps?.let { add("current ≤ ${it / 1000} mA") }
    }
    return if (parts.isEmpty()) "None" else parts.joinToString(" · ")
}

private fun deciToC(deci: Int?): String {
    if (deci == null) return "—"
    val whole = deci / 10
    val frac = kotlin.math.abs(deci % 10)
    return "$whole.$frac°C"
}

@Composable
private fun InfoCard(text: String, error: Boolean = false) {
    BombCard {
        Text(
            text = text,
            modifier = Modifier.padding(16.dp),
            fontSize = 13.sp,
            color = if (error) BombTheme.colors.critical else BombTheme.miuix.onSurfaceVariantSummary,
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

private val NOT_CONNECTED = BombOperationResult("BACKEND_UNAVAILABLE", "Service is not connected")
