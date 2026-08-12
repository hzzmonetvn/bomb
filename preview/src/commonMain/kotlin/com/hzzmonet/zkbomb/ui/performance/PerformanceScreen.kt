package com.hzzmonet.zkbomb.ui.performance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.hzzmonet.zkbomb.data.BombPerformanceProfileDef
import com.hzzmonet.zkbomb.data.BombServiceState
import com.hzzmonet.zkbomb.data.BombThermalGuardianBounds
import com.hzzmonet.zkbomb.data.BombThermalGuardianConfig
import com.hzzmonet.zkbomb.data.PerformanceUiState
import com.hzzmonet.zkbomb.data.V6ApplyState
import com.hzzmonet.zkbomb.data.rememberPerformance
import com.hzzmonet.zkbomb.preview.PreviewUiState
import com.hzzmonet.zkbomb.ui.common.V6ApplyStatusLine
import com.hzzmonet.zkbomb.ui.design.BombTheme
import com.hzzmonet.zkbomb.ui.design.component.BombBadge
import com.hzzmonet.zkbomb.ui.design.component.BombCard
import com.hzzmonet.zkbomb.ui.design.component.BombRowDivider
import com.hzzmonet.zkbomb.ui.design.component.BombSectionTitle
import com.hzzmonet.zkbomb.ui.design.component.BombSegmentedButton
import com.hzzmonet.zkbomb.ui.design.component.BombSliderPreference
import com.hzzmonet.zkbomb.ui.design.component.BombSwitchPreference
import com.hzzmonet.zkbomb.ui.design.component.BombUnsupportedState
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text

/**
 * Performance Profiles (BOMB_PLAN.md §15).
 *
 * A profile is a typed intent; each backend applies only the fields its
 * capabilities prove. This screen is readable (v10), so it shows the real active
 * profile and the fields that actually applied — never the requested intent — and
 * the Thermal Guardian's own forced profile, distinct from a user selection.
 */
fun LazyListScope.performanceContent(state: PreviewUiState, service: BombServiceState) {
    item { IntroCard() }
    item { PerformanceConsole(state, service) }
}

