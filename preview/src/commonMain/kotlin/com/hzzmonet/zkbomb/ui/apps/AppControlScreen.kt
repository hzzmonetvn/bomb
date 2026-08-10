package com.hzzmonet.zkbomb.ui.apps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hzzmonet.zkbomb.data.BombCapabilityKeys
import com.hzzmonet.zkbomb.data.BombComponentState
import com.hzzmonet.zkbomb.data.BombPackageComponent
import com.hzzmonet.zkbomb.data.BombServiceState
import com.hzzmonet.zkbomb.data.ComponentActionStatus
import com.hzzmonet.zkbomb.data.LiveApp
import com.hzzmonet.zkbomb.data.PackageInspectorHandle
import com.hzzmonet.zkbomb.data.PackageInspectorState
import com.hzzmonet.zkbomb.data.rememberPackageInspector
import com.hzzmonet.zkbomb.preview.PreviewData
import com.hzzmonet.zkbomb.preview.PreviewUiState
import com.hzzmonet.zkbomb.ui.design.BombIcons
import com.hzzmonet.zkbomb.ui.design.BombTheme
import com.hzzmonet.zkbomb.ui.design.component.BombAppAvatar
import com.hzzmonet.zkbomb.ui.design.component.BombBadge
import com.hzzmonet.zkbomb.ui.design.component.BombCard
import com.hzzmonet.zkbomb.ui.design.component.BombEmptyState
import com.hzzmonet.zkbomb.ui.design.component.BombPreference
import com.hzzmonet.zkbomb.ui.design.component.BombRowDivider
import com.hzzmonet.zkbomb.ui.design.component.BombSectionTitle
import com.hzzmonet.zkbomb.ui.design.component.BombSegmentedButton
import com.hzzmonet.zkbomb.ui.design.component.BombSwitchPreference
import com.hzzmonet.zkbomb.ui.design.component.BombUnsupportedState
import com.hzzmonet.zkbomb.ui.navigation.BombNavigator
import com.hzzmonet.zkbomb.ui.navigation.BombRoute
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton

/**
 * One screen per application.
 *
 * Everything under "Package" is read from PackageManager and is real on a
 * device. Operations that change another app are routed through the selected
 * ROM or root backend and remain protected for critical system packages.
 */
