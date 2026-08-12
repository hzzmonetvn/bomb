package com.hzzmonet.zkbomb.ui.firewall

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
import com.hzzmonet.zkbomb.data.BombNetworkAccess
import com.hzzmonet.zkbomb.data.BombOperationResult
import com.hzzmonet.zkbomb.data.BombServiceState
import com.hzzmonet.zkbomb.data.BombV6Validation
import com.hzzmonet.zkbomb.data.LiveApp
import com.hzzmonet.zkbomb.data.V6ApplyState
import com.hzzmonet.zkbomb.preview.PreviewData
import com.hzzmonet.zkbomb.ui.common.V6ApplyStatusLine
import com.hzzmonet.zkbomb.ui.design.BombIcon
import com.hzzmonet.zkbomb.ui.design.BombIcons
import com.hzzmonet.zkbomb.ui.design.BombTheme
import com.hzzmonet.zkbomb.ui.design.component.BombAppAvatar
import com.hzzmonet.zkbomb.ui.design.component.BombBadge
import com.hzzmonet.zkbomb.ui.design.component.BombCard
import com.hzzmonet.zkbomb.ui.design.component.BombEmptyState
import com.hzzmonet.zkbomb.ui.design.component.BombIconButton
import com.hzzmonet.zkbomb.ui.design.component.BombRowDivider
import com.hzzmonet.zkbomb.ui.design.component.BombSegmentedButton
import com.hzzmonet.zkbomb.ui.design.component.BombUnsupportedState
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text

/**
 * Firewall screen (BOMB_PLAN.md §18).
 *
 * Per-app/UID network policy: Wi-Fi, mobile and background access set to ALLOW or
 * DENY and pushed to the service's network-policy engine via [setFirewallRule].
 * Does not inspect packet payloads or credentials — it is a UID-level rule only.
 *
 * The v6 contract is write-only: there is no getter for the enforced policy, so
 * this screen is an **apply console**. It shows the policy the user is composing
 * and, once applied, the service's own result — never a fabricated "current"
 * state. Requires [BombCapabilityKeys.FIREWALL] = SUPPORTED.
 */
fun LazyListScope.firewallContent(
    service: BombServiceState,
    apps: List<LiveApp>?,
) {
    if (!service.isSupported(BombCapabilityKeys.FIREWALL)) {
        item {
            BombUnsupportedState(
                title = "Firewall unavailable",
                reason = "Per-app network policy is reported as " +
                    "${service.stateOf(BombCapabilityKeys.FIREWALL)} on this install. " +
                    "This uses UID-level network rules; no packet payload inspection is performed.",
            )
        }
        return
    }

    item { FirewallIntroCard() }

    when {
        apps == null -> item {
            InfoCard("Reading installed apps from the platform…")
        }
        else -> {
            val ruleable = apps
                .filter { BombV6Validation.isApplicationUid(it.uid) }
                .sortedBy { it.name.lowercase() }
            if (ruleable.isEmpty()) {
                item { BombEmptyState("No apps with their own UID were found") }
            } else {
                item { FirewallConsole(service, ruleable) }
            }
        }
    }
}

@Composable
private fun FirewallIntroCard() {
    BombCard {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = "About the firewall",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = BombTheme.miuix.onSurface,
            )
            Text(
                text = "Compose a per-app network policy and apply it. Bomb cannot read the " +
                    "enforced firewall state back from the service, so each row shows the " +
                    "policy you are about to apply and the service's result — not a live readout. " +
                    "Only apps that own their UID can be ruled.",
                modifier = Modifier.padding(top = 4.dp),
                fontSize = 12.sp,
                color = BombTheme.miuix.onSurfaceVariantSummary,
            )
        }
    }
}

/** Session draft policy for one app; defaults to all-allowed (the platform default). */
private data class FirewallDraft(
    val wifi: String = BombNetworkAccess.ALLOW,
    val mobile: String = BombNetworkAccess.ALLOW,
    val background: String = BombNetworkAccess.ALLOW,
) {
    val anyDenied: Boolean
        get() = wifi == BombNetworkAccess.DENY ||
            mobile == BombNetworkAccess.DENY ||
            background == BombNetworkAccess.DENY
}

