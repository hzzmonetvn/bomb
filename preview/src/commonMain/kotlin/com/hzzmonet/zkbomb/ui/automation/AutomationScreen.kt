package com.hzzmonet.zkbomb.ui.automation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hzzmonet.zkbomb.data.AutomationUiState
import com.hzzmonet.zkbomb.data.BombAutomationAction
import com.hzzmonet.zkbomb.data.BombAutomationActionType
import com.hzzmonet.zkbomb.data.BombAutomationBounds
import com.hzzmonet.zkbomb.data.BombAutomationCondition
import com.hzzmonet.zkbomb.data.BombAutomationConditionType
import com.hzzmonet.zkbomb.data.BombAutomationFreezeMode
import com.hzzmonet.zkbomb.data.BombAutomationRule
import com.hzzmonet.zkbomb.data.BombAutomationTrigger
import com.hzzmonet.zkbomb.data.BombOperationResult
import com.hzzmonet.zkbomb.data.BombPerformanceProfile
import com.hzzmonet.zkbomb.data.BombRuleScope
import com.hzzmonet.zkbomb.data.BombRestorePolicy
import com.hzzmonet.zkbomb.data.BombScreenState
import com.hzzmonet.zkbomb.data.BombServiceState
import com.hzzmonet.zkbomb.data.LiveApp
import com.hzzmonet.zkbomb.data.V6ApplyState
import com.hzzmonet.zkbomb.data.rememberAutomation
import com.hzzmonet.zkbomb.preview.PreviewData
import com.hzzmonet.zkbomb.ui.common.V6ApplyStatusLine
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
import kotlin.random.Random

/**
 * Bomb Rules / Automation (BOMB_PLAN.md §17).
 *
 * Trigger → optional conditions → action, with an optional restore on the opposite
 * trigger. Automation is readable (v8), so this screen mirrors the real rule set,
 * the master switch, the observer status and the last trigger. Every write goes
 * to the engine, which validates it and returns a result shown verbatim; the
 * engine — not the UI — rejects rules that would freeze SystemUI, the launcher or
 * Bomb itself.
 */
fun LazyListScope.automationContent(
    service: BombServiceState,
    apps: List<LiveApp>?,
) {
    if (!service.isSupported(com.hzzmonet.zkbomb.data.BombCapabilityKeys.AUTOMATION_RULES)) {
        item {
            BombUnsupportedState(
                title = "Automation unavailable",
                reason = "The rule engine is reported as " +
                    "${service.stateOf(com.hzzmonet.zkbomb.data.BombCapabilityKeys.AUTOMATION_RULES)} on this install.",
            )
        }
        return
    }
    item { IntroCard() }
    item { AutomationConsole(service, apps) }
}

