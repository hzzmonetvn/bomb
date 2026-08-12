package com.hzzmonet.zkbomb.preview

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.hzzmonet.zkbomb.data.rememberBombService
import com.hzzmonet.zkbomb.data.rememberBombSettings
import com.hzzmonet.zkbomb.data.rememberInstallInfo
import com.hzzmonet.zkbomb.data.rememberInstalledApps
import com.hzzmonet.zkbomb.data.rememberProcessList
import com.hzzmonet.zkbomb.data.rememberSystemTelemetry
import com.hzzmonet.zkbomb.data.rememberSystemView
import com.hzzmonet.zkbomb.data.rememberVoipRecorder
import com.hzzmonet.zkbomb.ui.adblock.adBlockContent
import com.hzzmonet.zkbomb.ui.apps.appControlContent
import com.hzzmonet.zkbomb.ui.apps.appListContent
import com.hzzmonet.zkbomb.ui.apps.rememberVisibleApps
import com.hzzmonet.zkbomb.ui.automation.automationContent
import com.hzzmonet.zkbomb.ui.battery.batteryLabContent
import com.hzzmonet.zkbomb.ui.bridge.bridgeContent
import com.hzzmonet.zkbomb.ui.firewall.firewallContent
import com.hzzmonet.zkbomb.ui.dashboard.homeContent
import com.hzzmonet.zkbomb.ui.appearance.appearanceContent
import com.hzzmonet.zkbomb.ui.design.BackdropImageState
import com.hzzmonet.zkbomb.ui.design.BombBackdrop
import com.hzzmonet.zkbomb.ui.design.rememberBackdropImageState
import com.hzzmonet.zkbomb.ui.design.BombBackdropStyle
import com.hzzmonet.zkbomb.ui.design.BombIcons
import com.hzzmonet.zkbomb.ui.design.LocalBombSurfaceAlpha
import com.hzzmonet.zkbomb.ui.design.BombTheme
import com.hzzmonet.zkbomb.ui.design.component.BombBadge
import com.hzzmonet.zkbomb.ui.design.component.BombIconButton
import com.hzzmonet.zkbomb.ui.design.component.BombScaffold
import com.hzzmonet.zkbomb.ui.freeze.freezeContent
import com.hzzmonet.zkbomb.ui.freeze.freezePickerContent
import com.hzzmonet.zkbomb.ui.mode.executionModeContent
import com.hzzmonet.zkbomb.ui.logs.logGovernorContent
import com.hzzmonet.zkbomb.ui.more.moreContent
import com.hzzmonet.zkbomb.ui.network.networkContent
import com.hzzmonet.zkbomb.ui.privacy.appVisibilityContent
import com.hzzmonet.zkbomb.ui.privacy.settingsVirtualizationContent
import com.hzzmonet.zkbomb.ui.navigation.BombBottomBar
import com.hzzmonet.zkbomb.ui.navigation.BombBackHandler
import com.hzzmonet.zkbomb.ui.navigation.BombRoute
import com.hzzmonet.zkbomb.ui.navigation.rememberBombNavigator
import com.hzzmonet.zkbomb.ui.navigation.titleOf
import com.hzzmonet.zkbomb.ui.performance.performanceContent
import com.hzzmonet.zkbomb.ui.recorder.recorderContent
import com.hzzmonet.zkbomb.ui.recorder.voipPickerContent
import com.hzzmonet.zkbomb.ui.stats.monitorContent
import com.hzzmonet.zkbomb.ui.taskmanager.processDetailContent
import com.hzzmonet.zkbomb.ui.taskmanager.taskManagerContent
import kotlinx.coroutines.flow.collect

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import com.hzzmonet.zkbomb.ui.capability.rememberCapabilityViewModel

/**
 * Android application entry point.
 */
@Composable
fun BombApp() {
    val settings = rememberBombSettings()
    val state = remember(settings) { PreviewUiState(settings) }
    val backdropImage = rememberBackdropImageState()
    LaunchedEffect(state) {
        snapshotFlow {
            listOf(
                state.monitors.toMap(),
                state.voipWatched.toMap(),
                state.freezeList.toMap(),
                state.ruleEnabled.toMap(),
                state.bridgeEnabled.toMap(),
            )
        }.collect { state.persistCollections() }
    }
    BombTheme(darkTheme = state.darkTheme) {
        val hasBackdrop = state.backdrop != BombBackdropStyle.None &&
            !(state.backdrop == BombBackdropStyle.Custom && backdropImage.image == null)
        val pageBackdrop = if (state.darkTheme) Color(0xFF141518) else Color(0xFFE6E8EC)
        CompositionLocalProvider(
            LocalBombSurfaceAlpha provides if (hasBackdrop) state.cardOpacity else 1f,
        ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(pageBackdrop),
        ) {
            BombBackdrop(
                style = state.backdrop,
                darkTheme = state.darkTheme,
                image = backdropImage.image,
                dim = state.backdropDim,
                blurRadius = state.backdropBlur.dp,
            )
            BombAppShell(state, backdropImage)
        }
        }
    }
}

