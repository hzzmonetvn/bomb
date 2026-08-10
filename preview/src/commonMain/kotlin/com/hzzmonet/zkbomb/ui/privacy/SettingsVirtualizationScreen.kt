package com.hzzmonet.zkbomb.ui.privacy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
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
import com.hzzmonet.zkbomb.data.BombSettingsCatalog
import com.hzzmonet.zkbomb.data.BombSettingsKey
import com.hzzmonet.zkbomb.data.BombSettingsValueType
import com.hzzmonet.zkbomb.data.BombV6Validation
import com.hzzmonet.zkbomb.data.LiveApp
import com.hzzmonet.zkbomb.data.V6ApplyState
import com.hzzmonet.zkbomb.preview.PreviewData
import com.hzzmonet.zkbomb.ui.common.V6ApplyStatusLine
import com.hzzmonet.zkbomb.ui.design.BombIcon
import com.hzzmonet.zkbomb.ui.design.BombIcons
import com.hzzmonet.zkbomb.ui.design.BombTheme
import com.hzzmonet.zkbomb.ui.design.component.BombAppRow
import com.hzzmonet.zkbomb.ui.design.component.BombBadge
import com.hzzmonet.zkbomb.ui.design.component.BombBottomSheet
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
 * Per-App Settings Virtualization (BOMB_PLAN.md §13).
 *
 * A profile is a set of Settings overrides; assigned to an app, it makes that app
 * observe virtual values while the real system settings are never mutated. The
 * service accepts only classified APP_READ keys and canonical typed values.
 *
 * The v6 contract is write-only, so this is an apply console: keys are chosen from
 * the virtualizable [BombSettingsCatalog] (no free-text keys that would be
 * rejected), each write goes straight to the service, and its result is shown
 * verbatim. Requires [BombCapabilityKeys.SETTINGS_VIRTUALIZATION] = SUPPORTED,
 * a ROM-mode framework patch.
 */
fun LazyListScope.settingsVirtualizationContent(
    service: BombServiceState,
    apps: List<LiveApp>?,
) {
    if (!service.isSupported(BombCapabilityKeys.SETTINGS_VIRTUALIZATION)) {
        item {
            BombUnsupportedState(
                title = "Settings Virtualization unavailable",
                reason = "Virtual Settings requires a ROM-side framework patch and is reported as " +
                    "${service.stateOf(BombCapabilityKeys.SETTINGS_VIRTUALIZATION)} here. When active it " +
                    "intercepts Settings reads per-caller without mutating real global values.",
            )
        }
        return
    }

    item { SettingsIntroCard() }
    item { SettingsConsole(service, apps) }
}

@Composable
private fun SettingsIntroCard() {
    BombCard {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = "About Settings Virtualization",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = BombTheme.miuix.onSurface,
            )
            Text(
                text = "Build a profile from the virtualizable keys below and assign it to an app. " +
                    "That app reads the overridden values while the real system settings are " +
                    "unchanged. Only keys the framework reads inside an app process can be " +
                    "virtualized, so the list is deliberately short.",
                modifier = Modifier.padding(top = 4.dp),
                fontSize = 12.sp,
                color = BombTheme.miuix.onSurfaceVariantSummary,
            )
        }
    }
}

/** A session override: exactly the fields the [addSettingsOverride] call carries. */
private data class UiOverride(
    val namespace: String,
    val key: String,
    val valueType: String,
    val value: String?,
    val enabled: Boolean,
)

/** A session profile. Its overrides and last-result live in Compose state. */
private class UiProfile(val id: String, val name: String) {
    val overrides = mutableStateListOf<UiOverride>()
    var status by mutableStateOf<V6ApplyState>(V6ApplyState.Idle)
}

