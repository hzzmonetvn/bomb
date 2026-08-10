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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hzzmonet.zkbomb.data.BombServiceState
import com.hzzmonet.zkbomb.data.ProcessListState
import com.hzzmonet.zkbomb.data.ProcessRow
import com.hzzmonet.zkbomb.data.SelectedProcessMemoryState
import com.hzzmonet.zkbomb.data.SystemTelemetryState
import com.hzzmonet.zkbomb.data.formatBytesGb
import com.hzzmonet.zkbomb.data.rememberSelectedProcessMemory
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
    service: BombServiceState,
    telemetry: SystemTelemetryState,
    processList: ProcessListState,
) {
    // CPU and RAM come from the same privileged SystemTelemetrySnapshot that
    // Monitor uses — one pipeline, one CPU% (computed from /proc/stat deltas by
    // the service), instead of the old unprivileged SystemView headline that read
    // "—" on a locked-down build while the process rows showed real per-process CPU.
    item { TelemetrySummaryCard(telemetry) }

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

    processSection(state, processList)

    if (state.selectedProcessPid != null) {
        item {
            ProcessDetailBottomSheet(
                state = state,
                navigator = navigator,
                service = service,
                processList = processList,
            )
        }
    }
}

/**
 * The real process list, from the privileged snapshot. Every non-Ready outcome
 * is shown as itself — loading, unsupported, empty or error — rather than being
 * papered over with a sample.
 */
private fun LazyListScope.processSection(state: PreviewUiState, processList: ProcessListState) {
    item { BombSectionTitle("Processes") }

    when (processList) {
        ProcessListState.Loading -> item { LoadingCard() }

        is ProcessListState.Unsupported -> item {
            BombUnsupportedState(title = "System-wide process list", reason = processList.reason)
        }

        is ProcessListState.Error -> item {
            BombCard {
                Text(
                    text = processList.message,
                    modifier = Modifier.padding(16.dp),
                    fontSize = 13.sp,
                    color = BombTheme.colors.critical,
                )
            }
        }

        ProcessListState.Empty -> item {
            BombEmptyState("The service returned no visible processes")
        }

        is ProcessListState.Ready -> readyProcesses(state, processList)
    }
}

