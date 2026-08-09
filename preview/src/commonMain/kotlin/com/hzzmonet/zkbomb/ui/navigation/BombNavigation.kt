package com.hzzmonet.zkbomb.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import com.hzzmonet.zkbomb.ui.design.BombIcons
import com.hzzmonet.zkbomb.ui.design.LocalBombSurfaceAlpha
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem

sealed interface BombRoute {
    data object Home : BombRoute
    data object Apps : BombRoute
    data object Monitor : BombRoute
    data object Automation : BombRoute
    data object More : BombRoute

    data object TaskManager : BombRoute
    data object Freeze : BombRoute
    data object Performance : BombRoute
    data object BatteryLab : BombRoute
    data object Network : BombRoute
    data object LogGovernor : BombRoute
    data object Bridge : BombRoute
    data object Recorder : BombRoute
    data object FreezePicker : BombRoute
    data object VoipPicker : BombRoute
    data object ExecutionMode : BombRoute
    data object Appearance : BombRoute
    data class AppControl(val packageName: String) : BombRoute
    data class ProcessDetail(val pid: Int) : BombRoute
}

data class BombTab(
    val route: BombRoute,
    val label: String,
    val icon: ImageVector,
)

val bombTabs = listOf(
    BombTab(BombRoute.Home, "Home", BombIcons.Home),
    BombTab(BombRoute.Apps, "Apps", BombIcons.Apps),
    BombTab(BombRoute.Monitor, "Monitor", BombIcons.Monitor),
    BombTab(BombRoute.Automation, "Rules", BombIcons.Automation),
    BombTab(BombRoute.More, "More", BombIcons.More),
)

/**
 * Minimal back-stack navigator.
 *
 * The preview deliberately does not pull `navigation-compose`: `:app` will own
 * that decision, and a preview that hard-codes a navigation library makes the
 * screens harder to lift later.
 */
@Stable
class BombNavigator(
    initialTab: BombRoute = BombRoute.Home,
    initialDetail: BombRoute? = null,
) {
    var tab: BombRoute by mutableStateOf(initialTab)
        private set

    private val stack = mutableStateListOf<BombRoute>().apply {
        if (initialDetail != null) add(initialDetail)
    }

    val current: BombRoute get() = stack.lastOrNull() ?: tab

    val isAtTabRoot: Boolean get() = stack.isEmpty()

    fun selectTab(route: BombRoute) {
        stack.clear()
        tab = route
    }

    fun push(route: BombRoute) {
        stack.add(route)
    }

    fun pop() {
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
    }
}

@Composable
fun rememberBombNavigator(startAt: String? = null): BombNavigator = remember(startAt) {
    val (tab, detail) = startRouteFor(startAt)
    BombNavigator(initialTab = tab, initialDetail = detail)
}

/**
 * Maps a URL fragment to a starting screen so a specific screen can be linked
 * during review: `#monitor`, `#tasks`, `#battery`, …
 */
fun startRouteFor(slug: String?): Pair<BombRoute, BombRoute?> = when (slug?.lowercase()) {
    "apps" -> BombRoute.Apps to null
    "monitor", "stats" -> BombRoute.Monitor to null
    "rules", "automation" -> BombRoute.Automation to null
    "more" -> BombRoute.More to null
    "tasks", "taskmanager" -> BombRoute.Home to BombRoute.TaskManager
    "freeze" -> BombRoute.Home to BombRoute.Freeze
    "addapps", "freezeadd" -> BombRoute.Home to BombRoute.FreezePicker
    "performance", "profiles" -> BombRoute.Home to BombRoute.Performance
    "battery" -> BombRoute.More to BombRoute.BatteryLab
    "network" -> BombRoute.More to BombRoute.Network
    "logs" -> BombRoute.More to BombRoute.LogGovernor
    "bridge" -> BombRoute.More to BombRoute.Bridge
    "recorder" -> BombRoute.More to BombRoute.Recorder
    "voip" -> BombRoute.More to BombRoute.VoipPicker
    "mode", "root" -> BombRoute.More to BombRoute.ExecutionMode
    "appearance", "background" -> BombRoute.More to BombRoute.Appearance
    "process" -> BombRoute.Home to BombRoute.ProcessDetail(9214)
    "appcontrol" -> BombRoute.Apps to BombRoute.AppControl("org.telegram.messenger")
    else -> BombRoute.Home to null
}

@Composable
fun BombBottomBar(navigator: BombNavigator) {
    val alpha = LocalBombSurfaceAlpha.current
    // Nearly opaque even over a backdrop: the list scrolls under this bar, and a
    // real blur needs a platform RenderEffect the web build cannot provide.
    NavigationBar(
        color = MiuixTheme.colorScheme.surfaceContainer.copy(alpha = if (alpha < 1f) 0.95f else 1f),
    ) {
        bombTabs.forEach { tab ->
            NavigationBarItem(
                selected = navigator.tab == tab.route && navigator.isAtTabRoot,
                onClick = { navigator.selectTab(tab.route) },
                icon = tab.icon,
                label = tab.label,
            )
        }
    }
}

fun titleOf(route: BombRoute): String = when (route) {
    BombRoute.Home -> "Bomb"
    BombRoute.Apps -> "Apps"
    BombRoute.Monitor -> "Monitor"
    BombRoute.Automation -> "Bomb Rules"
    BombRoute.More -> "More"
    BombRoute.TaskManager -> "Processes"
    BombRoute.Freeze -> "Freeze Engine"
    BombRoute.Performance -> "Performance"
    BombRoute.BatteryLab -> "Battery Lab"
    BombRoute.Network -> "Network"
    BombRoute.LogGovernor -> "Log Governor"
    BombRoute.Bridge -> "Bomb Bridge"
    BombRoute.Recorder -> "Call Recorder"
    BombRoute.FreezePicker -> "Add apps"
    BombRoute.VoipPicker -> "VoIP apps"
    BombRoute.ExecutionMode -> "Execution mode"
    BombRoute.Appearance -> "Appearance"
    is BombRoute.AppControl -> "App Control"
    is BombRoute.ProcessDetail -> "Process"
}
