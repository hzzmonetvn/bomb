package com.hzzmonet.zkbomb.core

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import com.hzzmonet.zkbomb.api.BombResult
import com.hzzmonet.zkbomb.api.BridgeEventStatusParcel
import com.hzzmonet.zkbomb.api.BridgeStatusSnapshot
import com.hzzmonet.zkbomb.domain.bridge.BombLiveEvent
import com.hzzmonet.zkbomb.domain.bridge.BombLiveEventValidator
import com.hzzmonet.zkbomb.domain.bridge.BridgeRendererAvailability
import com.hzzmonet.zkbomb.domain.bridge.BridgeRendererKind
import com.hzzmonet.zkbomb.domain.bridge.BridgeRoutingPolicy
import com.hzzmonet.zkbomb.domain.bridge.LiveEventState
import java.util.LinkedHashMap

internal data class HyperIslandProbeSnapshot(
    val featurePresent: Boolean,
    val protocolVersion: Int,
    val permitted: Boolean,
) {
    val available: Boolean get() = featurePresent && protocolVersion in 1..3 && permitted
}

internal fun interface HyperIslandCapabilitySource {
    fun probe(): HyperIslandProbeSnapshot
}

internal class HyperIslandCapabilityProbe(
    private val context: Context,
    private val properties: SystemPropertyReader,
) : HyperIslandCapabilitySource {
    override fun probe(): HyperIslandProbeSnapshot {
        val feature = properties.get("persist.sys.feature.island") == "1"
        val protocol = runCatching {
            Settings.System.getInt(context.contentResolver, FOCUS_PROTOCOL_SETTING, 0)
        }.getOrDefault(0).takeIf { it in 1..3 } ?: 0
        val permitted = if (feature && protocol > 0) {
            runCatching {
                val extras = Bundle().apply { putString("package", context.packageName) }
                context.contentResolver.call(
                    Uri.parse(FOCUS_PROVIDER),
                    "canShowFocus",
                    null,
                    extras,
                )?.getBoolean("canShowFocus", false) == true
            }.getOrDefault(false)
        } else false
        return HyperIslandProbeSnapshot(feature, protocol, permitted)
    }

    private companion object {
        const val FOCUS_PROTOCOL_SETTING = "notification_focus_protocol"
        const val FOCUS_PROVIDER = "content://miui.statusbar.notification.public"
    }
}

internal data class HyperIslandPayload(
    val jsonParam: String,
    val resources: Bundle? = null,
)

/** Implemented only by a pinned, protocol-versioned HyperIsland payload package. */
internal interface HyperIslandPayloadEncoder {
    val available: Boolean
    fun encode(event: BombLiveEvent, protocolVersion: Int): HyperIslandPayload?
}

internal object UnavailableHyperIslandPayloadEncoder : HyperIslandPayloadEncoder {
    override val available = false
    override fun encode(event: BombLiveEvent, protocolVersion: Int): HyperIslandPayload? = null
}

internal enum class BridgeNotificationMode { NOTIFICATION, LIVE_UPDATE, HYPER_ISLAND }

internal interface BridgeNotificationPort {
    fun notificationAvailable(): Boolean
    fun liveUpdateAvailable(): Boolean
    fun post(event: BombLiveEvent, mode: BridgeNotificationMode, payload: HyperIslandPayload?): Boolean
    fun cancel(eventId: String)
}

internal class AndroidBridgeNotificationPort(private val context: Context) : BridgeNotificationPort {
    private val manager = context.getSystemService(NotificationManager::class.java)

    override fun notificationAvailable(): Boolean = manager != null &&
        (Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED)

    override fun liveUpdateAvailable(): Boolean {
        if (!notificationAvailable() || Build.VERSION.SDK_INT < 36) return false
        return runCatching {
            val method = NotificationManager::class.java.getMethod("canPostPromotedNotifications")
            method.invoke(manager) as? Boolean == true
        }.getOrDefault(false)
    }

