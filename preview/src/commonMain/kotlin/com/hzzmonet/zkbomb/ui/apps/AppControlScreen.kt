package com.hzzmonet.zkbomb.ui.apps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hzzmonet.zkbomb.data.LiveApp
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
import com.hzzmonet.zkbomb.ui.design.component.BombSwitchPreference
import com.hzzmonet.zkbomb.ui.design.component.BombUnsupportedState
import com.hzzmonet.zkbomb.ui.navigation.BombNavigator
import com.hzzmonet.zkbomb.ui.navigation.BombRoute
import top.yukonga.miuix.kmp.basic.Text

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
) {
    val live = apps?.firstOrNull { it.packageName == packageName }
    val sample = PreviewData.apps.firstOrNull { it.packageName == packageName }
    if (live == null && sample == null) {
        item { BombEmptyState("Package not found") }
        return
    }

    val name = live?.name ?: sample!!.name
    val isSystem = live?.isSystem ?: sample!!.system
    val protectedPackage = isSystem && (
        packageName.startsWith("com.android.systemui") ||
            packageName.startsWith("com.miui.home") ||
            packageName.startsWith("com.hzzmonet.zkbomb")
        )
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
                    iconPackage = if (live != null) packageName else null,
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
                        val frozen = state.freezeList.containsKey(packageName)
                        BombBadge(
                            text = when {
                                live != null && !live.enabled -> "Disabled"
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

    if (live != null) {
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
                value = sample?.profile ?: "Default",
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
                title = "Force stop",
                enabled = !protectedPackage,
                onClick = {},
            )
            BombRowDivider()
            BombPreference(
                title = "Restrict background",
                enabled = !protectedPackage,
                onClick = {},
            )
            BombRowDivider()
            BombPreference(
                title = "Clear cache",
                onClick = {},
            )
            BombRowDivider()
            BombSwitchPreference(
                title = "Advanced mode",
                summary = "Exposes component-level controls",
                checked = state.advancedMode,
                onCheckedChange = { state.advancedMode = it },
            )
        }
    }
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