@Composable
private fun FirewallConsole(service: BombServiceState, apps: List<LiveApp>) {
    val controller = service.controller
    val searchState = rememberTextFieldState()
    val query by remember { derivedStateOf { searchState.text.toString().lowercase().trim() } }

    // Session-scoped: the composed drafts and the last apply result per package.
    val drafts = remember { mutableStateMapOf<String, FirewallDraft>() }
    val status = remember { mutableStateMapOf<String, V6ApplyState>() }

    val filtered by remember(apps, query) {
        derivedStateOf {
            if (query.isEmpty()) apps
            else apps.filter {
                it.name.lowercase().contains(query) || it.packageName.lowercase().contains(query)
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BombCard {
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

        if (filtered.isEmpty()) {
            BombEmptyState("No results for \"$query\"")
        } else {
            BombCard {
                filtered.forEachIndexed { idx, app ->
                    key(app.packageName) {
                    if (idx > 0) BombRowDivider()
                    FirewallAppRow(
                        app = app,
                        draft = drafts[app.packageName] ?: FirewallDraft(),
                        status = status[app.packageName] ?: V6ApplyState.Idle,
                        controllerAvailable = controller != null,
                        onDraftChange = { drafts[app.packageName] = it },
                        onApply = {
                            val draft = drafts[app.packageName] ?: FirewallDraft()
                            status[app.packageName] = V6ApplyState.Applying
                            if (controller == null) {
                                status[app.packageName] = V6ApplyState.Done(NOT_CONNECTED)
                            } else {
                                controller.setFirewallRule(
                                    uid = app.uid,
                                    wifiAccess = draft.wifi,
                                    mobileAccess = draft.mobile,
                                    backgroundAccess = draft.background,
                                    note = null,
                                ) { result -> status[app.packageName] = V6ApplyState.Done(result) }
                            }
                        },
                        onReset = {
                            status[app.packageName] = V6ApplyState.Applying
                            if (controller == null) {
                                status[app.packageName] = V6ApplyState.Done(NOT_CONNECTED)
                            } else {
                                controller.clearFirewallRule(app.uid) { result ->
                                    if (result.isSuccess) drafts.remove(app.packageName)
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
}

@Composable
private fun FirewallAppRow(
    app: LiveApp,
    draft: FirewallDraft,
    status: V6ApplyState,
    controllerAvailable: Boolean,
    onDraftChange: (FirewallDraft) -> Unit,
    onApply: () -> Unit,
    onReset: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val applying = status is V6ApplyState.Applying

    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BombAppAvatar(
                label = app.name,
                tint = PreviewData.tintFor(app.packageName),
                size = 38.dp,
                iconPackage = app.packageName,
            )
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    text = app.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = BombTheme.miuix.onSurface,
                )
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    AccessChip("Wi-Fi", draft.wifi)
                    AccessChip("Mobile", draft.mobile)
                    AccessChip("BG", draft.background)
                }
            }
            BombIconButton(
                icon = BombIcons.Chevron,
                contentDescription = if (expanded) "Collapse" else "Expand",
                onClick = { expanded = !expanded },
                tint = BombTheme.miuix.onSurfaceVariantActions,
            )
        }

        if (expanded) {
            BombRowDivider()
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "${app.packageName} · uid ${app.uid}",
                    fontSize = 11.sp,
                    color = BombTheme.miuix.onSurfaceVariantSummary,
                )
                AccessControl("Wi-Fi", draft.wifi) { onDraftChange(draft.copy(wifi = it)) }
                AccessControl("Mobile data", draft.mobile) { onDraftChange(draft.copy(mobile = it)) }
                AccessControl("Background data", draft.background) { onDraftChange(draft.copy(background = it)) }

                V6ApplyStatusLine(status)

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onApply,
                        enabled = controllerAvailable && !applying,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) {
                        Text(text = if (applying) "Applying…" else "Apply policy")
                    }
                    Button(
                        onClick = onReset,
                        enabled = controllerAvailable && !applying && draft.anyDenied,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(),
                    ) {
                        Text(text = "Reset to allow")
                    }
                }
            }
        }
    }
}

@Composable
private fun AccessControl(label: String, access: String, onChange: (String) -> Unit) {
    Column {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = BombTheme.miuix.onSurface,
        )
        BombSegmentedButton(
            options = listOf("Allow", "Deny"),
            selectedIndex = if (access == BombNetworkAccess.DENY) 1 else 0,
            onSelected = { onChange(if (it == 1) BombNetworkAccess.DENY else BombNetworkAccess.ALLOW) },
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun AccessChip(label: String, access: String) {
    val allowed = access == BombNetworkAccess.ALLOW
    BombBadge(
        text = if (allowed) label else "No $label",
        color = if (allowed) BombTheme.colors.ok else BombTheme.colors.warn,
    )
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