@Composable
private fun IntroCard() {
    BombCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Typed intent, capability-gated",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = BombTheme.miuix.onSurface,
            )
            Text(
                text = "A profile requests swappiness, page-cluster, refresh rate and CPU/GPU/" +
                    "thermal strategies. Only the fields this device can honour are applied — the " +
                    "applied set below is read back from the backend, not assumed.",
                modifier = Modifier.padding(top = 6.dp),
                fontSize = 12.sp,
                color = BombTheme.miuix.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun PerformanceConsole(state: PreviewUiState, service: BombServiceState) {
    val controller = service.controller
    var refreshKey by remember { mutableIntStateOf(0) }
    val uiState = rememberPerformance(
        service = service,
        active = true,
        intervalMillis = state.samplingIntervalMillis,
        refreshKey = refreshKey,
    )
    var profileStatus by remember { mutableStateOf<V6ApplyState>(V6ApplyState.Idle) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (uiState) {
            PerformanceUiState.Loading ->
                InfoCard("Reading performance profiles from the privileged service…")

            is PerformanceUiState.Unsupported ->
                BombUnsupportedState(title = "Performance Profiles", reason = uiState.reason)

            is PerformanceUiState.Error ->
                InfoCard(uiState.message, error = true)

            is PerformanceUiState.Ready -> {
                val snapshot = uiState.snapshot
                val names = snapshot.profiles.map { it.name }
                // "None" is the cleared state; its index is the profile count.
                val options = names.map { profileLabel(it) } + "None"
                val activeIdx = snapshot.activeProfile
                    ?.let { active -> names.indexOfFirst { it == active } }
                    ?.takeIf { it >= 0 } ?: names.size

                BombSegmentedButton(
                    options = options,
                    selectedIndex = activeIdx,
                    onSelected = { selected ->
                        profileStatus = V6ApplyState.Applying
                        if (controller == null) {
                            profileStatus = V6ApplyState.Done(NOT_CONNECTED)
                        } else if (selected == names.size) {
                            controller.clearPerformanceProfile { result ->
                                profileStatus = V6ApplyState.Done(result)
                                if (result.isSuccess) refreshKey++
                            }
                        } else {
                            controller.setPerformanceProfile(names[selected]) { result ->
                                profileStatus = V6ApplyState.Done(result)
                                if (result.isSuccess) refreshKey++
                            }
                        }
                    },
                )
                V6ApplyStatusLine(profileStatus)

                ActiveProfileCard(snapshot)

                val detail = snapshot.activeProfile?.let { active ->
                    snapshot.profiles.firstOrNull { it.name == active }
                }
                if (detail != null) {
                    BombSectionTitle("Profile definition")
                    ProfileDetailCard(detail, snapshot.appliedFields)
                }

                BombSectionTitle("Thermal Guardian")
                ThermalGuardianCard(
                    snapshot = snapshot,
                    controllerAvailable = controller != null,
                    onApply = { config, onResult ->
                        if (controller == null) onResult(NOT_CONNECTED)
                        else controller.setThermalGuardianConfig(config) { result ->
                            onResult(result)
                            if (result.isSuccess) refreshKey++
                        }
                    },
                    onClear = { onResult ->
                        if (controller == null) onResult(NOT_CONNECTED)
                        else controller.clearThermalGuardianConfig { result ->
                            onResult(result)
                            if (result.isSuccess) refreshKey++
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ActiveProfileCard(snapshot: com.hzzmonet.zkbomb.data.BombPerformanceProfilesSnapshot) {
    BombCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Active profile",
                        fontSize = 13.sp,
                        color = BombTheme.miuix.onSurfaceVariantSummary,
                    )
                    Text(
                        text = snapshot.activeProfile?.let { profileLabel(it) } ?: "None (stock)",
                        modifier = Modifier.padding(top = 2.dp),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium,
                        color = BombTheme.miuix.onSurface,
                    )
                }
                BombBadge(
                    text = if (snapshot.memoryControlAvailable) "Memory ✓" else "Memory ✗",
                    color = if (snapshot.memoryControlAvailable) BombTheme.colors.ok
                    else BombTheme.miuix.onSurfaceVariantSummary,
                )
            }
            Text(
                text = if (snapshot.appliedFields.isEmpty()) {
                    "No fields applied on this device."
                } else {
                    "Applied: " + snapshot.appliedFields.joinToString(", ") { fieldLabel(it) }
                },
                modifier = Modifier.padding(top = 10.dp),
                fontSize = 12.sp,
                color = BombTheme.miuix.onSurfaceVariantSummary,
            )
            snapshot.thermalGuardianActiveProfile?.let { forced ->
                Text(
                    text = "Thermal Guardian is holding ${profileLabel(forced)}.",
                    modifier = Modifier.padding(top = 6.dp),
                    fontSize = 12.sp,
                    color = BombTheme.colors.warn,
                )
            }
        }
    }
}

@Composable
private fun ProfileDetailCard(def: BombPerformanceProfileDef, appliedFields: List<String>) {
    BombCard {
        DetailRow("vm.swappiness", def.swappiness.toString(), appliedFields.contains("swappiness"))
        BombRowDivider()
        DetailRow("vm.page-cluster", def.pageCluster.toString(), appliedFields.contains("pageCluster"))
        BombRowDivider()
        DetailRow(
            "Refresh rate",
            def.refreshRateHz?.let { "$it Hz" } ?: "unchanged",
            appliedFields.contains("refreshRateHz"),
        )
        BombRowDivider()
        DetailRow("CPU strategy", strategyLabel(def.cpuStrategy), appliedFields.contains("cpuStrategy"))
        BombRowDivider()
        DetailRow("GPU strategy", strategyLabel(def.gpuStrategy), appliedFields.contains("gpuStrategy"))
        BombRowDivider()
        DetailRow("Thermal strategy", strategyLabel(def.thermalStrategy), appliedFields.contains("thermalStrategy"))
        BombRowDivider()
        DetailRow("Monitor preset", strategyLabel(def.monitorPreset), applied = false)
    }
}

@Composable
private fun DetailRow(label: String, value: String, applied: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, modifier = Modifier.weight(1f), fontSize = 14.sp, color = BombTheme.miuix.onSurface)
        if (applied) {
            BombBadge(text = "applied", color = BombTheme.colors.ok, modifier = Modifier.padding(end = 8.dp))
        }
        Text(text = value, fontSize = 14.sp, color = BombTheme.miuix.onSurfaceVariantActions)
    }
}

// ------------------------------------------------------------------ Thermal Guardian

@Composable
private fun ThermalGuardianCard(
    snapshot: com.hzzmonet.zkbomb.data.BombPerformanceProfilesSnapshot,
    controllerAvailable: Boolean,
    onApply: (BombThermalGuardianConfig, (com.hzzmonet.zkbomb.data.BombOperationResult) -> Unit) -> Unit,
    onClear: ((com.hzzmonet.zkbomb.data.BombOperationResult) -> Unit) -> Unit,
) {
    val current = snapshot.thermalGuardianConfig
    val enabled = current != null
    val base = current ?: BombThermalGuardianBounds.DEFAULT

    var editing by remember(enabled) { mutableStateOf(false) }
    // Init once, not keyed on the polled snapshot: a fresh (equal) config object
    // arrives every sampling tick, and keying on it would snap the sliders back
    // under the user's finger mid-edit. Config only changes by a user write.
    var restoreC by remember { mutableStateOf(base.restoreAtDeciCelsius.toFloat()) }
    var sustainableC by remember { mutableStateOf(base.sustainableAtDeciCelsius.toFloat()) }
    var ecoC by remember { mutableStateOf(base.ecoAtDeciCelsius.toFloat()) }
    var cooldownSec by remember { mutableStateOf(base.cooldownMillis / 1000f) }
    var status by remember { mutableStateOf<V6ApplyState>(V6ApplyState.Idle) }

    val config = BombThermalGuardianConfig(
        sustainableAtDeciCelsius = sustainableC.toInt(),
        ecoAtDeciCelsius = ecoC.toInt(),
        restoreAtDeciCelsius = restoreC.toInt(),
        cooldownMillis = (cooldownSec.toInt() * 1000L),
    )
    val violation = BombThermalGuardianBounds.violation(config)

    BombCard {
        BombSwitchPreference(
            title = "Thermal Guardian",
            summary = if (enabled) {
                "Active — eases to Sustainable, then Eco, as the SoC heats"
            } else {
                "Automatically ease the profile when the device runs hot"
            },
            checked = enabled || editing,
            onCheckedChange = { on ->
                if (on) {
                    editing = true
                } else {
                    editing = false
                    status = V6ApplyState.Applying
                    onClear { status = V6ApplyState.Done(it) }
                }
            },
            enabled = controllerAvailable,
        )

        if (enabled || editing) {
            BombRowDivider()
            BombSliderPreference(
                title = "Restore below",
                value = restoreC,
                onValueChange = { restoreC = it },
                valueLabel = deciToLabel(restoreC),
                valueRange = BombThermalGuardianBounds.MIN_RESTORE_DECI_CELSIUS.toFloat()..
                    BombThermalGuardianBounds.MAX_RESTORE_DECI_CELSIUS.toFloat(),
                enabled = controllerAvailable,
            )
            BombSliderPreference(
                title = "Ease to Sustainable at",
                value = sustainableC,
                onValueChange = { sustainableC = it },
                valueLabel = deciToLabel(sustainableC),
                valueRange = BombThermalGuardianBounds.MIN_SUSTAINABLE_DECI_CELSIUS.toFloat()..
                    BombThermalGuardianBounds.MAX_SUSTAINABLE_DECI_CELSIUS.toFloat(),
                enabled = controllerAvailable,
            )
            BombSliderPreference(
                title = "Ease to Eco at",
                value = ecoC,
                onValueChange = { ecoC = it },
                valueLabel = deciToLabel(ecoC),
                valueRange = BombThermalGuardianBounds.MIN_ECO_DECI_CELSIUS.toFloat()..
                    BombThermalGuardianBounds.MAX_ECO_DECI_CELSIUS.toFloat(),
                enabled = controllerAvailable,
            )
            BombSliderPreference(
                title = "Cooldown before restore",
                value = cooldownSec,
                onValueChange = { cooldownSec = it },
                valueLabel = "${cooldownSec.toInt()} s",
                valueRange = (BombThermalGuardianBounds.MIN_COOLDOWN_MILLIS / 1000).toFloat()..600f,
                enabled = controllerAvailable,
            )
            if (violation != null) {
                Text(
                    text = violation,
                    modifier = Modifier.padding(horizontal = 16.dp),
                    fontSize = 12.sp,
                    color = BombTheme.colors.warn,
                )
            }
            Button(
                onClick = {
                    status = V6ApplyState.Applying
                    onApply(config) { status = V6ApplyState.Done(it) }
                },
                enabled = controllerAvailable && violation == null && status !is V6ApplyState.Applying,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(text = if (enabled) "Update guardian" else "Enable guardian")
            }
            V6ApplyStatusLine(status, modifier = Modifier.padding(start = 16.dp, bottom = 8.dp))
        }
    }
}

// ------------------------------------------------------------------ helpers

private fun profileLabel(name: String): String =
    name.split('_').joinToString(" ") { it.lowercase().replaceFirstChar(Char::uppercase) }

private fun strategyLabel(name: String): String =
    name.split('_').joinToString(" ") { it.lowercase().replaceFirstChar(Char::uppercase) }

private fun fieldLabel(field: String): String = when (field) {
    "swappiness" -> "swappiness"
    "pageCluster" -> "page-cluster"
    "refreshRateHz" -> "refresh rate"
    "cpuStrategy" -> "CPU"
    "gpuStrategy" -> "GPU"
    "thermalStrategy" -> "thermal"
    else -> field
}

private fun deciToLabel(deci: Float): String {
    val whole = deci.toInt() / 10
    val frac = deci.toInt() % 10
    return "$whole.$frac °C"
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

private val NOT_CONNECTED = com.hzzmonet.zkbomb.data.BombOperationResult("BACKEND_UNAVAILABLE", "Service is not connected")
