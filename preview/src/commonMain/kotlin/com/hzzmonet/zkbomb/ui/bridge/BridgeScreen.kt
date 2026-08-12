package com.hzzmonet.zkbomb.ui.bridge

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
import com.hzzmonet.zkbomb.data.BombBridgeRenderer
import com.hzzmonet.zkbomb.data.BombBridgeStatus
import com.hzzmonet.zkbomb.data.BombLiveEvent
import com.hzzmonet.zkbomb.data.BombLiveEventBounds
import com.hzzmonet.zkbomb.data.BombLiveEventState
import com.hzzmonet.zkbomb.data.BombLiveEventType
import com.hzzmonet.zkbomb.data.BombOperationResult
import com.hzzmonet.zkbomb.data.BombServiceState
import com.hzzmonet.zkbomb.data.BridgeUiState
import com.hzzmonet.zkbomb.data.V6ApplyState
import com.hzzmonet.zkbomb.data.rememberBridge
import com.hzzmonet.zkbomb.preview.PreviewUiState
import com.hzzmonet.zkbomb.ui.common.V6ApplyStatusLine
import com.hzzmonet.zkbomb.ui.design.BombIcons
import com.hzzmonet.zkbomb.ui.design.BombTheme
import com.hzzmonet.zkbomb.ui.design.component.BombBadge
import com.hzzmonet.zkbomb.ui.design.component.BombBottomSheet
import com.hzzmonet.zkbomb.ui.design.component.BombCard
import com.hzzmonet.zkbomb.ui.design.component.BombEmptyState
import com.hzzmonet.zkbomb.ui.design.component.BombIconButton
import com.hzzmonet.zkbomb.ui.design.component.BombPreference
import com.hzzmonet.zkbomb.ui.design.component.BombRowDivider
import com.hzzmonet.zkbomb.ui.design.component.BombSectionTitle
import com.hzzmonet.zkbomb.ui.design.component.BombSegmentedButton
import com.hzzmonet.zkbomb.ui.design.component.BombSliderPreference
import com.hzzmonet.zkbomb.ui.design.component.BombSwitchPreference
import com.hzzmonet.zkbomb.ui.design.component.BombUnsupportedState
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import kotlin.random.Random

/**
 * Bomb Bridge — Live Updates / HyperIsland (BOMB_PLAN.md §14).
 *
 * One normalised live-event model is routed to whichever renderer the device
 * actually supports: HyperOS live-update, HyperIsland, or a guaranteed
 * notification fallback. The bridge is readable (v10), so this screen shows the
 * real renderer availability probed on the device and the events currently live;
 * publishing and dismissing go through the service, which revalidates every event
 * and returns a result shown verbatim.
 */
fun LazyListScope.bridgeContent(state: PreviewUiState, service: BombServiceState) {
    item { IntroCard() }
    item { BridgeConsole(state, service) }
}

