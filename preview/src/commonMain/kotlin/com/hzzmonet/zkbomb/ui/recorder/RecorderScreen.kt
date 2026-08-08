package com.hzzmonet.zkbomb.ui.recorder

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hzzmonet.zkbomb.preview.PreviewData
import com.hzzmonet.zkbomb.preview.PreviewUiState
import com.hzzmonet.zkbomb.ui.design.BombIcon
import com.hzzmonet.zkbomb.ui.design.BombIcons
import com.hzzmonet.zkbomb.ui.design.BombTheme
import com.hzzmonet.zkbomb.ui.design.component.BombBadge
import com.hzzmonet.zkbomb.ui.design.component.BombCard
import com.hzzmonet.zkbomb.ui.design.component.BombEmptyState
import com.hzzmonet.zkbomb.ui.design.component.BombPreference
import com.hzzmonet.zkbomb.ui.design.component.BombRowDivider
import com.hzzmonet.zkbomb.ui.design.component.BombSectionTitle
import com.hzzmonet.zkbomb.ui.design.component.BombSegmentedButton
import com.hzzmonet.zkbomb.ui.navigation.BombNavigator
import com.hzzmonet.zkbomb.ui.navigation.BombRoute
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton

private val modes = listOf("Off", "Ask", "Automatic")

fun LazyListScope.recorderContent(state: PreviewUiState, navigator: BombNavigator) {
    item { BombSectionTitle("Phone calls") }
    item {
        BombSegmentedButton(
            options = modes,
            selectedIndex = state.recorderPhone,
            onSelected = { state.recorderPhone = it },
        )
    }

    item { BombSectionTitle("VoIP apps") }
    item {
        BombCard {
            BombPreference(
                title = "Watched apps",
                summary = "Only these apps arm the recorder",
                value = "${state.voipWatched.count { it.value }} selected",
                icon = BombIcons.Apps,
                iconTint = BombTheme.miuix.primary,
                onClick = { navigator.push(BombRoute.VoipPicker) },
            )
            BombRowDivider()
            BombPreference(
                title = "When a call starts",
                summary = "Ask each time, or start without prompting",
                value = modes[state.recorderVoip],
                onClick = { state.recorderVoip = (state.recorderVoip + 1) % modes.size },
            )
        }
    }

    item { BombSectionTitle("Detection") }
    item { DetectionFlowCard() }

    if (state.recorderVoip == 1) {
        item { BombSectionTitle("Prompt preview") }
        item { AskPromptPreview() }
    }

    item { BombSectionTitle("Recording") }
    item {
        BombCard {
            BombPreference(title = "Format", value = "Opus", onClick = {})
            BombRowDivider()
            BombPreference(title = "Retention", value = "30 days", onClick = {})
            BombRowDivider()
            BombPreference(
                title = "Storage",
                summary = "Encrypted metadata · audio kept out of logs",
                value = "0 B",
                onClick = {},
            )
            BombRowDivider()
            BombPreference(
                title = "Recording indicator",
                summary = "Always visible while capture is active",
                value = "On",
                enabled = false,
                onClick = {},
            )
        }
    }

    item { BombSectionTitle("Recordings") }
    item { BombEmptyState("No recordings") }

    item {
        BombCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Call recording is regulated differently across regions. " +
                        "Bomb keeps the indicator visible, the mode explicit and the " +
                        "per-app policy under the user's control.",
                    fontSize = 12.sp,
                    color = BombTheme.miuix.onSurfaceVariantSummary,
                )
            }
        }
    }
}

/**
 * The trigger chain, spelled out. It matters that this is narrow: the recorder
 * is not listening in the background — it arms only at step 3.
 */
@Composable
private fun DetectionFlowCard() {
    BombCard {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FlowStep(1, "A watched app comes to the foreground", "Nothing is armed before this")
            FlowStep(2, "Audio mode reports an active call", "MODE_IN_COMMUNICATION, not a guess")
            FlowStep(3, "Bomb asks", "One prompt, per call — or records directly on Automatic")
            FlowStep(4, "Indicator stays visible", "For the whole recording, no silent capture")
        }
    }
}

@Composable
private fun FlowStep(number: Int, title: String, detail: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(21.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(BombTheme.colors.accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = number.toString(),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = BombTheme.colors.accent,
            )
        }
        Column(modifier = Modifier.padding(start = 11.dp)) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = BombTheme.miuix.onSurface,
            )
            Text(
                text = detail,
                modifier = Modifier.padding(top = 2.dp),
                fontSize = 12.sp,
                color = BombTheme.miuix.onSurfaceVariantSummary,
            )
        }
    }
}

/** What the user actually sees when a watched app starts a call. */
@Composable
private fun AskPromptPreview() {
    val firstWatched = PreviewData.voipApps.firstOrNull { it.captureSupported }?.name ?: "Zalo"
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(BombTheme.miuix.surfaceContainerHigh)
            .padding(18.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(BombTheme.colors.critical.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    BombIcon(
                        icon = BombIcons.Recorder,
                        tint = BombTheme.colors.critical,
                        modifier = Modifier.size(19.dp),
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                ) {
                    Text(
                        text = "Record this call?",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = BombTheme.miuix.onSurface,
                    )
                    Text(
                        text = "$firstWatched · call in progress",
                        modifier = Modifier.padding(top = 2.dp),
                        fontSize = 12.sp,
                        color = BombTheme.miuix.onSurfaceVariantSummary,
                    )
                }
                BombBadge("VoIP", BombTheme.colors.accent)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TextButton(
                    text = "Not now",
                    onClick = {},
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {},
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = "Record",
                        color = BombTheme.miuix.onPrimary,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            Text(
                text = "Remembering the answer is per app, and can be cleared at any time.",
                modifier = Modifier.padding(top = 12.dp),
                fontSize = 11.sp,
                color = BombTheme.miuix.onSurfaceVariantSummary,
            )
        }
    }
}