@Composable
private fun IntroCard() {
    BombCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Trigger → Conditions → Action → Restore",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = BombTheme.miuix.onSurface,
            )
            Text(
                text = "Manual actions always win over rules. Rules that would freeze SystemUI, " +
                    "the launcher, the active keyboard or Bomb itself are rejected by the engine, " +
                    "not by this screen.",
                modifier = Modifier.padding(top = 6.dp),
                fontSize = 12.sp,
                color = BombTheme.miuix.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun AutomationConsole(service: BombServiceState, apps: List<LiveApp>?) {
    val controller = service.controller
    var refreshKey by remember { mutableIntStateOf(0) }
    val state = rememberAutomation(service, refreshKey)
    var editing by remember { mutableStateOf<EditorTarget?>(null) }
    // Per-rule inline apply status (enable toggle / delete).
    val rowStatus = remember { mutableStateMapOf<String, V6ApplyState>() }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (state) {
            AutomationUiState.Loading ->
                InfoCard("Reading automation rules from the privileged service…")

            is AutomationUiState.Unsupported ->
                BombUnsupportedState(title = "Automation", reason = state.reason)

            is AutomationUiState.Error ->
                InfoCard(state.message, error = true)

            is AutomationUiState.Ready -> {
                val snapshot = state.snapshot
                MasterSwitchCard(
                    enabled = snapshot.enabled,
                    observerRunning = snapshot.observerRunning,
                    lastTrigger = snapshot.lastTrigger,
                    controllerAvailable = controller != null,
                    onToggle = { on ->
                        controller?.setAutomationEnabled(on) { refreshKey++ }
                    },
                )

                BombSectionTitle("Rules")
                if (snapshot.rules.isEmpty()) {
                    BombCard { BombEmptyState("No rules yet — add one below") }
                } else {
                    BombCard {
                        snapshot.rules.forEachIndexed { idx, rule ->
                            if (idx > 0) BombRowDivider()
                            RuleRow(
                                rule = rule,
                                status = rowStatus[rule.id] ?: V6ApplyState.Idle,
                                apps = apps,
                                controllerAvailable = controller != null,
                                onToggle = { on ->
                                    rowStatus[rule.id] = V6ApplyState.Applying
                                    val updated = rule.copy(enabled = on)
                                    if (controller == null) {
                                        rowStatus[rule.id] = V6ApplyState.Done(NOT_CONNECTED)
                                    } else {
                                        controller.upsertAutomationRule(updated) { result ->
                                            rowStatus[rule.id] = V6ApplyState.Done(result)
                                            if (result.isSuccess) refreshKey++
                                        }
                                    }
                                },
                                onEdit = { editing = EditorTarget(rule) },
                                onDelete = {
                                    rowStatus[rule.id] = V6ApplyState.Applying
                                    if (controller == null) {
                                        rowStatus[rule.id] = V6ApplyState.Done(NOT_CONNECTED)
                                    } else {
                                        controller.deleteAutomationRule(rule.id) { result ->
                                            rowStatus[rule.id] = V6ApplyState.Done(result)
                                            if (result.isSuccess) refreshKey++
                                        }
                                    }
                                },
                            )
                        }
                    }
                }

                BombCard {
                    BombPreference(
                        title = "Add rule",
                        summary = "Trigger, optional condition and an action",
                        icon = BombIcons.Automation,
                        iconTint = BombTheme.miuix.primary,
                        enabled = controller != null,
                        onClick = { editing = EditorTarget(null) },
                    )
                }
            }
        }
    }

    val target = editing
    if (target != null) {
        RuleEditorSheet(
            existing = target.rule,
            apps = apps,
            onDismiss = { editing = null },
            onSave = { rule, onResult ->
                if (controller == null) {
                    onResult(NOT_CONNECTED)
                } else {
                    controller.upsertAutomationRule(rule) { result ->
                        onResult(result)
                        if (result.isSuccess) {
                            refreshKey++
                            editing = null
                        }
                    }
                }
            },
        )
    }
}

private data class EditorTarget(val rule: BombAutomationRule?)

@Composable
private fun MasterSwitchCard(
    enabled: Boolean,
    observerRunning: Boolean,
    lastTrigger: String?,
    controllerAvailable: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    BombCard {
        BombSwitchPreference(
            title = "Automation enabled",
            summary = if (enabled) "Rules are evaluated on device signals" else "All rules are paused",
            checked = enabled,
            onCheckedChange = onToggle,
            enabled = controllerAvailable,
        )
        BombRowDivider()
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Signal observer",
                    fontSize = 13.sp,
                    color = BombTheme.miuix.onSurfaceVariantSummary,
                )
                Text(
                    text = lastTrigger?.let { "Last trigger: ${triggerLabel(it)}" } ?: "No trigger yet",
                    modifier = Modifier.padding(top = 2.dp),
                    fontSize = 12.sp,
                    color = BombTheme.miuix.onSurfaceVariantSummary,
                )
            }
            BombBadge(
                text = if (observerRunning) "Running" else "Stopped",
                color = if (observerRunning) BombTheme.colors.ok else BombTheme.miuix.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun RuleRow(
    rule: BombAutomationRule,
    status: V6ApplyState,
    apps: List<LiveApp>?,
    controllerAvailable: Boolean,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val applying = status is V6ApplyState.Applying
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = rule.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = BombTheme.miuix.onSurface,
                )
                Text(
                    text = describeRule(rule, apps),
                    modifier = Modifier.padding(top = 2.dp),
                    fontSize = 12.sp,
                    color = BombTheme.miuix.onSurfaceVariantSummary,
                )
            }
            BombIconButton(
                icon = BombIcons.AppControl,
                contentDescription = "Edit rule",
                onClick = onEdit,
                tint = BombTheme.miuix.onSurfaceVariantActions,
            )
            BombIconButton(
                icon = BombIcons.Stop,
                contentDescription = "Delete rule",
                onClick = onDelete,
                tint = BombTheme.colors.warn,
            )
        }
        BombSwitchPreference(
            title = "Enabled",
            checked = rule.enabled,
            onCheckedChange = onToggle,
            enabled = controllerAvailable && !applying,
        )
        V6ApplyStatusLine(status, modifier = Modifier.padding(start = 16.dp, bottom = 8.dp))
    }
}

