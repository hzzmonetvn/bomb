package com.hzzmonet.zkbomb.ui.performance

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hzzmonet.zkbomb.data.BombCapabilityKeys
import com.hzzmonet.zkbomb.data.BombServiceState
import com.hzzmonet.zkbomb.preview.PreviewUiState
import com.hzzmonet.zkbomb.ui.design.BombTheme
import com.hzzmonet.zkbomb.ui.design.component.BombCard
import com.hzzmonet.zkbomb.ui.design.component.BombPreference
import com.hzzmonet.zkbomb.ui.design.component.BombRowDivider
import com.hzzmonet.zkbomb.ui.design.component.BombSectionTitle
import com.hzzmonet.zkbomb.ui.design.component.BombSegmentedButton
import com.hzzmonet.zkbomb.ui.design.component.BombUnsupportedState
import top.yukonga.miuix.kmp.basic.Text

private val profiles = listOf("Eco", "Balanced", "Performance", "Gaming")

fun LazyListScope.performanceContent(state: PreviewUiState, service: BombServiceState) {
    val controller = service.controller
    val canTuneMemory = service.isSupported(BombCapabilityKeys.ZRAM_CONTROL)

    item {
        LaunchedEffect(controller) {
            controller?.getMemoryStatus { status ->
                if (status?.globalSwappiness != null) {
                    state.memorySwappiness = status.globalSwappiness
                }
                state.memoryOperationMessage = when {
                    status == null -> "Memory telemetry is unavailable"
                    !status.zramPresent -> "This device has no zram device"
                    else -> null
                }
            }
        }
    }

    item {
        BombSegmentedButton(
            options = profiles,
            selectedIndex = state.performanceProfile,
            onSelected = { selected ->
                val tuning = tuningFor(selected)
                when {
                    controller == null -> state.memoryOperationMessage = "BombCoreService is not connected"
                    !canTuneMemory -> state.memoryOperationMessage = "ZRAM_CONTROL is not supported on this install"
                    else -> {
                        state.memoryOperationMessage = "Applying…"
                        controller.setMemoryConfig(tuning.swappiness, tuning.pageCluster) { result ->
                            state.memoryOperationMessage = result.message ?: result.status
                            if (result.isSuccess) {
                                state.performanceProfile = selected
                                state.memorySwappiness = tuning.swappiness
                                state.memoryPageCluster = tuning.pageCluster
                            }
                        }
                    }
                }
            },
        )
    }

    item { BombSectionTitle("Live memory tuning") }
    item {
        BombCard {
            BombPreference(
                title = "vm.swappiness",
                summary = "Applied immediately through the ROM init backend",
                value = state.memorySwappiness.toString(),
                enabled = false,
                onClick = { },
            )
            BombRowDivider()
            BombPreference(
                title = "vm.page-cluster",
                summary = "Applied immediately; 0 avoids swap read-ahead",
                value = state.memoryPageCluster.toString(),
                enabled = false,
                onClick = { },
            )
            state.memoryOperationMessage?.let { message ->
                BombRowDivider()
                Text(
                    text = message,
                    modifier = Modifier.padding(16.dp),
                    fontSize = 12.sp,
                    color = BombTheme.miuix.onSurfaceVariantSummary,
                )
            }
        }
    }

    item {
        BombUnsupportedState(
            title = "CPU, GPU, refresh-rate and thermal profiles",
            reason = "Those controls do not have typed, probed backends yet. The profile " +
                "buttons above currently change only swappiness and page-cluster.",
        )
    }
}

private data class MemoryTuning(val swappiness: Int, val pageCluster: Int)

private fun tuningFor(index: Int) = when (index) {
    0 -> MemoryTuning(swappiness = 80, pageCluster = 0)
    2 -> MemoryTuning(swappiness = 160, pageCluster = 0)
    3 -> MemoryTuning(swappiness = 200, pageCluster = 0)
    else -> MemoryTuning(swappiness = 100, pageCluster = 0)
}
