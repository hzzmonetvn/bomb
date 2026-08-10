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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hzzmonet.zkbomb.data.BombServiceState
import com.hzzmonet.zkbomb.data.ProcessListState
import com.hzzmonet.zkbomb.data.ProcessRow
import com.hzzmonet.zkbomb.data.SelectedProcessMemoryState
import com.hzzmonet.zkbomb.data.rememberSelectedProcessMemory
import com.hzzmonet.zkbomb.preview.PreviewData
import com.hzzmonet.zkbomb.ui.design.component.BombAppAvatar
import com.hzzmonet.zkbomb.ui.design.BombTheme
import com.hzzmonet.zkbomb.ui.design.component.BombBadge
import com.hzzmonet.zkbomb.ui.design.component.BombCard
import com.hzzmonet.zkbomb.ui.design.component.BombEmptyState
import com.hzzmonet.zkbomb.ui.design.component.BombPreference
import com.hzzmonet.zkbomb.ui.design.component.BombSectionTitle
import com.hzzmonet.zkbomb.ui.design.component.BombUnsupportedState
import com.hzzmonet.zkbomb.ui.design.component.formatOneDecimal
import com.hzzmonet.zkbomb.ui.navigation.BombNavigator
import com.hzzmonet.zkbomb.ui.navigation.BombRoute
import top.yukonga.miuix.kmp.basic.Text

/**
 * Per-process detail for a pid, resolved from the live privileged snapshot.
 *
 * There is no sample path: if the snapshot is unavailable the screen says so, and
 * if the pid is not in the current sample it reports the process as gone. Every
 * value maps to a real snapshot field; anything the framework withheld shows "—".
 */
fun LazyListScope.processDetailContent(
    pid: Int,
    navigator: BombNavigator,
    service: BombServiceState,
    processList: ProcessListState,
) {
    when (processList) {
        ProcessListState.Loading ->
            item { InfoCard("Reading processes from the privileged service…") }

        is ProcessListState.Unsupported ->
            item { BombUnsupportedState(title = "Process detail", reason = processList.reason) }

        is ProcessListState.Error ->
            item { InfoCard(processList.message, error = true) }

        ProcessListState.Empty ->
            item { BombEmptyState("Process $pid is no longer running") }

        is ProcessListState.Ready -> {
            val row = processList.processes.firstOrNull { it.pid == pid }
            if (row == null) {
                item { BombEmptyState("Process $pid is no longer running") }
            } else {
                processDetail(row, navigator, service)
            }
        }
    }
}

private fun LazyListScope.processDetail(
    row: ProcessRow,
    navigator: BombNavigator,
    service: BombServiceState,
) {
    item {
        BombCard {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                BombAppAvatar(
                    label = row.processName,
                    tint = PreviewData.tintFor(row.processName),
                    size = 44.dp,
                )
                Column(modifier = Modifier.padding(start = 13.dp)) {
                    Text(
                        text = row.processName,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = BombTheme.miuix.onSurface,
                    )
                    Text(
                        text = row.packageName ?: "uid ${row.uid}",
                        modifier = Modifier.padding(top = 2.dp),
                        fontSize = 12.sp,
                        color = BombTheme.miuix.onSurfaceVariantSummary,
                    )
                    Row(
                        modifier = Modifier.padding(top = 7.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (row.foreground) BombBadge("Foreground", BombTheme.colors.ok)
                        if (row.isSystem) BombBadge("System", BombTheme.miuix.primary)
                        row.category?.let { BombBadge(it, BombTheme.colors.accent) }
                    }
                }
            }
        }
    }

    item { BombSectionTitle("CPU") }
    item {
        BombCard {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                DetailLine("Share of total CPU", row.cpuPercent?.let { "${formatOneDecimal(it)}%" } ?: "—")
                DetailLine("CPU time (ticks)", row.cpuTimeTicks?.toString() ?: "—")
            }
        }
    }

    item { BombSectionTitle("Memory") }
    item {
        // On-demand v7 sample for this exact PID, fetched only while this detail is
        // composed and cancelled when it leaves. No captured tap-generation here
        // (this is the deep-link route), so the reuse guard stays off; the PID-gone
        // case is already handled by the row lookup above.
        val memory = rememberSelectedProcessMemory(
            service = service,
            pid = row.pid,
            expectedStartTimeTicks = null,
            currentStartTimeTicks = row.startTimeTicks,
        )
        BombCard {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                MemoryLines(memory, row)
            }
        }
    }

    item { BombSectionTitle("Process") }
    item {
        BombCard {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                DetailLine("PID", row.pid.toString())
                DetailLine("UID / user", "${row.uid} / ${row.userId}")
                DetailLine("Threads", row.threadCount?.toString() ?: "—")
                DetailLine("Importance", row.importance.toString())
                DetailLine("Start time (ticks)", row.startTimeTicks?.toString() ?: "—")
            }
        }
    }

    row.packageName?.let { pkg ->
        item { BombSectionTitle("Actions") }
        item {
            BombCard {
                BombPreference(
                    title = "Open App Control",
                    summary = "Freeze, disable and inspect this package",
                    onClick = { navigator.push(BombRoute.AppControl(pkg)) },
                )
            }
        }
    }
}

@Composable
private fun InfoCard(text: String, error: Boolean = false) {
    BombCard {
        Text(
            text = text,
            modifier = Modifier.padding(16.dp),
            fontSize = 13.sp,
            color = if (error) BombTheme.colors.critical else BombTheme.miuix.onSurfaceVariantSummary,
        )
    }
}

private fun Long?.toMb(): String = this?.let { "${it / (1024 * 1024)} MB" } ?: "—"

/**
 * Memory rows for the selected process: the v7 on-demand sample, or the list
 * snapshot values when the contract is older (Unsupported). Non-Ready states say
 * why; a null metric renders "—", never a fabricated 0.
 */
@Composable
private fun MemoryLines(state: SelectedProcessMemoryState, fallback: ProcessRow) {
    when (state) {
        SelectedProcessMemoryState.Loading ->
            DetailLine("Memory", "sampling…")

        SelectedProcessMemoryState.Unsupported -> {
            DetailLine("PSS", fallback.pssBytes.toMb())
            DetailLine("Private dirty", fallback.privateDirtyBytes.toMb())
            DetailLine("RSS", fallback.rssBytes.toMb())
        }

        SelectedProcessMemoryState.Disappeared ->
            DetailLine("Memory", "process ended or PID reused")

        is SelectedProcessMemoryState.Unavailable ->
            DetailLine("Memory", state.reason)

        is SelectedProcessMemoryState.Error ->
            DetailLine("Memory", state.message)

        is SelectedProcessMemoryState.Ready -> {
            DetailLine("PSS", state.memory.pssBytes.toMb())
            DetailLine("Private dirty", state.memory.privateDirtyBytes.toMb())
            DetailLine("RSS", state.memory.rssBytes.toMb())
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
