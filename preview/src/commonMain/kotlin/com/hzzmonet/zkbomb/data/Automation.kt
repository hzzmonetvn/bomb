package com.hzzmonet.zkbomb.data

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * The state of the automation snapshot fetch.
 *
 * Unlike the write-only v6 surfaces, automation is readable, so [Ready] carries
 * the real rule set, the master switch and the last trigger — the UI mirrors the
 * service instead of guessing.
 */
sealed interface AutomationUiState {
    data object Loading : AutomationUiState
    data class Unsupported(val reason: String) : AutomationUiState
    data class Error(val message: String) : AutomationUiState
    data class Ready(val snapshot: BombAutomationSnapshot) : AutomationUiState
}

/**
 * Client-side mirror of the engine's `AutomationRuleValidator` bounds (v8).
 *
 * The service is the authority — `upsertAutomationRule` returns a
 * [BombOperationResult] the UI shows verbatim — but the UI disables Save on a rule
 * it can already see is invalid rather than firing a doomed write.
 */
object BombAutomationBounds {
    const val MIN_VERSION = 8
    const val MAX_ID_LENGTH = 128
    const val MAX_NAME_LENGTH = 256
    const val MAX_PRIORITY = 1_000
    const val MAX_COOLDOWN_MILLIS = 86_400_000L
    const val MAX_DEBOUNCE_MILLIS = 60_000L
    const val MAX_CONDITIONS = 16
    const val MAX_ACTIONS = 16

    fun isValidId(id: String): Boolean =
        id.isNotEmpty() && id.length <= MAX_ID_LENGTH &&
            id.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '_' || it == '-' }

    fun isValidName(name: String): Boolean =
        name.isNotBlank() && name.length <= MAX_NAME_LENGTH

    /**
     * Reason the composed rule would be rejected by the engine, or null when it is
     * acceptable. Mirrors the parts of `AutomationRuleValidator` the UI can trip.
     */
    fun violation(rule: BombAutomationRule): String? {
        if (!isValidId(rule.id)) return "Rule id must be 1–$MAX_ID_LENGTH of A–Z, 0–9, . _ -"
        if (!isValidName(rule.name)) return "Rule name must be 1–$MAX_NAME_LENGTH characters"
        if (rule.priority !in 0..MAX_PRIORITY) return "Priority must be 0–$MAX_PRIORITY"
        if (rule.cooldownMillis !in 0..MAX_COOLDOWN_MILLIS) return "Cooldown out of range"
        if (rule.debounceMillis !in 0..MAX_DEBOUNCE_MILLIS) return "Debounce out of range"
        if (rule.actions.isEmpty() || rule.actions.size > MAX_ACTIONS) return "A rule needs 1–$MAX_ACTIONS actions"
        if (rule.conditions.size > MAX_CONDITIONS) return "Too many conditions"
        if (rule.scope == BombRuleScope.PER_APP && rule.triggerPackageName == null) {
            return "Per-app scope needs a trigger package"
        }
        rule.actions.forEach { action ->
            if (action.type == BombAutomationActionType.SET_FREEZE_MODE) {
                if (action.userId !in 0..9_999) return "Action user id out of range"
                if (action.value == "DISABLED") return "Automation may not disable applications"
                val screenTrigger = !BombAutomationTrigger.isAppTrigger(rule.trigger)
                if (action.packageName == null && screenTrigger) {
                    return "A screen-triggered freeze needs a target package"
                }
            }
        }
        return null
    }
}

/**
 * Fetches the automation snapshot once on entry and again whenever [refreshKey]
 * changes — the screen bumps it after enabling, upserting or deleting a rule so
 * the list reflects the write. There is no continuous poll: nothing here changes
 * without a user action, so polling would sample an idle service for nothing.
 */
@Composable
fun rememberAutomation(service: BombServiceState, refreshKey: Int = 0): AutomationUiState {
    val controller = service.controller
    val apiVersion = service.apiVersion ?: 0
    var loaded by remember { mutableStateOf(false) }
    var raw by remember { mutableStateOf<BombAutomationSnapshot?>(null) }

    LaunchedEffect(controller, apiVersion, refreshKey) {
        loaded = false
        raw = null
        if (apiVersion < BombAutomationBounds.MIN_VERSION || controller == null) return@LaunchedEffect
        raw = controller.awaitAutomation()
        loaded = true
    }

    val snapshot = raw
    return when {
        apiVersion < BombAutomationBounds.MIN_VERSION ->
            AutomationUiState.Unsupported("The connected service is older than v${BombAutomationBounds.MIN_VERSION}.")
        controller == null -> AutomationUiState.Error("Bomb's privileged service is not reachable.")
        !loaded -> AutomationUiState.Loading
        snapshot == null -> AutomationUiState.Error("The automation snapshot call failed.")
        else -> AutomationUiState.Ready(snapshot)
    }
}

private suspend fun BombServiceController.awaitAutomation(): BombAutomationSnapshot? =
    suspendCancellableCoroutine { continuation ->
        getAutomationRules { result -> if (continuation.isActive) continuation.resume(result) }
    }
