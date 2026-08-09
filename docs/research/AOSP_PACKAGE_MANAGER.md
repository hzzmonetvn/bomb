# Research — AOSP PackageManager visibility

```text
Reference:
platform/frameworks/base @ 1cdfff555f4a21f71ccc978290e2e212e2f8b168
(main, commit date 2025-03-26, "Merge \"Remove Redundant Variable for
getStatusBarHeightForRotation\" into main")

Checkout:
sparse cone — core/java/android/app, core/java/android/content/pm,
core/java/android/provider, packages/SettingsProvider,
services/core/java/com/android/server/{am,pm,wm}
License: Apache-2.0 (MODULE_LICENSE_APACHE2 at repo root; NOTICE present)
```

Research question from the master plan §31.4:

> Where can Bomb insert one shared caller-aware visibility policy with the fewest,
> safest framework patches?

**Answer: one method — `AppsFilterBase.shouldFilterApplication(...)`.** Everything
else in this note is the evidence, the exemptions that must be preserved, and the
surfaces that method does *not* cover.

---

## 1. The two-layer structure

```text
IPackageManager binder call
        |
        v
PackageManagerService.snapshotComputer()      PackageManagerService.java:1157
        |
        +-- snapshot  -> ComputerEngine        (immutable, lock-free)
        +-- live      -> ComputerLocked        ComputerLocked.java:30
                          extends ComputerEngine
        |
        v
ComputerEngine.shouldFilterApplication(...)    ComputerEngine.java:2513
        |
        v
AppsFilterSnapshot.shouldFilterApplication()   AppsFilterSnapshot.java:66
        implemented once in
AppsFilterBase.shouldFilterApplication(...)    AppsFilterBase.java:333
        |
        +-- AppsFilterImpl        (mutable, extends AppsFilterLocked -> AppsFilterBase)
        +-- AppsFilterSnapshotImpl (extends AppsFilterBase)
```

`ComputerLocked extends ComputerEngine` (ComputerLocked.java:30) and both
`AppsFilterImpl` (AppsFilterImpl.java:95 → AppsFilterLocked.java:27) and
`AppsFilterSnapshotImpl` (AppsFilterSnapshotImpl.java:27) extend
`AppsFilterBase`. A patch in the base class is therefore live-path and
snapshot-path simultaneously — no duplicated policy.

---

## 2. Verified call sites

`ComputerEngine.java` contains **104** textual occurrences of
`shouldFilterApplication`. The overloads at lines 2594 / 2606 / 2617 / 2627 /
2643 / 2653 / 2664 all funnel into the 7-argument root overload at line 2513,
whose last statement is:

```java
// ComputerEngine.java:2585-2587
int appId = UserHandle.getAppId(callingUid);
final SettingBase callingPs = mSettings.getSettingBase(appId);
return mAppsFilter.shouldFilterApplication(this, callingUid, callingPs, ps, userId);
```

Audit surfaces required by master plan §12, each verified in this commit:

| Master-plan surface | Method | File:line | Filter call |
| --- | --- | --- | --- |
| installed packages | `getInstalledPackagesBody` | ComputerEngine.java:1709 | `shouldFilterApplication(ps, callingUid, userId)` |
| installed applications | `getInstalledApplications` | ComputerEngine.java:4660 | same |
| package info | `getPackageInfoInternal` | ComputerEngine.java:1593 | same |
| application info | `getApplicationInfoInternal` | ComputerEngine.java:997 | same |
| package → UID | `getPackageUidInternal` | ComputerEngine.java:2703 | `shouldFilterApplication(ps, callingUid, userId)` ×2 (normal + `MATCH_KNOWN_PACKAGES`) |
| UID → packages | `getPackagesForUidInternalBody` | ComputerEngine.java:2001 | same, per package of a shared UID |
| UID → name | `getNameForUid` / `getNamesForUids` | ComputerEngine.java:4384 / 4421 | `shouldFilterApplicationIncludingUninstalled` |
| GIDs | `getPackageGids` | ComputerEngine.java:3728 | via `shouldFilterApplication` |
| activity resolution | `applyPostResolutionFilter` | ComputerEngine.java:1277 | **`mAppsFilter.shouldFilterApplication` directly** |
| service resolution | `applyPostServiceResolutionFilter` | ComputerEngine.java:1311 | **`mAppsFilter.shouldFilterApplication` directly** |
| receiver resolution | `ResolveIntentHelper.queryIntentReceiversInternal` | ResolveIntentHelper.java:434 | delegates to `computer.applyPostResolutionFilter` |
| provider lookup | `resolveContentProvider` | ComputerEngine.java:4804 | `shouldFilterApplication(packageState, callingUid, component, TYPE_PROVIDER, userId)` |
| provider enumeration | `queryContentProviders` | ComputerEngine.java:4889 | `shouldFilterApplication(...)` per entry |
| cross-service (AMS/WMS) | `filterAppAccess` ×3 | ComputerEngine.java:2997 / 3002 / 3009 | consumed by `am/ContentProviderHelper.java`, `am/ActivityManagerService.java`, `am/ActiveServices.java`, `wm/ActivityStarter.java`, `wm/ActivityRecord.java` |
| LauncherApps | `LauncherAppsService` | LauncherAppsService.java:650, 1149, 2251 | `mPackageManagerInternal.filterAppAccess(...)` |

