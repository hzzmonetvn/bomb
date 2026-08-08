package com.hzzmonet.zkbomb.ui.automation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hzzmonet.zkbomb.preview.PreviewData
import com.hzzmonet.zkbomb.preview.PreviewUiState
import com.hzzmonet.zkbomb.ui.design.BombIcons
import com.hzzmonet.zkbomb.ui.design.BombTheme
import com.hzzmonet.zkbomb.ui.design.component.BombCard
import com.hzzmonet.zkbomb.ui.design.component.BombQuickAction
import com.hzzmonet.zkbomb.ui.design.component.BombRowDivider
import com.hzzmonet.zkbomb.ui.design.component.BombSectionTitle
import com.hzzmonet.zkbomb.ui.design.component.BombSwitchPreference
import top.yukonga.miuix.kmp.basic.Text

fun LazyListScope.automationContent(state: PreviewUiState) {
    item {
        BombCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Trigger → Conditions → Actions → Restore",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = BombTheme.miuix.onSurface,
                )
                Text(
                    text = "Manual actions always win over rules. Rules that would " +
                        "freeze SystemUI, the launcher, the active keyboard or Bomb " +
                        "itself are rejected by the engine, not by the UI.",
                    modifier = Modifier.padding(top = 6.dp),
                    fontSize = 12.sp,
                    color = BombTheme.miuix.onSurfaceVariantSummary,
                )
            }
        }
    }

    item { BombSectionTitle("Rules") }
    item {
        BombCard {
            PreviewData.rules.forEachIndexed { index, rule ->
                if (index > 0) BombRowDivider()
                BombSwitchPreference(
                    title = rule.name,
                    summary = "${rule.trigger}\n${rule.action}",
                    checked = state.ruleEnabled[rule.name] == true,
                    onCheckedChange = { state.ruleEnabled[rule.name] = it },
                )
            }
        }
    }

    item { BombSectionTitle("Templates") }
    item {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BombQuickAction(
                title = "Gaming",
                icon = BombIcons.Performance,
                tint = BombTheme.colors.accent,
                onClick = {},
                modifier = Modifier.weight(1f),
            )
            BombQuickAction(
                title = "Battery",
                icon = BombIcons.Battery,
                tint = BombTheme.colors.ok,
                onClick = {},
                modifier = Modifier.weight(1f),
            )
            BombQuickAction(
                title = "Screen off",
                icon = BombIcons.Freeze,
                tint = BombTheme.colors.network,
                onClick = {},
                modifier = Modifier.weight(1f),
            )
            BombQuickAction(
                title = "Thermal",
                icon = BombIcons.Thermal,
                tint = BombTheme.colors.warn,
                onClick = {},
                modifier = Modifier.weight(1f),
            )
        }
    }

    item { BombSectionTitle("Safety") }
    item {
        BombCard {
            Column(modifier = Modifier.padding(16.dp)) {
                listOf(
                    "Cooldown" to "30 s between repeats of the same action",
                    "Debounce" to "Rapid trigger flapping is collapsed",
                    "Precedence" to "Manual > safety > per-app > global",
                    "History" to "Last 200 executions kept for conflict resolution",
                ).forEachIndexed { index, (title, detail) ->
                    Row(modifier = Modifier.padding(top = if (index == 0) 0.dp else 10.dp)) {
                        Text(
                            text = title,
                            modifier = Modifier.weight(0.32f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = BombTheme.miuix.onSurface,
                        )
                        Text(
                            text = detail,
                            modifier = Modifier.weight(0.68f),
                            fontSize = 13.sp,
                            color = BombTheme.miuix.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }
    }
}
