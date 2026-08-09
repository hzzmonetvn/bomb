package com.hzzmonet.zkbomb.preview

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hzzmonet.zkbomb.data.BombSettings
import com.hzzmonet.zkbomb.ui.design.BombBackdropStyle
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * Hoisted UI state. User-facing settings are persisted immediately; search
 * queries and temporary list filters intentionally remain session-only.
 */
@Stable
class PreviewUiState(
    private val settings: BombSettings,
    startDark: Boolean = true,
) {
    var darkTheme by booleanPreference("appearance.dark_theme", startDark)

    // Apps
    val appQuery = TextFieldState()
    var appFilter by mutableStateOf(0)

    // Task Manager
    val processQuery = TextFieldState()
    var selectedProcessPid by mutableStateOf<Int?>(null)
    var processSort by mutableStateOf(0)
    var processFilter by mutableStateOf(0)
    var refreshLevel by intPreference("task.refresh_level", 2)

    /** Sampling interval actually used by the telemetry poller. */
    val samplingIntervalMillis: Long
        get() = when (refreshLevel) {
            0 -> 500L
            1 -> 1000L
            2 -> 2000L
            else -> 5000L
        }

    // Monitor
    val monitors = booleanMapPreference("monitor.enabled") {
        mapOf(
            "Classic Monitor" to true,
            "Processes Monitor" to false,
            "Thread Monitor" to false,
            "Mini Monitor" to false,
            "FPS Stats" to true,
            "Temperature Monitor" to false,
        )
    }.toMutableStateMap()
    var overlayPreset by intPreference("monitor.overlay_preset", 0)
    var overlayOpacity by floatPreference("monitor.overlay_opacity", 0.82f)
    var overlayScale by floatPreference("monitor.overlay_scale", 1.0f)

    // Freeze
    var autoFreeze by booleanPreference("freeze.auto", true)
    var freezeDelay by floatPreference("freeze.delay", 5f)
    var freezeOnScreenOff by booleanPreference("freeze.screen_off", false)
    var unfreezeOnLaunch by booleanPreference("freeze.unfreeze_on_launch", true)
    val freezeExclusions = booleanMapPreference("freeze.exclusions") {
        mapOf(
            "com.android.systemui" to true,
            "com.miui.home" to true,
            "com.hzzmonet.zkbomb" to true,
        )
    }.toMutableStateMap()

    // Performance
    var performanceProfile by intPreference("performance.profile", 1)
    var memorySwappiness by intPreference("memory.swappiness", 100)
    var memoryPageCluster by intPreference("memory.page_cluster", 0)
    var memoryOperationMessage by mutableStateOf<String?>(null)

    // Battery
    var chargeLimit by booleanPreference("battery.charge_limit", false)
    var chargeLimitLevel by floatPreference("battery.charge_limit_level", 80f)

    // Logs
    var logProfile by intPreference("logs.profile", 1)
    var logOperationMessage by mutableStateOf<String?>(null)

    // Network
    var networkFilter by mutableStateOf(0)

    // Recorder
    var recorderPhone by intPreference("recorder.phone", 2)
    var recorderVoip by intPreference("recorder.voip", 1)
    var recorderFormat by intPreference("recorder.format", 0)
    var recorderRetention by intPreference("recorder.retention", 1)
    val voipQuery = TextFieldState()

    /**
     * Apps the recorder watches, chosen by the user from the installed list.
     *
     * Starts **empty**. It used to pre-select two packages off a hardcoded
     * sample list, which meant a fresh install claimed to be watching two apps
     * the user had never chosen — and, on a device where those packages are not
     * installed, two apps that do not exist.
     */
    val voipWatched = booleanMapPreference("recorder.voip_watched") {
        emptyMap()
    }.toMutableStateMap()

    // Appearance
    var backdrop by enumPreference("appearance.backdrop", BombBackdropStyle.Ember)
    var backdropDim by floatPreference("appearance.dim", 0.35f)
    var backdropBlur by floatPreference("appearance.blur", 0f)
    var cardOpacity by floatPreference("appearance.card_opacity", 0.74f)

    // App Control
    var advancedMode by booleanPreference("app_control.advanced", false)
    var appFreezeMode by mutableStateOf("UNKNOWN")
    var appOperationMessage by mutableStateOf<String?>(null)

    // Execution mode — Bomb is always backed by ROM integration or root.
    var execMode by enumPreference("execution.mode", PreviewData.ExecMode.ROM)

    /** Packages the freeze engine manages: package name → freeze mode. */
    val freezeList = stringMapPreference("freeze.packages") {
        PreviewData.apps
            .filter { it.frozen || it.freezeMode == "Smart" || it.freezeMode == "Soft Freeze" }
            .associate { it.packageName to it.freezeMode }
    }.toMutableStateMap()

    val freezePickerQuery = TextFieldState()

    val ruleEnabled = booleanMapPreference("rules.enabled") {
        PreviewData.rules.associate { it.name to it.enabled }
    }.toMutableStateMap()

    val bridgeEnabled = booleanMapPreference("bridge.enabled") {
        PreviewData.bridgeEvents.associate { it.title to true }
    }.toMutableStateMap()

    /** Called by the composition observer for settings stored in snapshot maps. */
    fun persistCollections() {
        settings.putString("monitor.enabled", encodeBooleanMap(monitors))
        settings.putString("recorder.voip_watched", encodeBooleanMap(voipWatched))
        settings.putString("freeze.packages", encodeStringMap(freezeList))
        settings.putString("freeze.exclusions", encodeBooleanMap(freezeExclusions))
        settings.putString("rules.enabled", encodeBooleanMap(ruleEnabled))
        settings.putString("bridge.enabled", encodeBooleanMap(bridgeEnabled))
    }

    private fun booleanPreference(key: String, defaultValue: Boolean) = PersistedState(
        initialValue = settings.getBoolean(key, defaultValue),
        save = { settings.putBoolean(key, it) },
    )

    private fun intPreference(key: String, defaultValue: Int) = PersistedState(
        initialValue = settings.getInt(key, defaultValue),
        save = { settings.putInt(key, it) },
    )

    private fun floatPreference(key: String, defaultValue: Float) = PersistedState(
        initialValue = settings.getFloat(key, defaultValue),
        save = { settings.putFloat(key, it) },
    )

    private fun booleanMapPreference(
        key: String,
        defaultValue: () -> Map<String, Boolean>,
    ): Map<String, Boolean> {
        val encoded = settings.getString(key, MAP_UNSET)
        return if (encoded == MAP_UNSET) defaultValue() else decodeBooleanMap(encoded)
    }

    private fun stringMapPreference(
        key: String,
        defaultValue: () -> Map<String, String>,
    ): Map<String, String> {
        val encoded = settings.getString(key, MAP_UNSET)
        return if (encoded == MAP_UNSET) defaultValue() else decodeStringMap(encoded)
    }

    private inline fun <reified T : Enum<T>> enumPreference(key: String, defaultValue: T): PersistedState<T> {
        val saved = settings.getString(key, defaultValue.name)
        val initial = enumValues<T>().firstOrNull { it.name == saved } ?: defaultValue
        return PersistedState(initialValue = initial, save = { settings.putString(key, it.name) })
    }
}

