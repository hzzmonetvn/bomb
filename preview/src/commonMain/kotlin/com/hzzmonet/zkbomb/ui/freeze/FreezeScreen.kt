package com.hzzmonet.zkbomb.ui.freeze

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hzzmonet.zkbomb.data.BombCapabilityKeys
import com.hzzmonet.zkbomb.data.BombServiceState
import com.hzzmonet.zkbomb.data.LiveApp
import com.hzzmonet.zkbomb.preview.PreviewData
import com.hzzmonet.zkbomb.preview.PreviewUiState
import com.hzzmonet.zkbomb.ui.design.BombIcons
import com.hzzmonet.zkbomb.ui.design.BombTheme
import com.hzzmonet.zkbomb.ui.design.component.BombAppRow
import com.hzzmonet.zkbomb.ui.design.component.BombCapabilityBadge
import com.hzzmonet.zkbomb.ui.design.component.BombCard
import com.hzzmonet.zkbomb.ui.design.component.BombEmptyState
import com.hzzmonet.zkbomb.ui.design.component.BombPreference
import com.hzzmonet.zkbomb.ui.design.component.BombRowDivider
import com.hzzmonet.zkbomb.ui.design.component.BombSectionTitle
import com.hzzmonet.zkbomb.ui.design.component.BombSliderPreference
import com.hzzmonet.zkbomb.ui.design.component.BombSwitchPreference
import com.hzzmonet.zkbomb.ui.design.component.BombUnsupportedState
import com.hzzmonet.zkbomb.ui.navigation.BombNavigator
import com.hzzmonet.zkbomb.ui.navigation.BombRoute
import top.yukonga.miuix.kmp.basic.Text

fun LazyListScope.freezeContent(
    state: PreviewUiState,
    navigator: BombNavigator,
    service: BombServiceState,
    apps: List<LiveApp>?,
) {
    val softState = service.stateOf(BombCapabilityKeys.SOFT_FREEZE)
    val deepState = service.stateOf(BombCapabilityKeys.DEEP_FREEZE)
    val canFreeze = softState == "SUPPORTED" || deepState == "SUPPORTED"

    if (!canFreeze) {
        item {
            BombUnsupportedState(
                title = "Freezing is not available on this install",
                reason = "Neither soft nor deep freeze is supported here. The " +
                    "settings below are saved, but nothing will be frozen until " +
                    "a backend can carry them out.",
            )
        }
    }

    item { BombSectionTitle("Auto-Freeze Policy") }
    item {
        BombCard {
            BombSwitchPreference(
                title = "Auto freeze",
                summary = "Automatically freeze managed apps when in background",
                checked = state.autoFreeze,
                onCheckedChange = { state.autoFreeze = it },
                enabled = canFreeze,
            )
            BombRowDivider()
            BombSliderPreference(
                title = "Delay",
                value = state.freezeDelay,
                onValueChange = { state.freezeDelay = it },
                valueLabel = "${state.freezeDelay.toInt()} min",
                valueRange = 1f..30f,
                steps = 28,
                enabled = canFreeze && state.autoFreeze,
            )
            BombRowDivider()
            BombSwitchPreference(
                title = "Unfreeze on user launch",
                summary = "Thaw automatically when the user opens the app",
                checked = state.unfreezeOnLaunch,
                onCheckedChange = { state.unfreezeOnLaunch = it },
                enabled = canFreeze && state.autoFreeze,
            )
            BombRowDivider()
            BombSwitchPreference(
                title = "Freeze on screen off",
                summary = "Trigger immediate freeze when device screen turns off",
                checked = state.freezeOnScreenOff,
                onCheckedChange = { state.freezeOnScreenOff = it },
                enabled = canFreeze && state.autoFreeze,
            )
        }
    }

    item { BombSectionTitle("Freeze Mechanism Backend") }
    item {
        BombCard {
            MechanismRow(
                title = "Soft freeze",
                summary = "Platform freezer — processes suspended, package untouched",
                state = softState,
            )
            BombRowDivider()
            MechanismRow(
                title = "Deep freeze",
                summary = "Package suspend / disable, resolved at runtime",
                state = deepState,
            )
        }
    }

    val allAppsList = if (!apps.isNullOrEmpty()) apps else PreviewData.apps.map {
        LiveApp(
            name = it.name,
            packageName = it.packageName,
            isSystem = it.system,
        )
    }

    val managedApps = allAppsList.filter { state.freezeList.containsKey(it.packageName) }

    item { BombSectionTitle("Managed Apps (${managedApps.size})") }
    item {
        BombCard {
            BombPreference(
                title = "Add managed apps",
                summary = "Choose packages for the freeze engine to manage",
                icon = BombIcons.Apps,
                iconTint = BombTheme.miuix.primary,
                onClick = { navigator.push(BombRoute.FreezePicker) },
            )
            if (managedApps.isEmpty()) {
                BombRowDivider()
                BombEmptyState("No apps added to freeze list yet")
            } else {
                managedApps.forEach { app ->
                    val freezeMode = state.freezeList[app.packageName] ?: "Normal"
                    val badgeColor = when (freezeMode) {
                        "Soft Freeze", "Soft" -> BombTheme.colors.cpu
                        "Deep Freeze", "Deep" -> BombTheme.colors.frozen
                        else -> BombTheme.colors.ok
                    }
                    BombRowDivider()
                    BombAppRow(
                        name = app.name,
                        packageName = app.packageName,
                        tint = badgeColor,
                        trailing = freezeMode,
                        onClick = { navigator.push(BombRoute.AppControl(app.packageName)) },
                    )
                }
            }
        }
    }

    // Exclusion List Management
    item { BombSectionTitle("Exclusion List (Never Freeze)") }
    item {
        BombCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "System Exclusions (Protected)",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = BombTheme.miuix.onSurface,
                )
                Text(
                    text = "SystemUI · Launcher · Active Keyboard · Bomb (`com.hzzmonet.zkbomb`) · Framework core",
                    modifier = Modifier.padding(top = 4.dp),
                    fontSize = 12.sp,
                    color = BombTheme.miuix.onSurfaceVariantSummary,
                )
            }
            BombRowDivider()

            val exclusionApps = allAppsList.take(6)
            exclusionApps.forEachIndexed { index, app ->
                if (index > 0) BombRowDivider()
                val isExcluded = state.freezeExclusions[app.packageName] == true
                BombSwitchPreference(
                    title = app.name,
                    summary = app.packageName,
                    checked = isExcluded,
                    onCheckedChange = { isChecked ->
                        if (isChecked) {
                            state.freezeExclusions[app.packageName] = true
                        } else {
                            state.freezeExclusions.remove(app.packageName)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun MechanismRow(title: String, summary: String, state: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 15.sp, color = BombTheme.miuix.onSurface)
            Text(
                text = summary,
                modifier = Modifier.padding(top = 2.dp),
                fontSize = 12.sp,
                color = BombTheme.miuix.onSurfaceVariantSummary,
            )
        }
        BombCapabilityBadge(state)
    }
}