**The important negative result:** intent resolution does **not** go through
`ComputerEngine.shouldFilterApplication`. `applyPostResolutionFilter`
(ComputerEngine.java:1213) and `applyPostServiceResolutionFilter`
(ComputerEngine.java:1303) call `mAppsFilter.shouldFilterApplication` directly,
bypassing the ComputerEngine wrapper entirely.

```java
// ComputerEngine.java:1270-1279  (applyPostResolutionFilter)
if (ephemeralPkgName == null) {
    // caller is a full app
    SettingBase callingSetting =
            mSettings.getSettingBase(UserHandle.getAppId(filterCallingUid));
    PackageStateInternal resolvedSetting =
            getPackageStateInternal(info.activityInfo.packageName, 0);
    if (resolveForStart
            || !mAppsFilter.shouldFilterApplication(this,
            filterCallingUid, callingSetting, resolvedSetting, userId)) {
        continue;
    }
}
```

**Bomb implication:** a patch placed only in `ComputerEngine` would hide a package
from `getInstalledPackages()` while leaving it fully resolvable through
`queryIntentActivities()` — precisely the inconsistency master plan §12 forbids.
`AppsFilterBase.shouldFilterApplication` is below both, so it is the correct and
only necessary hook.

Note `resolveForStart ||` short-circuits the filter: an *actual launch* is never
filtered, only enumeration/resolution. Bomb must decide deliberately whether a
hidden package stays launchable by an explicit component start (recommendation:
yes — hiding is a discovery policy, not a sandbox).

---

## 3. What `AppsFilterBase.shouldFilterApplication` already decides

`AppsFilterBase.java:333-375`, in order:

```java
int callingAppId = UserHandle.getAppId(callingUid);
if (callingAppId < Process.FIRST_APPLICATION_UID          // (1)
        || targetPkgSetting.getAppId() < Process.FIRST_APPLICATION_UID   // (2)
        || callingAppId == targetPkgSetting.getAppId()) { // (3)
    return false;
} else if (Process.isSdkSandboxUid(callingAppId)) { ... }
// (4) cache
if (mCacheReady && mCacheEnabled) {
    if (!shouldFilterApplicationUsingCache(callingUid, targetPkgSetting.getAppId(), userId)) {
        return false;
    }
} else {
    if (!shouldFilterApplicationInternal((Computer) snapshot, callingUid, callingSetting,
            targetPkgSetting, userId)) {
        return false;
    }
}
return !DEBUG_ALLOW_ALL;
```

Consequences that directly constrain Bomb:

1. **Caller below `FIRST_APPLICATION_UID` (10000) is never filtered.** system_server,
   root, shell, radio, and every `android.uid.system` sharedUserId process. This is
   exactly master-plan §12's "do not apply user visibility filtering to internal
   system_server package-management operations" — AOSP already guarantees it, and
   Bomb must insert *after* this check or replicate it.
2. **Target below `FIRST_APPLICATION_UID` is never filtered.** A system/priv-app
   *target* cannot be hidden through this mechanism at all. If Bomb wants to hide a
   system package from a normal caller, the check must be inserted *before* line
   340 — and Bomb then owns the whole exemption burden itself.
3. `shouldFilterApplicationInternal` (line 394) is the expensive path and honours
   `requestsQueryAllPackages` (line 482): **a caller holding `QUERY_ALL_PACKAGES`
   returns `false` (not filtered) before any queryable-set logic runs.** This is
   why master plan §12 is right that Bomb must not implement hiding by revoking
   `QUERY_ALL_PACKAGES` — but it also means Bomb's own check must sit *above* this
   short-circuit, otherwise every modern app with that permission bypasses Bomb.
4. The `mShouldFilterCache` (line 377) is a `(callingUid, targetUid)` matrix
   maintained by `AppsFilterImpl`. Bomb must **not** try to encode its policy into
   that cache: it is invalidated on package/user change by AppsFilterImpl and has
   no notion of Bomb revisions. Bomb carries its own immutable snapshot instead.

### Bomb insertion point (decided)

