# Research — LiveBridge (Android Live Updates + HyperOS dual rendering)

```text
Reference:
https://github.com/appsfolder/livebridge.git @ 11c935c0517b52cf823b3431fb9e42d9d3254c12
(commit date 2026-07-22)

License: GPL-3.0 (LICENSE) — research only, no code reuse.
Source root: android/app/src/main/kotlin/com/appsfolder/livebridge/liveupdate/
Sizes: LiveUpdateNotifier.kt 4979 lines, LiveParserDictionary.kt 831,
       LiveUpdateNotificationListenerService.kt 337, HyperBridgeAdapter.kt 232
```

Note: this repository is also vendored inside the sibling workspace project
`ProjectZK` (see `docs/ARCHITECTURE.md` §2.2), so its behaviour is already
observable on the target ROM.

---

## 1. It renders to *both* targets from one pipeline

This is the structural difference from HyperBridge, and the reason LiveBridge is
the better architectural model for Bomb.

Official Android Live Updates path (`LiveUpdateNotifier.kt`):

```kotlin
builder.setRequestPromotedOngoing(true)                      // :1848
...
NotificationCompat.ProgressStyle()                           // :1887, :1898
```

HyperOS path (`HyperBridgeAdapter.kt`):

```kotlin
private const val PARAM_KEY = "miui.focus.param"             // :26
fun apply(...) {                                             // :33
    if (!HyperIslandNotification.isSupported(context)) { ... }   // :47
    ...
}
```

One notifier produces the event representation; the HyperOS payload is applied by
an **adapter** that can decline (`isSupported`). That is exactly master plan §23's

```text
Sources -> BombLiveEvent -> { HyperIsland renderer | Live Update renderer | notification fallback }
```

with the adapter as the renderer. Bomb adopts this shape and makes the fallback
explicit rather than implicit.

---

## 2. Source parsing: what "normalize a notification" actually costs

`LiveParserDictionary.kt` (831 lines) is a data class of ~40 fields — regexes and
keyword sets, all externalised as configuration rather than code:

```kotlin
val smartRules: List<SmartRuleEntry>
val blockedSourcePackages: Set<String>
val privacyRedactionPlaceholders: Set<String>
val knownNavigationPackages: Set<String>
val navigationDistancePattern: Regex
val otpStrongTriggers: Set<String>
val otpLooseTriggerPattern: Regex
val moneyContextPattern: Regex
val textProgressPercentPattern: Regex
val weatherTemperaturePattern: Regex
... (weather condition patterns for thunder/rain/snow/fog/wind/sun/cloud)
```

And the extraction side reads the standard notification extras
(`LiveUpdateNotifier.kt:1256-1268, 1607-1616, 1776-1781`):

```text
EXTRA_TITLE, EXTRA_TITLE_BIG, EXTRA_TEXT, EXTRA_BIG_TEXT, EXTRA_SUB_TEXT,
EXTRA_SUMMARY_TEXT, EXTRA_INFO_TEXT, EXTRA_TEXT_LINES, EXTRA_MESSAGES,
EXTRA_PROGRESS, EXTRA_PROGRESS_MAX, EXTRA_PROGRESS_INDETERMINATE,
EXTRA_SHOW_CHRONOMETER, EXTRA_CHRONOMETER_COUNT_DOWN
```

**Bomb implications:**

1. **Structured extras are reliable; text heuristics are not.** `EXTRA_PROGRESS`,
   `EXTRA_PROGRESS_MAX`, `EXTRA_CHRONOMETER_COUNT_DOWN` and `MediaSession` metadata
   are contracts. Regex-mining "72%" out of `EXTRA_TEXT`
   (`textProgressPercentPattern`) is guesswork that breaks per app, per locale.
   Bomb's v1 sources use **only** structured signals; anything derived from free
   text must be marked as best-effort in the UI or not shipped.
2. Bomb's own first-party sources (telemetry, charging, recording, hotspot, VPN)
   need no parsing at all — they emit `BombLiveEvent` directly. Those should ship
   first, precisely because they are exact. Notification mirroring is the hard,
   heuristic part and belongs later in Phase 14.
3. `privacyRedactionPlaceholders` and OTP detection exist because mirroring
   notifications re-publishes their content. Bomb inherits that responsibility:
   **never mirror notification text into a log, and never persist it.** CLAUDE.md's
   "never log secrets" applies directly here — an OTP in a mirrored event is a
   secret.
