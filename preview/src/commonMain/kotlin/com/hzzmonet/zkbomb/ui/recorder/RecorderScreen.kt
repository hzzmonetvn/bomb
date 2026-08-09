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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hzzmonet.zkbomb.data.VoipCaptureSupport
import com.hzzmonet.zkbomb.data.VoipRecorderHandle
import com.hzzmonet.zkbomb.data.VoipRecording
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
import com.hzzmonet.zkbomb.ui.design.component.BombUnsupportedState
import com.hzzmonet.zkbomb.ui.navigation.BombNavigator
import com.hzzmonet.zkbomb.ui.navigation.BombRoute
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.extra.SuperSwitch

/**
 * Recording formats, with the trade-off spelled out.
 *
 * A codec name alone tells the user nothing. Each entry says what it costs in
 * space and what it costs in compatibility, because those are the two things
 * that actually decide the choice — and a recording made in the wrong format is
 * not something you can fix afterwards.
 */
private data class RecorderFormat(val label: String, val description: String)

private val recorderFormats = listOf(
    RecorderFormat(
        "AAC (m4a)",
        "Plays anywhere without conversion, including on iPhone and in Windows and " +
            "macOS players. This is what Bomb records to today.",
    ),
    RecorderFormat(
        "Opus",
        "Open voice codec. Smallest files, best quality per byte — around a tenth " +
            "the size of WAV. Plays on Android and in browsers; older desktop " +
            "players may need a converter.",
    ),
    RecorderFormat(
        "WAV",
        "Uncompressed. Roughly ten times larger than Opus and grows fast — about " +
            "600 MB an hour. Only worth it if the audio will be processed further.",
    ),
)

// index → retention horizon in days; null is "keep forever".
private val retentionOptions = listOf("7 days", "30 days", "90 days", "Keep forever")
private val retentionDays = listOf(7, 30, 90, null)

private val voipModes = listOf("Ask each call", "Automatic")

fun LazyListScope.recorderContent(
    state: PreviewUiState,
    navigator: BombNavigator,
    recorder: VoipRecorderHandle,
) {
    val rec = recorder.state
    val controller = recorder.controller
    val watched = state.voipWatched.filter { it.value }.keys.toList()
    val automatic = state.recorderVoip >= 2

    // Effects live inside an item so they run in a composable scope. This one
    // reconciles the running service with the user's settings and enforces
    // retention — it never *enables* recording (that needs the explicit switch
    // below, so consent is never triggered by simply opening the screen).
    item {
        val watchedKey = watched.sorted().joinToString(",")
        LaunchedEffect(rec.enabled, watchedKey, automatic) {
            if (rec.enabled) controller.updateConfig(watched, automatic)
        }
        LaunchedEffect(state.recorderRetention, rec.recordings.size) {
            controller.applyRetention(retentionDays[state.recorderRetention])
        }
    }

    // Phone calls are a separate, telephony-side path and are not wired yet;
    // saying so plainly beats a tri-state control that silently does nothing.
    item { BombSectionTitle("Phone calls") }
    item {
        BombUnsupportedState(
            title = "Phone call recording isn't in this build",
            reason = "This is the cellular-call path, separate from VoIP. It is not " +
                "implemented yet. VoIP app calls below are recorded.",
        )
    }

    item { BombSectionTitle("VoIP calls") }

    if (!rec.recordable && !rec.enabled) {
        item {
            BombUnsupportedState(
                title = supportTitle(rec.support),
                reason = rec.supportDetail ?: "Call capture is not available on this build.",
            )
        }
    }

    item {
        BombCard {
            SuperSwitch(
                title = "Record VoIP calls",
                summary = when {
                    rec.enabled -> "Armed — Bomb is watching for calls"
                    else -> "Grants capture consent, then records watched apps"
                },
                checked = rec.enabled,
                enabled = rec.enabled || rec.support == VoipCaptureSupport.SUPPORTED ||
                    rec.support == VoipCaptureSupport.UNPROBED,
                onCheckedChange = { on ->
                    if (on) controller.enable(watched, automatic) else controller.disable()
                },
            )
            BombRowDivider()
            BombPreference(
                title = "Watched apps",
                summary = "Only these apps arm the recorder",
                value = "${watched.size} selected",
                icon = BombIcons.Apps,
                iconTint = BombTheme.miuix.primary,
                onClick = { navigator.push(BombRoute.VoipPicker) },
            )
        }
    }

    if (rec.enabled) {
        item { LiveStatusCard(rec.isRecording, rec.activePackage, onStop = { controller.stopCurrent() }) }

        item { BombSectionTitle("When a call starts") }
        item {
            BombSegmentedButton(
                options = voipModes,
                selectedIndex = if (automatic) 1 else 0,
                onSelected = { state.recorderVoip = if (it == 1) 2 else 1 },
            )
        }
    }

    // An ASK-mode prompt: a watched app is in a call and the user must decide.
    rec.pendingPromptPackage?.let { pkg ->
        item { BombSectionTitle("Record this call?") }
        item {
            CallPromptCard(
                packageName = pkg,
                onAnswer = { record, remember -> controller.answerPrompt(pkg, record, remember) },
            )
        }
    }

    rec.error?.let { message ->
        item { RecorderErrorCard(message, onDismiss = { controller.clearError() }) }
    }

    item { BombSectionTitle("Detection") }
    item { DetectionFlowCard() }

    item { BombSectionTitle("Recording") }
    item {
        BombCard {
            BombPreference(
                title = "Format",
                summary = recorderFormats[state.recorderFormat].description,
                value = recorderFormats[state.recorderFormat].label,
                onClick = {
                    state.recorderFormat = (state.recorderFormat + 1) % recorderFormats.size
                },
            )
            BombRowDivider()
            BombPreference(
                title = "Retention",
                summary = "Recordings older than this are deleted automatically",
                value = retentionOptions[state.recorderRetention],
                onClick = {
                    state.recorderRetention = (state.recorderRetention + 1) % retentionOptions.size
                },
            )
            BombRowDivider()
            BombPreference(
                title = "Storage used",
                summary = "Kept in Bomb's private storage · audio never enters a log",
                value = formatBytes(rec.storageBytes),
            )
        }
    }

    item { BombSectionTitle("Recordings") }
    if (rec.recordings.isEmpty()) {
        item { BombEmptyState("No recordings") }
    } else {
        item {
            BombCard {
                rec.recordings.forEachIndexed { index, recording ->
                    if (index > 0) BombRowDivider()
                    RecordingRow(recording, onDelete = { controller.delete(recording.id) })
                }
            }
        }
    }

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

private fun supportTitle(support: VoipCaptureSupport): String = when (support) {
    VoipCaptureSupport.DENIED -> "Missing a permission for call capture"
    VoipCaptureSupport.SILENT -> "Call audio does not reach the capture path"
    VoipCaptureSupport.UNAVAILABLE -> "No call-capture path on this build"
    VoipCaptureSupport.UNPROBED -> "Checking call-capture support…"
    VoipCaptureSupport.SUPPORTED -> "Call capture available"
}

@Composable
private fun LiveStatusCard(isRecording: Boolean, activePackage: String?, onStop: () -> Unit) {
    BombCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val tint = if (isRecording) BombTheme.colors.critical else BombTheme.colors.ok
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(tint.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                BombIcon(icon = BombIcons.Recorder, tint = tint, modifier = Modifier.size(18.dp))
            }
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    text = if (isRecording) "Recording" else "Watching for calls",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = BombTheme.miuix.onSurface,
                )
                Text(
                    text = if (isRecording) (activePackage ?: "call in progress")
                    else "Nothing is captured until a watched app is in a call",
                    modifier = Modifier.padding(top = 2.dp),
                    fontSize = 12.sp,
                    color = BombTheme.miuix.onSurfaceVariantSummary,
                )
            }
            if (isRecording) {
                TextButton(text = "Stop", onClick = onStop)
            } else {
                BombBadge("ARMED", BombTheme.colors.ok)
            }
        }
    }
}

