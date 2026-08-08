package com.hzzmonet.zkbomb.ui.mode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hzzmonet.zkbomb.preview.PreviewData
import com.hzzmonet.zkbomb.preview.PreviewUiState
import com.hzzmonet.zkbomb.ui.design.BombTheme
import com.hzzmonet.zkbomb.ui.design.component.BombBadge
import com.hzzmonet.zkbomb.ui.design.component.BombCard
import com.hzzmonet.zkbomb.ui.design.component.BombPreference
import com.hzzmonet.zkbomb.ui.design.component.BombRowDivider
import com.hzzmonet.zkbomb.ui.design.component.BombSectionTitle
import com.hzzmonet.zkbomb.ui.design.component.BombSegmentedButton
import top.yukonga.miuix.kmp.basic.Text

private val modeLabels = listOf("ROM", "Root")

/**
 * How Bomb obtains privilege on this device.
 *
 * Some operations are genuinely hard to ship inside a ROM: they need SELinux
 * types or rules the prebuilt-image patcher cannot add safely. Root mode exists
 * for exactly that gap — a Magisk/KernelSU module can load `sepolicy.rule` at
 * boot and start `bombd` without repacking a single partition.
 *
 * Root is a **backend**, not an API. The AIDL surface is identical in all three
 * modes: the same typed calls, the same validation, the same `BombResult`.
 * Nothing here exposes a shell to the UI.
 */
fun LazyListScope.executionModeContent(state: PreviewUiState) {
    item {
        BombSegmentedButton(
            options = modeLabels,
            selectedIndex = state.execMode.ordinal,
            onSelected = { state.execMode = PreviewData.ExecMode.entries[it] },
        )
    }

    item { ModeSummaryCard(state.execMode) }

    item { BombSectionTitle("Detected") }
    item {
        BombCard {
            BombPreference(
                title = "Privileged app",
                summary = "system_ext/priv-app + permission allowlist",
                value = if (state.execMode == PreviewData.ExecMode.ROM) "Yes" else "No",
                onClick = {},
            )
            BombRowDivider()
            BombPreference(
                title = "Root manager",
                summary = "Magisk / KernelSU",
                value = if (state.execMode == PreviewData.ExecMode.ROOT) "KernelSU" else "Not granted",
                onClick = {},
            )
            BombRowDivider()
            BombPreference(
                title = "bombd",
                summary = "Native daemon for typed sysfs/procfs operations",
                value = when (state.execMode) {
                    PreviewData.ExecMode.ROM -> "Running · SELinux domain bombd"
                    PreviewData.ExecMode.ROOT -> "Running · started by module"
                },
                onClick = {},
            )
            BombRowDivider()
            BombPreference(
                title = "SELinux",
                summary = "Never set permissive by Bomb, in any mode",
                value = "Enforcing",
                enabled = false,
                onClick = {},
            )
        }
    }

    item { BombSectionTitle("Capabilities in this mode") }
    item {
        BombCard {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                PreviewData.capabilities.forEach { capability ->
                    val level = when (state.execMode) {
                        PreviewData.ExecMode.ROM -> capability.rom
                        PreviewData.ExecMode.ROOT -> capability.root
                    }
                    CapabilityRow(capability, level)
                }
            }
        }
    }

    item { BombSectionTitle("How root mode ships") }
    item { RootModeCard() }
}

@Composable
private fun ModeSummaryCard(mode: PreviewData.ExecMode) {
    val (title, body) = when (mode) {
        PreviewData.ExecMode.ROM -> "Integrated into the ROM" to
            "Bomb is a privileged app in system_ext with its own SELinux domains " +
            "and bombd labelled at build time. Everything is available and nothing " +
            "depends on a root manager staying installed."

        PreviewData.ExecMode.ROOT -> "Root module" to
            "Bomb installs as a normal app plus a Magisk/KernelSU module. The module " +
            "carries sepolicy.rule, so the SELinux types the ROM patcher cannot add " +
            "safely are loaded at boot instead — and bombd starts from the module's " +
            "service script. This is the path for tweaks that are impractical to " +
            "integrate into a prebuilt image."

    }
    val tint = when (mode) {
        PreviewData.ExecMode.ROM -> BombTheme.miuix.primary
        PreviewData.ExecMode.ROOT -> BombTheme.colors.accent
    }
    BombCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = BombTheme.miuix.onSurface,
                )
                BombBadge(text = modeLabels[mode.ordinal], color = tint)
            }
            Text(
                text = body,
                modifier = Modifier.padding(top = 8.dp),
                fontSize = 13.sp,
                color = BombTheme.miuix.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun CapabilityRow(capability: PreviewData.DemoCapability, level: Int) {
    val (label, color) = when (level) {
        2 -> "Full" to BombTheme.colors.ok
        1 -> "Partial" to BombTheme.colors.warn
        else -> "Unavailable" to BombTheme.colors.frozen
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = capability.name,
                fontSize = 14.sp,
                color = BombTheme.miuix.onSurface,
            )
            Text(
                text = capability.detail,
                modifier = Modifier.padding(top = 2.dp),
                fontSize = 11.sp,
                color = BombTheme.miuix.onSurfaceVariantSummary,
            )
        }
        BombBadge(text = label, color = color)
    }
}

@Composable
private fun RootModeCard() {
    BombCard {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            RootLine(
                "module.prop + service.sh",
                "Starts bombd as its own user, not as a root shell for the UI",
                BombTheme.colors.accent,
            )
            RootLine(
                "sepolicy.rule",
                "Declares bomb_app / bombd / bomb_data_file and the minimal allow " +
                    "rules — loaded by magiskpolicy at boot, no partition repack",
                BombTheme.colors.gpu,
            )
            RootLine(
                "Same AIDL surface",
                "No executeShell, no writeSysfs, no setProperty. Root only changes " +
                    "which backend answers a typed call",
                BombTheme.colors.ok,
            )
            RootLine(
                "Degrades honestly",
                "If the module is removed, the backend becomes unavailable and the UI " +
                    "reports that state rather than failing silently",
                BombTheme.colors.network,
            )
        }
    }
}

@Composable
private fun RootLine(title: String, detail: String, color: Color) {
    Column {
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = color,
        )
        Text(
            text = detail,
            modifier = Modifier.padding(top = 2.dp),
            fontSize = 12.sp,
            color = BombTheme.miuix.onSurfaceVariantSummary,
        )
    }
}