private const val MAP_UNSET = "\u001d"

private class PersistedState<T>(
    initialValue: T,
    private val save: (T) -> Unit,
) : ReadWriteProperty<Any?, T> {
    private val state: MutableState<T> = mutableStateOf(initialValue)

    override fun getValue(thisRef: Any?, property: KProperty<*>): T = state.value

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        if (state.value == value) return
        state.value = value
        save(value)
    }
}

private fun <T> Map<String, T>.toMutableStateMap() =
    mutableStateMapOf<String, T>().also { it.putAll(this) }

private fun encodeBooleanMap(values: Map<String, Boolean>): String = values.entries.joinToString("\u001e") {
    "${it.key}\u001f${if (it.value) 1 else 0}"
}

private fun encodeStringMap(values: Map<String, String>): String = values.entries.joinToString("\u001e") {
    "${it.key}\u001f${it.value}"
}

private fun decodeBooleanMap(encoded: String): Map<String, Boolean> = encoded
    .split('\u001e')
    .mapNotNull { item ->
        val separator = item.lastIndexOf('\u001f')
        if (separator <= 0) null else {
            item.substring(0, separator) to (item.substring(separator + 1) == "1")
        }
    }
    .toMap()

private fun decodeStringMap(encoded: String): Map<String, String> = encoded
    .split('\u001e')
    .mapNotNull { item ->
        val separator = item.indexOf('\u001f')
        if (separator <= 0) null else item.substring(0, separator) to item.substring(separator + 1)
    }
    .toMap()
