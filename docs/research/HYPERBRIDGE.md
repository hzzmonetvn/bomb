# Research — HyperBridge (HyperOS Island rendering)

```text
Reference:
https://github.com/D4vidDf/HyperBridge.git @ c31838361b192ff87ab57283aca16105c3c67966
(commit date 2026-07-30)

License: Apache-2.0 (LICENSE)
Single Gradle module :app (settings.gradle.kts)
Package: com.d4viddf.hyperbridge
```

Companion notes: [`LIVEBRIDGE.md`](./LIVEBRIDGE.md),
[`LIVE_UPDATES.md`](./LIVE_UPDATES.md) (which carries the three-way comparison
master plan §32 requires).

---

## 1. The capability probe — the single most useful finding

`util/XiaomiNotificationHelper.kt` is a complete, runnable HyperIsland capability
detector, and it is exactly what `BombCapabilities.HyperIslandBridge` needs:

```kotlin
// :15  — is the Island feature present at all?
fun isSupportIsland(): Boolean =
    getSystemPropertyBoolean("persist.sys.feature.island", false)

// :26  — which focus-notification protocol generation?
//        1 = OS1, 2 = OS2, 3 = OS3 / Hyper Island, 0 = unsupported/unknown
fun getFocusProtocolVersion(context: Context): Int =
    Settings.System.getInt(context.contentResolver, "notification_focus_protocol", 0)

// :44  — is *this package* allowed to post focus notifications?
fun hasFocusPermission(context: Context): Boolean {
    val uri = "content://miui.statusbar.notification.public".toUri()
    val extras = Bundle().apply { putString("package", context.packageName) }
    val bundle = context.contentResolver.call(uri, "canShowFocus", null, extras)
    return bundle?.getBoolean("canShowFocus", false) ?: false
}
```

Three independent signals, three different mechanisms — a system property, a
`Settings.System` int, and a ContentProvider call. All three are probed, none
assumed, and every one is wrapped in a `try`/`catch` returning the negative value.

**Bomb implications:**

1. `HyperIslandBridge` is a **three-state** capability, not a boolean:
   `Unsupported` (no island feature) / `Supported-but-not-permitted`
   (`canShowFocus == false`) / `Available`. The middle state is the interesting
   one — the feature exists but Bomb may not use it, and the UI must say which.
2. `getFocusProtocolVersion` returning 1/2/3 means the *payload format changes
   across HyperOS generations*. Bomb's `HyperOsRenderer` must branch on this
   value and report unsupported for `0`, rather than posting an OS3 payload to an
   OS1 device and rendering nothing.
3. `hasFocusPermission` is documented in the source itself as "an expensive
   operation and calls a ContentProvider" (:38-39). Probe it once at service
   start and on package/permission-change events — never per event.

---

## 2. How the island is actually posted

`service/PermanentIslandManager.kt:185-198`:

```kotlin
val data = HyperIslandData(builder.buildResourceBundle(), builder.buildJsonParam())

val notifBuilder = NotificationCompat.Builder(context, "hyper_bridge_notification_channel")
    .setSmallIcon(...)
    .setContentTitle("Permanent Island")
    .setPriority(NotificationCompat.PRIORITY_MIN)
    .setOngoing(true)

notifBuilder.addExtras(data.resources)

val notification = notifBuilder.build()
notification.extras.putString("miui.focus.param", data.jsonParam)

ShizukuManager.notify(context, PERMANENT_BRIDGE_ID, notification)
```

The HyperIsland surface is therefore: **an ordinary ongoing notification carrying
a JSON payload in the `miui.focus.param` extra, plus a resource `Bundle` of
bitmaps/icons.** `HyperIslandData` (models/HyperIslandData.kt) is literally
`(resources: Bundle, jsonParam: String)`. The JSON schema is built by the external
`hyperisland_kit` library, not by this repo.

**Bomb implication:** the renderer boundary is clean — build a payload, attach two
extras, post a notification. That is easy to isolate behind
`BridgeRenderer.render(BombLiveEvent)`, which is what master plan §23 requires.
The schema itself is undocumented vendor surface, so Bomb pins the library version
it builds against and treats a schema change as a capability regression, not a
crash.

---

## 3. The workaround that Bomb must not need

`util/ShizukuManager.kt:96-120`:

```kotlin
fun notify(context: Context, id: Int, notification: Notification) {
    ...
    // Briefly disable XMSF network to bypass MIUI/HyperOS interception
    XmsfNetworkHelper.setXmsfNetworkingEnabled(context, false)
    delay(50)
    NotificationManagerCompat.from(context).notify(id, notification)
    // Schedule network restore after 1 second
    ...
}
```

