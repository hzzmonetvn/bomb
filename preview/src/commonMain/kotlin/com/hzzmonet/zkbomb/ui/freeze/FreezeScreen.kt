package com.hzzmonet.zkbomb.ui.freeze

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hzzmonet.zkbomb.preview.PreviewData
import com.hzzmonet.zkbomb.preview.PreviewUiState
import com.hzzmonet.zkbomb.ui.design.BombIcons
import com.hzzmonet.zkbomb.ui.design.BombTheme
import com.hzzmonet.zkbomb.ui.design.component.BombAppRow
import com.hzzmonet.zkbomb.ui.design.component.BombCard
import com.hzzmonet.zkbomb.ui.design.component.BombEmptyState
import com.hzzmonet.zkbomb.ui.design.component.BombPreference
import com.hzzmonet.zkbomb.ui.design.component.BombRowDivider
import com.hzzmonet.zkbomb.ui.design.component.BombSectionTitle
import com.hzzmonet.zkbomb.ui.design.component.BombSliderPreference
import com.hzzmonet.zkbomb.ui.design.component.BombSwitchPreference
import com.hzzmonet.zkbomb.ui.navigation.BombNavigator
import com.hzzmonet.zkbomb.ui.navigation.BombRoute
import top.yukonga.miuix.kmp.basic.Text

fun LazyListScope.freezeContent(state: PreviewUiState, navigator: BombNavigator) {
    item { BombSectionTitle("Policy") }
    item {
        BombCard {
            BombSwitchPreference(
                title = "Auto freeze",
                summary = "Freeze eligible apps after they leave the foreground",
                checked = state.autoFreeze,
                onCheckedChange = { state.autoFreeze = it },
            )
            BombRowDivider()
            BombSliderPreference(
                title = "Delay",
                value = state.freezeDelay,
                onValueChange = { state.freezeDelay = it },
                valueLabel = "${state.freezeDelay.toInt()} min",
                valueRange = 1f..30f,
                steps = 28,
            )
            BombRowDivider()
            BombSwitchPreference(
                title = "Unfreeze on user launch",
                summary = "Thaw automatically when the user opens the app",
                checked = state.unfreezeOnLaunch,
                onCheckedChange = { state.unfreezeOnLaunch = it },
            )
            BombRowDivider()
            BombSwitchPreference(
                title = "Freeze on screen off",
                checked = state.freezeOnScreenOff,
                onCheckedChange = { state.freezeOnScreenOff = it },
            )
        }
    }

    item { BombSectionTitle("Backend") }
    item {
        BombCard {
            BombPreference(title = "Soft freeze", value = "Supported", onClick = {})
            BombRowDivider()
            BombPreference(
                title = "Deep freeze",
                summary = "Backend resolved at runtime from platform capability",
                value = "Supported",
                onClick = {},
            )
        }
    }

    val managed = PreviewData.apps.filter { state.freezeList.containsKey(it.packageName) }
    item { BombSectionTitle("Managed apps · ${managed.size}") }
    item {
        BombCard {
            BombPreference(
                title = "Add apps",
                summary = "Choose which packages the freeze engine manages",
                icon = BombIcons.Apps,
                iconTint = BombTheme.miuix.primary,
                onClick = { navigator.push(BombRoute.FreezePicker) },
            )
            managed.forEach { app ->
                BombRowDivider()
                BombAppRow(
                    name = app.name,
                    packageName = app.packageName,
                    tint = PreviewData.tintFor(app.packageName),
                    trailing = state.freezeList[app.packageName],
                    onClick = { navigator.push(BombRoute.AppControl(app.packageName)) },
                )
            }
            if (managed.isEmpty()) {
                BombRowDivider()
                BombEmptyState("No apps selected yet")
            }
        }
    }

    item { BombSectionTitle("Exclusions") }
    item {
        BombCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Never frozen",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = BombTheme.miuix.onSurface,
                )
                Text(
                    text = "SystemUI · Launcher · Active keyboard · Bomb · required " +
                        "framework processes · vendor exclusion list",
                    modifier = Modifier.padding(top = 6.dp),
                    fontSize = 13.sp,
                    color = BombTheme.miuix.onSurfaceVariantSummary,
                )
                Text(
                    text = "Enforced in the policy engine, not in the UI — a rule or a " +
                        "mis-typed package cannot get past it.",
                    modifier = Modifier.padding(top = 8.dp),
                    fontSize = 12.sp,
                    color = BombTheme.colors.ok,
                )
            }
        }
    }
}
