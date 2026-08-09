# Research — HMA-OSS (Hide My Applist, OSS fork)

```text
Reference:
https://github.com/frknkrc44/HMA-OSS.git @ 72fd0f1d60b0b02ee9f2b1cf6f9d638e4d0dbeda
(commit date 2026-07-10)

License: AGPL-3.0  (LICENSE.md — "GNU AFFERO GENERAL PUBLIC LICENSE Version 3")
Modules: :app, :common, :zygote  (settings.gradle.kts)
Package namespaces: org.frknkrc44.hma_oss.* (zygote), icu.nullptr.hidemyapplist.common.*
```

> **License verdict up front: research only. No code, no adapted snippets, no
> derived files.** AGPL-3.0 is incompatible with shipping Bomb as part of a ROM
> image without licensing the whole of Bomb under AGPL. Everything below is a
> description of observed behaviour, written to inform an independent design.
> Recorded in [`REFERENCES.md`](./REFERENCES.md).

---

## 1. Required flow (master plan §32)

> target app → framework/API interception → calling UID resolution →
> visibility/settings policy lookup → decision → filtered/virtualized result

Traced concretely:

```text
target app calls PackageManager.getInstalledPackages()
        |
system_server, hooked at load time by the :zygote module
   (ZygoteEntry.java -> SystemServerHook.kt -> BulkHooker)
        |
hookBefore("com.android.server.pm.AppsFilterImpl", "shouldFilterApplication")
   PmsHookTarget34.kt:67-83
        |
callingUid  = frame.getArgument(2)            (arg 2 of shouldFilterApplication)
targetPkg   = getPackageNameFromPackageSettings(frame.getArgument(4))
callingApps = getPackagesForUid(snapshot, callingUid)   [binderLocalScope]
        |
applyPackageHiding(...)                        PmsHookTargetBase.kt:245-273
        |
HMAServiceCache.shouldHideFromUid(uid, target)  -> cache hit?
   else HMAService.shouldHide(caller, query, userId)   HMAService.kt:340-386
        |
returnValue.result = true      // "filter it"
```

`shouldFilterApplication`'s argument indices used by HMA
(`frame.getArgument(2)` = `callingUid`, `getArgument(4)` = `targetPkgSetting`)
match the AOSP signature verified independently in
[`AOSP_PACKAGE_MANAGER.md`](./AOSP_PACKAGE_MANAGER.md):

```java
// AppsFilterBase.java:333
public boolean shouldFilterApplication(PackageDataSnapshot snapshot, int callingUid,
        @Nullable Object callingSetting, PackageStateInternal targetPkgSetting, int userId)
```

**Cross-check result: HMA and AOSP agree on the chokepoint.** Bomb's independent
conclusion — patch `AppsFilterBase.shouldFilterApplication` — is confirmed by the
most mature implementation in this space. Bomb, having source access, patches the
base class directly instead of hooking the concrete `AppsFilterImpl`, which also
covers `AppsFilterSnapshotImpl` (HMA cannot, because it hooks one concrete class).

---

## 2. The extra hooks — a leak-surface catalogue

HMA does not stop at `shouldFilterApplication`. What it *additionally* hooks is the
most valuable output of this research, because each one is a path that does not
consult `AppsFilter`:

| Surface | Hook target | HMA source |
| --- | --- | --- |
| bulk package-state accessor | `ComputerEngine.getPackageStates` (hookAfter, entries removed from the returned `ArrayMap`) | PmsHookTargetBase.kt:53-88 |
| archived packages | `PackageManagerService.getArchivedPackageInternal` / Samsung's `getArchivedPackage` | PmsHookTarget34.kt:85-103 |
| permission holders | `ComputerEngine.addPackageHoldingPermissions` | PmsHookTargetBase.kt:107-119 |
| direct lookup | `ComputerEngine.getPackageInfoInternal`, `getApplicationInfoInternal` | PmsHookTargetBase.kt:120-143 |
| Samsung `generatePackageInfo` | `ComputerEngine.generatePackageInfo` | PmsHookTargetBase.kt:90-104 |
| UID → packages | `ComputerEngine.getPackagesForUid` | PmsHookTarget34.kt:27 |
| installer identity | `getInstallerPackageName`, `getInstallSourceInfo`, `isCallerInstallerOfRecord`, `PackageManagerNative.getInstallerForPackage` | PmsHookTargetBase.kt:145-240 |
| activity start | `ActivityStarter.executeRequest`, `checkStartAnyActivityPermission`, `ComputerEngine.applyPostResolutionFilter` | ActivityHook.kt |
| broadcasts | `BroadcastController.broadcastIntentLocked` | BroadcastHook.kt |
| package-change broadcasts | `BroadcastHelper.sendPackageBroadcast`, `PackageMonitor.onReceive` | PmsPackageEventsHook.kt |
| IME list | `InputMethodManagerService.getInputMethodList` / `getEnabledInputMethodList` | ImmHook.kt |
| accessibility services | `AccessibilityManagerService.getEnabledAccessibilityServiceList`, `addClient` | AccessibilityHook.kt |
| app data isolation (vold) | `StorageManagerService.needsStorageDataIsolation`, `ProcessList.startProcess` | AppDataIsolationHook.kt |
| Settings values | `android.content.ContentProvider$Transport.query` / `call` | ContentProviderHook.kt |

