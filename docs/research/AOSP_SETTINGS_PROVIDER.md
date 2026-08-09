# Research — AOSP SettingsProvider and per-caller virtualization

```text
Reference:
platform/frameworks/base @ 1cdfff555f4a21f71ccc978290e2e212e2f8b168
License: Apache-2.0

Relevant source:
packages/SettingsProvider/src/com/android/providers/settings/SettingsProvider.java
packages/SettingsProvider/src/com/android/providers/settings/SettingsState.java
packages/SettingsProvider/src/com/android/providers/settings/GenerationRegistry.java
core/java/android/provider/Settings.java
services/core/java/com/android/server/wm/ActivityTaskManagerService.java
services/core/java/com/android/server/wm/ActivityTaskManagerInternal.java
```

Research question from the master plan §31.4:

> Where can Bomb virtualize reads according to Binder caller without mutating the
> real system setting?

**Answer: in `SettingsProvider`, at the four `getXxxSetting` methods reached from
`SettingsProvider.call()` — and AOSP already does exactly this for one setting,
which is the proof the shape is sound.**

---

## 1. The read path, end to end

```text
App: Settings.System.getString(cr, "key")
        |
Settings.NameValueCache.getStringForUser()        Settings.java:3591
   per-process cache: mValues + GenerationTracker (shared MemoryIntArray)
        |  cache miss
        v  ContentProvider.call(CALL_METHOD_GET_SYSTEM, name, args)
SettingsProvider.call()                           SettingsProvider.java:434
        |
        +-- CALL_METHOD_GET_CONFIG -> getConfigSetting(name)
        +-- CALL_METHOD_GET_GLOBAL -> getGlobalSetting(name)           :1465
        +-- CALL_METHOD_GET_SECURE -> getSecureSetting(name, userId)   :1654
        +-- CALL_METHOD_GET_SYSTEM -> getSystemSetting(name, userId)   :1916
        |
        v
packageValueForCallResult(type, name, userId, setting, trackingGeneration)
                                                  SettingsProvider.java:2608
        |
        v
SettingsRegistry.getSettingLocked(type, userId, name)   SettingsProvider.java:3476
        -> SettingsState (the real stored value)
```

`getSystemSetting` in full (SettingsProvider.java:1916-1934):

```java
private Setting getSystemSetting(String name, int requestingUserId) {
    // Resolve the userId on whose behalf the call is made.
    final int callingUserId = resolveCallingUserIdEnforcingPermissions(requestingUserId);
    // Ensure the caller can access the setting.
    enforceSettingReadable(name, SETTINGS_TYPE_SYSTEM, UserHandle.getCallingUserId());
    // Determine the owning user as some profile settings are cloned from the parent.
    final int owningUserId = resolveOwningUserIdForSystemSettingLocked(callingUserId, name);
    synchronized (mLock) {
        return mSettingsRegistry.getSettingLocked(SETTINGS_TYPE_SYSTEM, owningUserId, name);
    }
}
```

Caller identity is already available and already used here — `Binder.getCallingUid()`
via `UserHandle.getCallingUserId()` and `resolveCallingUserIdEnforcingPermissions`.
No new plumbing is required to know who is asking.

---

## 2. AOSP's own per-caller virtualization precedent (decisive)

`getSecureSetting` (SettingsProvider.java:1654) does **not** always return the
stored value. Two caller-dependent behaviours already exist in this method:

**(a) Pretend-absent for inaccessible settings** — SettingsProvider.java:1670-1675:

```java
if (!isSecureSettingAccessible(name)) {
    // This caller is not permitted to access this setting. Pretend the setting doesn't
    // exist.
    SettingsState settings = mSettingsRegistry.getSettingsLocked(SETTINGS_TYPE_SECURE,
            owningUserId);
    return settings != null ? settings.getNullSetting() : null;
}
```

**(b) A genuinely per-caller value** — `ANDROID_ID` / SSAID,
SettingsProvider.java:1677-1683 and `isNewSsaidSetting` at :1692:

```java
if (isNewSsaidSetting(name)) {
    PackageInfo callingPkg = getCallingPackageInfo(owningUserId);
    synchronized (mLock) {
        return getSsaidSettingLocked(callingPkg, owningUserId);   // :1698
    }
}
```

```java
private boolean isNewSsaidSetting(String name) {
    return Settings.Secure.ANDROID_ID.equals(name)
            && UserHandle.getAppId(Binder.getCallingUid()) >= Process.FIRST_APPLICATION_UID;
}
```

**Bomb implication:** per-caller settings virtualization is not a novel hack. AOSP
ships the identical mechanism — same file, same method, resolved on
`Binder.getCallingUid()`, with the real stored value untouched. `isNewSsaidSetting`
is also the exemption template: `appId >= FIRST_APPLICATION_UID`, i.e. system
callers always get the real value. Bomb reuses that condition verbatim.

---

