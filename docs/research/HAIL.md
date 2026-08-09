# Research — Hail (freeze semantics)

```text
Reference:
https://github.com/aistra0528/Hail.git @ 4aef9ab2b9e7a2d04f55e9fa665f384b1deea25b
(commit date 2026-08-07)

License: GPL-3.0 (LICENSE)
Cross-checked against:
platform/frameworks/base @ 1cdfff555f4a21f71ccc978290e2e212e2f8b168
```

License verdict: GPL-3.0 — **research only, no code reuse.** Recorded in
[`REFERENCES.md`](./REFERENCES.md).

Master plan §31.3 requires this note to explicitly explain why `suspend`, `hide`,
`disable` and `force stop` are not equivalent. §1 does that with mechanical proof;
the rest is architecture.

---

## 1. `disable != hide != suspend != force-stop` — proven, not asserted

Hail detects each state from a **different bit** of `ApplicationInfo`
(`app/src/main/kotlin/com/aistra/hail/utils/HPackages.kt:47-68`):

```kotlin
fun isAppDisabled(packageName: String): Boolean =
    getApplicationInfoOrNull(packageName)?.enabled?.not() ?: false            // :47

fun isAppHidden(packageName: String): Boolean = getApplicationInfoOrNull(packageName)?.let {
    (ApplicationInfo::class.java.getField("privateFlags").get(it) as Int) and 1 == 1
} ?: false                                                                    // :49

fun isAppStopped(packageName: String): Boolean = getApplicationInfoOrNull(packageName)
    ?.run { flags and ApplicationInfo.FLAG_STOPPED == ApplicationInfo.FLAG_STOPPED } ?: false  // :53

fun isAppSuspended(packageName: String): Boolean = getApplicationInfoOrNull(packageName)?.let {
    when {
        HTarget.N -> it.flags and ApplicationInfo.FLAG_SUSPENDED == ApplicationInfo.FLAG_SUSPENDED
        else -> false
    }
} ?: false                                                                    // :57

fun isAppUninstalled(packageName: String): Boolean = getApplicationInfoOrNull(packageName)
    ?.run { flags and ApplicationInfo.FLAG_INSTALLED != ApplicationInfo.FLAG_INSTALLED } ?: true  // :66
```

Four distinct pieces of state, four distinct platform APIs, four distinct
observable behaviours:

| Mechanism | Platform write API | Stored as | Visible to the app? | Visible in launcher? | Survives reboot? | Reversal |
| --- | --- | --- | --- | --- | --- | --- |
| **force stop** | `ActivityManager.forceStopPackage` (AMS.java:3792, needs `FORCE_STOP_PACKAGES`) | `FLAG_STOPPED` on `PackageUserState` (`PackageManagerService.java:4666 setStopped`) | app is killed; flag readable | yes, still listed | flag persists, but any launch clears it | **none — it is an event, not a state** |
| **disable** | `PackageManager.setApplicationEnabledSetting` → `setEnabledSettings` (PackageManagerService.java:3849) | enabled setting per user | `ApplicationInfo.enabled == false` | icon removed | yes | re-enable |
| **hide** | `DevicePolicyManager.setApplicationHidden` → `setApplicationHiddenSettingAsUser` (PackageManagerService.java:5933) | `PRIVATE_FLAG_HIDDEN` | app behaves as **not installed** | icon removed | yes | un-hide |
| **suspend** | `DevicePolicyManager.setPackagesSuspended` → `SuspendPackageHelper.setPackagesSuspended` (SuspendPackageHelper.java:97) | `FLAG_SUSPENDED` + suspending-package + optional `SuspendDialogInfo` | app cannot start; **remains installed and queryable** | icon greyed, tap shows a dialog | yes | unsuspend |

AOSP-side constraints found while cross-checking, each of which Bomb must honour:

- `setApplicationHiddenSettingAsUser` **refuses** to hide a package that is an
  active device admin (`isPackageDeviceAdmin`, :5941-5944) and refuses `"android"`
  outright (:5946-5950). Returns `false`, does not throw.
- `setPackagesSuspended` returns **the names of failed packages** — a partial-
  failure API. Any wrapper returning a single boolean is discarding information.
- `forceStopPackage` returns `void` and silently ignores protected packages
  (see [`AOSP_PROCESS_MANAGEMENT.md`](./AOSP_PROCESS_MANAGEMENT.md) §1).

**The reversal column is the design-critical one.** Hail encodes it directly:

```kotlin
// AppManager.kt:47-58
HailData.MODE_SU_STOP      -> !frozen || HShell.forceStopApp(packageName)
HailData.MODE_PRIVAPP_STOP -> !frozen || HPackages.forceStopApp(packageName)
```