Class-name constants are in `zygote/util/ZygoteConstants.kt`.

**Bomb implications:**

1. `getArchivedPackageInternal` is cited in HMA's own comment as an AOSP-side
   visibility leak (referencing `aosp-mirror/platform_frameworks_base` commit
   `5bc482bd99ea18fe0b4064d486b29d5ae2d65139`). Bomb must check the archived-package
   path in its Phase 8 verification matrix — it is a real, documented hole.
2. `ComputerEngine.getPackageStates` is a bulk internal accessor that returns raw
   `PackageStateInternal`s without per-entry filtering. Bomb inherits this exposure
   and must decide explicitly: either it is system-internal (callers below
   `FIRST_APPLICATION_UID`, therefore exempt anyway) or it needs the same treatment.
3. Activity start, broadcasts, IME list and accessibility list are **not** PM
   visibility at all. They are separate features HMA bundles under one product.
   Bomb's Phase 8 scope is package visibility; these belong to later phases or to
   an explicit "not covered" statement. Claiming HMA parity without them would be
   dishonest.
4. The per-SDK hook targets (`PmsHookTarget29/30/31/33/34`) exist only because
   HMA must survive on unknown ROMs. Bomb builds against one known ROM's source
   and needs **no** version-dispatch layer — a significant simplification, and a
   reason not to imitate HMA's structure.

---

## 3. The policy function, and what is wrong with it for Bomb

`HMAService.shouldHide(caller: String?, query: String?, userId: Int)` — HMAService.kt:340.
Evaluation order as written:

1. `caller == null || query == null` → false
2. `caller == BuildConfig.APP_PACKAGE_NAME` → false (self-exemption)
3. `caller in Constants.packagesShouldNotHide || query in ...` → false
   (`common/.../Constants.kt:92`)
4. `caller == query` → false
5. `config.scope[caller] ?: return false` — no config for this caller → false
6. WebView/browser protection: current WebView provider and default browser are
   never hidden and never hidden *from*
7. `query in appConfig.extraAppList` → `!useWhitelist`
8. `query in appConfig.extraOppositeAppList` → `useWhitelist`
9. templates (`config.templates[...]`), with a GMS-connection exemption
10. presets (`AppPresets`), with a Play-Store→GMS caller substitution
11. `useWhitelist && excludeSystemApps && query in systemApps` → false
12. default: `useWhitelist`

**Observation that matters most:** `userId` is a parameter, but the hide decision
never uses it. It is consumed only by `getDefaultBrowser(userId)` in step 6. The
policy itself is keyed by **package name only** — `config.scope[caller]`.

This is precisely the mistake master plan §12 "Multi-user" forbids:

> Policy identity must include `userId`. Do not key policy only by package name.

**Bomb implication:** Bomb keys policy by `(userId, callerPackage)` and resolves
the caller from `Binder.getCallingUid()` → `UserHandle.getUserId(uid)` +
`getPackagesForUid`. A work-profile clone of an app must be configurable
independently of its primary-user instance. This is a deliberate divergence from
HMA, justified here.

Steps 2/3/4 (self-exemption, hard exemption set, caller==target) are sound and
Bomb reproduces the *concept* — as `BombVisibilityPolicy` exemptions — with its
own list.

---

## 4. The cache — a clear "do not copy"

`HMAServiceCache` (service/HMAServiceCache.kt, 28 lines total):

```kotlin
private val uidHideCache = mutableListOf<Triple<Int, String, MutableList<String>>>()

fun shouldHideFromUid(uid: Int, query: String?): Boolean? {
    if (query == null) return null
    return uidHideCache.firstOrNull { it.first == uid && it.third.contains(query) } != null
}
```

Problems, on a path that runs on **every PackageManager query of every app**:

- linear scan of a `MutableList` of `Triple`s, plus a nested `List.contains`;
- unsynchronized mutable state read and written from arbitrary binder threads;
- negative results are never cached, so the miss path always reaches
  `shouldHide` with its template/preset scans;
- one `caller` string per uid, which silently mis-attributes shared-UID callers;
- invalidation is all-or-nothing (`clearUidCache()`).

**Bomb implication — this validates master plan §12 "Performance" as a hard rule.**
Bomb compiles configuration into an immutable `VisibilityPolicySnapshot`
(revision + per-user `SparseArray<uid → hidden set>`), published through a single
`volatile` reference and swapped atomically on change. Lookup is O(1) array
indexing with no allocation and no locking, positive and negative alike. No
per-check I/O of any kind, which is the master plan's explicit requirement.

---

## 5. Settings virtualization in HMA — one genuinely important trick

