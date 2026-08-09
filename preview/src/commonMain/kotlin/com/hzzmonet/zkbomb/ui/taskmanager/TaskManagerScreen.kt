package com.hzzmonet.zkbomb.ui.taskmanager

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hzzmonet.zkbomb.data.SystemView
import com.hzzmonet.zkbomb.preview.PreviewData
import com.hzzmonet.zkbomb.preview.PreviewUiState
import com.hzzmonet.zkbomb.ui.design.BombIcon
import com.hzzmonet.zkbomb.ui.design.BombIcons
import com.hzzmonet.zkbomb.ui.design.BombTheme
import com.hzzmonet.zkbomb.ui.design.component.BombAppAvatar
import com.hzzmonet.zkbomb.ui.design.component.BombBadge
import com.hzzmonet.zkbomb.ui.design.component.BombBottomSheet
import com.hzzmonet.zkbomb.ui.design.component.BombCard
import com.hzzmonet.zkbomb.ui.design.component.BombEmptyState
import com.hzzmonet.zkbomb.ui.design.component.BombProcessRow
import com.hzzmonet.zkbomb.ui.design.component.BombRowDivider
import com.hzzmonet.zkbomb.ui.design.component.BombSectionTitle
import com.hzzmonet.zkbomb.ui.design.component.BombSegmentedButton
import com.hzzmonet.zkbomb.ui.design.component.BombUnsupportedState
import com.hzzmonet.zkbomb.ui.design.component.formatOneDecimal
import com.hzzmonet.zkbomb.ui.navigation.BombNavigator
import com.hzzmonet.zkbomb.ui.navigation.BombRoute
import com.hzzmonet.zkbomb.ui.stats.CpuDetailCard
import com.hzzmonet.zkbomb.ui.stats.MemoryCard
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField

private val sorts = listOf("CPU", "RAM", "PID", "Name")
private val filters = listOf("All", "Apps", "System")

/** Refresh levels from the spec. The interval is a user choice, never a constant. */
private val refreshLevels = listOf(
    "0.5s" to "Realtime",
    "1s" to "Fast",
    "2s" to "Balanced",
    "5s" to "Battery",
)

fun LazyListScope.taskManagerContent(
    state: PreviewUiState,
    navigator: BombNavigator,
    system: SystemView,
) {
    item { CpuDetailCard(system, showTrend = true) }
    item { MemoryCard(system) }

    item { BombSectionTitle("Refresh") }
    item {
        BombCard {
            BombSegmentedButton(
                options = refreshLevels.map { it.first },
                selectedIndex = state.refreshLevel,
                onSelected = { state.refreshLevel = it },
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 18.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Sampling stops when this screen is not visible",
                    modifier = Modifier.weight(1f),
                    fontSize = 12.sp,
                    color = BombTheme.miuix.onSurfaceVariantSummary,
                )
                Text(
                    text = refreshLevels[state.refreshLevel].second,
                    fontSize = 12.sp,
                    color = BombTheme.miuix.onSurfaceVariantActions,
                )
            }
        }
    }

    if (system.isLive) {
        liveProcesses(system)
    } else {
        sampleProcesses(state, navigator)
    }

    if (state.selectedProcessPid != null) {
        item { ProcessDetailBottomSheet(state = state, navigator = navigator) }
    }
}

/**
 * On a device an unprivileged app sees exactly one process: its own. Android
 * hides the rest (`hidepid`, and `getRunningAppProcesses` returns only the
 * caller since Android 5). So Bomb shows what it genuinely has and names what
 * it would take to show the rest — it does not fill the screen with samples.
 */
private fun LazyListScope.liveProcesses(system: SystemView) {
    item { BombSectionTitle("Bomb's own process") }
    item {
        BombCard {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                OwnProcessLine("PSS", system.ownPssMb?.let { "$it MB" })
                OwnProcessLine("Threads", system.ownThreads?.toString())
                OwnProcessLine("Frame rate", system.fps?.let { "$it FPS" })
                OwnProcessLine("Uptime (device)", system.uptime)
            }
        }
    }

    item {
        BombUnsupportedState(
            title = "System-wide process list",
            reason = "Android hides other processes from unprivileged apps: /proc is " +
                "mounted with hidepid and getRunningAppProcesses returns only the " +
                "caller. A real task manager needs the priv-app or root backend — see " +
                "docs/ROM_INTEGRATION.md.",
        )
    }
}

@Composable
private fun OwnProcessLine(label: String, value: String?) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            fontSize = 13.sp,
            color = BombTheme.miuix.onSurfaceVariantSummary,
        )
        Text(
            text = value ?: "—",
            fontSize = 13.sp,
            color = BombTheme.miuix.onSurface,
        )
    }
}

