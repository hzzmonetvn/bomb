package com.hzzmonet.zkbomb.ui.logs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hzzmonet.zkbomb.preview.PreviewData
import com.hzzmonet.zkbomb.preview.PreviewUiState
import com.hzzmonet.zkbomb.ui.design.BombTheme
import com.hzzmonet.zkbomb.ui.design.component.BombBadge
import com.hzzmonet.zkbomb.ui.design.component.BombCard
import com.hzzmonet.zkbomb.ui.design.component.BombPreference
import com.hzzmonet.zkbomb.ui.design.component.BombRowDivider
import com.hzzmonet.zkbomb.ui.design.component.BombSectionTitle
import com.hzzmonet.zkbomb.ui.design.component.BombSegmentedButton
import top.yukonga.miuix.kmp.basic.Text

private val profiles = listOf("Default", "Balanced", "Quiet", "Dev")

fun LazyListScope.logGovernorContent(state: PreviewUiState) {
    item {
        BombSegmentedButton(
            options = profiles,
            selectedIndex = state.logProfile,
            onSelected = { state.logProfile = it },
        )
    }

    item {
        BombCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Estimated log rate",
                        modifier = Modifier.weight(1f),
                        fontSize = 14.sp,
                        color = BombTheme.miuix.onSurface,
                    )
                    Text(
                        text = "${rateFor(state.logProfile)} events/s",
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
            }
        }
    }

    item { BombSectionTitle("Always preserved") }
    item {
        BombCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                    BombBadge("Fatal", BombTheme.colors.critical)
                    BombBadge("Crashes", BombTheme.colors.critical)
                    BombBadge("SELinux denials", BombTheme.colors.warn)
                    BombBadge("Security", BombTheme.colors.ok)
                }
                Text(
                    text = "Quiet reduces verbose and debug noise. It never disables " +
                        "logd, tombstones or bugreports — debugging a ROM has to stay " +
                        "possible.",
                    modifier = Modifier.padding(top = 10.dp),
                    fontSize = 12.sp,
                    color = BombTheme.miuix.onSurfaceVariantSummary,
                )
            }
        }
    }

    item { BombSectionTitle("Per-tag overrides") }
    item {
        BombCard {
            PreviewData.logTags.forEachIndexed { index, (tag, level, enabled) ->
                if (index > 0) BombRowDivider()
                BombPreference(
                    title = tag,
                    summary = if (enabled) null else "Disabled",
                    value = level,
                    onClick = {},
                )
            }
            BombRowDivider()
            BombPreference(title = "Add tag override", onClick = {})
        }
    }
}

private fun rateFor(index: Int) = when (index) {
    0 -> "1 840"
    1 -> "620"
    2 -> "180"
    else -> "3 100"
}
