package com.hzzmonet.zkbomb.ui.more

import androidx.compose.foundation.lazy.LazyListScope
import com.hzzmonet.zkbomb.preview.PreviewData
import com.hzzmonet.zkbomb.preview.PreviewUiState
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hzzmonet.zkbomb.ui.design.BombBackdropStyle
import com.hzzmonet.zkbomb.ui.design.BombIcons
import com.hzzmonet.zkbomb.ui.design.BombTheme
import com.hzzmonet.zkbomb.ui.design.component.BombCard
import com.hzzmonet.zkbomb.ui.design.component.BombPreference
import com.hzzmonet.zkbomb.ui.design.component.BombRowDivider
import com.hzzmonet.zkbomb.ui.design.component.BombSectionTitle
import com.hzzmonet.zkbomb.ui.design.component.BombSegmentedButton
import com.hzzmonet.zkbomb.ui.navigation.BombNavigator
import com.hzzmonet.zkbomb.ui.navigation.BombRoute

fun LazyListScope.moreContent(state: PreviewUiState, navigator: BombNavigator) {
    item { BombSectionTitle("Privilege") }
    item {
        BombCard {
            BombPreference(
                title = "Execution mode",
                summary = "How Bomb gets privilege on this device",
                value = when (state.execMode) {
                    PreviewData.ExecMode.ROM -> "ROM"
                    PreviewData.ExecMode.ROOT -> "Root"
                },
                icon = BombIcons.AppControl,
                iconTint = BombTheme.colors.accent,
                onClick = { navigator.push(BombRoute.ExecutionMode) },
            )
        }
    }

    item { BombSectionTitle("Modules") }
    item {
        BombCard {
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
                summary = "Battery, charging and health",
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
                icon = BombIcons.Logs,
                iconTint = BombTheme.colors.warn,
                onClick = { navigator.push(BombRoute.LogGovernor) },
            )
            BombRowDivider()
            BombPreference(
                title = "Call Recorder",
                summary = "Recording configuration",
                icon = BombIcons.Recorder,
                iconTint = BombTheme.colors.critical,
                onClick = { navigator.push(BombRoute.Recorder) },
            )
        }
    }

    item { BombSectionTitle("Appearance") }
    item {
        BombCard {
            BombPreference(
                title = "Background",
                summary = "Wallpaper, dim, blur and card opacity",
                value = state.backdrop.label,
                icon = BombIcons.Stats,
                iconTint = BombTheme.colors.gpu,
                onClick = { navigator.push(BombRoute.Appearance) },
            )
        }
    }

    item { BombSectionTitle("Bomb") }
    item {
        BombCard {
            BombPreference(
                title = "Settings",
                summary = "Use the module pages above; a separate settings page is not implemented",
                enabled = false,
                onClick = { },
            )
            BombRowDivider()
            BombPreference(
                title = "Capabilities",
                value = capabilitySummary(state),
                onClick = { navigator.push(BombRoute.ExecutionMode) },
            )
            BombRowDivider()
            BombPreference(
                title = "About",
                value = "com.hzzmonet.zkbomb",
                enabled = false,
                onClick = { },
            )
        }
    }
}

private fun capabilitySummary(state: PreviewUiState): String {
    val full = PreviewData.capabilities.count { capability ->
        val level = when (state.execMode) {
            PreviewData.ExecMode.ROM -> capability.rom
            PreviewData.ExecMode.ROOT -> capability.root
        }
        level == 2
    }
    return "$full of ${PreviewData.capabilities.size}"
}