`ContentProviderHook` (hook/ContentProviderHook.kt) hooks the *generic*
`android.content.ContentProvider$Transport` in system_server and filters for
`uri.authority == "settings"`:

- `query` (hookAfter, :31) — single-setting URIs return a fabricated
  `MatrixCursor(["name","value"])`; list URIs are rebuilt row by row with
  replacements substituted;
- `call` (hookBefore, :137) — intercepts `GET_global` / `GET_secure` / `GET_system`
  and returns:

```kotlin
returnValue.result = Bundle().apply {
    putString(Settings.NameValueTable.VALUE, replacement.value)
    putInt("_generation_index", -1)
}
```

`"_generation_index"` is `Settings.CALL_METHOD_GENERATION_INDEX_KEY`
(verified: `core/java/android/provider/Settings.java:3007`). On the client side:

```java
// Settings.java (NameValueCache.getStringForUser)
final int index = b.getInt(CALL_METHOD_GENERATION_INDEX_KEY, -1);
if (array != null && index >= 0) { ...install GenerationTracker... }
else { maybeCloseGenerationArray(array); }
...
if (mGenerationTrackers.get(name) != null && !isGenerationChanged()) {
    mValues.put(name, value);      // only cached when a tracker exists
}
```

**Returning index `-1` suppresses client-side caching entirely for that key.** The
app re-queries the provider on every read, so the per-caller value can never go
stale and no generation bookkeeping is needed.

**Bomb implication — this supersedes the "bump the generation" approach sketched
in [`AOSP_SETTINGS_PROVIDER.md`](./AOSP_SETTINGS_PROVIDER.md) §4.** For a key that
Bomb virtualizes, Bomb omits generation data from the returned `Bundle` instead of
invalidating everyone's cache. Cost: one binder call per read of that key by that
app. Benefit: correctness without global invalidation, and non-virtualized keys keep
their fast cached path untouched. Both notes now record this decision.

Two things Bomb does **differently**:

1. **Hook the typed provider, not the generic transport.** Bomb patches
   `SettingsProvider.getSystemSetting/getSecureSetting/getGlobalSetting`.
   `ContentProvider$Transport.call` is on the path of *every* provider call in
   system_server; adding a per-call authority string comparison there is a cost
   Bomb does not need to pay when it can patch the settings provider itself.
2. **Resolve the caller from `Binder.getCallingUid()`, not from the
   `AttributionSource` package name.** HMA's `getCallingPackages`
   (ContentProviderHook.kt:167-177) reads `attrSource.packageName` and falls back to
   `ServiceUtils.getCallingApps()` on any throw. Bomb's rule is that privileged
   decisions derive from the kernel-supplied UID, never from an attribution string.

`HMAService.getSpoofedSetting(caller, name, database)` (HMAService.kt:307-329)
resolves per-caller templates then presets, and its `ReplacementItem` carries a
`database` field ("system"/"secure"/"global") — the same namespace split the master
plan §13 requires. Bomb's `SettingsOverride` model matches this shape but adds an
explicit `valueType`, which HMA lacks (everything is a `String`).

---

## 6. What Bomb takes from this research

| HMA behaviour | Bomb decision |
| --- | --- |
| Hook `AppsFilterImpl.shouldFilterApplication` | Patch `AppsFilterBase.shouldFilterApplication` — same chokepoint, covers snapshot impl too |
| Per-SDK hook target classes | Not needed; Bomb builds against a known source tree |
| Policy keyed by caller package only | Bomb keys by `(userId, callerPackage)` |
| Linear-list uid cache, unsynchronized | Immutable snapshot + volatile publish, O(1), allocation-free |
| Self/hard exemption list | Same concept, Bomb-owned list, centralized |
| Installer-source spoofing (`fake user/system install source`) | **Out of scope** — adjacent to store/integrity spoofing that master plan §13 forbids |
| Settings via generic `ContentProvider$Transport` | Bomb patches `SettingsProvider` getters directly |
| `_generation_index = -1` to suppress client caching | **Adopted** — the correct mechanism for per-caller values |
| Untyped string setting values | Bomb adds explicit `valueType` and validates |
| Zygisk/Xposed delivery | Bomb delivers as a ROM framework patch; capability reports unsupported in root mode |

---

## 7. Open questions this research did not answer

- HMA hooks `ActivityStarter.executeRequest` for launch-time hiding. Bomb has not
  decided whether a hidden package should remain launchable by explicit component
  start (AOSP's `resolveForStart ||` short-circuit means it is, by default). Flag
  for the Phase 8 design review; the master plan does not state a preference.
- `AppDataIsolationHook` touches `StorageManagerService.needsStorageDataIsolation`.
  Whether package hiding has any interaction with vold data isolation on the target
  ROM is untested and unclaimed.
- HMA's `RiskyPackageUtils` / preset lists encode a threat model (detector apps,
  root apps, Xposed modules). Bomb takes no position on those lists and ships no
  equivalent preset content.
