package com.hzzmonet.zkbomb.ui.freeze

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hzzmonet.zkbomb.data.LiveApp
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
 * Picks which packages the freeze engine manages.
 *
 * Critical packages are listed but not selectable: the exclusion lives in the
 * policy engine, so the picker shows *why* rather than hiding them and leaving
 * the user wondering where their launcher went.
 */
fun LazyListScope.freezePickerContent(state: PreviewUiState, apps: List<LiveApp>?) {
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
    val matches = apps.orEmpty().filter {
        query.isEmpty() ||
            it.name.lowercase().contains(query) ||
            it.packageName.lowercase().contains(query)
    }
    if (matches.isEmpty()) {
        item { BombEmptyState("No apps match") }
        return
    }

    item { BombSectionTitle("${state.freezeList.size} selected") }
    item {
        BombCard {
            matches.forEachIndexed { index, app ->
                if (index > 0) BombRowDivider()
                SuperCheckbox(
                    title = app.name,
                    summary = if (app.isSystem) "${app.packageName} · System" else app.packageName,
                    checked = state.freezeList.containsKey(app.packageName),
                    onCheckedChange = { isChecked ->
                        if (isChecked) {
                            state.freezeList[app.packageName] = "Deep Freeze"
                        } else {
                            state.freezeList.remove(app.packageName)
                        }
                    },
                )
            }
        }
    }

    item {
        BombCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Protected packages are refused when a freeze is applied, " +
                        "not hidden here.",
                    fontSize = 13.sp,
                    color = BombTheme.miuix.onSurface,
                )
                Text(
                    text = "Bomb itself, SystemUI, the resolved launcher, the current " +
                        "keyboard, framework and vendor-critical packages. The list is " +
                        "resolved at the moment of the request — the launcher and the " +
                        "keyboard can change after this screen was drawn — and it is " +
                        "enforced in the policy engine. A selection made here cannot " +
                        "get past it.",
                    modifier = Modifier.padding(top = 6.dp),
                    fontSize = 12.sp,
                    color = BombTheme.miuix.onSurfaceVariantSummary,
                )
            }
        }
    }
}