private fun LazyListScope.sampleProcesses(state: PreviewUiState, navigator: BombNavigator) {
    item { BombSectionTitle("Processes") }
    item {
        TextField(
            state = state.processQuery,
            label = "Search processes (Name, Package, PID)",
            useLabelAsPlaceholder = true,
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = {
                BombIcon(
                    icon = BombIcons.Search,
                    tint = BombTheme.miuix.onSurfaceVariantSummary,
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .size(18.dp),
                )
            },
        )
    }
    item {
        BombSegmentedButton(
            options = sorts,
            selectedIndex = state.processSort,
            onSelected = { state.processSort = it },
        )
    }
    item {
        BombSegmentedButton(
            options = filters,
            selectedIndex = state.processFilter,
            onSelected = { state.processFilter = it },
        )
    }

    val query = state.processQuery.text.toString().trim().lowercase()
    val visible = PreviewData.processes
        .filter { process ->
            val matchFilter = when (state.processFilter) {
                1 -> !process.system
                2 -> process.system
                else -> true
            }
            val matchQuery = query.isEmpty() ||
                process.name.lowercase().contains(query) ||
                process.processName.lowercase().contains(query) ||
                process.pid.toString().contains(query)
            matchFilter && matchQuery
        }
        .sortedWith(
            when (state.processSort) {
                1 -> compareByDescending<PreviewData.DemoProcess> { it.ramMb }
                2 -> compareBy<PreviewData.DemoProcess> { it.pid }
                3 -> compareBy<PreviewData.DemoProcess> { it.name }
                else -> compareByDescending<PreviewData.DemoProcess> { it.cpu }
            }
        )

    item { BombSectionTitle("${visible.size} processes") }
    item {
        if (visible.isEmpty()) {
            BombEmptyState("No matching processes found")
        } else {
            BombCard {
                visible.forEachIndexed { index, process ->
                    if (index > 0) BombRowDivider()
                    BombProcessRow(
                        name = process.name,
                        processName = process.processName,
                        pid = process.pid,
                        cpuPercent = process.cpu,
                        memoryMb = process.ramMb,
                        tint = PreviewData.tintFor(process.processName),
                        state = process.state,
                        onClick = { state.selectedProcessPid = process.pid },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProcessDetailBottomSheet(
    state: PreviewUiState,
    navigator: BombNavigator,
) {
    val pid = state.selectedProcessPid ?: return
    val process = PreviewData.processes.firstOrNull { it.pid == pid } ?: return
    val detail = PreviewData.detailFor(process)
    val app = PreviewData.apps.firstOrNull { it.packageName == process.processName }
    val isFrozen = state.freezeList.containsKey(process.processName)

    BombBottomSheet(
        onDismissRequest = { state.selectedProcessPid = null },
        title = process.name,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BombAppAvatar(
                    label = process.name,
                    tint = PreviewData.tintFor(process.processName),
                    size = 42.dp,
                )
                Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(
                        text = process.processName,
                        fontSize = 13.sp,
                        color = BombTheme.miuix.onSurfaceVariantSummary,
                    )
                    Row(
                        modifier = Modifier.padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        BombBadge(
                            text = detail.schedState,
                            color = if (detail.schedState == "Running") {
                                BombTheme.colors.ok
                            } else {
                                BombTheme.colors.frozen
                            },
                        )
                        BombBadge(
                            text = "PID ${process.pid}",
                            color = BombTheme.miuix.primary,
                        )
                        if (process.system) {
                            BombBadge(text = "System", color = BombTheme.colors.warn)
                        }
                    }
                }
            }

            BombCard {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    DetailLine("PID", process.pid.toString())
                    DetailLine("UID", detail.uid.toString())
                    DetailLine("Process State", detail.schedState)
                    DetailLine("CPU Utilisation", "${formatOneDecimal(process.cpu)}%")
                    DetailLine("CPU Breakdown", "User: ${formatOneDecimal(detail.userPercent)}% · Sys: ${formatOneDecimal(detail.sysPercent)}%")
                    DetailLine("Memory (RSS / PSS)", "${process.ramMb} MB / ${detail.pssMb} MB")
                    DetailLine("Swap / Threads", "${detail.swapMb} MB / ${detail.threads} threads")
                    DetailLine("oom_score_adj", detail.oomAdj.toString())
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        state.selectedProcessPid = null
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(text = "Force Stop")
                }

                Button(
                    onClick = {
                        if (isFrozen) {
                            state.freezeList.remove(process.processName)
                        } else {
                            state.freezeList[process.processName] = "Soft Freeze"
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(text = if (isFrozen) "Unfreeze" else "Freeze")
                }
            }

            if (app != null) {
                Button(
                    onClick = {
                        val targetPkg = process.processName
                        state.selectedProcessPid = null
                        navigator.push(BombRoute.AppControl(targetPkg))
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = "Open App Control")
                }
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
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
