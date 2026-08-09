package com.hzzmonet.zkbomb.ui.network

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hzzmonet.zkbomb.data.SystemView
import com.hzzmonet.zkbomb.preview.PreviewData
import com.hzzmonet.zkbomb.preview.PreviewUiState
import com.hzzmonet.zkbomb.ui.design.BombIcons
import com.hzzmonet.zkbomb.ui.design.BombTheme
import com.hzzmonet.zkbomb.ui.design.component.BombAppRow
import com.hzzmonet.zkbomb.ui.design.component.BombCard
import com.hzzmonet.zkbomb.ui.design.component.BombPreference
import com.hzzmonet.zkbomb.ui.design.component.BombRowDivider
import com.hzzmonet.zkbomb.ui.design.component.BombSectionTitle
import com.hzzmonet.zkbomb.ui.design.component.BombSegmentedButton
import com.hzzmonet.zkbomb.ui.design.component.BombStatCard
import com.hzzmonet.zkbomb.ui.design.component.BombUnsupportedState

private val filters = listOf("All", "Wi-Fi", "Mobile")

fun LazyListScope.networkContent(state: PreviewUiState, system: SystemView) {
    item { RateCard(system) }
    item {
        BombSegmentedButton(
            options = filters,
            selectedIndex = state.networkFilter,
            onSelected = { state.networkFilter = it },
        )
    }

    if (system.isLive) {
        item {
            BombUnsupportedState(
                title = "Per-app traffic",
                reason = "TrafficStats reports per-UID bytes only for Bomb's own UID, and " +
                    "NetworkStatsManager needs the PACKAGE_USAGE_STATS special access. " +
                    "Device totals above are real.",
            )
        }
        return
    }

    item { BombSectionTitle("Per-app traffic") }
    item {
        BombCard {
            PreviewData.traffic.forEachIndexed { index, app ->
                if (index > 0) BombRowDivider()
                BombAppRow(
                    name = app.name,
                    packageName = app.packageName,
                    tint = PreviewData.tintFor(app.packageName),
                    badges = buildList {
                        if (!app.mobileAllowed) add("No mobile" to BombTheme.colors.warn)
                    },
                    trailing = "↓ ${app.downMb}",
                    onClick = {},
                )
            }
        }
    }

    item { BombSectionTitle("Policy") }
    item {
        BombCard {
            BombPreference(title = "Private DNS", value = "Automatic", onClick = {})
            BombRowDivider()
            BombPreference(title = "VPN", value = "None active", onClick = {})
            BombRowDivider()
            BombPreference(title = "Hotspot clients", value = "0", onClick = {})
        }
    }
}

@Composable
private fun RateCard(system: SystemView) {
    BombCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BombStatCard(
                label = "Download",
                value = system.netDown ?: "—",
                unit = "",
                color = BombTheme.colors.network,
                icon = BombIcons.Network,
                modifier = Modifier.weight(1f),
            )
            BombStatCard(
                label = "Upload",
                value = system.netUp ?: "—",
                unit = "",
                color = BombTheme.colors.gpu,
                icon = BombIcons.Network,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