@Composable
private fun SettingsConsole(service: BombServiceState, apps: List<LiveApp>?) {
    val controller = service.controller
    val profiles = remember { mutableStateListOf<UiProfile>() }
    var idCounter by remember { mutableIntStateOf(0) }
    var showCreate by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BombSectionTitle("Settings Profiles")
        BombCard {
            BombPreference(
                title = "Create profile",
                summary = "A named set of Settings overrides to assign to apps",
                icon = BombIcons.Apps,
                iconTint = BombTheme.miuix.primary,
                enabled = controller != null,
                onClick = { showCreate = true },
            )
            if (profiles.isEmpty()) {
                BombRowDivider()
                BombEmptyState("No profiles yet — create one above")
            } else {
                profiles.forEach { profile ->
                    BombRowDivider()
                    ProfileRow(
                        profile = profile,
                        controllerAvailable = controller != null,
                        onDelete = {
                            profile.status = V6ApplyState.Applying
                            if (controller == null) {
                                profile.status = V6ApplyState.Done(NOT_CONNECTED)
                            } else {
                                controller.deleteSettingsProfile(profile.id) { result ->
                                    if (result.isSuccess) profiles.remove(profile)
                                    else profile.status = V6ApplyState.Done(result)
                                }
                            }
                        },
                        onAddOverride = { draft, onDone ->
                            if (controller == null) {
                                onDone(NOT_CONNECTED)
                            } else {
                                controller.addSettingsOverride(
                                    profileId = profile.id,
                                    namespace = draft.namespace,
                                    key = draft.key,
                                    valueType = draft.valueType,
                                    value = draft.value,
                                    enabled = draft.enabled,
                                ) { result ->
                                    if (result.isSuccess) {
                                        profile.overrides.removeAll { it.namespace == draft.namespace && it.key == draft.key }
                                        profile.overrides.add(draft)
                                    }
                                    onDone(result)
                                }
                            }
                        },
                        onRemoveOverride = { override ->
                            if (controller == null) {
                                profile.status = V6ApplyState.Done(NOT_CONNECTED)
                            } else {
                                controller.removeSettingsOverride(profile.id, override.namespace, override.key) { result ->
                                    if (result.isSuccess) profile.overrides.remove(override)
                                    else profile.status = V6ApplyState.Done(result)
                                }
                            }
                        },
                    )
                }
            }
        }

        BombSectionTitle("Profile Assignments")
        AssignmentCard(service = service, apps = apps, profiles = profiles)
    }

    if (showCreate) {
        CreateProfileSheet(
            onDismiss = { showCreate = false },
            onCreate = { name ->
                val id = "p${idCounter + 1}"
                idCounter += 1
                controller?.createSettingsProfile(id, name) { result ->
                    if (result.isSuccess) {
                        profiles.add(UiProfile(id, name))
                    }
                    // A failed create surfaces on the newest profile's row is not
                    // possible (it was not added); nothing to show beyond the sheet
                    // closing — the list simply stays as it was.
                }
                showCreate = false
            },
        )
    }
}

@Composable
private fun ProfileRow(
    profile: UiProfile,
    controllerAvailable: Boolean,
    onDelete: () -> Unit,
    onAddOverride: (UiOverride, (BombOperationResult) -> Unit) -> Unit,
    onRemoveOverride: (UiOverride) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profile.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = BombTheme.miuix.onSurface,
                )
                Text(
                    text = "id ${profile.id} · ${profile.overrides.size} override(s)",
                    modifier = Modifier.padding(top = 2.dp),
                    fontSize = 12.sp,
                    color = BombTheme.miuix.onSurfaceVariantSummary,
                )
            }
            BombIconButton(
                icon = BombIcons.Chevron,
                contentDescription = if (expanded) "Collapse" else "Expand",
                onClick = { expanded = !expanded },
                tint = BombTheme.miuix.onSurfaceVariantActions,
            )
            BombIconButton(
                icon = BombIcons.Stop,
                contentDescription = "Delete profile",
                onClick = onDelete,
                tint = BombTheme.colors.warn,
            )
        }

        if (expanded) {
            BombRowDivider()
            if (profile.overrides.isEmpty()) {
                BombEmptyState("No overrides — add one below")
            } else {
                profile.overrides.forEachIndexed { idx, override ->
                    if (idx > 0) BombRowDivider()
                    OverrideRow(override = override, onRemove = { onRemoveOverride(override) })
                }
            }
            BombRowDivider()
            BombPreference(
                title = "Add Setting override",
                summary = "Choose a virtualizable key and its value",
                icon = BombIcons.AppControl,
                iconTint = BombTheme.miuix.primary,
                enabled = controllerAvailable,
                onClick = { showAdd = true },
            )
            V6ApplyStatusLine(
                profile.status,
                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
            )
        }
    }

    if (showAdd) {
        AddOverrideSheet(
            onDismiss = { showAdd = false },
            onAdd = { draft, onResult ->
                onAddOverride(draft) { result ->
                    onResult(result)
                    if (result.isSuccess) showAdd = false
                }
            },
        )
    }
}

@Composable
private fun OverrideRow(override: UiOverride, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                BombBadge(text = override.namespace, color = namespaceColor(override.namespace))
                Text(
                    text = override.key,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = BombTheme.miuix.onSurface,
                )
            }
            Row(
                modifier = Modifier.padding(top = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BombBadge(text = override.valueType, color = BombTheme.miuix.onSurfaceVariantSummary)
                Text(
                    text = "= ${override.value ?: "null"}",
                    fontSize = 13.sp,
                    color = BombTheme.miuix.onSurfaceVariantSummary,
                )
                if (!override.enabled) BombBadge(text = "disabled", color = BombTheme.colors.warn)
            }
        }
        BombIconButton(
            icon = BombIcons.Stop,
            contentDescription = "Remove override",
            onClick = onRemove,
            tint = BombTheme.colors.warn,
        )
    }
}