"Unfreezing" a force-stopped app is `!frozen` → `true` — a no-op that reports
success, because there is nothing to undo. Force stop cannot be modelled as a
reversible freeze state; it is a one-shot action whose effect ends at the next
launch.

**Bomb implication:** `FreezeMode.SOFT_FREEZE` must not be implemented as force
stop. Master plan §7's four modes map onto the platform as:

| Bomb mode | Mechanism | Reversible | Capability |
| --- | --- | --- | --- |
| `NORMAL` | none | — | always |
| `SOFT_FREEZE` | background restriction / cgroup freezer via `CachedAppOptimizer` | yes | `SoftFreeze` |
| `DEEP_FREEZE` | suspend (preferred) or hide | yes | `DeepFreeze` |
| `DISABLED` | `setApplicationEnabledSetting` | yes | `ComponentControl` |

Force stop stays what it is: a **Task Manager action**, never a freeze mode.

Recommendation between suspend and hide for `DEEP_FREEZE`: **suspend**. Hidden
apps behave as uninstalled, which breaks other apps' queries, backup, and the
app's own data lifecycle assumptions; suspend keeps the package queryable, shows
the user a dialog on launch, and carries the suspending-package identity so the
platform can attribute and undo it. Hide remains available where suspend is
unsupported, reported as a distinct capability.

---

## 2. The `mode = privilege × mechanism` matrix

`AppManager.setAppFrozen` (app/src/main/kotlin/com/aistra/hail/app/AppManager.kt:45-67)
is a flat `when` over composite mode constants:

```text
OWNER   × { HIDE, SUSPEND }                  -> HPolicy   (DevicePolicyManager, device owner)
DHIZUKU × { HIDE, SUSPEND }                  -> HDhizuku
SU      × { STOP, DISABLE, HIDE, SUSPEND }   -> HShell    (root shell)
SHIZUKU × { STOP, DISABLE, HIDE, SUSPEND }   -> HShizuku
ISLAND  × { HIDE, SUSPEND }                  -> HIsland
PRIVAPP × { STOP, DISABLE }                  -> HPackages (direct framework calls)
```

Two observations:

1. **The matrix is sparse, and the gaps are capability facts.** `OWNER` cannot
   force-stop or disable; `PRIVAPP` cannot hide or suspend (those need device-owner
   authority). Hail encodes this by simply not offering the combination. This is
   precisely master plan §3.3's capability-first design, discovered empirically by
   a mature app.
2. `PRIVAPP` is Bomb's ROM mode. Hail's privileged-app path
   (`HPackages.forceStopApp` :77, `HPackages.setAppDisabled` :88) uses direct
   framework calls — `ActivityManager.forceStopPackage` via
   `HiddenApiBypass.invoke`, and `PackageManager.setApplicationEnabledSetting`.
   **Bomb reaches the same two operations without reflection**, because a
   ROM-integrated priv-app with the right allowlist calls them directly.

`HPolicy` (utils/HPolicy.kt:24-30) shows what device-owner authority buys:

```kotlin
fun setAppHidden(packageName: String, hidden: Boolean): Boolean =
    isDeviceOwnerActive && dpm.setApplicationHidden(admin, packageName, hidden)

fun setAppSuspended(packageName: String, suspended: Boolean): Boolean =
    isDeviceOwnerActive && HTarget.N && dpm.setPackagesSuspended(
        admin, arrayOf(packageName), suspended
    ).isEmpty()
```

Note `.isEmpty()` — it collapses the failed-package array into a boolean, losing
which package failed. Bomb keeps the array.

**Bomb decision:** Bomb does **not** become a device owner. Device-owner status is
exclusive, conflicts with enterprise management, is hard to remove, and would make
Bomb a single point of failure for the device. Suspend/hide are reached through the
privileged service instead, and if the ROM cannot grant that, the capability
reports unsupported rather than prompting the user to run a `dpm set-device-owner`
command.

---

## 3. Verify-by-observation, not by return value

`HPackages.setAppDisabled` (:88-100):

```kotlin
fun setAppDisabled(packageName: String, disabled: Boolean): Boolean {
    getApplicationInfoOrNull(packageName) ?: return false
    if (disabled) forceStopApp(packageName)          // disable alone does not kill the process
    runCatching {
        val newState = if (!disabled) COMPONENT_ENABLED_STATE_ENABLED else COMPONENT_ENABLED_STATE_DISABLED
        app.packageManager.setApplicationEnabledSetting(packageName, newState, 0)
    }.onFailure { HLog.e(it) }
    return isAppDisabled(packageName) == disabled     // re-read, don't trust the call
}
```