    override fun post(
        event: BombLiveEvent,
        mode: BridgeNotificationMode,
        payload: HyperIslandPayload?,
    ): Boolean {
        val notificationManager = manager ?: return false
        if (!notificationAvailable()) return false
        if (mode == BridgeNotificationMode.LIVE_UPDATE && !liveUpdateAvailable()) return false
        if (mode == BridgeNotificationMode.HYPER_ISLAND && payload == null) return false
        return runCatching {
            ensureChannel(notificationManager)
            val active = event.state == LiveEventState.ACTIVE || event.state == LiveEventState.PAUSED
            val builder = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle(event.title)
                .setContentText(event.subtitle ?: event.compactText.orEmpty())
                .setSubText(event.compactText?.take(MAX_COMPACT_TEXT))
                .setWhen(event.timestampMillis)
                .setOnlyAlertOnce(true)
                .setOngoing(active)
                .setAutoCancel(!active)
            event.progress?.let { progress ->
                builder.setProgress(100, progress.percent ?: 0, progress.indeterminate)
            }
            if (mode == BridgeNotificationMode.LIVE_UPDATE) applyLiveUpdate(builder, event)
            val notification = builder.build()
            if (mode == BridgeNotificationMode.HYPER_ISLAND) {
                payload!!.resources?.let { notification.extras.putAll(it) }
                notification.extras.putString(HYPER_PARAM_KEY, payload.jsonParam)
            }
            notificationManager.notify(notificationId(event.id), notification)
            true
        }.getOrDefault(false)
    }

    override fun cancel(eventId: String) {
        runCatching { manager?.cancel(notificationId(eventId)) }
    }

    private fun applyLiveUpdate(builder: Notification.Builder, event: BombLiveEvent) {
        if (Build.VERSION.SDK_INT < 36) return
        runCatching {
            Notification.Builder::class.java
                .getMethod("setRequestPromotedOngoing", java.lang.Boolean.TYPE)
                .invoke(builder, true)
        }
        event.compactText?.take(MAX_COMPACT_TEXT)?.let { compact ->
            runCatching {
                Notification.Builder::class.java
                    .getMethod("setShortCriticalText", CharSequence::class.java)
                    .invoke(builder, compact)
            }
        }
    }