4. `blockedSourcePackages` — a source blocklist is mandatory, not optional.

---

## 3. Deduplication and lifecycle — the non-obvious hard part

`LiveUpdateNotificationListenerService.kt`:

- `onNotificationPosted` / `onNotificationRemoved` (:115, :132) keyed by
  `sbn.key`;
- a **self-dismissal guard**: `rememberSelfDismissedSourceKey(sbn.key)` before
  `cancelNotification(sbn.key)` (:239-244), consumed in `onNotificationRemoved`
  (:138) — without it, Bomb cancelling the original notification would be seen as
  the user dismissing it and would tear down the mirror it just created;
- `result.dedupKind` decides whether the original is auto-dismissed (:224);
- a snapshot sync over `activeNotifications` at connect time (:50, :103) so a
  service restart re-adopts already-posted notifications instead of losing them.

**Bomb implications:** all three are requirements, not refinements.

1. `BombLiveEvent.id` must be derived from `(sourcePackage, sbn.key)` so repeated
   updates of the same notification update one event rather than creating many.
2. The self-dismiss guard is a real feedback loop; Bomb's `BridgeNormalizer` needs
   the same suppression set, and master plan §27's "loop prevention" applies to the
   bridge as well as to Rules.
3. On service (re)connect, reconcile against `getActiveNotifications()`; otherwise
   a Bomb restart leaves orphaned island entries with no owner.

---

## 4. Per-app presentation overrides

`AppPresentationOverrides.kt` models per-package overrides for which field feeds
the title, content, compact text and icon
(`NotificationTitleSource`, `NotificationContentSource`, `CompactTextSource`,
`NotificationIconSource`, each with a `from(raw: String?)` parser, :11-47), plus
`resolve(packageNameLower)` (:86) and `parse`/`encode` (:103, :140) for
persistence as a single encoded string.

**Bomb implication:** this is `BridgeRule` in master plan §23 — per-source-package
configuration of how an event is presented. Bomb stores it as a Room entity rather
than an encoded string, but the field set is a good starting point: title source,
content source, compact/critical text source, icon source, enabled, event type
override.

---

## 5. Device identification

`DeviceProps.kt` reads market name from, in order:
`ro.product.marketname`, `ro.config.marketing_name`, `ro.product.odm.marketname`,
`ro.product.vendor.marketname`, falling back to `Build.MODEL`, and treats
`"unknown"` as absent.

Minor, but it is the correct pattern for any property read Bomb does: an ordered
candidate list, blank/`unknown` rejected, an explicit fallback. Bomb's capability
detectors follow the same shape.

---

## 6. What Bomb takes

| LiveBridge behaviour | Bomb decision |
| --- | --- |
| One notifier, adapter applies HyperOS payload, adapter can decline | **Adopt** — this is the renderer split |
| Official `setRequestPromotedOngoing` + `ProgressStyle` | Adopt as `LiveUpdateRenderer` |
| `miui.focus.param` via adapter with `isSupported` gate | Adopt as `HyperOsRenderer` |
| Structured notification extras as the parse source | Adopt |
| Regex/keyword text mining (OTP, weather, money, progress-from-text) | v1: **out of scope**; if ever added, marked best-effort |
| Blocked source packages | Adopt (mandatory) |
| Privacy redaction of mirrored content | Adopt — and never persist or log event text |
| Self-dismiss guard around `cancelNotification` | Adopt |
| Snapshot reconcile on listener connect | Adopt |
| Per-app presentation overrides | Adopt as `BridgeRule` (Room entity) |
| 4979-line notifier | **Reject the shape** — split source/normalizer/renderer |

---

## 7. Testing strategy

`:domain`, pure JVM, against a fake source event stream:

- event identity: two updates of the same `sbn.key` produce one event with two
  revisions, not two events;
- self-dismiss suppression: cancel-then-removed does not tear down the mirror;
- reconcile: given N active source notifications at connect, exactly N events
  exist afterwards, no duplicates;
- blocked source package produces no event;
- progress normalization: `EXTRA_PROGRESS`/`EXTRA_PROGRESS_MAX` → 0..1, with
  `EXTRA_PROGRESS_INDETERMINATE` mapping to a distinct state, and max = 0 not
  causing a division by zero;
- renderer selection: with HyperOS unsupported, the event still renders through
  the notification fallback, and the chosen renderer is reported in the event's
  state so the UI never claims island rendering that did not happen.
