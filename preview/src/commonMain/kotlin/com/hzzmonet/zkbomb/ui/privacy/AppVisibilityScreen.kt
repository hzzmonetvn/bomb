package com.hzzmonet.zkbomb.ui.privacy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hzzmonet.zkbomb.data.BombCapabilityKeys
import com.hzzmonet.zkbomb.data.BombOperationResult
import com.hzzmonet.zkbomb.data.BombServiceState
import com.hzzmonet.zkbomb.data.BombV6Validation
import com.hzzmonet.zkbomb.data.BombVisibilityMode
import com.hzzmonet.zkbomb.data.LiveApp
import com.hzzmonet.zkbomb.data.V6ApplyState
import com.hzzmonet.zkbomb.preview.PreviewData
import com.hzzmonet.zkbomb.ui.common.V6ApplyStatusLine
import com.hzzmonet.zkbomb.ui.design.BombIcon
import com.hzzmonet.zkbomb.ui.design.BombIcons
import com.hzzmonet.zkbomb.ui.design.BombTheme
import com.hzzmonet.zkbomb.ui.design.component.BombAppAvatar
import com.hzzmonet.zkbomb.ui.design.component.BombAppRow
import com.hzzmonet.zkbomb.ui.design.component.BombCard
import com.hzzmonet.zkbomb.ui.design.component.BombEmptyState
import com.hzzmonet.zkbomb.ui.design.component.BombIconButton
import com.hzzmonet.zkbomb.ui.design.component.BombPreference
import com.hzzmonet.zkbomb.ui.design.component.BombRowDivider
import com.hzzmonet.zkbomb.ui.design.component.BombSectionTitle
import com.hzzmonet.zkbomb.ui.design.component.BombSegmentedButton
import com.hzzmonet.zkbomb.ui.design.component.BombSwitchPreference
import com.hzzmonet.zkbomb.ui.design.component.BombUnsupportedState
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text

/**
 * App Visibility — caller-aware package hiding (BOMB_PLAN.md §12).
 *
 * Not plain Android 11 package visibility: Bomb makes specific packages behave as
 * if they do not exist **for one configured caller app**, while other callers see
 * them normally. A BLACKLIST hides the listed packages from the caller; a
 * WHITELIST hides everything except the listed packages (plus mandatory system
 * exemptions the service enforces).
 *
 * The v6 contract is write-only — no getter for the active policy — so this is an
 * apply console: pick a caller, compose its policy, apply it, and read back the
 * service's result. Requires [BombCapabilityKeys.PACKAGE_VISIBILITY_VIRTUALIZATION]
 * = SUPPORTED, a ROM-mode framework patch.
 */
fun LazyListScope.appVisibilityContent(
    service: BombServiceState,
    apps: List<LiveApp>?,
    /** Pre-selected caller package, e.g. opened from App Control. Null for global entry. */
    callerPackage: String? = null,
) {
    if (!service.isSupported(BombCapabilityKeys.PACKAGE_VISIBILITY_VIRTUALIZATION)) {
        item {
            BombUnsupportedState(
                title = "Package Visibility Virtualization unavailable",
                reason = "This feature requires a ROM-side framework patch and is reported as " +
                    "${service.stateOf(BombCapabilityKeys.PACKAGE_VISIBILITY_VIRTUALIZATION)} on this install. " +
                    "It is not equivalent to revoking QUERY_ALL_PACKAGES.",
            )
        }
        return
    }

    item { VisibilityIntroCard() }

    when {
        apps == null -> item { InfoCard("Reading installed apps from the platform…") }
        apps.isEmpty() -> item { BombEmptyState("No installed apps were found") }
        else -> item { VisibilityConsole(service, apps, callerPackage) }
    }
}