    private fun ensureChannel(notificationManager: NotificationManager) {
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Bomb Live Updates", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun notificationId(eventId: String): Int = eventId.hashCode() and Int.MAX_VALUE

    private companion object {
        const val CHANNEL_ID = "bomb_live_updates"
        const val HYPER_PARAM_KEY = "miui.focus.param"
        const val MAX_COMPACT_TEXT = 48
    }
}

internal interface BridgeRenderer {
    val kind: BridgeRendererKind
    fun available(): Boolean
    fun render(event: BombLiveEvent): Boolean
}

private class NotificationBridgeRenderer(
    private val port: BridgeNotificationPort,
) : BridgeRenderer {
    override val kind = BridgeRendererKind.NOTIFICATION
    override fun available() = port.notificationAvailable()
    override fun render(event: BombLiveEvent) =
        port.post(event, BridgeNotificationMode.NOTIFICATION, null)
}

private class LiveUpdateBridgeRenderer(
    private val port: BridgeNotificationPort,
) : BridgeRenderer {
    override val kind = BridgeRendererKind.LIVE_UPDATE
    override fun available() = port.liveUpdateAvailable()
    override fun render(event: BombLiveEvent) =
        port.post(event, BridgeNotificationMode.LIVE_UPDATE, null)
}

private class HyperIslandBridgeRenderer(
    private val port: BridgeNotificationPort,
    private val probe: HyperIslandCapabilitySource,
    private val encoder: HyperIslandPayloadEncoder,
) : BridgeRenderer {
    override val kind = BridgeRendererKind.HYPER_ISLAND
    override fun available() = port.notificationAvailable() && probe.probe().available && encoder.available
    override fun render(event: BombLiveEvent): Boolean {
        val capability = probe.probe()
        if (!capability.available || !encoder.available) return false
        val payload = encoder.encode(event, capability.protocolVersion) ?: return false
        return port.post(event, BridgeNotificationMode.HYPER_ISLAND, payload)
    }
}

class LiveUpdateBridgeBackend internal constructor(
    private val port: BridgeNotificationPort,
    private val hyperProbe: HyperIslandCapabilitySource,
    private val hyperEncoder: HyperIslandPayloadEncoder = UnavailableHyperIslandPayloadEncoder,
) {
    private val renderers: Map<BridgeRendererKind, BridgeRenderer> = listOf(
        LiveUpdateBridgeRenderer(port),
        HyperIslandBridgeRenderer(port, hyperProbe, hyperEncoder),
        NotificationBridgeRenderer(port),
    ).associateBy { it.kind }
    private val statuses = LinkedHashMap<String, BridgeEventStatusParcel>()

    @Synchronized
    internal fun publish(event: BombLiveEvent): BombResult {
        BombLiveEventValidator.violations(event).firstOrNull()?.let {
            return BombResult.invalidArgument(it)
        }
        if (event.state == LiveEventState.DISMISSED) return dismiss(event.id)
        val availability = availability()
        for (kind in BridgeRoutingPolicy.candidates(availability)) {
            val renderer = renderers.getValue(kind)
            if (!renderer.render(event)) continue
            if (event.state == LiveEventState.ACTIVE || event.state == LiveEventState.PAUSED) {
                statuses[event.id] = BridgeEventStatusParcel(
                    event.id, kind.name, event.state.name, event.timestampMillis,
                )
            } else {
                statuses.remove(event.id)
            }
            trimStatuses()
            return BombResult.success()
        }
        return if (!availability.notification && !availability.liveUpdate && !availability.hyperIsland) {
            BombResult.unsupported("No permitted bridge renderer is available")
        } else {
            BombResult.backendUnavailable("Every available bridge renderer declined the event")
        }
    }

    @Synchronized
    internal fun dismiss(eventId: String): BombResult {
        if (!EVENT_ID.matches(eventId)) return BombResult.invalidArgument("eventId")
        port.cancel(eventId)
        statuses.remove(eventId)
        return BombResult.success()
    }

    @Synchronized
    internal fun snapshot(): BridgeStatusSnapshot {
        val hyper = hyperProbe.probe()
        return BridgeStatusSnapshot(
            notificationAvailable = port.notificationAvailable(),
            liveUpdateAvailable = port.liveUpdateAvailable(),
            hyperIslandFeaturePresent = hyper.featurePresent,
            hyperIslandProtocolVersion = hyper.protocolVersion,
            hyperIslandPermitted = hyper.permitted,
            hyperIslandPayloadAdapterAvailable = hyperEncoder.available,
            activeEvents = statuses.values.toList(),
        )
    }

    internal fun availability(): BridgeRendererAvailability = BridgeRendererAvailability(
        liveUpdate = renderers.getValue(BridgeRendererKind.LIVE_UPDATE).available(),
        hyperIsland = renderers.getValue(BridgeRendererKind.HYPER_ISLAND).available(),
        notification = renderers.getValue(BridgeRendererKind.NOTIFICATION).available(),
    )

    private fun trimStatuses() {
        while (statuses.size > MAX_ACTIVE_EVENTS) {
            statuses.remove(statuses.keys.first())
        }
    }

    companion object {
        internal fun create(context: Context, properties: SystemPropertyReader): LiveUpdateBridgeBackend =
            LiveUpdateBridgeBackend(
                AndroidBridgeNotificationPort(context),
                HyperIslandCapabilityProbe(context, properties),
            )

        private const val MAX_ACTIVE_EVENTS = 128
        private val EVENT_ID = Regex("^[A-Za-z0-9._:-]{1,128}$")
    }
}
