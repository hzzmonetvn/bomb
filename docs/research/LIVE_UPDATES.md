# Research — Android Live Updates (official) and the three-way comparison

```text
Reference:
https://github.com/android/platform-samples.git @ 16455a951b7c608b61ff2b45c17f6446384ed219
(commit date 2026-07-27)
License: Apache-2.0 (LICENSE)

Relevant source:
samples/user-interface/live-updates/src/main/java/com/example/platform/ui/
    live_updates/SnackbarNotificationManager.kt
samples/user-interface/live-updates/src/main/AndroidManifest.xml
```

Master plan §32 requires that Bomb Bridge research is not complete until
HyperBridge, LiveBridge and the official Android Live Updates are compared. §3 of
this note is that comparison. §34 sets the precedence: **official Android contracts
win over third-party assumptions.**

---

## 1. The official API surface

`SnackbarNotificationManager.kt:224-228` — the base builder:

```kotlin
fun buildBaseNotification(appContext: Context, orderState: OrderState): NotificationCompat.Builder {
    return NotificationCompat.Builder(...)
        .setOngoing(true)
        .setRequestPromotedOngoing(true)
        ...
}
```

Per-state content, e.g. :56-73:

```kotlin
.setContentTitle(
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
        SpannableStringBuilder().append(
            orderText,
            Notification.createSemanticStyleAnnotation(Notification.SEMANTIC_STYLE_INFO),
            0,
        )
    } else { orderText },
)
.setContentText("Confirming with bakery...")
.setShortCriticalText("Placing")
.setStyle(buildBaseProgressStyle(INITIALIZING).setProgressIndeterminate(true))
```

Progress structure, :179-210:

```kotlin
NotificationCompat.ProgressStyle()
    .setProgressPoints(listOf(ProgressStyle.Point(25).setColor(pointColor), ...))
    .setProgressSegments(listOf(ProgressStyle.Segment(25).setColor(segmentColor), ...))
```

So the official contract is:

| Element | API | Purpose |
| --- | --- | --- |
| opt into promotion | `setRequestPromotedOngoing(true)` + `setOngoing(true)` | asks the system to promote this ongoing notification |
| compact/critical text | `setShortCriticalText(String)` | the string shown in the collapsed/status-bar surface |
| progress | `NotificationCompat.ProgressStyle` with `Point` and `Segment` lists, or `setProgressIndeterminate(true)` | structured, styleable progress |
| semantic emphasis | `Notification.createSemanticStyleAnnotation(SEMANTIC_STYLE_INFO)` on a `Spannable` (API `CINNAMON_BUN`+) | marks text importance, version-gated |
| permission | `POST_NOTIFICATIONS` only (AndroidManifest.xml) | **no special permission, no allowlist** |

Two guarded API levels appear in the sample: `BAKLAVA` (Android 16) for
`ProgressStyle` / promoted ongoing, and `CINNAMON_BUN` for semantic style
annotations. Bomb's ROM targets Android 15/16, so `BAKLAVA` gating is required and
`CINNAMON_BUN` features must be treated as optional.

**`setShortCriticalText` is the single most important find for Bomb.** It is the
official, supported way to render exactly the compact strings master plan §6/§23
ask for:

```text
118 FPS | 41 C | 5.7 W
67% | 28 W | 37 C
Recording | 08:42
```

No vendor payload, no allowlist, no workaround.

---

## 2. What Bomb's `BombLiveEvent` must carry

Reconciling the three implementations, the intersection that all renderers can
consume:

| `BombLiveEvent` field | Live Updates | HyperOS island | Notification fallback |
| --- | --- | --- | --- |
| `title` | `setContentTitle` | JSON title field | `setContentTitle` |
| `subtitle` | `setContentText` | JSON sub-title | `setContentText` |
| `compactText` | **`setShortCriticalText`** | small-island text | `setSubText` |
| `progress` (0..1 or indeterminate) | `ProgressStyle` | JSON progress | `setProgress` |
| `icon` | `setSmallIcon` / large icon | resource `Bundle` | `setSmallIcon` |
| `state` | ongoing/promoted | island lifecycle | ongoing |
| `actions` | `addAction` | `HyperAction` list | `addAction` |
| `timestamp` | `setWhen` | JSON | `setWhen` |