private fun namespaceColor(namespace: String) = when (namespace) {
    com.hzzmonet.zkbomb.data.BombSettingsNamespace.SYSTEM -> androidx.compose.ui.graphics.Color(0xFF3B82F6)
    com.hzzmonet.zkbomb.data.BombSettingsNamespace.SECURE -> androidx.compose.ui.graphics.Color(0xFFF59E0B)
    else -> androidx.compose.ui.graphics.Color(0xFF10B981)
}

// ------------------------------------------------------------------ Assignment

@Composable
private fun AssignmentCard(
    service: BombServiceState,
    apps: List<LiveApp>?,
    profiles: List<UiProfile>,
) {
    val controller = service.controller
    val searchState = rememberTextFieldState()
    val query by remember { derivedStateOf { searchState.text.toString().lowercase().trim() } }
    // package -> assigned profile id (session view of what we last applied).
    val assigned = remember { mutableStateMapOf<String, String>() }
    val status = remember { mutableStateMapOf<String, V6ApplyState>() }

    if (apps == null) {
        BombCard { InfoText("Reading installed apps from the platform…") }
        return
    }
    val targets = remember(apps) { apps.filter { !it.isSystem }.sortedBy { it.name.lowercase() } }
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
        when {
            profiles.isEmpty() -> {
                BombRowDivider()
                InfoText("Create a profile first, then assign it to apps here.")
            }
            filtered.isEmpty() -> {
                BombRowDivider()
                BombEmptyState("No user apps match \"$query\"")
            }
            else -> Column {
                filtered.forEachIndexed { idx, app ->
                    if (idx > 0) BombRowDivider()
                    AssignmentRow(
                        app = app,
                        profiles = profiles,
                        assignedProfileId = assigned[app.packageName],
                        status = status[app.packageName] ?: V6ApplyState.Idle,
                        controllerAvailable = controller != null,
                        onAssign = { profile ->
                            status[app.packageName] = V6ApplyState.Applying
                            if (controller == null) {
                                status[app.packageName] = V6ApplyState.Done(NOT_CONNECTED)
                            } else {
                                controller.assignSettingsProfile(app.packageName, profile.id) { result ->
                                    if (result.isSuccess) assigned[app.packageName] = profile.id
                                    status[app.packageName] = V6ApplyState.Done(result)
                                }
                            }
                        },
                        onClear = {
                            status[app.packageName] = V6ApplyState.Applying
                            if (controller == null) {
                                status[app.packageName] = V6ApplyState.Done(NOT_CONNECTED)
                            } else {
                                controller.clearSettingsAssignment(app.packageName) { result ->
                                    if (result.isSuccess) assigned.remove(app.packageName)
                                    status[app.packageName] = V6ApplyState.Done(result)
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AssignmentRow(
    app: LiveApp,
    profiles: List<UiProfile>,
    assignedProfileId: String?,
    status: V6ApplyState,
    controllerAvailable: Boolean,
    onAssign: (UiProfile) -> Unit,
    onClear: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val assignedName = profiles.firstOrNull { it.id == assignedProfileId }?.name

    Column {
        BombAppRow(
            name = app.name,
            packageName = app.packageName,
            tint = PreviewData.tintFor(app.packageName),
            badges = if (assignedName != null) listOf(assignedName to BombTheme.colors.ok) else emptyList(),
            trailing = if (assignedName == null) "None" else null,
            iconPackage = app.packageName,
            onClick = { expanded = !expanded },
        )
        if (expanded) {
            BombRowDivider()
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                profiles.forEach { profile ->
                    BombSwitchPreference(
                        title = profile.name,
                        summary = "id ${profile.id} · ${profile.overrides.size} override(s)",
                        checked = assignedProfileId == profile.id,
                        onCheckedChange = { checked -> if (checked) onAssign(profile) },
                    )
                }
                V6ApplyStatusLine(status, modifier = Modifier.padding(top = 4.dp))
                Button(
                    onClick = onClear,
                    enabled = controllerAvailable && assignedProfileId != null && status !is V6ApplyState.Applying,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    colors = ButtonDefaults.buttonColors(),
                ) {
                    Text(text = "Clear assignment")
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Sheets

@Composable
private fun CreateProfileSheet(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    val nameState = rememberTextFieldState()
    val name by remember { derivedStateOf { nameState.text.toString().trim() } }
    val valid = BombV6Validation.isValidProfileName(name)

    BombBottomSheet(onDismissRequest = onDismiss, title = "New Settings Profile") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = "Profile name", fontSize = 13.sp, color = BombTheme.miuix.onSurfaceVariantSummary)
            SheetTextField(nameState, "e.g. Night reading")
            Button(
                onClick = { if (valid) onCreate(name) },
                enabled = valid,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(text = "Create")
            }
        }
    }
}

@Composable
private fun AddOverrideSheet(
    onDismiss: () -> Unit,
    onAdd: (UiOverride, (BombOperationResult) -> Unit) -> Unit,
) {
    val keys = BombSettingsCatalog.APP_READ_KEYS
    var selectedKey by remember { mutableStateOf<BombSettingsKey?>(null) }
    val valueTypes = listOf(
        BombSettingsValueType.STRING, BombSettingsValueType.INTEGER, BombSettingsValueType.LONG,
        BombSettingsValueType.FLOAT, BombSettingsValueType.BOOLEAN, BombSettingsValueType.NULL,
    )
    var typeIndex by remember { mutableIntStateOf(0) }
    val valueState = rememberTextFieldState()
    var submitting by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    val valueType = valueTypes[typeIndex]
    val rawValue by remember { derivedStateOf { valueState.text.toString() } }
    val effectiveValue = if (valueType == BombSettingsValueType.NULL) null else rawValue
    val violation = BombV6Validation.settingsValueViolation(valueType, effectiveValue)
    val canAdd = selectedKey != null && violation == null && !submitting

    BombBottomSheet(onDismissRequest = onDismiss, title = "Add Setting Override") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = "Key", fontSize = 13.sp, color = BombTheme.miuix.onSurfaceVariantSummary)
            Column(
                modifier = Modifier
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                keys.forEachIndexed { idx, key ->
                    if (idx > 0) BombRowDivider()
                    KeyRow(
                        key = key,
                        selected = selectedKey == key,
                        onClick = {
                            selectedKey = key
                            typeIndex = valueTypes.indexOf(key.suggestedType).coerceAtLeast(0)
                            errorText = null
                        },
                    )
                }
            }

            Text(text = "Value type", fontSize = 13.sp, color = BombTheme.miuix.onSurfaceVariantSummary)
            BombSegmentedButton(
                options = valueTypes,
                selectedIndex = typeIndex,
                onSelected = { typeIndex = it; errorText = null },
            )

            if (valueType != BombSettingsValueType.NULL) {
                Text(text = "Value", fontSize = 13.sp, color = BombTheme.miuix.onSurfaceVariantSummary)
                SheetTextField(valueState, "Canonical ${valueType.lowercase()} value")
            }

            val shownError = errorText ?: violation?.takeIf { rawValue.isNotEmpty() || valueType == BombSettingsValueType.NULL }
            if (shownError != null) {
                Text(text = shownError, fontSize = 12.sp, color = BombTheme.colors.warn)
            }

            Button(
                onClick = {
                    val key = selectedKey ?: return@Button
                    val v = violation
                    if (v != null) {
                        errorText = v
                        return@Button
                    }
                    submitting = true
                    errorText = null
                    onAdd(
                        UiOverride(
                            namespace = key.namespace,
                            key = key.key,
                            valueType = valueType,
                            value = effectiveValue,
                            enabled = true,
                        ),
                    ) { result ->
                        submitting = false
                        if (!result.isSuccess) {
                            errorText = "${result.status}${result.message?.let { ": $it" } ?: ""}"
                        }
                    }
                },
                enabled = canAdd,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(text = if (submitting) "Adding…" else "Add override")
            }
        }
    }
}

@Composable
private fun KeyRow(key: BombSettingsKey, selected: Boolean, onClick: () -> Unit) {
    BombPreference(
        title = "${key.namespace} / ${key.key}",
        summary = "${key.description} · default ${key.suggestedType}",
        value = if (selected) "✓" else null,
        onClick = onClick,
    )
}

@Composable
private fun SheetTextField(state: TextFieldState, placeholder: String) {
    Box(modifier = Modifier.fillMaxWidth()) {
        if (state.text.isEmpty()) {
            Text(text = placeholder, fontSize = 15.sp, color = BombTheme.miuix.onSurfaceVariantSummary)
        }
        BasicTextField(state = state, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun SearchField(searchState: TextFieldState) {
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
                Text(text = "Search apps…", fontSize = 15.sp, color = BombTheme.miuix.onSurfaceVariantSummary)
            }
            BasicTextField(state = searchState, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun InfoText(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(16.dp),
        fontSize = 13.sp,
        color = BombTheme.miuix.onSurfaceVariantSummary,
    )
}

private val NOT_CONNECTED = BombOperationResult("BACKEND_UNAVAILABLE", "Service is not connected")
