package com.hzzmonet.zkbomb.domain.bridge

import com.hzzmonet.zkbomb.domain.freeze.PackageNameValidator

enum class LiveEventType {
    MEDIA, DOWNLOAD, UPLOAD, NAVIGATION, TIMER, CALL, CALL_RECORDING,
    CHARGING, HOTSPOT, VPN, FILE_TRANSFER, GAME_STATS, APP_INSTALL, CUSTOM,
}

enum class LiveEventState { ACTIVE, PAUSED, COMPLETED, FAILED, DISMISSED }

data class LiveEventProgress(
    val fraction: Double? = null,
    val indeterminate: Boolean = false,
) {
    val percent: Int? get() = fraction?.let { (it * 100).toInt().coerceIn(0, 100) }
}

data class BombLiveEvent(
    val id: String,
    val sourcePackage: String,
    val type: LiveEventType,
    val title: String,
    val subtitle: String?,
    val compactText: String?,
    val progress: LiveEventProgress?,
    val state: LiveEventState,
    val timestampMillis: Long,
)

object BombLiveEventValidator {
    private val ID = Regex("^[A-Za-z0-9._:-]{1,128}$")

    fun violations(event: BombLiveEvent): List<String> = buildList {
        if (!ID.matches(event.id)) add("id")
        if (!PackageNameValidator.isValid(event.sourcePackage)) add("sourcePackage")
        if (event.title.isBlank() || event.title.length > 120 || event.title.hasControl()) add("title")
        if (event.subtitle != null &&
            (event.subtitle.length > 240 || event.subtitle.hasControl())
        ) add("subtitle")
        if (event.compactText != null &&
            (event.compactText.length > 128 || event.compactText.hasControl())
        ) add("compactText")
        if (event.timestampMillis < 0) add("timestampMillis")
        event.progress?.let { progress ->
            if (progress.indeterminate && progress.fraction != null) add("progress")
            if (progress.fraction != null &&
                (!progress.fraction.isFinite() || progress.fraction !in 0.0..1.0)
            ) add("progress")
        }
    }

    private fun String.hasControl(): Boolean = any { it.isISOControl() && it != '\n' }
}

enum class BridgeRendererKind { LIVE_UPDATE, HYPER_ISLAND, NOTIFICATION }

data class BridgeRendererAvailability(
    val liveUpdate: Boolean,
    val hyperIsland: Boolean,
    val notification: Boolean,
)

/** Stable renderer preference. A renderer may still decline and fall through. */
object BridgeRoutingPolicy {
    fun candidates(availability: BridgeRendererAvailability): List<BridgeRendererKind> = buildList {
        if (availability.liveUpdate) add(BridgeRendererKind.LIVE_UPDATE)
        if (availability.hyperIsland) add(BridgeRendererKind.HYPER_ISLAND)
        if (availability.notification) add(BridgeRendererKind.NOTIFICATION)
    }
}