fun LazyListScope.appControlContent(
    state: PreviewUiState,
    packageName: String,
    navigator: BombNavigator,
    apps: List<LiveApp>?,
    service: BombServiceState,
) {
    // Loading vs not-found are distinct: a null list means PackageManager has
    // not answered yet; an answered list without this package means it is gone.
    // Neither is filled in from a sample.
    if (apps == null) {
        item { BombEmptyState("Reading installed packages…") }
        return
    }
    val live = apps.firstOrNull { it.packageName == packageName }
    if (live == null) {
        item { BombEmptyState("Package not found on this device") }
        return
    }

    val name = live.name
    val isSystem = live.isSystem
    val protectedPackage = isSystem && (
        packageName.startsWith("com.android.systemui") ||
            packageName.startsWith("com.miui.home") ||
            packageName.startsWith("com.hzzmonet.zkbomb")
        )
    val canDeepFreeze = service.isSupported(BombCapabilityKeys.DEEP_FREEZE)
    val controller = service.controller

    item {
        LaunchedEffect(packageName, controller) {
            state.appFreezeMode = "UNKNOWN"
            state.appOperationMessage = null
            controller?.getFreezeStatus(packageName) { status ->
                state.appFreezeMode = status?.mode ?: "UNAVAILABLE"
                status?.exclusionReason?.let { state.appOperationMessage = "Protected: $it" }
            }
        }
    }
    item {
        BombCard {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BombAppAvatar(
                    label = name,
                    tint = PreviewData.tintFor(packageName),
                    size = 48.dp,
                    iconPackage = packageName,
                )
                Column(modifier = Modifier.padding(start = 14.dp)) {
                    Text(
                        text = name,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = BombTheme.miuix.onSurface,
                    )
                    Text(
                        text = packageName,
                        modifier = Modifier.padding(top = 3.dp),
                        fontSize = 12.sp,
                        color = BombTheme.miuix.onSurfaceVariantSummary,
                    )
                    Row(
                        modifier = Modifier.padding(top = 7.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val frozen = state.appFreezeMode !in listOf("NORMAL", "UNKNOWN", "UNAVAILABLE")
                        BombBadge(
                            text = when {
                                !live.enabled -> "Disabled"
                                frozen -> "Frozen"
                                else -> "Active"
                            },
                            color = if (frozen) BombTheme.colors.frozen else BombTheme.colors.ok,
                        )
                        if (isSystem) BombBadge("System", BombTheme.miuix.primary)
                        if (protectedPackage) BombBadge("Protected", BombTheme.colors.warn)
                    }
                }
            }
        }
    }

    item { BombSectionTitle("Package") }
    item {
        BombCard {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                InfoLine("Version", live.versionName ?: "—")
                InfoLine("UID", live.uid.toString())
                InfoLine("Target SDK", live.targetSdk.toString())
                live.minSdk?.let { InfoLine("Min SDK", it.toString()) }
                live.permissionCount?.let { InfoLine("Permissions", it.toString()) }
                InfoLine("Enabled", if (live.enabled) "Yes" else "No")
                live.apkPath?.let { InfoLine("APK", it) }
            }
        }
    }

    item { BombSectionTitle("Bomb") }
    item {
        BombCard {
            BombSwitchPreference(
                title = "Managed by Freeze Engine",
                summary = if (protectedPackage) {
                    "Critical package — never frozen"
                } else {
                    "Add this app to the freeze list"
                },
                checked = state.freezeList.containsKey(packageName),
                onCheckedChange = { checked ->
                    if (checked) {
                        state.freezeList[packageName] = "Deep Freeze"
                    } else {
                        state.freezeList.remove(packageName)
                    }
                },
                enabled = !protectedPackage,
            )
            BombRowDivider()
            BombPreference(
                title = "Performance profile",
                value = null,
                icon = BombIcons.Performance,
                iconTint = BombTheme.colors.accent,
                onClick = { navigator.push(BombRoute.Performance) },
            )
        }
    }

    item { BombSectionTitle("Control") }
    item {
        BombCard {
            BombPreference(
                title = "Privacy — App Visibility",
                summary = "Caller-aware package hiding (HMA-style)",
                icon = BombIcons.AppControl,
                iconTint = BombTheme.colors.frozen,
                onClick = { navigator.push(BombRoute.AppVisibility) },
            )
            BombRowDivider()
            BombPreference(
                title = "Privacy — Settings Virtualization",
                summary = "Virtual Android Settings values for this app",
                icon = BombIcons.AppControl,
                iconTint = BombTheme.colors.cpu,
                onClick = { navigator.push(BombRoute.SettingsVirtualization) },
            )
            BombRowDivider()
            BombPreference(
                title = "Network — AdBlock",
                summary = "DNS-based domain blocking rules",
                icon = BombIcons.Network,
                iconTint = BombTheme.colors.warn,
                onClick = { navigator.push(BombRoute.AdBlock) },
            )
            BombRowDivider()
            BombPreference(
                title = "Network — Firewall",
                summary = "Per-app Wi-Fi / Mobile / Background data policy",
                icon = BombIcons.Network,
                iconTint = BombTheme.colors.network,
                onClick = { navigator.push(BombRoute.Firewall) },
            )
        }
    }
    item {
        BombCard {
            BombPreference(
                title = "Freeze now",
                summary = "Force-stop, then suspend this package",
                value = state.appFreezeMode.takeUnless { it == "UNKNOWN" },
                enabled = !protectedPackage && canDeepFreeze && controller != null,
                onClick = {
                    state.appOperationMessage = "Applying…"
                    controller?.setFreezeMode(packageName, "DEEP_FREEZE") { result ->
                        state.appOperationMessage = result.message ?: result.status
                        if (result.isSuccess) state.appFreezeMode = "DEEP_FREEZE"
                    }
                },
            )
            BombRowDivider()
            BombPreference(
                title = "Unfreeze now",
                summary = "Re-enable and unsuspend this package",
                enabled = controller != null && state.appFreezeMode !in listOf("NORMAL", "UNKNOWN", "UNAVAILABLE"),
                onClick = {
                    state.appOperationMessage = "Applying…"
                    controller?.setFreezeMode(packageName, "NORMAL") { result ->
                        state.appOperationMessage = result.message ?: result.status
                        if (result.isSuccess) state.appFreezeMode = "NORMAL"
                    }
                },
            )
            BombRowDivider()
            val canForceStop = service.isSupported(BombCapabilityKeys.PACKAGE_FORCE_STOP)
            BombPreference(
                title = "Force stop",
                summary = when {
                    protectedPackage -> "Critical package — never force-stopped"
                    !canForceStop -> "Unsupported on this build"
                    else -> "End every process of this package now"
                },
                enabled = !protectedPackage && canForceStop && controller != null,
                onClick = {
                    state.appOperationMessage = "Stopping…"
                    controller?.forceStopPackage(packageName) { result ->
                        state.appOperationMessage = result.message ?: result.status
                    }
                },
            )
            BombRowDivider()
            BombPreference(
                title = "Restrict background / clear cache",
                summary = "Unavailable until their typed service APIs are implemented",
                enabled = false,
                onClick = { },
            )
            state.appOperationMessage?.let { message ->
                BombRowDivider()
                Text(
                    text = message,
                    modifier = Modifier.padding(16.dp),
                    fontSize = 12.sp,
                    color = BombTheme.miuix.onSurfaceVariantSummary,
                )
            }
        }
    }

    item { ComponentExplorerSection(service = service, packageName = packageName) }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            modifier = Modifier.weight(0.34f),
            fontSize = 13.sp,
            color = BombTheme.miuix.onSurfaceVariantSummary,
        )
        Text(
            text = value,
            modifier = Modifier.weight(0.66f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = BombTheme.miuix.onSurface,
        )
    }
}

