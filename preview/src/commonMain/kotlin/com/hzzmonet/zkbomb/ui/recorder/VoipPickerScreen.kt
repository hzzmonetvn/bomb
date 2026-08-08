package com.hzzmonet.zkbomb.ui.recorder

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hzzmonet.zkbomb.preview.PreviewData
import com.hzzmonet.zkbomb.preview.PreviewUiState
import com.hzzmonet.zkbomb.ui.design.BombIcon
import com.hzzmonet.zkbomb.ui.design.BombIcons
import com.hzzmonet.zkbomb.ui.design.BombTheme
import com.hzzmonet.zkbomb.ui.design.component.BombCard
import com.hzzmonet.zkbomb.ui.design.component.BombEmptyState
import com.hzzmonet.zkbomb.ui.design.component.BombRowDivider
import com.hzzmonet.zkbomb.ui.design.component.BombSectionTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.extra.SuperCheckbox

/**
 * Chooses which VoIP apps the recorder watches.
 *
 * Apps whose audio cannot actually be captured on this build are listed but not
 * selectable — offering them and then silently producing an empty recording
 * would be worse than saying so here.
 */
fun LazyListScope.voipPickerContent(state: PreviewUiState) {
    item {
        TextField(
            state = state.voipQuery,
            label = "Search apps",
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

    val query = state.voipQuery.text.toString().trim().lowercase()
    val matches = PreviewData.voipApps.filter {
        query.isEmpty() ||
            it.name.lowercase().contains(query) ||
            it.packageName.lowercase().contains(query)
    }
    if (matches.isEmpty()) {
        item { BombEmptyState("No apps match") }
        return
    }

    val supported = matches.filter { it.captureSupported }
    val unsupported = matches.filter { !it.captureSupported }
    val watched = state.voipWatched.count { it.value }

    item { BombSectionTitle("$watched watched") }
    item {
        BombCard {
            supported.forEachIndexed { index, app ->
                if (index > 0) BombRowDivider()
                SuperCheckbox(
                    title = app.name,
                    summary = app.packageName,
                    checked = state.voipWatched[app.packageName] == true,
                    onCheckedChange = { state.voipWatched[app.packageName] = it },
                )
            }
        }
    }

    if (unsupported.isNotEmpty()) {
        item { BombSectionTitle("Cannot be recorded") }
        item {
            BombCard {
                unsupported.forEachIndexed { index, app ->
                    if (index > 0) BombRowDivider()
                    SuperCheckbox(
                        title = app.name,
                        summary = "${app.packageName} · ${app.note}",
                        checked = false,
                        onCheckedChange = null,
                        enabled = false,
                    )
                }
            }
        }
    }

    item {
        BombCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Watching an app costs nothing while it is closed. Bomb only " +
                        "arms the recorder when a watched app is in the foreground and " +
                        "the audio mode reports an active call.",
                    fontSize = 12.sp,
                    color = BombTheme.miuix.onSurfaceVariantSummary,
                )
            }
        }
    }
}
