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
import com.hzzmonet.zkbomb.preview.PreviewData
import com.hzzmonet.zkbomb.preview.PreviewUiState
import com.hzzmonet.zkbomb.ui.design.BombTheme
import com.hzzmonet.zkbomb.ui.design.component.BombAppAvatar
import com.hzzmonet.zkbomb.ui.design.component.BombBadge
import com.hzzmonet.zkbomb.ui.design.component.BombCard
import com.hzzmonet.zkbomb.ui.design.component.BombEmptyState
import com.hzzmonet.zkbomb.ui.design.component.BombMeter
import com.hzzmonet.zkbomb.ui.design.component.BombPreference
import com.hzzmonet.zkbomb.ui.design.component.BombRowDivider
import com.hzzmonet.zkbomb.ui.design.component.BombSectionTitle
import com.hzzmonet.zkbomb.ui.design.component.formatOneDecimal
import com.hzzmonet.zkbomb.ui.navigation.BombNavigator
import com.hzzmonet.zkbomb.ui.navigation.BombRoute
import top.yukonga.miuix.kmp.basic.Text

/**
 * Per-process detail. Sections follow the spec's Overview / Memory / Threads /
 * Power layout; every value maps to a specific procfs or framework source, and
 * anything the current execution mode cannot read says so instead of guessing.
 */
fun LazyListScope.processDetailContent(
    state: PreviewUiState,
    pid: Int,
    navigator: BombNavigator,
) {
    val process = PreviewData.processes.firstOrNull { it.pid == pid }
    if (process == null) {
        item { BombEmptyState("Process no longer running") }
        return
    }
    val detail = PreviewData.detailFor(process)
    val app = PreviewData.apps.firstOrNull { it.packageName == process.processName }

    item {
        BombCard {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BombAppAvatar(
                    label = process.name,
                    tint = PreviewData.tintFor(process.processName),
                    size = 44.dp,
                )
                Column(modifier = Modifier.padding(start = 13.dp)) {
                    Text(
                        text = process.name,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = BombTheme.miuix.onSurface,
                    )
                    Text(
                        text = process.processName,
                        modifier = Modifier.padding(top = 2.dp),
                        fontSize = 12.sp,
                        color = BombTheme.miuix.onSurfaceVariantSummary,
                    )
                    Row(
                        modifier = Modifier.padding(top = 7.dp),
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
                        if (process.system) BombBadge("System", BombTheme.miuix.primary)
                    }
                }
            }
        }
    }

    item { BombSectionTitle("CPU") }
    item {
        BombCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = "${formatOneDecimal(process.cpu)}%",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = BombTheme.colors.cpu,
                    )
                    Text(
                        text = "of total CPU",
                        modifier = Modifier.padding(start = 6.dp, bottom = 4.dp),
                        fontSize = 12.sp,
                        color = BombTheme.miuix.onSurfaceVariantSummary,
                    )
                }
                BombMeter(
                    progress = process.cpu / 20f,
                    color = BombTheme.colors.cpu,
                    height = 5.dp,
                    modifier = Modifier.padding(top = 10.dp),
                )
                Column(
                    modifier = Modifier.padding(top = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    DetailLine("User", "${formatOneDecimal(detail.userPercent)}%")
                    DetailLine("System", "${formatOneDecimal(detail.sysPercent)}%")
                    DetailLine("CPU time", detail.cpuTime)
                    DetailLine("Threads", detail.threads.toString())
                }
            }
        }
    }

    item { BombSectionTitle("Memory") }
    item {
        BombCard {
            Column(modifier = Modifier.padding(16.dp)) {
                DetailLine("RSS", "${process.ramMb} MB")
                Column(modifier = Modifier.padding(top = 7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    DetailLine("PSS", "${detail.pssMb} MB")
                    DetailLine("Swap", "${detail.swapMb} MB")
                }
            }
        }
    }

    item { BombSectionTitle("Process") }
    item {
        BombCard {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                DetailLine("PID", process.pid.toString())
                DetailLine("UID", detail.uid.toString())
                DetailLine("oom_score_adj", detail.oomAdj.toString())
                DetailLine("State", detail.schedState)
                DetailLine("Started", detail.startedAgo)
            }
        }
    }

    item { BombSectionTitle("Actions") }
    item {
        BombCard {
            BombPreference(
                title = "Force stop",
                enabled = !process.system,
                onClick = {},
            )
            BombRowDivider()
            BombPreference(
                title = "Freeze",
                summary = if (process.system) "Critical package — excluded" else null,
                enabled = !process.system,
                onClick = { navigator.push(BombRoute.Freeze) },
            )
            BombRowDivider()
            BombPreference(title = "Clear cache", onClick = {})
            if (app != null) {
                BombRowDivider()
                BombPreference(
                    title = "Open App Control",
                    onClick = { navigator.push(BombRoute.AppControl(app.packageName)) },
                )
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