// The three override states the backend accepts, in display order. The index in
// these two lists is the shared key between the segmented control and the value
// sent to setComponentState.
private val componentStateLabels = listOf("Default", "Enabled", "Disabled")
private val componentStateValues = listOf(
    BombComponentState.DEFAULT,
    BombComponentState.ENABLED,
    BombComponentState.DISABLED,
)

/**
 * Manifest component control for this package, expanded on demand.
 *
 * Collapsed by default: the full snapshot (every component, its permissions and
 * override) is only fetched when the user opens the section, not on every visit
 * to App Control.
 */
@Composable
private fun ComponentExplorerSection(service: BombServiceState, packageName: String) {
    var expanded by remember { mutableStateOf(false) }
    val inspector = rememberPackageInspector(service, packageName, enabled = expanded)

    // Wrapped in a Column: this composable is one LazyColumn item, and the title
    // and card are two siblings that must stack rather than overlap.
    Column {
        BombSectionTitle("Components")
        BombCard {
            BombPreference(
                title = "Manifest components",
                summary = "Enable or disable activities, services, receivers and providers",
                value = if (expanded) "Hide" else "Show",
                icon = BombIcons.AppControl,
                iconTint = BombTheme.colors.frozen,
                onClick = { expanded = !expanded },
            )
            if (expanded) {
                BombRowDivider()
                ComponentExplorerBody(inspector)
            }
        }
    }
}

@Composable
private fun ComponentExplorerBody(inspector: PackageInspectorHandle) {
    when (val state = inspector.state) {
        PackageInspectorState.Loading ->
            ComponentBodyText("Reading components from the privileged service…")

        is PackageInspectorState.Unsupported ->
            ComponentBodyText(state.reason)

        PackageInspectorState.Empty ->
            ComponentBodyText("This package exposes no components.")

        is PackageInspectorState.Error ->
            Column {
                ComponentBodyText(state.message, error = true)
                TextButton(
                    text = "Retry",
                    onClick = inspector.refresh,
                    modifier = Modifier.padding(start = 12.dp, bottom = 12.dp),
                )
            }

        is PackageInspectorState.Ready -> {
            val snapshot = state.snapshot
            Column {
                when {
                    snapshot.protectionReason != null -> ComponentBodyText(
                        "Protected: ${snapshot.protectionReason}. Components are read-only.",
                    )
                    !inspector.canMutate -> ComponentBodyText(
                        "Component control is not available on this build — showing current state only.",
                    )
                }
                if (snapshot.componentsTruncated) {
                    ComponentBodyText(
                        "Showing ${snapshot.components.size} of ${snapshot.totalComponentCount} components.",
                    )
                }
                snapshot.components.forEachIndexed { index, component ->
                    if (index > 0) BombRowDivider()
                    ComponentRow(
                        component = component,
                        status = inspector.itemStatus[component.className] ?: ComponentActionStatus.Idle,
                        canMutate = inspector.canMutate,
                        onSet = { target -> inspector.setComponent(component.className, target) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ComponentRow(
    component: BombPackageComponent,
    status: ComponentActionStatus,
    canMutate: Boolean,
    onSet: (String) -> Unit,
) {
    val selectedIndex = componentStateValues.indexOf(component.overrideState).coerceAtLeast(0)
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = component.className.substringAfterLast('.'),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = BombTheme.miuix.onSurface,
                )
                Text(
                    text = component.className,
                    modifier = Modifier.padding(top = 2.dp),
                    fontSize = 11.sp,
                    color = BombTheme.miuix.onSurfaceVariantSummary,
                )
            }
            BombBadge(component.kind, BombTheme.miuix.primary)
        }

        if (canMutate) {
            BombSegmentedButton(
                options = componentStateLabels,
                selectedIndex = selectedIndex,
                onSelected = { index ->
                    val target = componentStateValues[index]
                    if (target != component.overrideState) onSet(target)
                },
                modifier = Modifier.padding(top = 8.dp),
            )
        } else {
            Text(
                text = "Override: ${componentStateLabels.getOrElse(selectedIndex) { "Default" }}",
                modifier = Modifier.padding(top = 8.dp),
                fontSize = 12.sp,
                color = BombTheme.miuix.onSurfaceVariantSummary,
            )
        }

        when (status) {
            ComponentActionStatus.Applying -> Text(
                text = "Applying…",
                modifier = Modifier.padding(top = 6.dp),
                fontSize = 12.sp,
                color = BombTheme.miuix.onSurfaceVariantSummary,
            )
            is ComponentActionStatus.Failed -> Text(
                text = status.message,
                modifier = Modifier.padding(top = 6.dp),
                fontSize = 12.sp,
                color = BombTheme.colors.critical,
            )
            ComponentActionStatus.Idle -> Unit
        }
    }
}

@Composable
private fun ComponentBodyText(text: String, error: Boolean = false) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        fontSize = 13.sp,
        color = if (error) BombTheme.colors.critical else BombTheme.miuix.onSurfaceVariantSummary,
    )
}
