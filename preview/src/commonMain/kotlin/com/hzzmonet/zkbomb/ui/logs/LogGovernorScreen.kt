package com.hzzmonet.zkbomb.ui.logs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hzzmonet.zkbomb.data.BombCapabilityKeys
import com.hzzmonet.zkbomb.data.BombServiceState
import com.hzzmonet.zkbomb.preview.PreviewUiState
import com.hzzmonet.zkbomb.ui.design.BombTheme
import com.hzzmonet.zkbomb.ui.design.component.BombBadge
import com.hzzmonet.zkbomb.ui.design.component.BombCard
import com.hzzmonet.zkbomb.ui.design.component.BombSectionTitle
import com.hzzmonet.zkbomb.ui.design.component.BombSegmentedButton
import top.yukonga.miuix.kmp.basic.Text

private val profiles = listOf("Default", "Reduced", "Off")
private val profileNames = listOf("DEFAULT", "REDUCED", "OFF")

fun LazyListScope.logGovernorContent(state: PreviewUiState, service: BombServiceState) {
    item {
        LaunchedEffect(service.logLevel) {
            val index = profileNames.indexOf(service.logLevel)
            if (index >= 0) state.logProfile = index
        }
        BombSegmentedButton(
            options = profiles,
            selectedIndex = state.logProfile,
            onSelected = { selected ->
                val capability = if (selected == 2) {
                    BombCapabilityKeys.LOG_DISABLE
                } else {
                    BombCapabilityKeys.LOG_REDUCE
                }
                when {
                    service.controller == null ->
                        state.logOperationMessage = "BombCoreService is not connected"
                    selected != 0 && !service.isSupported(capability) ->
                        state.logOperationMessage = "$capability is not supported on this install"
                    else -> service.controller.setLogLevel(profileNames[selected]) { result ->
                        state.logOperationMessage = result.message ?: result.status
                        if (result.isSuccess) state.logProfile = selected
                    }
                }
            },
        )
    }

    item {
        BombCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "ROM backend",
                        modifier = Modifier.weight(1f),
                        fontSize = 14.sp,
                        color = BombTheme.miuix.onSurface,
                    )
                    Text(
                        text = service.logLevel ?: "Unavailable",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = BombTheme.colors.warn,
                    )
                }
                Text(
                    text = "Backed by the ROM's property-driven logging profile — the " +
                        "same mechanism init.rc already uses, exposed as typed profiles " +
                        "instead of loose properties.",
                    modifier = Modifier.padding(top = 8.dp),
                    fontSize = 12.sp,
                    color = BombTheme.miuix.onSurfaceVariantSummary,
                )
                state.logOperationMessage?.let { message ->
                    Text(
                        text = message,
                        modifier = Modifier.padding(top = 8.dp),
                        fontSize = 12.sp,
                        color = BombTheme.miuix.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }

    item { BombSectionTitle("Safety") }
    item {
        BombCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                    BombBadge("Reduced keeps logd", BombTheme.colors.ok)
                    BombBadge("Off stops logd", BombTheme.colors.critical)
                }
                Text(
                    text = "Reduced preserves crash log and SELinux denials. Off stops " +
                        "logd completely, makes bugreports incomplete, and leaving Off " +
                        "requires a reboot. Tombstones and ANR traces remain separate.",
                    modifier = Modifier.padding(top = 10.dp),
                    fontSize = 12.sp,
                    color = BombTheme.miuix.onSurfaceVariantSummary,
                )
            }
        }
    }

}