Three things Bomb adopts verbatim as *behaviour* (not code):

1. **Disabling does not stop the running process** — force stop must be issued
   first, or the app keeps running until it dies on its own.
2. **The result is derived from a re-read of platform state**, not from the API
   returning without throwing. This matches the AOSP finding that
   `forceStopPackage` returns `void` and `setApplicationHiddenSettingAsUser`
   returns `false` silently.
3. Existence is checked first; a missing package fails fast rather than producing
   a confusing platform exception.

---

## 4. Exclusions

Hail's protection is minimal and package-scoped:

```kotlin
// AppManager.kt:23  — setListFrozen
val excludeMe = appInfo.filter { it.packageName != BuildConfig.APPLICATION_ID }
// AppManager.kt:45  — setAppFrozen
fun setAppFrozen(packageName: String, frozen: Boolean): Boolean =
    packageName != BuildConfig.APPLICATION_ID && when (...)
```

Only self-exclusion. There is no built-in protection for SystemUI, the launcher,
the active IME, or framework packages — the user is expected not to freeze them.

**Bomb implication:** master plan §7's mandatory exclusion list (Bomb, SystemUI,
launcher, current IME, essential framework packages, vendor-critical packages) is
a genuine improvement, not redundancy. It must be enforced in `:domain`
(`CriticalPackages`) so it is unit-testable and cannot be bypassed from the UI —
and the *current* IME must be resolved at decision time, not from a static list,
because it changes.

---

## 5. Auto-freeze: Hail's policy is much simpler than Bomb's

`AutoFreezeService` (services/AutoFreezeService.kt) is a foreground
`NotificationListenerService` that registers a `ScreenOffReceiver` for
`Intent.ACTION_SCREEN_OFF` and posts a notification with "freeze now" actions.
The trigger is **screen off** (plus manual actions and `AutoFreezeWorker`).

Master plan §7 asks for considerably more:

```text
App leaves foreground -> +30s -> background restricted -> +5min -> Deep Freeze
```

That requires foreground/background transition events, per-app timers that survive
process death, and a "unfreeze on explicit user launch" path — none of which exist
in Hail. Bomb's `FreezePolicyEngine` is therefore new work with no reference
implementation to lean on, which is exactly why the master plan lists it as the
top-priority test target.

Design notes falling out of this gap:

- foreground transitions come from `UsageStatsManager` events or an
  `ActivityTaskManager` observer in the privileged service — not from polling;
- timers must be tracked as *deadlines* (absolute times) in the service, so a
  service restart re-derives pending work rather than losing it;
- "unfreeze on explicit user launch" needs a launch interception point; for suspend
  mode the platform already shows a dialog, which is the natural hook.

---

## 6. What Bomb takes

| Hail behaviour | Bomb decision |
| --- | --- |
| Four mechanisms detected from four different `ApplicationInfo` bits | Adopt as the capability model; each is a separate `BombCapabilities` entry |
| Force stop modelled as `!frozen \|\| forceStop(...)` | Adopt the *semantics*: force stop is an action, never a freeze state |
| `mode = privilege × mechanism`, sparse matrix | Adopt as `FreezeBackend` capability matrix |
| Device owner for hide/suspend | **Reject** — Bomb does not become device owner |
| Verify by re-reading platform state | Adopt |
| Force stop before disable | Adopt |
| Boolean collapse of `setPackagesSuspended`'s failed array | **Reject** — keep per-package results |
| Self-exclusion only | **Reject** — full critical-package exclusion in `:domain` |
| Screen-off auto-freeze | Insufficient — Bomb implements the tiered policy engine |

---

## 7. Testing strategy

`:domain`, pure JVM:

- `CriticalPackages`: Bomb itself, SystemUI, resolved launcher, **current** IME,
  framework packages, vendor list — each rejected with a typed reason;
- mode→mechanism resolution given a capability set, including "no mechanism
  available" → `Unsupported`;
- `FreezePolicyEngine`: foreground-departure delay, screen-off trigger, tier
  escalation, manual override beating automation, unfreeze-on-launch, and no
  oscillation across a state sequence;
- deadline recomputation after a simulated service restart.

Device (Phase 5/6 exit gate):

- for each mechanism, freeze then read back the corresponding `ApplicationInfo`
  bit — the four must not be conflated;
- suspend on a package that the platform refuses reports the failed package;
- disable on a running app kills the process (force-stop-then-disable ordering).
