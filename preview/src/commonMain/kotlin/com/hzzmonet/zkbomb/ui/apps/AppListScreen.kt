package com.hzzmonet.zkbomb.ui.apps

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hzzmonet.zkbomb.data.LiveApp
import com.hzzmonet.zkbomb.preview.PreviewData
import com.hzzmonet.zkbomb.preview.PreviewUiState
import com.hzzmonet.zkbomb.ui.design.BombIcon
import com.hzzmonet.zkbomb.ui.design.BombIcons
import com.hzzmonet.zkbomb.ui.design.BombTheme
import com.hzzmonet.zkbomb.ui.design.component.BombAppRow
import com.hzzmonet.zkbomb.ui.design.component.BombCard
import com.hzzmonet.zkbomb.ui.design.component.BombEmptyState
import com.hzzmonet.zkbomb.ui.design.component.BombRowDivider
import com.hzzmonet.zkbomb.ui.design.component.BombSectionTitle
import com.hzzmonet.zkbomb.ui.design.component.BombSegmentedButton
import com.hzzmonet.zkbomb.ui.navigation.BombNavigator
import com.hzzmonet.zkbomb.ui.navigation.BombRoute
import top.yukonga.miuix.kmp.basic.TextField

private val filters = listOf("All", "User", "System", "Frozen", "Disabled")

/**
 * Real installed packages when the platform can enumerate them, sample rows
 * otherwise. Rows are emitted individually so a 300-app list stays lazy and
 * icons load only for what is on screen.
 */
/**
 * The filtered app list, memoised.
 *
 * `derivedStateOf` recomputes the filter only when the query, the filter chip or
 * the freeze set actually change — not on every unrelated shell recomposition
 * (theme toggle, the LIVE/SAMPLE poll). Computed by the host composable and passed
 * into [appListContent], which is a non-composable `LazyListScope` builder and so
 * cannot itself hold remembered state. Null mirrors [apps] being unavailable.
 */
@Composable
fun rememberVisibleApps(state: PreviewUiState, apps: List<LiveApp>?): List<LiveApp>? {
    if (apps == null) return null
    val visible by remember(apps) {
        derivedStateOf {
            val query = state.appQuery.text.toString().trim().lowercase()
            apps.filter { app ->
                val matchesQuery = query.isEmpty() ||
                    app.name.lowercase().contains(query) ||
                    app.packageName.lowercase().contains(query)
                val matchesFilter = when (state.appFilter) {
                    1 -> !app.isSystem
                    2 -> app.isSystem
                    3 -> state.freezeList.containsKey(app.packageName)
                    4 -> !app.enabled
                    else -> true
                }
                matchesQuery && matchesFilter
            }
        }
    }
    return visible
}

fun LazyListScope.appListContent(
    state: PreviewUiState,
    navigator: BombNavigator,
    apps: List<LiveApp>?,
    visible: List<LiveApp>?,
) {
    item {
        TextField(
            state = state.appQuery,
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

    item {
        BombSegmentedButton(
            options = filters,
            selectedIndex = state.appFilter,
            onSelected = { state.appFilter = it },
        )
    }

    if (apps == null || visible == null) {
        val query = state.appQuery.text.toString().trim().lowercase()
        sampleApps(state, navigator, query)
        return
    }

    item { BombSectionTitle("${visible.size} of ${apps.size} apps") }

    if (visible.isEmpty()) {
        item { BombEmptyState("No apps match this filter") }
        return
    }

    // Keyed by package so a filter or search change re-keys stable rows instead of
    // recomposing every position — Compose reuses the unchanged rows (and their
    // already-loaded icons) rather than rebuilding the list wholesale.
    items(count = visible.size, key = { visible[it].packageName }) { index ->
        val app = visible[index]
        BombCard {
            BombAppRow(
                name = app.name,
                packageName = app.packageName,
                tint = PreviewData.tintFor(app.packageName),
                iconPackage = app.packageName,
                badges = buildList {
                    if (state.freezeList.containsKey(app.packageName)) {
                        add("Frozen" to BombTheme.colors.frozen)
                    }
                    if (!app.enabled) add("Disabled" to BombTheme.colors.warn)
                    if (app.isSystem) add("System" to BombTheme.miuix.primary)
                },
                trailing = app.versionName,
                onClick = { navigator.push(BombRoute.AppControl(app.packageName)) },
            )
        }
    }
}

private fun LazyListScope.sampleApps(
    state: PreviewUiState,
    navigator: BombNavigator,
    query: String,
) {
    val visible = PreviewData.apps.filter { app ->
        val matchesQuery = query.isEmpty() ||
            app.name.lowercase().contains(query) ||
            app.packageName.lowercase().contains(query)
        val matchesFilter = when (state.appFilter) {
            1 -> !app.system
            2 -> app.system
            3 -> app.frozen
            4 -> false
            else -> true
        }
        matchesQuery && matchesFilter
    }

    if (visible.isEmpty()) {
        item { BombEmptyState("No apps match this filter") }
        return
    }

    item {
        BombCard {
            visible.forEachIndexed { index, app ->
                if (index > 0) BombRowDivider()
                BombAppRow(
                    name = app.name,
                    packageName = app.packageName,
                    tint = PreviewData.tintFor(app.packageName),
                    badges = buildList {
                        if (app.frozen) add("Frozen" to BombTheme.colors.frozen)
                        if (app.system) add("System" to BombTheme.miuix.primary)
                        if (app.running && !app.frozen) add("Running" to BombTheme.colors.ok)
                    },
                    trailing = if (app.ramMb > 0) "${app.ramMb} MB" else null,
                    onClick = { navigator.push(BombRoute.AppControl(app.packageName)) },
                )
            }
        }
    }
}
