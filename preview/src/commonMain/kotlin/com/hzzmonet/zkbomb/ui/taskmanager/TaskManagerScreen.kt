package com.hzzmonet.zkbomb.ui.taskmanager

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hzzmonet.zkbomb.data.SystemView
import com.hzzmonet.zkbomb.preview.PreviewData
import com.hzzmonet.zkbomb.preview.PreviewUiState
import com.hzzmonet.zkbomb.ui.design.BombTheme
import com.hzzmonet.zkbomb.ui.design.component.BombCard
import com.hzzmonet.zkbomb.ui.design.component.BombProcessRow
import com.hzzmonet.zkbomb.ui.design.component.BombRowDivider
import com.hzzmonet.zkbomb.ui.design.component.BombSectionTitle
import com.hzzmonet.zkbomb.ui.design.component.BombSegmentedButton
import com.hzzmonet.zkbomb.ui.design.component.BombUnsupportedState
import com.hzzmonet.zkbomb.ui.navigation.BombNavigator
import com.hzzmonet.zkbomb.ui.navigation.BombRoute
import com.hzzmonet.zkbomb.ui.stats.CpuDetailCard
import com.hzzmonet.zkbomb.ui.stats.MemoryCard
import top.yukonga.miuix.kmp.basic.Text

private val sorts = listOf("CPU", "Memory", "PID", "Name")
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

    val visible = PreviewData.processes
        .filter {
            when (state.processFilter) {
                1 -> !it.system
                2 -> it.system
                else -> true
            }
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
                    onClick = { navigator.push(BombRoute.ProcessDetail(process.pid)) },
                )
            }
        }
    }
}