`progress` deserves a note: the official API models it as **points and segments**,
not a single float. Bomb's model keeps a normalized `0..1` plus an optional list of
milestones, so the Live Update renderer can express segments while the other two
renderers degrade to a plain bar.

---

## 3. Three-way comparison (master plan §32 requirement)

| Dimension | Official Live Updates | HyperBridge | LiveBridge |
| --- | --- | --- | --- |
| Target surface | AOSP promoted ongoing notification | HyperOS Island (`miui.focus.param`) | both |
| Permission needed | `POST_NOTIFICATIONS` | `canShowFocus` from `content://miui.statusbar.notification.public` **plus** de-facto vendor allowlist | both |
| Capability probe | API level check (`BAKLAVA`) | `persist.sys.feature.island` + `notification_focus_protocol` + `canShowFocus` | `HyperIslandNotification.isSupported` + API level |
| Payload | typed builder API | undocumented JSON + resource `Bundle` | typed builder → adapter |
| Stability | platform contract, versioned | vendor surface, changes per OS1/2/3 | inherits both |
| Workarounds required | none | XMSF network toggle via Shizuku | none observed |
| Source→render coupling | n/a (app is the source) | **tight** — 10 translators each build island payload | **loose** — notifier + adapter |
| Compact text | `setShortCriticalText` | small-island string | both |
| Progress | `ProgressStyle` points/segments | JSON progress | both |
| License | Apache-2.0 | Apache-2.0 | GPL-3.0 |

**Conclusions, applying master plan §34's precedence:**

1. **`LiveUpdateRenderer` is the primary renderer**, not the fallback. It is an
   official contract, needs no vendor permission, and produces the compact strings
   Bomb wants. Previous framing (HyperOS first, notification fallback) inverts the
   risk.
2. **`HyperOsRenderer` is an enhancement**, gated behind the three-signal probe
   from [`HYPERBRIDGE.md`](./HYPERBRIDGE.md) §1, and never a prerequisite.
3. **`NotificationRenderer` remains the guaranteed floor** for devices below
   Android 16 or where promotion is denied. Promotion is a *request*
   (`setRequestPromotedOngoing`) — the system may decline, so the fallback is not
   hypothetical.
4. Bomb's renderer selection must be **observable**: `BombLiveEvent` carries which
   renderer actually handled it, so the UI states "shown as notification" instead
   of implying island rendering that never happened. This is the same anti-
   fabrication rule as telemetry.
5. LiveBridge's **architecture** is the model; its **code** is GPL-3.0 and off
   limits. HyperBridge and platform-samples are Apache-2.0, so their techniques may
   be reused with attribution — though the capability probe is three property/
   settings reads and is more cheaply reimplemented than vendored.

---

## 4. Order of work for Phase 14

1. `BombLiveEvent` model + `BridgeRenderer` interface + `NotificationRenderer`
   (works everywhere, testable immediately).
2. `LiveUpdateRenderer` behind an API-level probe.
3. First-party sources — telemetry, charging, recording, hotspot, VPN. These emit
   exact data with no parsing, and they are what makes the feature Bomb-specific.
4. `HyperOsRenderer` behind the capability probe.
5. Notification-mirroring sources last, structured extras only.

Doing HyperOS first would make the entire feature hostage to an undocumented
vendor surface and a permission Bomb may not hold.

---

## 5. Testing strategy

- renderer selection matrix: {API < 16, API ≥ 16} × {island supported/permitted,
  not} → exactly one renderer chosen, and it is reported in the event;
- promotion declined by the system → event still visible via the notification
  path, no crash, no duplicate;
- progress mapping: `0..1` → `ProgressStyle` segments; indeterminate preserved
  across all three renderers;
- compact text truncation rules are per-renderer and must not corrupt the event
  model (the event stores the full string, renderers truncate);
- `POST_NOTIFICATIONS` denied → capability reports unsupported rather than silently
  dropping events.