private fun LazyListScope.readyProcesses(state: PreviewUiState, ready: ProcessListState.Ready) {
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
    val visible = ready.processes
        .filter { row ->
            val matchFilter = when (state.processFilter) {
                1 -> !row.isSystem
                2 -> row.isSystem
                else -> true
            }
            val matchQuery = query.isEmpty() ||
                row.processName.lowercase().contains(query) ||
                (row.packageName?.lowercase()?.contains(query) == true) ||
                row.pid.toString().contains(query)
            matchFilter && matchQuery
        }
        .sortedWith(
            when (state.processSort) {
                // Unknown values sort last: -1 keeps a withheld metric off the top.
                1 -> compareByDescending<ProcessRow> { it.memoryMb ?: -1 }
                2 -> compareBy<ProcessRow> { it.pid }
                3 -> compareBy<ProcessRow> { it.processName.lowercase() }
                else -> compareByDescending<ProcessRow> { it.cpuPercent ?: -1f }
            },
        )

    item {
        BombSectionTitle(
            if (ready.truncated) "${visible.size} shown · list capped by the service" else "${visible.size} processes",
        )
    }
    item {
        if (visible.isEmpty()) {
            BombEmptyState("No matching processes found")
        } else {
            BombCard {
                visible.forEachIndexed { index, row ->
                    if (index > 0) BombRowDivider()
                    BombProcessRow(
                        name = row.processName,
                        processName = row.packageName ?: "uid ${row.uid}",
                        pid = row.pid,
                        cpuPercent = row.cpuPercent,
                        memoryMb = row.memoryMb,
                        tint = PreviewData.tintFor(row.processName),
                        state = if (row.foreground) "FG" else null,
                        onClick = {
                            // Capture the exact process generation, not just the PID,
                            // so the memory view can reject a reused PID.
                            state.selectedProcessPid = row.pid
                            state.selectedProcessStartTicks = row.startTimeTicks
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingCard() {
    BombCard {
        Text(
            text = "Reading processes from the privileged service…",
            modifier = Modifier.padding(16.dp),
            fontSize = 13.sp,
            color = BombTheme.miuix.onSurfaceVariantSummary,
        )
    }
}

/**
 * The system CPU/RAM headline, from the privileged telemetry snapshot — the same
 * source Monitor uses. CPU% is null on the first sample (a rate needs two), shown
 * as "collecting…" rather than a fabricated 0. Every non-Ready outcome is shown
 * as itself; there is no SystemView/sample fallback.
 */
@Composable
private fun TelemetrySummaryCard(state: SystemTelemetryState) {
    when (state) {
        SystemTelemetryState.Loading ->
            SummaryNote("Reading system telemetry from the privileged service…")

        is SystemTelemetryState.Unsupported ->
            BombUnsupportedState(title = "System CPU / memory", reason = state.reason)

        is SystemTelemetryState.Error ->
            SummaryNote(state.message, error = true)

        is SystemTelemetryState.Ready -> {
            val telemetry = state.telemetry
            val usedGb = formatBytesGb(telemetry.totalMemoryBytes - telemetry.availableMemoryBytes)
            val totalGb = formatBytesGb(telemetry.totalMemoryBytes)
            val memoryText = when {
                usedGb != null && totalGb != null -> "$usedGb / $totalGb GB"
                usedGb != null -> "$usedGb GB"
                else -> "—"
            }
            BombCard {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "CPU",
                            fontSize = 13.sp,
                            color = BombTheme.miuix.onSurfaceVariantSummary,
                        )
                        Row(
                            modifier = Modifier.padding(top = 2.dp),
                            verticalAlignment = Alignment.Bottom,
                        ) {
                            Text(
                                text = state.cpuPercent?.toString() ?: "—",
                                fontSize = 30.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (state.cpuPercent != null) {
                                    BombTheme.miuix.onSurface
                                } else {
                                    BombTheme.miuix.onSurfaceVariantSummary
                                },
                            )
                            Text(
                                text = if (state.cpuPercent != null) "% all cores" else "collecting…",
                                modifier = Modifier.padding(start = 3.dp, bottom = 4.dp),
                                fontSize = 12.sp,
                                color = BombTheme.miuix.onSurfaceVariantSummary,
                            )
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "Memory",
                            fontSize = 13.sp,
                            color = BombTheme.miuix.onSurfaceVariantSummary,
                        )
                        Text(
                            text = memoryText,
                            modifier = Modifier.padding(top = 2.dp),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = BombTheme.colors.ram,
                        )
                        if (telemetry.lowMemory) {
                            Text(
                                text = "Low memory",
                                modifier = Modifier.padding(top = 2.dp),
                                fontSize = 11.sp,
                                color = BombTheme.colors.warn,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryNote(text: String, error: Boolean = false) {
    BombCard {
        Text(
            text = text,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            fontSize = 13.sp,
            color = if (error) BombTheme.colors.critical else BombTheme.miuix.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun ProcessDetailBottomSheet(
    state: PreviewUiState,
    navigator: BombNavigator,
    service: BombServiceState,
    processList: ProcessListState,
) {
    val pid = state.selectedProcessPid ?: return
    val dismiss = {
        state.selectedProcessPid = null
        state.selectedProcessStartTicks = null
    }
    val row = (processList as? ProcessListState.Ready)?.processes?.firstOrNull { it.pid == pid }
    if (row == null) {
        // The process ended (or the list refreshed) while the sheet was open. No
        // memory call is made here — the PID-gone case is handled by this lookup.
        BombBottomSheet(onDismissRequest = dismiss, title = "Process $pid") {
            BombEmptyState("This process is no longer in the latest sample")
        }
        return
    }

    // On-demand, single call for this exact PID; cancelled when the sheet closes
    // or the PID changes. The identity guard uses the generation captured at tap.
    val memory = rememberSelectedProcessMemory(
        service = service,
        pid = pid,
        expectedStartTimeTicks = state.selectedProcessStartTicks,
        currentStartTimeTicks = row.startTimeTicks,
    )

    BombBottomSheet(
        onDismissRequest = dismiss,
        title = row.processName,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                BombAppAvatar(
                    label = row.processName,
                    tint = PreviewData.tintFor(row.processName),
                    size = 42.dp,
                )
                Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(
                        text = row.packageName ?: "uid ${row.uid}",
                        fontSize = 13.sp,
                        color = BombTheme.miuix.onSurfaceVariantSummary,
                    )
                    Row(
                        modifier = Modifier.padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        BombBadge(text = "PID ${row.pid}", color = BombTheme.miuix.primary)
                        if (row.foreground) BombBadge(text = "Foreground", color = BombTheme.colors.ok)
                        if (row.isSystem) BombBadge(text = "System", color = BombTheme.colors.warn)
                        row.category?.let { BombBadge(text = it, color = BombTheme.colors.accent) }
                    }
                }
            }

            BombCard {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    DetailLine("PID", row.pid.toString())
                    DetailLine("UID / user", "${row.uid} / ${row.userId}")
                    DetailLine("CPU (share of total)", row.cpuPercent?.let { "${formatOneDecimal(it)}%" } ?: "—")
                    MemoryDetailLines(memory, row)
                    DetailLine("Threads", row.threadCount?.toString() ?: "—")
                    DetailLine("Importance", row.importance.toString())
                }
            }

            // Force stop and component control are the Package Inspector backend
            // (COMPONENT_CONTROL / PACKAGE_FORCE_STOP). Until that is wired, the
            // action is disabled rather than faked; freeze already has a home.
            row.packageName?.let { pkg ->
                Button(
                    onClick = {
                        dismiss()
                        navigator.push(BombRoute.AppControl(pkg))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(text = "Open App Control")
                }
            }
        }
    }
}

private fun Long?.toMb(): String = this?.let { "${it / (1024 * 1024)} MB" } ?: "—"

/**
 * The selected process's memory rows. On v7 these are the on-demand sample; on an
 * older service (Unsupported) they fall back to the list snapshot values already
 * in hand — no extra call. Every non-Ready state says why, and a null metric
 * renders "—", never a fabricated 0.
 */
@Composable
private fun MemoryDetailLines(state: SelectedProcessMemoryState, fallback: ProcessRow) {
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
