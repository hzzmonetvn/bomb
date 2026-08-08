package com.hzzmonet.zkbomb.ui.freeze

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hzzmonet.zkbomb.preview.PreviewData
import com.hzzmonet.zkbomb.preview.PreviewUiState
import com.hzzmonet.zkbomb.ui.design.BombIcon
import com.hzzmonet.zkbomb.ui.design.BombIcons
import com.hzzmonet.zkbomb.ui.design.BombTheme
import com.hzzmonet.zkbomb.ui.design.component.BombCard
import com.hzzmonet.zkbomb.ui.design.component.BombEmptyState
import com.hzzmonet.zkbomb.ui.design.component.BombRowDivider
import com.hzzmonet.zkbomb.ui.design.component.BombSectionTitle
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.extra.SuperCheckbox

/**
 * Picks which packages the freeze engine manages.
 *
 * Critical packages are listed but not selectable: the exclusion lives in the
 * policy engine, so the picker shows *why* rather than hiding them and leaving
 * the user wondering where their launcher went.
 */
fun LazyListScope.freezePickerContent(state: PreviewUiState) {
    item {
        TextField(
            state = state.freezePickerQuery,
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

    val query = state.freezePickerQuery.text.toString().trim().lowercase()
    val matches = PreviewData.apps.filter {
        query.isEmpty() ||
            it.name.lowercase().contains(query) ||
            it.packageName.lowercase().contains(query)
    }
    val selectable = matches.filter { it.freezeMode != "Excluded" }
    val protected = matches.filter { it.freezeMode == "Excluded" }

    if (matches.isEmpty()) {
        item { BombEmptyState("No apps match") }
        return
    }

    item { BombSectionTitle("${state.freezeList.size} selected") }
    item {
        BombCard {
            selectable.forEachIndexed { index, app ->
                if (index > 0) BombRowDivider()
                val checked = state.freezeList.containsKey(app.packageName)
                SuperCheckbox(
                    title = app.name,
                    summary = app.packageName,
                    checked = checked,
                    onCheckedChange = { isChecked ->
                        if (isChecked) {
                            state.freezeList[app.packageName] =
                                app.freezeMode.takeIf { it != "Never" } ?: "Deep Freeze"
                        } else {
                            state.freezeList.remove(app.packageName)
                        }
                    },
                )
            }
        }
    }

    if (protected.isNotEmpty()) {
        item { BombSectionTitle("Protected · cannot be frozen") }
        item {
            BombCard {
                protected.forEachIndexed { index, app ->
                    if (index > 0) BombRowDivider()
                    SuperCheckbox(
                        title = app.name,
                        summary = "${app.packageName} · critical package",
                        checked = false,
                        onCheckedChange = null,
                        enabled = false,
                    )
                }
            }
        }
    }
}