## 3. Why the master plan is right to forbid `settings put`

`SettingsState` is the single stored value per `(type, userId, key)`
(`SettingsRegistry.getSettingLocked`, SettingsProvider.java:3476). Writing it
changes the value for **every** caller and fires
`GenerationRegistry.incrementGeneration` (GenerationRegistry.java:93), invalidating
every app's cache system-wide. A foreground-app-triggered write loop would produce
exactly the shared-state race the master plan §13 describes. Confirmed by source,
not assumed.

---

## 4. The client cache is the real constraint

`NameValueCache` (Settings.java:3453) caches per app process:

```java
// Settings.java:3591-3594
final boolean isSelf = (userHandle == UserHandle.myUserId());
final boolean useCache = isSelf && !isInSystemServer();
```

- The cache lives in the **calling app's** process, so two callers holding
  different virtualized values is structurally fine — they never share `mValues`.
- Invalidation is driven by a `GenerationTracker` over a `MemoryIntArray` handed
  out by `packageValueForCallResult` (SettingsProvider.java:2608) via
  `GenerationRegistry.addGenerationData` (GenerationRegistry.java:150). The
  generation index is keyed by `(type, userId)` + setting name — **not** by caller.
- `isInSystemServer()` (Settings.java:3309) disables the cache inside system_server.

**Bomb implication (the one real risk):** when a Bomb *override* changes but the
underlying value does not, no generation bump happens and app processes keep
serving stale cached values indefinitely.

Two ways out, and the second wins:

1. *Bump the generation.* Call
   `GenerationRegistry.incrementGeneration(SettingsState.makeKey(type, userId), name)`
   for every key whose override changed. Correct, but invalidates that key for
   **every** app, not just the affected one.
2. **Suppress caching for virtualized keys only.** Omit the generation data from
   the returned `Bundle` — the client installs no `GenerationTracker` and therefore
   never populates `mValues`:

   ```java
   // Settings.java, NameValueCache.getStringForUser
   final int index = b.getInt(CALL_METHOD_GENERATION_INDEX_KEY, -1);
   if (array != null && index >= 0) { /* install tracker */ }
   ...
   if (mGenerationTrackers.get(name) != null && !isGenerationChanged()) {
       mValues.put(name, value);   // only cached when a tracker exists
   }
   ```

   `CALL_METHOD_GENERATION_INDEX_KEY` is `"_generation_index"` (Settings.java:3007).

**Decision: option 2.** Only the virtualized `(caller, key)` pairs pay a binder
call per read; every non-virtualized key keeps the platform's fast cached path
untouched, and there is no global invalidation storm. This is also what HMA-OSS
does in practice — see [`HMA_OSS.md`](./HMA_OSS.md) §5, where the same
`_generation_index = -1` trick is used and cross-verified against this source.

The residual limitation stands either way: values an app read once and cached in
its own fields do not change. Bomb's UI must say "applies on next app start"
rather than implying instant effect.

A second, subtler consequence: a Bomb override applied while an app is already
running takes effect on the next read after the generation bump. Values an app read
once at startup and cached in its own fields will not change. Bomb's UI must say
"applies on next app start" rather than implying instant effect.

---

## 5. Insertion point for Bomb

One private helper, consulted at the top of each of the three user-facing getters
(`getSystemSetting` :1916, `getSecureSetting` :1654, `getGlobalSetting` :1465),
placed **after** `enforceSettingReadable` so Bomb never widens read access:

```java
final Setting override = BombSettingsPolicy.resolve(
        SETTINGS_TYPE_SYSTEM, name, Binder.getCallingUid(), owningUserId);
if (override != null) {
    return override;      // real SettingsState untouched
}
```

`CALL_METHOD_GET_CONFIG` (DeviceConfig) is deliberately **not** hooked — it is
system/`configurator`-only surface and virtualizing it would break flag rollout.

Ordering rules that fall out of the source:

1. after `resolveCallingUserIdEnforcingPermissions` — Bomb needs the resolved
   `owningUserId`, and cross-user permission enforcement must run first;
2. after `enforceSettingReadable` (SettingsProvider.java:2258) — a caller that may
   not read the key must still get the `SecurityException`, not a Bomb value;
3. before the SSAID branch — Bomb must never virtualize `ANDROID_ID`; it is
   already per-caller and is identity-adjacent (see §7);
4. `Binder.getCallingUid()` is read directly in the provider, which runs inside
   system_server; in-process system reads therefore report uid 1000 and are exempt
   by the same `>= FIRST_APPLICATION_UID` test AOSP uses at :1692.

---

## 6. The `font_scale` example in the master plan is architecturally wrong

Master plan §13 uses:

```text
Real font_scale = 1.10 ; App A sees 1.00 ; App B sees 1.10
```

Verified in this commit: `Settings.System.FONT_SCALE` (Settings.java:5135) is not
read by apps for rendering. It is read **once, by system_server**:

```java
// ActivityTaskManagerService.updateFontScaleIfNeeded()  :5007-5013
final float scaleFactor = Settings.System.getFloatForUser(mContext.getContentResolver(),
        FONT_SCALE, 1.0f, userId);
```

and written into the **global `Configuration`**, which is then distributed to app
processes. Apps consume `Configuration.fontScale`, never the setting. Because that
read comes from system_server (uid 1000), Bomb's virtualization deliberately
exempts it — so virtualizing `font_scale` would change nothing at all, in either
direction.

The per-app configuration override infrastructure that *would* work is
`ActivityTaskManagerInternal.PackageConfigurationUpdater`
(ActivityTaskManagerInternal.java:684), created via
`createPackageConfigurationUpdater(packageName, userId)` (:629). At this commit it
exposes exactly three fields:

```java
PackageConfigurationUpdater setNightMode(int nightMode);
PackageConfigurationUpdater setLocales(LocaleList locales);
PackageConfigurationUpdater setGrammaticalGender(int gender);
boolean commit();
```

**No `setFontScale`.** Per-app font scale is therefore not achievable at this
platform level through either mechanism, and Bomb must not offer it.

**Bomb implication:** the Settings Virtualization feature is real and useful, but
its key set is bounded by "settings the app itself reads at runtime" — not
"anything visible in the Settings app". Phase 9 must ship a curated, capability-
tagged allowlist derived by checking *who reads the key*, and the UI must classify
each key as:

| Class | Meaning | Example |
| --- | --- | --- |
| `APP_READ` | the app reads it directly → virtualization works | most `Settings.Secure`/`System` keys apps query themselves |
| `SYSTEM_READ` | only system_server reads it → virtualization is a no-op | `font_scale`, most WM/policy keys |
| `PER_APP_CONFIG` | needs `PackageConfigurationUpdater`, not virtualization | night mode, locales |
| `FORBIDDEN` | identity/attestation adjacent | see §7 |

Presenting a `SYSTEM_READ` key as virtualizable would be fabricated capability —
forbidden by CLAUDE.md and by master plan §40.

---

## 7. Keys Bomb must refuse

Master plan §13 forbids spoofing attestation / Verified Boot / Play Integrity /
hardware security properties. Concretely, from this source:

- `Settings.Secure.ANDROID_ID` — already per-caller by design (:1692); Bomb
  overriding it would break the platform's own SSAID contract.
- Anything reached through `CALL_METHOD_GET_CONFIG` (DeviceConfig namespaces).
- Keys under `WritableNamespaces` / `NonWritableNamespacesForBackgroundUserPrefixes`
  (files in the same package) — these encode platform-owned write policy and are
  not Bomb's to reinterpret.

Verified Boot and attestation values are **not** in SettingsProvider at all — they
are system properties and keystore state — so this subsystem structurally cannot
spoof them. Good: the boundary is enforced by architecture, not by discipline.

---

## 8. Bomb design consequences

1. Virtualization is a **read-side** feature in `SettingsProvider`; the write path
   (`insertSystemSetting` :1936, `mutateSystemSetting`) is never touched.
2. `(namespace, key, valueType, callerPackage|callerUid, userId)` is the override
   identity; `SettingsState.makeKey(type, userId)` is the platform's own key shape
   and Bomb should mirror it.
3. Value typing: `Setting` stores a `String`. Typed values (STRING/INT/LONG/FLOAT/
   BOOLEAN/NULL) are a Bomb-side concern — Bomb must serialize exactly as the
   platform does (`Boolean` as `"0"`/`"1"`, `Float` via `Float.toString`) or apps
   parsing with `Settings.Secure.getInt` will throw `SettingNotFoundException`.
   This is a unit-test target.
4. A `NULL` override must return `settings.getNullSetting()`, matching :1673 —
   not a Java `null`, which falls through to the query interface.
5. This is a `SettingsProvider` (system_server) change → **ROM mode only**.
   `BombCapabilities.SettingsVirtualization` reports unsupported in root mode.
6. Policy lookup is on a hot path (every settings read of every app). Same rule as
   App Visibility: immutable snapshot, volatile reference, zero I/O per call.

---

## 9. Testing strategy

Pure-JVM against `BombSettingsPolicy`:

- caller-specific override returns virtual value; a different caller gets the real
  value; system caller (appId < 10000) always gets the real value;
- disabled override falls through;
- unknown key → `PASS`, never a fabricated value;
- typed serialization round-trip for all six value types, including the `"0"`/`"1"`
  boolean convention and NULL → null-setting;
- `userId` isolation;
- profile assignment resolution when one profile is assigned to several packages;
- key classification: a `SYSTEM_READ` key cannot be assigned an override at all
  (validator rejects it).

Device verification (Phase 9 exit gate): an app reading a virtualized `APP_READ`
key observes the virtual value; `settings get` from shell observes the real value;
generation bump forces a re-read within one call.