@Composable
private fun RecordingRow(recording: VoipRecording, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = recording.packageName,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = BombTheme.miuix.onSurface,
                )
                if (recording.silent) {
                    Box(modifier = Modifier.padding(start = 8.dp)) {
                        BombBadge("SILENT", BombTheme.colors.critical)
                    }
                }
            }
            Text(
                text = "${formatDuration(recording.durationMillis)} · ${formatBytes(recording.sizeBytes)}",
                modifier = Modifier.padding(top = 2.dp),
                fontSize = 12.sp,
                color = BombTheme.miuix.onSurfaceVariantSummary,
            )
        }
        TextButton(text = "Delete", onClick = onDelete)
    }
}

@Composable
private fun CallPromptCard(
    packageName: String,
    onAnswer: (record: Boolean, remember: Boolean) -> Unit,
) {
    var rememberChoice by remember { mutableStateOf(false) }
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
                Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(
                        text = "Record this call?",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = BombTheme.miuix.onSurface,
                    )
                    Text(
                        text = "$packageName · call in progress",
                        modifier = Modifier.padding(top = 2.dp),
                        fontSize = 12.sp,
                        color = BombTheme.miuix.onSurfaceVariantSummary,
                    )
                }
                BombBadge("VoIP", BombTheme.colors.accent)
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Remember for $packageName",
                    modifier = Modifier.weight(1f),
                    fontSize = 12.sp,
                    color = BombTheme.miuix.onSurfaceVariantSummary,
                )
                TextButton(
                    text = if (rememberChoice) "On" else "Off",
                    onClick = { rememberChoice = !rememberChoice },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TextButton(
                    text = "Not now",
                    onClick = { onAnswer(false, rememberChoice) },
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = { onAnswer(true, rememberChoice) },
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
        }
    }
}

@Composable
private fun RecorderErrorCard(message: String, onDismiss: () -> Unit) {
    BombCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                fontSize = 13.sp,
                color = BombTheme.colors.critical,
            )
            TextButton(text = "Dismiss", onClick = onDismiss)
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

private fun formatDuration(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> "0 B"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> {
        // One decimal of MB without String.format, which the KMP common stdlib
        // does not provide. Tenths via integer math, then split.
        val tenths = bytes * 10 / (1024L * 1024L)
        "${tenths / 10}.${tenths % 10} MB"
    }
}