@Composable
private fun VisibilityIntroCard() {
    BombCard {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = "About App Visibility",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = BombTheme.miuix.onSurface,
            )
            Text(
                text = "Choose a caller app, then the packages it should not be able to see. Bomb " +
                    "cannot read the enforced policy back, so this shows the policy you are about " +
                    "to apply and the service's result — not a live readout. Other apps are unaffected.",
                modifier = Modifier.padding(top = 4.dp),
                fontSize = 12.sp,
                color = BombTheme.miuix.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun VisibilityConsole(
    service: BombServiceState,
    apps: List<LiveApp>,
    callerPackage: String?,
) {
    val controller = service.controller
    // A caller must own its UID for the policy to be scoped to it.
    val callerCandidates = remember(apps) {
        apps.filter { BombV6Validation.isApplicationUid(it.uid) }.sortedBy { it.name.lowercase() }
    }
    var selectedCaller by remember {
        mutableStateOf(callerCandidates.firstOrNull { it.packageName == callerPackage })
    }
    var mode by remember { mutableStateOf(BombVisibilityMode.BLACKLIST) }
    // package -> selected (hidden for blacklist, kept-visible for whitelist).
    val selection = remember { mutableStateMapOf<String, Boolean>() }
    var status by remember { mutableStateOf<V6ApplyState>(V6ApplyState.Idle) }
    val applying = status is V6ApplyState.Applying

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BombSectionTitle("Caller App")
        CallerSelector(
            candidates = callerCandidates,
            selected = selectedCaller,
            onSelect = {
                selectedCaller = it
                status = V6ApplyState.Idle
            },
        )

        val caller = selectedCaller
        if (caller != null) {
            BombSectionTitle("Visibility Mode")
            ModeCard(mode) { mode = it }

            BombSectionTitle(if (mode == BombVisibilityMode.WHITELIST) "Visible Packages" else "Hidden Packages")
            PackageSelectionCard(
                apps = apps,
                caller = caller,
                mode = mode,
                selection = selection,
            )

            val chosen = selection.filterValues { it }.keys.toList()
            BombCard {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "${chosen.size} package(s) selected for ${caller.name}",
                        fontSize = 12.sp,
                        color = BombTheme.miuix.onSurfaceVariantSummary,
                    )
                    V6ApplyStatusLine(status)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                status = V6ApplyState.Applying
                                if (controller == null) {
                                    status = V6ApplyState.Done(NOT_CONNECTED)
                                } else {
                                    controller.setVisibilityPolicy(caller.uid, mode, chosen) { result ->
                                        status = V6ApplyState.Done(result)
                                    }
                                }
                            },
                            // A whitelist with nothing selected would hide everything; block
                            // that footgun rather than let it through to the service.
                            enabled = controller != null && !applying && chosen.isNotEmpty(),
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColorsPrimary(),
                        ) {
                            Text(text = if (applying) "Applying…" else "Apply policy")
                        }
                        Button(
                            onClick = {
                                status = V6ApplyState.Applying
                                if (controller == null) {
                                    status = V6ApplyState.Done(NOT_CONNECTED)
                                } else {
                                    controller.clearVisibilityPolicy(caller.uid) { result ->
                                        if (result.isSuccess) selection.clear()
                                        status = V6ApplyState.Done(result)
                                    }
                                }
                            },
                            enabled = controller != null && !applying,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(),
                        ) {
                            Text(text = "Clear policy")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CallerSelector(
    candidates: List<LiveApp>,
    selected: LiveApp?,
    onSelect: (LiveApp) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val searchState = rememberTextFieldState()
    val query by remember { derivedStateOf { searchState.text.toString().lowercase().trim() } }
    val filtered by remember(candidates, query) {
        derivedStateOf {
            if (query.isEmpty()) candidates
            else candidates.filter {
                it.name.lowercase().contains(query) || it.packageName.lowercase().contains(query)
            }
        }
    }

    BombCard {
        if (selected != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BombAppAvatar(
                    label = selected.name,
                    tint = PreviewData.tintFor(selected.packageName),
                    size = 40.dp,
                    iconPackage = selected.packageName,
                )
                Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(
                        text = selected.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = BombTheme.miuix.onSurface,
                    )
                    Text(
                        text = "${selected.packageName} · uid ${selected.uid}",
                        modifier = Modifier.padding(top = 2.dp),
                        fontSize = 12.sp,
                        color = BombTheme.miuix.onSurfaceVariantSummary,
                    )
                }
                BombIconButton(
                    icon = BombIcons.Chevron,
                    contentDescription = "Change caller app",
                    onClick = { expanded = !expanded },
                    tint = BombTheme.miuix.onSurfaceVariantActions,
                )
            }
        } else {
            BombPreference(
                title = "Select caller app",
                summary = "Choose which app's package queries are filtered",
                icon = BombIcons.AppControl,
                iconTint = BombTheme.miuix.primary,
                onClick = { expanded = !expanded },
            )
        }

        if (expanded) {
            BombRowDivider()
            SearchField(searchState)
            if (filtered.isEmpty()) {
                BombEmptyState("No apps match \"$query\"")
            } else {
                Column {
                    filtered.forEachIndexed { idx, app ->
                        key(app.packageName) {
                            if (idx > 0) BombRowDivider()
                            BombAppRow(
                                name = app.name,
                                packageName = app.packageName,
                                tint = PreviewData.tintFor(app.packageName),
                                trailing = if (app.packageName == selected?.packageName) "✓" else null,
                                iconPackage = app.packageName,
                                onClick = {
                                    onSelect(app)
                                    expanded = false
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeCard(mode: String, onModeChange: (String) -> Unit) {
    BombCard {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            BombSegmentedButton(
                options = listOf("Blacklist", "Whitelist"),
                selectedIndex = if (mode == BombVisibilityMode.WHITELIST) 1 else 0,
                onSelected = {
                    onModeChange(if (it == 1) BombVisibilityMode.WHITELIST else BombVisibilityMode.BLACKLIST)
                },
            )
            Text(
                text = if (mode == BombVisibilityMode.WHITELIST) {
                    "WHITELIST — only the selected packages are visible to the caller. " +
                        "Mandatory system/framework packages are always exempt."
                } else {
                    "BLACKLIST — the selected packages are hidden from the caller. " +
                        "Everything else remains visible."
                },
                modifier = Modifier.padding(top = 10.dp),
                fontSize = 12.sp,
                color = BombTheme.miuix.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun PackageSelectionCard(
    apps: List<LiveApp>,
    caller: LiveApp,
    mode: String,
    selection: androidx.compose.runtime.snapshots.SnapshotStateMap<String, Boolean>,
) {
    val searchState = rememberTextFieldState()
    val query by remember { derivedStateOf { searchState.text.toString().lowercase().trim() } }
    // Hiding the caller from itself is nonsensical; keep it out of the target list.
    val targets = remember(apps, caller) {
        apps.filter { it.packageName != caller.packageName }.sortedBy { it.name.lowercase() }
    }
    val filtered by remember(targets, query) {
        derivedStateOf {
            if (query.isEmpty()) targets
            else targets.filter {
                it.name.lowercase().contains(query) || it.packageName.lowercase().contains(query)
            }
        }
    }

    BombCard {
        SearchField(searchState)
        if (filtered.isEmpty()) {
            BombEmptyState("No apps match \"$query\"")
        } else {
            Column {
                filtered.forEachIndexed { idx, app ->
                    key(app.packageName) {
                        if (idx > 0) BombRowDivider()
                        BombSwitchPreference(
                            title = app.name,
                            summary = if (mode == BombVisibilityMode.WHITELIST) {
                                "Visible to ${caller.name} — ${app.packageName}"
                            } else {
                                "Hidden from ${caller.name} — ${app.packageName}"
                            },
                            checked = selection[app.packageName] == true,
                            onCheckedChange = { checked ->
                                if (checked) selection[app.packageName] = true else selection.remove(app.packageName)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(searchState: androidx.compose.foundation.text.input.TextFieldState) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BombIcon(
            icon = BombIcons.Search,
            tint = BombTheme.miuix.onSurfaceVariantSummary,
            modifier = Modifier.size(18.dp),
        )
        Box(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
            if (searchState.text.isEmpty()) {
                Text(
                    text = "Search apps…",
                    fontSize = 15.sp,
                    color = BombTheme.miuix.onSurfaceVariantSummary,
                )
            }
            BasicTextField(state = searchState, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun InfoCard(text: String) {
    BombCard {
        Text(
            text = text,
            modifier = Modifier.padding(16.dp),
            fontSize = 13.sp,
            color = BombTheme.miuix.onSurfaceVariantSummary,
        )
    }
}

private val NOT_CONNECTED = BombOperationResult("BACKEND_UNAVAILABLE", "Service is not connected")