// ------------------------------------------------------------------ Editor sheet

@Composable
private fun RuleEditorSheet(
    existing: BombAutomationRule?,
    apps: List<LiveApp>?,
    onDismiss: () -> Unit,
    onSave: (BombAutomationRule, (BombOperationResult) -> Unit) -> Unit,
) {
    val id = remember { existing?.id ?: "rule_${Random.nextInt(100_000, 1_000_000)}" }
    val nameState = rememberTextFieldState(existing?.name ?: "")

    val triggerValues = BombAutomationTrigger.ALL
    var triggerIdx by remember {
        mutableIntStateOf(existing?.trigger?.let { triggerValues.indexOf(it).coerceAtLeast(0) } ?: 0)
    }
    val trigger = triggerValues[triggerIdx]

    val scopeValues = listOf(BombRuleScope.GLOBAL, BombRuleScope.PER_APP, BombRuleScope.SAFETY)
    var scopeIdx by remember {
        mutableIntStateOf(existing?.scope?.let { scopeValues.indexOf(it).coerceAtLeast(0) } ?: 0)
    }
    val scope = scopeValues[scopeIdx]

    var triggerPackage by remember { mutableStateOf(existing?.triggerPackageName) }

    // Optional single condition.
    val existingCondition = existing?.conditions?.firstOrNull()
    var conditionOn by remember { mutableStateOf(existingCondition != null) }
    val conditionTypes = listOf(
        BombAutomationConditionType.SCREEN_IS,
        BombAutomationConditionType.FOREGROUND_PACKAGE_IS,
        BombAutomationConditionType.FOREGROUND_PACKAGE_IS_NOT,
    )
    var conditionTypeIdx by remember {
        mutableIntStateOf(existingCondition?.type?.let { conditionTypes.indexOf(it).coerceAtLeast(0) } ?: 0)
    }
    val conditionType = conditionTypes[conditionTypeIdx]
    var conditionScreenOn by remember {
        mutableStateOf(existingCondition?.takeIf { it.type == BombAutomationConditionType.SCREEN_IS }?.value != BombScreenState.OFF)
    }
    var conditionPackage by remember {
        mutableStateOf(existingCondition?.takeIf { it.type != BombAutomationConditionType.SCREEN_IS }?.value)
    }

    // Single action.
    val existingAction = existing?.actions?.firstOrNull()
    val actionTypes = listOf(BombAutomationActionType.SET_PERFORMANCE_PROFILE, BombAutomationActionType.SET_FREEZE_MODE)
    var actionTypeIdx by remember {
        mutableIntStateOf(existingAction?.type?.let { actionTypes.indexOf(it).coerceAtLeast(0) } ?: 0)
    }
    val actionType = actionTypes[actionTypeIdx]
    var profileIdx by remember {
        mutableIntStateOf(
            existingAction?.takeIf { it.type == BombAutomationActionType.SET_PERFORMANCE_PROFILE }
                ?.value?.let { BombPerformanceProfile.ALL.indexOf(it).coerceAtLeast(0) } ?: 1,
        )
    }
    var freezeIdx by remember {
        mutableIntStateOf(
            existingAction?.takeIf { it.type == BombAutomationActionType.SET_FREEZE_MODE }
                ?.value?.let { BombAutomationFreezeMode.ALL.indexOf(it).coerceAtLeast(0) } ?: 1,
        )
    }
    var actionPackage by remember {
        mutableStateOf(existingAction?.takeIf { it.type == BombAutomationActionType.SET_FREEZE_MODE }?.packageName)
    }

    var restoreOn by remember { mutableStateOf(existing?.restorePolicy == BombRestorePolicy.ON_OPPOSITE_TRIGGER) }

    var submitting by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    val name by remember { derivedStateOf { nameState.text.toString().trim() } }

    val condition = if (conditionOn) {
        when (conditionType) {
            BombAutomationConditionType.SCREEN_IS ->
                BombAutomationCondition(conditionType, if (conditionScreenOn) BombScreenState.ON else BombScreenState.OFF)
            else -> conditionPackage?.let { BombAutomationCondition(conditionType, it) }
        }
    } else {
        null
    }
    val action = when (actionType) {
        BombAutomationActionType.SET_PERFORMANCE_PROFILE ->
            BombAutomationAction(actionType, BombPerformanceProfile.ALL[profileIdx], null, 0)
        else ->
            BombAutomationAction(actionType, BombAutomationFreezeMode.ALL[freezeIdx], actionPackage, 0)
    }
    val rule = BombAutomationRule(
        id = id,
        name = name,
        enabled = existing?.enabled ?: true,
        trigger = trigger,
        triggerPackageName = if (BombAutomationTrigger.isAppTrigger(trigger) || scope == BombRuleScope.PER_APP) triggerPackage else null,
        conditions = listOfNotNull(condition),
        actions = listOf(action),
        scope = scope,
        priority = existing?.priority ?: 0,
        cooldownMillis = existing?.cooldownMillis ?: 30_000L,
        debounceMillis = existing?.debounceMillis ?: 1_000L,
        restorePolicy = if (restoreOn) BombRestorePolicy.ON_OPPOSITE_TRIGGER else BombRestorePolicy.NONE,
    )
    // Additional UI-only completeness checks the bounds mirror does not cover.
    val incomplete = when {
        conditionOn && conditionType != BombAutomationConditionType.SCREEN_IS && conditionPackage == null ->
            "Choose a package for the condition"
        actionType == BombAutomationActionType.SET_FREEZE_MODE && actionPackage == null &&
            !BombAutomationTrigger.isAppTrigger(trigger) ->
            "Choose a package for the freeze action"
        else -> null
    }
    val violation = incomplete ?: BombAutomationBounds.violation(rule)
    val canSave = violation == null && !submitting

    BombBottomSheet(onDismissRequest = onDismiss, title = if (existing == null) "New rule" else "Edit rule") {
        Column(
            modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FieldLabel("Name")
            SheetTextField(nameState, "e.g. Game mode on launch")

            FieldLabel("Trigger")
            BombSegmentedButton(
                options = listOf("App opened", "App closed", "Screen on", "Screen off"),
                selectedIndex = triggerIdx,
                onSelected = { triggerIdx = it },
            )

            FieldLabel("Scope")
            BombSegmentedButton(
                options = listOf("Global", "Per-app", "Safety"),
                selectedIndex = scopeIdx,
                onSelected = { scopeIdx = it },
            )

            if (BombAutomationTrigger.isAppTrigger(trigger) || scope == BombRuleScope.PER_APP) {
                FieldLabel(if (scope == BombRuleScope.PER_APP) "Trigger app (required)" else "Trigger app (any if unset)")
                PackagePicker(
                    apps = apps,
                    selected = triggerPackage,
                    allowAny = scope != BombRuleScope.PER_APP,
                    onSelect = { triggerPackage = it },
                )
            }

            BombSwitchPreference(
                title = "Add a condition",
                summary = "Only run when device state matches",
                checked = conditionOn,
                onCheckedChange = { conditionOn = it },
            )
            if (conditionOn) {
                BombSegmentedButton(
                    options = listOf("Screen is", "Foreground is", "Not foreground"),
                    selectedIndex = conditionTypeIdx,
                    onSelected = { conditionTypeIdx = it },
                )
                if (conditionType == BombAutomationConditionType.SCREEN_IS) {
                    BombSegmentedButton(
                        options = listOf("On", "Off"),
                        selectedIndex = if (conditionScreenOn) 0 else 1,
                        onSelected = { conditionScreenOn = it == 0 },
                    )
                } else {
                    PackagePicker(
                        apps = apps,
                        selected = conditionPackage,
                        allowAny = false,
                        onSelect = { conditionPackage = it },
                    )
                }
            }

            FieldLabel("Action")
            BombSegmentedButton(
                options = listOf("Performance", "Freeze"),
                selectedIndex = actionTypeIdx,
                onSelected = { actionTypeIdx = it },
            )
            if (actionType == BombAutomationActionType.SET_PERFORMANCE_PROFILE) {
                BombSegmentedButton(
                    options = BombPerformanceProfile.ALL.map { it.lowercase().replaceFirstChar(Char::uppercase) },
                    selectedIndex = profileIdx,
                    onSelected = { profileIdx = it },
                )
            } else {
                BombSegmentedButton(
                    options = BombAutomationFreezeMode.ALL.map { it.replace('_', ' ').lowercase().replaceFirstChar(Char::uppercase) },
                    selectedIndex = freezeIdx,
                    onSelected = { freezeIdx = it },
                )
                FieldLabel(if (BombAutomationTrigger.isAppTrigger(trigger)) "Freeze target (trigger app if unset)" else "Freeze target (required)")
                PackagePicker(
                    apps = apps,
                    selected = actionPackage,
                    allowAny = BombAutomationTrigger.isAppTrigger(trigger),
                    onSelect = { actionPackage = it },
                )
            }

            BombSwitchPreference(
                title = "Restore on opposite trigger",
                summary = "Undo the action when the inverse event fires",
                checked = restoreOn,
                onCheckedChange = { restoreOn = it },
            )

            val shownError = errorText ?: violation
            if (shownError != null) {
                Text(text = shownError, fontSize = 12.sp, color = BombTheme.colors.warn)
            }

            Button(
                onClick = {
                    submitting = true
                    errorText = null
                    onSave(rule) { result ->
                        submitting = false
                        if (!result.isSuccess) {
                            errorText = "${result.status}${result.message?.let { ": $it" } ?: ""}"
                        }
                    }
                },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(text = if (submitting) "Saving…" else "Save rule")
            }
        }
    }
}

