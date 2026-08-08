package com.hzzmonet.zkbomb.ui.bridge

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hzzmonet.zkbomb.preview.PreviewData
import com.hzzmonet.zkbomb.preview.PreviewUiState
import com.hzzmonet.zkbomb.ui.design.BombTheme
import com.hzzmonet.zkbomb.ui.design.component.BombCard
import com.hzzmonet.zkbomb.ui.design.component.BombMonitorOverlay
import com.hzzmonet.zkbomb.ui.design.component.BombPreference
import com.hzzmonet.zkbomb.ui.design.component.BombRowDivider
import com.hzzmonet.zkbomb.ui.design.component.BombSectionTitle
import com.hzzmonet.zkbomb.ui.design.component.BombSwitchPreference
import com.hzzmonet.zkbomb.ui.design.component.OverlayEntry
import top.yukonga.miuix.kmp.basic.Text

fun LazyListScope.bridgeContent(state: PreviewUiState) {
    item {
        BombCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Sources are normalised into one event model, then handed to " +
                        "whichever renderer the device actually supports.",
                    fontSize = 13.sp,
                    color = BombTheme.miuix.onSurfaceVariantSummary,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    BombMonitorOverlay(
                        compact = true,
                        entries = listOf(
                            OverlayEntry("", "Gaming", BombTheme.colors.accent),
                            OverlayEntry("", "${PreviewData.FPS} FPS", BombTheme.colors.fps),
                            OverlayEntry("", "${PreviewData.SOC_TEMP}°C", BombTheme.colors.thermal),
                            OverlayEntry("", "${PreviewData.POWER_WATTS} W", BombTheme.colors.power),
                        ),
                    )
                }
            }
        }
    }

    item { BombSectionTitle("Renderers") }
    item {
        BombCard {
            BombPreference(title = "HyperOS live update", value = "Supported", onClick = {})
            BombRowDivider()
            BombPreference(title = "Live Update", value = "Supported", onClick = {})
            BombRowDivider()
            BombPreference(
                title = "Notification",
                summary = "Guaranteed fallback when a renderer probes unsupported",
                value = "Always",
                onClick = {},
            )
        }
    }

    item { BombSectionTitle("Events") }
    item {
        BombCard {
            PreviewData.bridgeEvents.forEachIndexed { index, event ->
                if (index > 0) BombRowDivider()
                BombSwitchPreference(
                    title = event.title,
                    summary = "${event.subtitle} · ${event.type} → ${event.renderer}",
                    checked = state.bridgeEnabled[event.title] == true,
                    onCheckedChange = { state.bridgeEnabled[event.title] = it },
                )
            }
        }
    }
}
