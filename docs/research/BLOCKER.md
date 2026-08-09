# Research — Blocker (component control)

```text
Reference:
https://github.com/lihenggui/blocker.git @ 58f16ac68c3c0e0fe6ca1c29ee785e73c2f7ab0c
(commit date 2026-07-20)

License: Apache-2.0 (LICENSE)
Architecture: multi-module Gradle, Hilt DI, Room, Compose — settings.gradle.kts
lists 24 :core/:feature modules including :core:component-controller,
:core:ifw-api, :core:domain, :core:data, :core:model
```

License verdict: Apache-2.0 is compatible with Bomb. Code reuse would be legally
possible with attribution, but the useful output here is the **architecture**, not
the code — Blocker's controllers are shell/Shizuku-based, which Bomb's typed-API
rule forbids.

---

## 1. Required flow (master plan §32)

> selected component → component model → backend → PackageManager/IFW operation →
> state refresh

Traced end to end:

```text
UI toggle
   |
LocalComponentRepository.controlComponent(component, newState, controllerType)
   core/data/.../respository/component/LocalComponentRepository.kt
   |
   +-- effectiveControllerType = arg ?: userDataRepository.userData.first().controllerType
   |
   +-- IFW          -> controlInIfwMode(component, newState)
   +-- PM           -> controlInPmMode(component, newState)
   +-- SHIZUKU      -> controlInShizukuMode(component, newState)
   +-- IFW_PLUS_PM  -> controlInCombinedMode(component, newState)
   |
   v  (IController implementations, core/component-controller)
IfwController        -> IIntentFirewall.addComponentFilter / removeComponentFilter
RootApiController    -> RootCommandExecutor.execute(SetComponentEnabledSettingCommand(...))
ShizukuController    -> Shizuku binder
CombinedController   -> both IFW and PM, result = ifwResult && pmResult
   |
   v
updateComponentStatus(packageName, componentName)
   re-reads BOTH backends:
     pmBlocked  = !pmController.checkComponentEnableState(...)
     ifwBlocked = !ifwController.checkComponentEnableState(...)
   appComponentDao.update(newState)
   |
   v
Room Flow (appComponentDao.getByPackageName) re-emits -> UI recomposes
```

The UI never learns state from the operation's return value. It learns it from a
**re-read of the backends written into Room**, and Room's `Flow` pushes the update.
That is the correct shape and Bomb adopts it.

---

## 2. The finding that matters most: two mechanisms, not one

`ComponentInfo` (core/model/.../data/ComponentInfo.kt) carries **two independent
blocked flags**:

```kotlin
data class ComponentInfo(
    val packageName: String,
    val name: String,
    val type: ComponentType,
    val simpleName: String = name.substringAfterLast('.'),
    val pmBlocked: Boolean = false,
    val exported: Boolean = false,
    val isRunning: Boolean = false,
    val ifwBlocked: Boolean = false,
    val description: String? = null,
) {
    fun enabled() = !(pmBlocked || ifwBlocked)
}
```

A component is enabled only when **neither** mechanism blocks it. This is not
cosmetic — the two mechanisms differ materially:

| | PackageManager component state | Intent Firewall |
| --- | --- | --- |
| Storage | per-package `PackageUserState` in `/data/system/users/<u>/package-restrictions.xml` | XML rule files under `<secure data>/system/ifw/` (`IfwStorageUtils.IFW_FOLDER = "/ifw"`) |
| Granularity | component enabled/disabled | intent-filter match blocked |
| Providers | supported | **not supported** — see below |
| Survives app update | state is kept per component name | rules keyed by component name in XML |
| Visible to the app | `PackageManager.getComponentEnabledSetting` reports it | invisible to the app |

Blocker encodes the provider limitation explicitly
(`LocalComponentRepository.controlInIfwMode`):

```kotlin
if (type == ComponentType.PROVIDER) {
    Timber.v("Component $packageName/$componentName is provider.")
    return controlInPmMode(component, newState)
}
```

And it handles the cross-mechanism interaction when *enabling*: enabling in IFW
mode first un-blocks PM if PM is what is blocking, and vice versa
(`controlInIfwMode` / `controlInPmMode`). Without that, a user toggling "enable"
sees nothing happen because the *other* mechanism still blocks the component.

**Bomb implications:**

1. Bomb's Component Explorer must model component state as a **set of blocking
   reasons**, not a boolean. Minimum: `pmDisabled`, `ifwBlocked`, plus Bomb's own
   `bombPolicy` if Bomb adds one. Master plan §8's "enabled state" column is
   under-specified; this is the correction.
2. "Enable" is not the inverse of "disable" — it must clear every blocking reason,
   or report which reason it could not clear.
3. ContentProviders cannot be blocked by IFW. A UI that offers IFW blocking for a
   provider is fabricating a capability.

---

## 3. Backend abstraction — good shape, wrong mechanism for Bomb

`IController` (core/component-controller/.../IController.kt) is a 6-method
interface: `init`, `switchComponent`, `enable`, `disable`, `batchEnable`,
`batchDisable`, `checkComponentEnableState`, with default batch implementations
that loop and count successes.

`CombinedController` (.../combined/CombinedController.kt) composes two controllers
and is worth reading for one detail — its combination rules differ per operation:

```kotlin
override suspend fun switchComponent(...) = ifwResult && pmResult   // write: AND
override suspend fun checkComponentEnableState(...) = ifwEnabled || pmEnabled  // read: OR
```