@Composable
private fun PackagePicker(
    apps: List<LiveApp>?,
    selected: String?,
    allowAny: Boolean,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val searchState = rememberTextFieldState()
    val query by remember { derivedStateOf { searchState.text.toString().lowercase().trim() } }
    val selectedApp = apps?.firstOrNull { it.packageName == selected }

    BombCard {
        BombPreference(
            title = selectedApp?.name ?: selected ?: if (allowAny) "Any app" else "Choose app",
            summary = selected,
            onClick = { expanded = !expanded },
        )
        if (expanded) {
            BombRowDivider()
            if (apps == null) {
                BombEmptyState("Installed apps are unavailable")
            } else {
                SheetTextField(searchState, "Search apps…")
                if (allowAny) {
                    BombPreference(
                        title = "Any app",
                        value = if (selected == null) "✓" else null,
                        onClick = { onSelect(null); expanded = false },
                    )
                }
                val filtered = apps.filter {
                    query.isEmpty() || it.name.lowercase().contains(query) || it.packageName.lowercase().contains(query)
                }.sortedBy { it.name.lowercase() }
                Column(modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                    filtered.forEachIndexed { idx, app ->
                        if (idx > 0) BombRowDivider()
                        BombAppRow(
                            name = app.name,
                            packageName = app.packageName,
                            tint = PreviewData.tintFor(app.packageName),
                            trailing = if (app.packageName == selected) "✓" else null,
                            iconPackage = app.packageName,
                            onClick = { onSelect(app.packageName); expanded = false },
                        )
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ describe / helpers

private fun triggerLabel(trigger: String): String = when (trigger) {
    BombAutomationTrigger.APP_FOREGROUND -> "app opened"
    BombAutomationTrigger.APP_BACKGROUND -> "app closed"
    BombAutomationTrigger.SCREEN_ON -> "screen on"
    BombAutomationTrigger.SCREEN_OFF -> "screen off"
    else -> trigger
}

private fun describeRule(rule: BombAutomationRule, apps: List<LiveApp>?): String {
    fun name(pkg: String?): String = pkg?.let { p -> apps?.firstOrNull { it.packageName == p }?.name ?: p } ?: "any app"
    val triggerText = when (rule.trigger) {
        BombAutomationTrigger.APP_FOREGROUND -> "When ${name(rule.triggerPackageName)} opens"
        BombAutomationTrigger.APP_BACKGROUND -> "When ${name(rule.triggerPackageName)} closes"
        BombAutomationTrigger.SCREEN_ON -> "When screen turns on"
        BombAutomationTrigger.SCREEN_OFF -> "When screen turns off"
        else -> rule.trigger
    }
    val action = rule.actions.firstOrNull()
    val actionText = when (action?.type) {
        BombAutomationActionType.SET_PERFORMANCE_PROFILE -> "set ${action.value.lowercase()} profile"
        BombAutomationActionType.SET_FREEZE_MODE -> "freeze ${name(action.packageName)} (${action.value.replace('_', ' ').lowercase()})"
        else -> "do nothing"
    }
    val extra = if (rule.conditions.isNotEmpty()) " · ${rule.conditions.size} condition(s)" else ""
    return "$triggerText → $actionText$extra"
}

@Composable
private fun FieldLabel(text: String) {
    Text(text = text, fontSize = 13.sp, color = BombTheme.miuix.onSurfaceVariantSummary)
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
private fun InfoCard(text: String, error: Boolean = false) {
    BombCard {
        Text(
            text = text,
            modifier = Modifier.padding(16.dp),
            fontSize = 13.sp,
            color = if (error) BombTheme.colors.critical else BombTheme.miuix.onSurfaceVariantSummary,
        )
    }
}

private val NOT_CONNECTED = BombOperationResult("BACKEND_UNAVAILABLE", "Service is not connected")