```java
// AppsFilterBase.shouldFilterApplication(), first statement of the try block
final BombVisibilityPolicy policy = BombVisibilityPolicy.getSnapshot();   // volatile read
if (policy != null) {
    final int decision = policy.evaluate(callingUid, targetPkgSetting.getAppId(),
            targetPkgSetting.getPackageName(), userId);
    if (decision == BombVisibilityPolicy.HIDE)  return true;
    if (decision == BombVisibilityPolicy.SHOW)  return false;   // WHITELIST allow
    // PASS -> fall through to unmodified AOSP logic
}
```

Placed before line 339 so rule (2) does not pre-empt Bomb, with Bomb re-checking
the caller-side exemption itself. One method, one patch, all thirteen surfaces
above. `getVisibilityAllowList` (ComputerEngine.java:5424) and `canQueryPackage`
(5465, 5472) are install-time/queryable-set helpers and are deliberately left
untouched — they answer "may this app query", not "does this package exist".

---

## 4. Shared UID reality (master plan §12 "Shared UID")

Confirmed by source, not assumed:

- `AppsFilterBase.shouldFilterApplicationInternal` (line 433-451) resolves the
  *caller* to either one `PackageStateInternal` or an `ArraySet` of them via
  `snapshot.getSharedUser(packageState.getSharedUserAppId()).getPackageStates()`.
- `ComputerEngine.shouldFilterApplication(SharedUserSetting, int, int)` (line 2627)
  filters a shared UID only if **every** member package filters
  (`filterApp &= ...`).
- `getPackagesForUidInternalBody` (line 2001) filters **per member package**, so a
  shared UID can legitimately return a partial package list.

**Bomb implication:** the policy key must be `(userId, callerUid, targetPackage)`,
and for a shared-UID caller Bomb evaluates the union of member packages. A
conflicting policy inside one shared UID (pkg A hides X, pkg B does not) is
unresolvable at the UID granularity AppsFilter operates on — Bomb must detect the
conflict at configuration time and reject it, exactly as the master plan requires.

---

## 5. Surfaces this hook does **not** cover

A hidden package remains discoverable through these, and each needs a separate
decision (documented now, implemented only if the phase requires it):

| Surface | Where | Bomb stance for Phase 8 |
| --- | --- | --- |
| `ActivityManager.getRunningAppProcesses` | `am/ActivityManagerService` | out of scope — process visibility is Task Manager's domain |
| `UsageStatsManager` | `usage/` (not in this checkout) | out of scope v1; note as a known leak |
| `/proc` enumeration, `pm list packages` via shell | kernel / shell UID | shell is below `FIRST_APPLICATION_UID`, deliberately exempt |
| `AccountManager`, `ContentResolver.getSyncAdapterTypes` | not in this checkout | known leak; document, do not silently claim coverage |
| Launcher icon presence | `LauncherAppsService` | already covered via `filterAppAccess` (§2) |

Master plan §12 demands consistency "where practical". This table is the honest
boundary of that word, and it belongs in the UI as a documented limitation rather
than an implied guarantee.

---

## 6. Bomb design consequences

1. **One patch, in `AppsFilterBase`.** Not `PackageManagerService`, not
   `ComputerEngine`, not per-API. Reviewable in a single diff.
2. **Immutable snapshot, volatile reference.** `shouldFilterApplication` is on the
   hottest path in `system_server`; the master plan's ban on Room/Binder/disk I/O
   per check is confirmed necessary — `mShouldFilterCache` exists precisely because
   the uncached path (`shouldFilterApplicationInternal`) was too slow. Bomb's
   `VisibilityPolicySnapshot` must be a pre-compiled `SparseArray`/`ArraySet`
   structure swapped atomically on revision change.
3. **Mandatory exemptions, centralized:** callingUid appId < 10000; caller ==
   Bomb's own UID; caller == target. Encoded once in `BombVisibilityPolicy`, never
   sprinkled through the framework.
4. **`userId` is a first-class key** — every AOSP surface above already passes
   `userId`; Bomb keying only by package name would be a regression against the
   platform.
5. **Delivery.** This is a `services.jar` change, so it lands as a ROM-mode
   framework patch, not something the root-mode backend can provide. Phase 8 must
   state that App Visibility is ROM-mode-only, and `BombCapabilities` must report
   `PackageVisibilityVirtualization` as unsupported in root mode rather than
   silently doing nothing.

---

## 7. Testing strategy implied by this research

Pure-JVM tests against `BombVisibilityPolicy` (no framework needed):

- blacklist hides only for the configured caller; a second caller still sees it;
- whitelist mode + mandatory exemption set;
- caller appId < 10000 always `PASS`;
- caller == target always `PASS`;
- shared-UID caller: union semantics, and conflict detection rejects the config;
- `userId` isolation: same package, two users, different policy;
- snapshot swap is atomic and revision-monotonic.

Framework-level verification (device, Phase 8 exit gate) must check the same
package through **all thirteen surfaces in §2** — enumeration, direct lookup,
UID mapping, and all four intent-resolution kinds — and report any that disagree.
