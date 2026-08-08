package com.hzzmonet.zkbomb.ui.performance

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hzzmonet.zkbomb.preview.PreviewData
import com.hzzmonet.zkbomb.preview.PreviewUiState
import com.hzzmonet.zkbomb.ui.design.BombTheme
import com.hzzmonet.zkbomb.ui.design.component.BombAppRow
import com.hzzmonet.zkbomb.ui.design.component.BombCard
import com.hzzmonet.zkbomb.ui.design.component.BombPreference
import com.hzzmonet.zkbomb.ui.design.component.BombRowDivider
import com.hzzmonet.zkbomb.ui.design.component.BombSectionTitle
import com.hzzmonet.zkbomb.ui.design.component.BombSegmentedButton
import com.hzzmonet.zkbomb.ui.design.component.BombUnsupportedState
import top.yukonga.miuix.kmp.basic.Text

private val profiles = listOf("Eco", "Balanced", "Performance", "Gaming")

fun LazyListScope.performanceContent(state: PreviewUiState) {
    item {
        BombSegmentedButton(
            options = profiles,
            selectedIndex = state.performanceProfile,
            onSelected = { state.performanceProfile = it },
        )
    }

    item { BombSectionTitle("Profile") }
    item {
        BombCard {
            BombPreference(title = "Refresh rate", value = refreshRateFor(state.performanceProfile), onClick = {})
            BombRowDivider()
            BombPreference(title = "CPU policy", value = cpuPolicyFor(state.performanceProfile), onClick = {})
            BombRowDivider()
            BombPreference(title = "Thermal strategy", value = thermalFor(state.performanceProfile), onClick = {})
            BombRowDivider()
            BombPreference(title = "Freeze policy", value = "Smart", onClick = {})
            BombRowDivider()
            BombPreference(title = "Stats overlay", value = if (state.performanceProfile == 3) "Gaming" else "Off", onClick = {})
        }
    }

    item {
        BombUnsupportedState(
            title = "GPU frequency control",
            reason = "No writable GPU governor node was detected on this device. " +
                "The profile still applies every field the backend does support; " +
                "unsupported fields are skipped rather than silently failing.",
        )
    }

    item { BombSectionTitle("Per-app") }
    item {
        BombCard {
            PreviewData.apps.filter { it.profile != "Default" && !it.system }
                .forEachIndexed { index, app ->
                    if (index > 0) BombRowDivider()
                    BombAppRow(
                        name = app.name,
                        packageName = app.packageName,
                        tint = PreviewData.tintFor(app.packageName),
                        trailing = app.profile,
                        onClick = {},
                    )
                }
        }
    }

    item { BombSectionTitle("Restore") }
    item {
        BombCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Row {
                    Text(
                        text = "On exit",
                        modifier = Modifier.weight(0.3f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = BombTheme.miuix.onSurface,
                    )
                    Text(
                        text = "Bomb restores only the values Bomb set. Settings changed " +
                            "by the ROM or the user while a profile was active are left alone.",
                        modifier = Modifier.weight(0.7f),
                        fontSize = 13.sp,
                        color = BombTheme.miuix.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
}

private fun refreshRateFor(index: Int) = when (index) {
    0 -> "60 Hz"
    3 -> "120 Hz"
    else -> "Adaptive"
}

private fun cpuPolicyFor(index: Int) = when (index) {
    0 -> "Efficiency"
    2 -> "Responsive"
    3 -> "Sustained peak"
    else -> "Default"
}

private fun thermalFor(index: Int) = when (index) {
    0 -> "Conservative"
    3 -> "Gaming"
    else -> "Default"
}