**Bomb implications:**

1. The interface shape (typed operation + capability-scoped backend + explicit
   state re-read) matches Bomb's `FreezeBackend`/`ComponentBlockBackend` plan.
   Adopt it.
2. The mechanism does not. `RootApiController` (root/api/RootApiController.kt)
   routes through `RootCommandExecutor.execute(SetComponentEnabledSettingCommand(...))`
   — a root command executor. Bomb's `CLAUDE.md` forbids generic shell execution;
   Bomb's equivalent is a typed AIDL call reaching
   `IPackageManager.setComponentEnabledSetting` in the privileged service, with the
   component name validated against the target package's actual manifest before the
   call. Blocker can pass an arbitrary string to `pm`; Bomb must not.
3. `batchEnable`/`batchDisable` returning a **count** rather than a per-item result
   is a weakness: the caller cannot tell *which* items failed. Bomb returns
   `List<BombResult>` keyed by component.
4. Blocker's IFW writes into `<secure data>/system/ifw/` and requires root for both
   read and write (`RootUnavailableException` thrown from `IntentFirewall.addComponentFilter`).
   In ROM mode Bomb reaches the same directory as a privileged service with a
   narrow SELinux rule for `bomb_data_file`-adjacent access; the capability probe
   must verify writability rather than assuming it.

---

## 4. Cache and error handling worth copying

`IntentFirewall` (core/ifw-api/.../IntentFirewall.kt) keeps
`cache: MutableMap<String, IfwRules>` keyed by package, and:

- on parse failure (`IfwXmlParseException`) it logs and returns an **empty rule
  set** (`cacheEmpty`) rather than throwing — a corrupt IFW file degrades to
  "nothing blocked" instead of breaking the screen;
- `saveRules` with an empty rule set **deletes** the file (`clear`) instead of
  writing an empty XML — avoids accumulating meaningless files;
- the cache is invalidated on write (`cache.remove(packageName)`) so the next read
  re-parses from disk.

Compare with HMA's unsynchronized list cache (see [`HMA_OSS.md`](./HMA_OSS.md) §4):
Blocker's is scoped, invalidated on write, and fails safe. Bomb takes this
approach for its own rule caches — but note this cache is *not* on a hot path, so
a `Map` is fine here where the visibility snapshot needs an array.

---

## 5. SDK / rule detection (relevant to master plan §10)

`GeneralRule` (core/model/.../data/GeneralRule.kt):

```kotlin
data class GeneralRule(
    val id: Int, val name: String, val iconUrl: String? = null, val company: String? = null,
    val searchKeyword: List<String> = listOf(),
    val networkSignature: List<String> = listOf(),
    val useRegexSearch: Boolean? = null,
    val description: String? = null,
    val safeToBlock: Boolean? = null,
    val sideEffect: String? = null,
    val website: String? = null,
    val contributors: List<String> = listOf(),
    val matchedAppCount: Int = 0,
)
```

Detection is **keyword/regex matching against component names**, with curated
metadata: `safeToBlock`, `sideEffect`, `website`, `contributors`. Rules are data,
fetched and stored, not code.

**Bomb implications:**

1. Master plan §10's detection signals (component prefixes, provider authorities,
   metadata, native library names) are consistent with this approach — matching,
   not hardcoded product logic. Confirmed viable.
2. `safeToBlock` + `sideEffect` are the honest way to present destructive controls,
   and match master plan §8's "Normal mode should avoid exposing destructive
   component controls too prominently". Bomb's Component Explorer should carry the
   same two fields and refuse to offer a one-tap block for anything without them.
3. Blocker ships a **network rule repository** (`:core:network`, `:core:git`).
   Bomb has no such infrastructure and should not acquire one for v1: a bundled,
   reviewed rule set with no network fetch is the smaller and safer scope.
4. Master plan §10's ban on `Bypass PAIRIP`-style buttons is consistent with
   Blocker's model — Blocker names the *SDK*, describes the side effect, and lets
   the generic component mechanism act.

---

## 6. What Bomb takes

| Blocker behaviour | Bomb decision |
| --- | --- |
| `IController` typed backend interface | Adopt the shape (`ComponentBlockBackend`) |
| Root shell command executor as the backend | **Reject** — typed AIDL → `setComponentEnabledSetting`, validated against the manifest |
| Two independent block flags (`pmBlocked`, `ifwBlocked`) | Adopt — component state is a set of reasons |
| Enable clears the *other* mechanism first | Adopt |
| Provider → PM only (IFW cannot block providers) | Adopt as a capability constraint |
| Batch returns a success count | **Reject** — per-item `BombResult` |
| Re-read both backends into Room, UI observes the Flow | Adopt |
| Rule metadata with `safeToBlock` / `sideEffect` | Adopt |
| Remote rule repository over network/git | Out of scope for v1 |
| Shizuku controller | Not applicable — Bomb has ROM and root modes |

---

## 7. Testing strategy implied

- component-name validation: a component not present in the target package's
  manifest is rejected before any backend call (Blocker has no such check; Bomb
  needs it because Bomb's API is privileged);
- state composition: `enabled = !(pmBlocked || ifwBlocked)` truth table;
- enable path clears both mechanisms; disable path touches only the selected one;
- provider + IFW mode routes to PM;
- IFW XML round-trip: serialize → deserialize → equal rule set; corrupt XML →
  empty rule set, never an exception to the UI;
- batch: partial failure reports exactly which components failed.