@Composable
private fun IntroCard() {
    BombCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "One event model, many renderers",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = BombTheme.miuix.onSurface,
            )
            Text(
                text = "Sources are normalised into a single live event, then handed to the best " +
                    "renderer the device supports. A notification is always the fallback, so an " +
                    "event never silently vanishes.",
                modifier = Modifier.padding(top = 6.dp),
                fontSize = 12.sp,
                color = BombTheme.miuix.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun BridgeConsole(state: PreviewUiState, service: BombServiceState) {
    val controller = service.controller
    var refreshKey by remember { mutableIntStateOf(0) }
    val uiState = rememberBridge(
        service = service,
        active = true,
        intervalMillis = state.samplingIntervalMillis,
        refreshKey = refreshKey,
    )
    var composing by remember { mutableStateOf(false) }
    val eventStatus = remember { mutableStateMapOf<String, V6ApplyState>() }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (uiState) {
            BridgeUiState.Loading ->
                InfoCard("Reading bridge status from the privileged service…")

            is BridgeUiState.Unsupported ->
                BombUnsupportedState(title = "Bomb Bridge", reason = uiState.reason)

            is BridgeUiState.Error ->
                InfoCard(uiState.message, error = true)

            is BridgeUiState.Ready -> {
                val status = uiState.status
                BombSectionTitle("Renderers")
                RenderersCard(status)

                BombSectionTitle("Live events")
                if (status.activeEvents.isEmpty()) {
                    BombCard { BombEmptyState("No live events right now") }
                } else {
                    BombCard {
                        status.activeEvents.forEachIndexed { idx, event ->
                            if (idx > 0) BombRowDivider()
                            EventRow(
                                eventId = event.eventId,
                                renderer = event.renderer,
                                stateName = event.state,
                                status = eventStatus[event.eventId] ?: V6ApplyState.Idle,
                                controllerAvailable = controller != null,
                                onDismiss = {
                                    eventStatus[event.eventId] = V6ApplyState.Applying
                                    if (controller == null) {
                                        eventStatus[event.eventId] = V6ApplyState.Done(NOT_CONNECTED)
                                    } else {
                                        controller.dismissLiveEvent(event.eventId) { result ->
                                            eventStatus[event.eventId] = V6ApplyState.Done(result)
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
                        title = "Publish a live event",
                        summary = "Push a normalised event through the bridge",
                        icon = BombIcons.Bridge,
                        iconTint = BombTheme.miuix.primary,
                        enabled = controller != null,
                        onClick = { composing = true },
                    )
                }
            }
        }
    }

    if (composing) {
        EventComposerSheet(
            onDismiss = { composing = false },
            onPublish = { event, onResult ->
                if (controller == null) {
                    onResult(NOT_CONNECTED)
                } else {
                    controller.publishLiveEvent(event) { result ->
                        onResult(result)
                        if (result.isSuccess) {
                            refreshKey++
                            composing = false
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun RenderersCard(status: BombBridgeStatus) {
    val hyperIslandReady = status.hyperIslandFeaturePresent &&
        status.hyperIslandPermitted &&
        status.hyperIslandPayloadAdapterAvailable
    BombCard {
        RendererRow(
            title = "HyperOS live update",
            detail = "MIUI/HyperOS focus-notification renderer",
            available = status.liveUpdateAvailable,
        )
        BombRowDivider()
        RendererRow(
            title = "HyperIsland",
            detail = hyperIslandDetail(status),
            available = hyperIslandReady,
        )
        BombRowDivider()
        RendererRow(
            title = "Notification",
            detail = "Guaranteed fallback for any unsupported renderer",
            available = status.notificationAvailable,
        )
    }
}

@Composable
private fun RendererRow(title: String, detail: String, available: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 15.sp, color = BombTheme.miuix.onSurface)
            Text(
                text = detail,
                modifier = Modifier.padding(top = 2.dp),
                fontSize = 12.sp,
                color = BombTheme.miuix.onSurfaceVariantSummary,
            )
        }
        BombBadge(
            text = if (available) "Available" else "Unavailable",
            color = if (available) BombTheme.colors.ok else BombTheme.miuix.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun EventRow(
    eventId: String,
    renderer: String,
    stateName: String,
    status: V6ApplyState,
    controllerAvailable: Boolean,
    onDismiss: () -> Unit,
) {
    val applying = status is V6ApplyState.Applying
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = eventId,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = BombTheme.miuix.onSurface,
                )
                Text(
                    text = "${rendererLabel(renderer)} · ${stateLabel(stateName)}",
                    modifier = Modifier.padding(top = 2.dp),
                    fontSize = 12.sp,
                    color = BombTheme.miuix.onSurfaceVariantSummary,
                )
            }
            BombIconButton(
                icon = BombIcons.Stop,
                contentDescription = "Dismiss event",
                onClick = onDismiss,
                tint = BombTheme.colors.warn,
            )
        }
        if (!controllerAvailable || applying || status is V6ApplyState.Done) {
            V6ApplyStatusLine(status, modifier = Modifier.padding(start = 16.dp, bottom = 8.dp))
        }
    }
}

// ------------------------------------------------------------------ Composer sheet

@Composable
private fun EventComposerSheet(
    onDismiss: () -> Unit,
    onPublish: (BombLiveEvent, (BombOperationResult) -> Unit) -> Unit,
) {
    val id = remember { "bomb_evt_${Random.nextInt(100_000, 1_000_000)}" }
    val titleState = rememberTextFieldState("")
    val subtitleState = rememberTextFieldState("")

    var typeIdx by remember { mutableIntStateOf(BombLiveEventType.ALL.indexOf("CUSTOM").coerceAtLeast(0)) }
    val type = BombLiveEventType.ALL[typeIdx]

    val stateValues = listOf(
        BombLiveEventState.ACTIVE,
        BombLiveEventState.PAUSED,
        BombLiveEventState.COMPLETED,
        BombLiveEventState.FAILED,
    )
    var stateIdx by remember { mutableIntStateOf(0) }
    val eventState = stateValues[stateIdx]

    var showProgress by remember { mutableStateOf(false) }
    var indeterminate by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0.4f) }

    var submitting by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    val title by remember { derivedStateOf { titleState.text.toString().trim() } }
    val subtitle by remember { derivedStateOf { subtitleState.text.toString().trim().ifEmpty { null } } }

    val event = BombLiveEvent(
        id = id,
        sourcePackage = SOURCE_PACKAGE,
        type = type,
        title = title,
        subtitle = subtitle,
        compactText = null,
        progressFraction = if (showProgress && !indeterminate) progress.toDouble() else null,
        progressIndeterminate = showProgress && indeterminate,
        state = eventState,
        timestampMillis = 0L,
    )
    val violation = BombLiveEventBounds.violation(event)
    val canPublish = violation == null && !submitting

    BombBottomSheet(onDismissRequest = onDismiss, title = "Publish live event") {
        Column(
            modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FieldLabel("Type")
            TypePicker(selectedIndex = typeIdx, onSelected = { typeIdx = it })

            FieldLabel("Title")
            SheetTextField(titleState, "e.g. Downloading update")

            FieldLabel("Subtitle (optional)")
            SheetTextField(subtitleState, "e.g. 42 MB of 120 MB")

            FieldLabel("State")
            BombSegmentedButton(
                options = listOf("Active", "Paused", "Completed", "Failed"),
                selectedIndex = stateIdx,
                onSelected = { stateIdx = it },
            )

            BombSwitchPreference(
                title = "Show progress",
                summary = "Attach a progress bar to the event",
                checked = showProgress,
                onCheckedChange = { showProgress = it },
            )
            if (showProgress) {
                BombSwitchPreference(
                    title = "Indeterminate",
                    summary = "Spinning bar with no fixed value",
                    checked = indeterminate,
                    onCheckedChange = { indeterminate = it },
                )
                if (!indeterminate) {
                    BombSliderPreference(
                        title = "Progress",
                        value = progress,
                        onValueChange = { progress = it },
                        valueLabel = "${(progress * 100).toInt()}%",
                        valueRange = 0f..1f,
                    )
                }
            }

            val shownError = errorText ?: violation
            if (shownError != null) {
                Text(text = shownError, fontSize = 12.sp, color = BombTheme.colors.warn)
            }

            Button(
                onClick = {
                    submitting = true
                    errorText = null
                    onPublish(event) { result ->
                        submitting = false
                        if (!result.isSuccess) {
                            errorText = "${result.status}${result.message?.let { ": $it" } ?: ""}"
                        }
                    }
                },
                enabled = canPublish,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(text = if (submitting) "Publishing…" else "Publish event")
            }
        }
    }
}

@Composable
private fun TypePicker(selectedIndex: Int, onSelected: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    BombCard {
        BombPreference(
            title = typeLabel(BombLiveEventType.ALL[selectedIndex]),
            summary = BombLiveEventType.ALL[selectedIndex],
            onClick = { expanded = !expanded },
        )
        if (expanded) {
            BombRowDivider()
            Column(modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                BombLiveEventType.ALL.forEachIndexed { idx, name ->
                    if (idx > 0) BombRowDivider()
                    BombPreference(
                        title = typeLabel(name),
                        value = if (idx == selectedIndex) "✓" else null,
                        onClick = { onSelected(idx); expanded = false },
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ helpers

private fun hyperIslandDetail(status: BombBridgeStatus): String = when {
    !status.hyperIslandFeaturePresent -> "Feature not present on this device"
    !status.hyperIslandPermitted -> "Present but not permitted for Bomb"
    !status.hyperIslandPayloadAdapterAvailable -> "Permitted but no payload adapter"
    else -> "Protocol v${status.hyperIslandProtocolVersion}"
}

private fun rendererLabel(name: String): String = when (name) {
    BombBridgeRenderer.LIVE_UPDATE -> "Live update"
    BombBridgeRenderer.HYPER_ISLAND -> "HyperIsland"
    BombBridgeRenderer.NOTIFICATION -> "Notification"
    else -> name
}

private fun stateLabel(name: String): String =
    name.split('_').joinToString(" ") { it.lowercase().replaceFirstChar(Char::uppercase) }

private fun typeLabel(name: String): String =
    name.split('_').joinToString(" ") { it.lowercase().replaceFirstChar(Char::uppercase) }

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

// The bridge attributes events to their source package; the UI publishes as Bomb.
private const val SOURCE_PACKAGE = "com.hzzmonet.zkbomb"

private val NOT_CONNECTED = BombOperationResult("BACKEND_UNAVAILABLE", "Service is not connected")