HyperBridge disables Xiaomi Message Service Framework networking through Shizuku
for ~1 second around each post, because HyperOS otherwise intercepts focus
notifications from apps that are not on its allowlist.

**Bomb implications, and this is a design-shaping one:**

1. **HyperOS gates focus notifications by package.** A third-party app has to
   fight the platform to use the island. This is the honest reason Bomb Bridge is
   ROM-mode-first: a ROM-integrated Bomb can be *in* that allowlist, and needs no
   workaround at all.
2. Bomb must **not** implement anything like this. Toggling a system service's
   network access around each notification is a race, is user-visible (XMSF
   handles push), and is precisely the kind of "detection evasion" the project
   rules exclude. If `canShowFocus` is false, Bomb reports the capability as
   not-permitted and falls back to the notification renderer.
3. It also tells us the fallback path matters more than the island path. Design
   `NotificationRenderer` first, `HyperOsRenderer` second.

---

## 4. Architecture: what to copy, what to avoid

`service/NotificationReaderService.kt` is a `NotificationListenerService` holding
ten translators (:112-122, :192-200):

```text
CallTranslator  NavTranslator  TimerTranslator  ProgressTranslator
DownloadTranslator  StandardTranslator  MessageTranslator  MediaTranslator
WidgetTranslator  LiveUpdateTranslator
```

Each translator both **parses** a source notification and **builds** the island
payload — `service/translators/*.kt` all call `buildResourceBundle()` /
`buildJsonParam()` directly.

**This is exactly the coupling master plan §23 forbids:**

> Do not tightly couple source parsing to the HyperIsland renderer.

Adding a second renderer (official Live Updates, or a plain notification) means
touching all ten translators. Bomb's shape instead:

```text
EventSource (notification listener, telemetry, recorder, charging, …)
        |
        v
BombLiveEvent   (id, sourcePackage, type, title, subtitle, icon,
                 progress, state, timestamp, actions)
        |
        +-- HyperOsRenderer        (miui.focus.param + resource Bundle)
        +-- LiveUpdateRenderer     (setRequestPromotedOngoing + ProgressStyle)
        +-- NotificationRenderer   (always-available fallback)
```

Sources produce `BombLiveEvent`. Renderers consume it. Neither knows the other.
A new source is one class; a new renderer is one class.

---

## 5. Other observations

- `models/NotificationType.kt`, `IslandConfig.kt`, `WidgetConfig.kt`,
  `IslandLimitMode.kt` show per-app configuration is a first-class concern —
  matching master plan §23's `BridgeRule` per source package.
- `service/WidgetOverlayService.kt` and `PermanentIslandManager` keep a persistent
  island alive; note the trick at :175-181 — an "empty" island is created with
  non-breaking spaces (`" ".repeat(currentWidth)`) to control width. That is a
  layout hack against an undocumented renderer, and a good illustration of why
  Bomb should keep the HyperOS payload construction inside a pinned library rather
  than hand-rolling it.
- `BootReceiver`, `InlineReplyService`/`InlineReplyReceiver` — action round-trip
  from the island back to the source notification. If Bomb's `BombLiveEvent.actions`
  is to work, the same `PendingIntent` forwarding is required; note it as scope,
  not as free.

---

## 6. What Bomb takes

| HyperBridge behaviour | Bomb decision |
| --- | --- |
| Three-signal capability probe (`persist.sys.feature.island`, `notification_focus_protocol`, `canShowFocus`) | **Adopt verbatim as behaviour** — this is the `HyperIslandBridge` probe |
| Protocol version 1/2/3 branching | Adopt; version 0 → unsupported |
| Island = ongoing notification + `miui.focus.param` JSON + resource `Bundle` | Adopt as the renderer's output contract |
| XMSF network toggle to bypass interception | **Reject** — report not-permitted and fall back |
| Per-type translators that both parse and render | **Reject** — split into `EventSource` → `BombLiveEvent` → `BridgeRenderer` |
| Per-app island configuration | Adopt as `BridgeRule` |
| Shizuku as the privilege path | Not applicable — Bomb is ROM-integrated |

---

## 7. Risks recorded

- **R-Bridge-1:** `miui.focus.param` schema is undocumented vendor surface and
  varies by protocol version. Mitigation: pin the builder library, gate on
  `getFocusProtocolVersion`, always keep the notification fallback.
- **R-Bridge-2:** `canShowFocus` may be false for Bomb even in ROM mode if the ROM's
  allowlist is not updated. Verification step for Phase 14: call the probe on the
  real build and report the result honestly rather than assuming priv-app status
  grants it.
- **R-Bridge-3:** `NotificationListenerService` requires user-granted notification
  access. Even a ROM-integrated Bomb should not silently pre-grant itself
  notification access; that is a user-visible privacy decision.