@Composable
private fun BombAppShell(
    state: PreviewUiState,
    backdropImage: BackdropImageState,
) {
    val navigator = rememberBombNavigator()
    val route = navigator.current
    var gestureProgress by remember { mutableFloatStateOf(0f) }
    var gestureActive by remember { mutableStateOf(false) }
    var fromLeftEdge by remember { mutableStateOf(true) }
    val settledProgress by animateFloatAsState(
        targetValue = gestureProgress,
        animationSpec = spring(stiffness = 700f, dampingRatio = 0.9f),
        label = "predictive-back-progress",
    )
    val displayedProgress = if (gestureActive) gestureProgress else settledProgress

    BombBackHandler(
        enabled = !navigator.isAtTabRoot,
        onProgress = { event ->
            gestureActive = true
            gestureProgress = event.progress
            fromLeftEdge = event.fromLeftEdge
        },
        onCancelled = {
            gestureActive = false
            gestureProgress = 0f
        },
        onBack = {
            navigator.pop()
            gestureActive = false
            gestureProgress = 0f
        },
    )
    // One sampler for the whole app: every screen reads the same snapshot.
    val system = rememberSystemView(state.samplingIntervalMillis)
    val apps = rememberInstalledApps()
    // Filtered once here (derivedStateOf) so the app list does not re-run its filter
    // on every unrelated shell recomposition; the LazyListScope builder cannot hold
    // remembered state itself.
    val visibleApps = rememberVisibleApps(state, apps)
    // Bound once for the whole app. Each additional call site would be another
    // connection opened and closed on every recomposition.
    val service = rememberBombService()
    val capabilityViewModel = rememberCapabilityViewModel(service)
    val install = rememberInstallInfo()
    // The call recorder binds once here for the same reason the service does: it
    // collects a process-wide state flow, and one collector for the app avoids a
    // fresh subscription on every screen that shows recorder status.
    val recorder = rememberVoipRecorder()
    // Privileged process/telemetry pollers. Hosted here (not inside a LazyColumn
    // item, which recycles) so a delta baseline is not lost on scroll, but gated
    // by route so sampling runs only while the screen that needs it is shown —
    // the "stop when the screen leaves" the Refresh card promises.
    val processList = rememberProcessList(
        service = service,
        active = route == BombRoute.TaskManager,
        intervalMillis = state.samplingIntervalMillis,
    )
    val telemetry = rememberSystemTelemetry(
        service = service,
        // Monitor and Task Manager share this one telemetry poller — the single
        // CPU/RAM pipeline. It stays off on every other route, so sampling still
        // stops when neither screen is shown.
        active = route == BombRoute.Monitor || route == BombRoute.TaskManager,
        intervalMillis = state.samplingIntervalMillis,
    )

    Crossfade(
        targetState = route,
        animationSpec = tween(durationMillis = 200),
        label = "shell-tab-transition",
    ) { targetRoute ->
        BombScaffold(
            title = titleOf(targetRoute),
            modifier = Modifier.graphicsLayer {
                val direction = if (fromLeftEdge) 1f else -1f
                translationX = size.width * 0.16f * displayedProgress * direction
                scaleX = 1f - 0.04f * displayedProgress
                scaleY = 1f - 0.04f * displayedProgress
                alpha = 1f - 0.08f * displayedProgress
                shadowElevation = 24f * displayedProgress
                shape = RoundedCornerShape(28.dp)
                clip = displayedProgress > 0f
            },
            onBack = if (navigator.isAtTabRoot) null else ({ navigator.pop() }),
            actions = {
                BombBadge(
                    text = if (system.isLive) "LIVE" else "SAMPLE",
                    color = if (system.isLive) BombTheme.colors.ok else BombTheme.colors.accent,
                    modifier = Modifier.padding(end = 4.dp),
                )
                BombIconButton(
                    icon = if (state.darkTheme) BombIcons.Sun else BombIcons.Moon,
                    contentDescription = "Toggle theme",
                    onClick = { state.darkTheme = !state.darkTheme },
                    modifier = Modifier.padding(end = 8.dp),
                )
            },
            bottomBar = {
                if (navigator.isAtTabRoot) BombBottomBar(navigator)
            },
        ) {
            when (targetRoute) {
                BombRoute.Home -> homeContent(state, navigator, system, service, install)
                BombRoute.Apps -> appListContent(state, navigator, apps, visibleApps)
                BombRoute.Monitor -> monitorContent(state, system, telemetry)
                BombRoute.Automation -> automationContent(service, apps)
                BombRoute.More -> moreContent(state, navigator)
                BombRoute.TaskManager -> taskManagerContent(state, navigator, service, telemetry, processList)
                BombRoute.Freeze -> freezeContent(state, navigator, service, apps)
                BombRoute.Performance -> performanceContent(state, service)
                BombRoute.BatteryLab -> batteryLabContent(state, system, service)
                BombRoute.Network -> networkContent(state, system)
                BombRoute.LogGovernor -> logGovernorContent(state, service)
                BombRoute.Bridge -> bridgeContent(state, service)
                BombRoute.Recorder -> recorderContent(state, navigator, recorder, service)
                BombRoute.FreezePicker -> freezePickerContent(state, apps)
                BombRoute.VoipPicker -> voipPickerContent(state, apps)
                BombRoute.ExecutionMode -> executionModeContent(service, install)
                BombRoute.Appearance -> appearanceContent(state, backdropImage)
                BombRoute.AppVisibility -> appVisibilityContent(service, apps)
                BombRoute.SettingsVirtualization -> settingsVirtualizationContent(service, apps)
                BombRoute.AdBlock -> adBlockContent(service)
                BombRoute.Firewall -> firewallContent(service, apps)
                is BombRoute.ProcessDetail -> processDetailContent(targetRoute.pid, navigator, service, processList)
                is BombRoute.AppControl -> appControlContent(state, targetRoute.packageName, navigator, apps, service)
            }
        }
    }
}
