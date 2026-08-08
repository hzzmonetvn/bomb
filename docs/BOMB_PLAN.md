# Bomb — Implementation Plan

Status: **proposed, awaiting review.** Nothing beyond `docs/` has been written.
Companion document: [`ARCHITECTURE.md`](./ARCHITECTURE.md) (audit findings).

Milestone IDs (M0–M15) match the milestone table supplied with the task.

---

## 1. Current repository state (summary)

| Question | Answer |
| --- | --- |
| Existing Bomb code? | None. `bomb/` contains only `CLAUDE.md` |
| Git repo? | No — `bomb/` is not under version control |
| Build system? | None in `bomb/`. ProjectZK's (AGP 9.3.0 / Kotlin 2.1.0 / Gradle 9.5.0) is the workspace convention |
| Project type? | Will be: **privileged APK + native daemon + ROM module**, delivered through `hzz/`'s prebuilt-image patcher |
| Native code? | None anywhere. **No NDK installed** |
| sepolicy? | None in `bomb/`. ROM-side CIL injection exists in `hzz/tools/py/selinuxpatch.py` |
| Tests? | None anywhere (ProjectZK has template stubs only) |
| CI? | None |
| Spec already implemented? | 0%. Adjacent techniques exist in ProjectZK (FPS overlay, live-update bridge, network rate) and in the ROM (`persist.sys.zk.minimal_logging` logging profile) |

---

## 2. Open decisions — needed before M2

These change the architecture materially. They are the reason this plan stops
before implementation.

> **Update 2026-08-07 — root mode is now a first-class execution mode.**
> Because some tweaks are impractical to integrate into the ROM under SELinux,
> Bomb supports two modes: **ROM** and **Root** (Magisk/KernelSU module).
> See `ARCHITECTURE.md` §5b. This *downgrades D1 from blocking*:
> the root backend reaches what priv-app cannot, without the platform key.
> D1 still decides the best ROM-mode default.

### D1 — Privilege model for `BombCoreService` (no longer blocking — see above)

No platform signing key is available, and there is no AOSP tree, so
`BombCoreService` cannot live in `system_server`.

| Option | Requires | Gets | Cost |
| --- | --- | --- | --- |
| **A. priv-app process + bound Binder service** *(recommended default)* | Nothing new | priv-app permissions (`DUMP`, `FORCE_STOP_PACKAGES`, `WRITE_SECURE_SETTINGS`, `PACKAGE_USAGE_STATS`, …); own `bomb_app` SELinux domain | No system UID → some framework calls unavailable; Deep Freeze depends on what priv-app perms allow |
| **B. Option A + `sharedUserId="android.uid.system"`** | ROM **platform private key** | system UID; near-full framework reach | Key must exist; app must be re-signed at ROM build; sharedUserId is legacy and complicates updates |
| **C. `bombd` registered with `servicemanager`** | New SELinux type + `service_contexts` entry | Native service reachable by name | More policy surface; only worth it if A/B are insufficient |

**Recommendation: A now, keep the AIDL surface identical so B is a
signing/manifest change only.** Confirm whether the ROM platform key is available.

### D2 — Where does `BombCoreService` run?

Recommendation: a separate process of the Bomb APK
(`android:process=":core"`), started at boot, bound over AIDL. Keeps the UI
process unprivileged-by-construction and gives a distinct SELinux label target.
`servicemanager` registration is deferred (D1-C).

### D3 — Is `bombd` needed for M1–M8?

Recommendation: **no**. procfs/sysfs telemetry is readable by the priv-app domain
with narrow SELinux rules. `bombd` is introduced only when a *write* to a
device node is required (Performance Profiles / charge control, M8+). This defers
the NDK dependency and keeps the initial attack surface minimal.

### D4 — MIUIX dependency vs. in-house design system

`top.yukonga.miuix.kmp:miuix:0.8.8` is available on Maven Central.
Recommendation: depend on it, and still put **every** usage behind the `Bomb*`
component layer so the library can be swapped without touching feature code.

### D5 — Room 2.8.4 vs Room 3.0.1

Recommendation: Room 2.8.4 (stable, widely used with AGP 9). Room3 only if a
concrete need appears.

---

## 3. Target architecture

See `ARCHITECTURE.md` §6 for the layer diagram and contracts. Summary of the
non-negotiables:

- UI never executes shell, never writes sysfs/procfs, never holds root.
- All privileged calls cross AIDL and return `BombResult`.
- All hardware-dependent behaviour is gated by `BombCapabilities`.
- Telemetry is subscriber-aware; no consumers ⇒ no collection.
- `bombd` has typed opcodes only; no generic exec/write API.
- SELinux stays enforcing; Bomb adds its own types with minimal rules.

---

## 4. Exact module mapping

Gradle project `bomb`, root at `/home/hzzmonet/zk/bomb`.

| Gradle module | Type | Package root | Contains |
| --- | --- | --- | --- |
| `:app` | `com.android.application` | `com.hzzmonet.zkbomb` | Compose UI, `ui/design` (Bomb design system), navigation, feature screens, overlay service, `data/` (Room, DataStore, `BombServiceClient`) |
| `:core-api` | `com.android.library` | `com.hzzmonet.zkbomb.api` | AIDL interfaces, `Parcelable` models, `BombResult`, `BombCapabilities`, client helper. **The only module both `:app` and `:system-service` depend on** |
| `:domain` | `java-library` (pure JVM) | `com.hzzmonet.zkbomb.domain` | `FreezePolicyEngine`, `RuleEngine` (trigger/condition/conflict/cooldown), `ProfileResolver`, telemetry math, validators. **No Android imports** ⇒ fast JVM unit tests |
| `:system-service` | `com.android.library` | `com.hzzmonet.zkbomb.core` | `BombCoreService` (AIDL stub impl), caller validation, capability detection, per-feature managers, backend interfaces + device backends |
| `daemon/` | non-Gradle, NDK/CMake | — | `bombd` C++ sources, `CommandDispatcher`, command handlers |
| `sepolicy/` | files | — | `bomb_app.te`, `bombd.te`, `file_contexts`, `service_contexts`, `seapp_contexts`, `property_contexts` (authoring format; translated to CIL by the ROM patcher, and to `sepolicy.rule` for the root module) |
| `module/` | files | — | **Root mode package**: `module.prop`, `service.sh` (starts `bombd`), `post-fs-data.sh`, `sepolicy.rule`, `customize.sh`, plus the `bombd` binary per ABI. Built from the same `sepolicy/` and `daemon/` sources — one policy definition, two delivery paths |
| `init/` | files | — | `bombd.rc` |
| `permissions/` | files | — | `privapp-permissions-com.hzzmonet.zkbomb.xml` |
| `docs/` | files | — | this plan + architecture |

Why `:domain` is a pure `java-library`: every priority test target named in the
spec (freeze policy, rule engine, capability filtering, telemetry math,
validation) lives there and becomes testable with plain JUnit — no emulator, no
Robolectric.

`:app` package layout follows the requested tree
(`ui/design`, `ui/navigation`, `ui/dashboard`, `ui/taskmanager`, `ui/stats`,
`ui/freeze`, `ui/apps`, `ui/bridge`, `ui/performance`, `ui/battery`, `ui/network`,
`ui/recorder`, `ui/logs`, `ui/automation`, `data/`, `service/`).

`:system-service` mirrors the requested tree (`task/`, `process/`, `freeze/`,
`appcontrol/`, `performance/`, `battery/`, `network/`, `logs/`, `bridge/`,
`recorder/`, `automation/`).

---

## 5. Implementation phases

Each phase lists the files expected to be created or changed. Files are
new unless marked *(edit)*.

### M0 — Repo audit *(this document)*

`docs/ARCHITECTURE.md`, `docs/BOMB_PLAN.md`.

Exit criteria: plan reviewed; D1–D5 answered.

---

### M0.6 — Android UI shell *(done)*

Built ahead of the privileged modules to settle the design language. The UI is
Android-only and uses the real MIUIX components; the browser target was removed
when the installable application became the only supported surface.

```
settings.gradle.kts, build.gradle.kts, gradle.properties
gradle/libs.versions.toml                 kotlin 2.3.20 / compose 1.10.3 / miuix 0.8.8
gradle/wrapper/*                          Gradle 9.6.1
preview/src/commonMain/…/ui/design/       BombTheme, BombIcons, Bomb* components
preview/src/commonMain/…/ui/<feature>/    feature screens
preview/src/androidMain/                  Android collectors, storage and back handling
app-preview/                              thin Android application package
```

Outcomes that change this plan:

- **R7 resolved.** Gradle 9.6.1 on the JDK 21 already under `~/.gradle/jdks`
  builds Kotlin 2.3.20 + Compose 1.10.3 cleanly. The JDK 26 on `PATH` is not
  used; Gradle should run on JDK 17 or 21.
- **D4 answered in practice.** Every MIUIX usage sits
  behind the `Bomb*` component layer, so the library stays swappable.
- The design system and screens are written as pure functions of state and
  callbacks — M1 lifts them into `:app` by replacing the state holder with
  ViewModels, not by rewriting UI.

### M0.5 — Buildable skeleton for the app modules

The only pre-approval work proposed: make the repository build and be
version-controlled. No features.

```
.gitignore
settings.gradle.kts              :app :core-api :domain :system-service
build.gradle.kts
gradle.properties                config cache on, jvmargs
gradle/libs.versions.toml        pinned versions (§6)
gradle/wrapper/gradle-wrapper.properties   9.5.0 (cached locally)
gradlew, gradlew.bat
local.properties                 (untracked) sdk.dir
app/build.gradle.kts             compileSdk 37, minSdk 35, targetSdk 37, JVM 17
app/src/main/AndroidManifest.xml minimal
core-api/build.gradle.kts        aidl buildFeature on
domain/build.gradle.kts
system-service/build.gradle.kts
```

Exit criteria: `./gradlew assembleDebug` and `./gradlew test` both run. This is
also where the JDK-26-vs-Gradle-daemon question (R7) gets settled empirically.

---

### M1 — UI foundation (navigation + Bomb design system)

```
app/src/main/java/com/hzzmonet/zkbomb/BombApplication.kt
app/.../MainActivity.kt
app/.../ui/navigation/BombNavHost.kt, BombDestinations.kt, BombBottomBar.kt
app/.../ui/design/BombTheme.kt, BombColors.kt, BombTypography.kt, BombShapes.kt,
        BombMotion.kt, BombHaptics.kt
app/.../ui/design/component/BombScaffold.kt, BombTopAppBar.kt, BombLargeTitle.kt,
        BombCard.kt, BombPreference.kt, BombSwitchPreference.kt,
        BombSliderPreference.kt, BombSegmentedButton.kt, BombDialog.kt,
        BombBottomSheet.kt, BombStatCard.kt, BombChart.kt, BombAppRow.kt,
        BombProcessRow.kt, BombEmptyState.kt, BombUnsupportedState.kt
app/.../ui/dashboard/HomeScreen.kt, HomeViewModel.kt, HomeUiState.kt
app/src/main/res/values/{strings,themes}.xml, values-night/, values-vi/
```

Five bottom-nav destinations (Home, Apps, Monitor, Automation, More) render with
real-but-empty state. `BombUnsupportedState` exists from day one so no screen is
ever tempted into fake data.

Exit criteria: dark + light render correctly; no stock Material component used
directly outside `ui/design`; navigation state survives rotation.

---

### M2 — Core IPC (AIDL + BombCoreService + capabilities)

```
core-api/src/main/aidl/com/hzzmonet/zkbomb/api/IBombService.aidl
core-api/src/main/aidl/.../IBombTelemetryCallback.aidl
core-api/src/main/aidl/.../<model>.aidl (parcelable declarations)
core-api/src/main/java/.../model/{BombResult, BombCapabilities, ProcessInfo,
        TelemetrySnapshot, AppState, FreezeMode, PerformanceProfile,
        BatterySnapshot, NetworkStats, LogProfile, BombLiveEvent,
        AutomationRule, BridgeRule}.kt
core-api/src/main/java/.../client/BombServiceClient.kt
system-service/src/main/java/.../BombCoreService.kt
system-service/.../CallerValidator.kt
system-service/.../capability/CapabilityDetector.kt, CapabilityRegistry.kt
system-service/.../BombServiceStarter.kt (BOOT_COMPLETED receiver)
app/.../data/BombServiceConnection.kt, BombRepository.kt
app/src/main/AndroidManifest.xml (edit) — :core process, boot receiver
domain/src/main/java/.../validation/{PackageValidator, PidValidator,
        UidValidator, RangeValidator}.kt
domain/src/test/java/.../validation/*Test.kt
```

Exit criteria: UI binds, receives a real `BombCapabilities`, and every unsupported
capability visibly disables its control. Validation tests pass.

---

### M3 — Task Manager

```
system-service/.../process/{ProcFsProcessSource, ProcessStatParser,
        ProcessSnapshotBuilder, ProcessActionManager}.kt
domain/.../process/{CpuUsageCalculator, ProcessSorter, ProcessFilter}.kt
domain/src/test/.../process/{CpuUsageCalculatorTest, ProcessSorterTest}.kt
app/.../ui/taskmanager/{ProcessListScreen, ProcessListViewModel,
        ProcessDetailScreen, ProcessDetailViewModel, ProcessRowMapper}.kt
core-api aidl (edit): getRunningProcesses, getProcessDetails, forceStop,
        clearCache, restrictBackground
```

Includes the refresh-level model (Realtime 500ms / Fast 1s / Balanced 2s /
Battery 5s) with collection paused when the screen is not resumed. No RAM cleaner.

Exit criteria: real `/proc` data; CPU% is a proper delta between samples
(counter reset and overflow handled — unit-tested); actions report typed results.

---

### M4 — Stats core (CPU / RAM / battery / thermal)

```
system-service/.../telemetry/{TelemetryEngine, TelemetryConfig,
        CpuCollector, MemoryCollector, BatteryCollector, ThermalCollector,
        SubscriberRegistry}.kt
domain/.../telemetry/{RateCalculator, DeltaCalculator, ThermalStateMapper}.kt
domain/src/test/.../telemetry/{RateCalculatorTest, DeltaCalculatorTest}.kt
app/.../ui/stats/{MonitorPickerScreen, ClassicMonitorScreen, StatsViewModel}.kt
```

Exit criteria: one `TelemetrySnapshot` stream feeds every consumer; zero
subscribers ⇒ zero reads (verified by a subscriber-count test).

---

### M5 — Stats advanced (FPS / GPU / process / thread / overlay)

```
system-service/.../telemetry/{FpsCollector, GpuCollector, ProcessCpuCollector,
        ThreadCollector, NetworkRateCollector}.kt
system-service/.../telemetry/backend/{GpuBackend, AdrenoGpuBackend,
        MaliGpuBackend, NoGpuBackend}.kt
app/.../service/BombOverlayService.kt
app/.../ui/design/component/BombMonitorOverlay.kt
app/.../ui/stats/overlay/{OverlayPresets, OverlayConfigScreen,
        OverlayPositionStore}.kt
app/src/main/AndroidManifest.xml (edit) — SYSTEM_ALERT_WINDOW, FGS special use
sepolicy/bomb_app.te (edit) — read access to the specific GPU sysfs nodes
```

GPU/FPS are capability-probed; on a device without exposed nodes the metric shows
"unsupported", never a number.

Exit criteria: overlay drags/snaps/persists per app; recomposition limited to
changed metrics.

---

### M6 — Freeze Engine

```
domain/.../freeze/{FreezePolicyEngine, FreezeDecision, CriticalPackages,
        FreezeSchedule}.kt
domain/src/test/.../freeze/{FreezePolicyEngineTest, CriticalPackagesTest}.kt
system-service/.../freeze/{FreezeManager, FreezeBackend, SoftFreezeBackend,
        DeepFreezeBackend, DisableBackend, FreezeScheduler}.kt
app/.../data/db/{BombDatabase, dao/*, entity/*}.kt
app/.../ui/freeze/{FreezeScreen, FreezeViewModel, FreezeAppConfigSheet}.kt
```

Exclusions (SystemUI, launcher, active IME, Bomb itself, framework processes,
vendor list) are enforced **in `:domain`**, so they are covered by pure unit tests
and cannot be bypassed from the UI.

Exit criteria: manual freeze/unfreeze works on a real device; auto-freeze respects
delay + foreground state; exclusion tests pass; oscillation impossible.

---

### M7 — App Control

```
system-service/.../appcontrol/{PackageInfoProvider, AppOpsReader,
        BackgroundRestrictionManager, ComponentStateManager}.kt
app/.../ui/apps/{AppListScreen, AppListViewModel, AppControlScreen,
        AppControlViewModel, AdvancedModeGate.kt}.kt
```

Component-level controls sit behind Advanced Mode; self-disable and
essential-component disable are blocked in `:domain`.

---

### M8 — Performance Profiles (+ first `bombd` need)

```
domain/.../performance/{ProfileResolver, CapabilityFilter, RestorePolicy}.kt
domain/src/test/.../performance/{ProfileResolverTest, RestoreBehaviorTest}.kt
system-service/.../performance/{PerformanceController, PerformanceBackend,
        GenericBackend, QcomBackend, MtkBackend, DeviceCapabilities}.kt
daemon/src/main/{main.cpp, CommandDispatcher.cpp, ArgValidator.cpp}
daemon/src/{performance,sysfs}/*.cpp
daemon/CMakeLists.txt
init/bombd.rc
sepolicy/bombd.te, sepolicy/file_contexts (edit)
app/.../ui/performance/{ProfileListScreen, ProfileEditScreen, ...}.kt
```

`bombd` enters here and only here, because this is the first *write* to device
nodes. Requires NDK (R2). Every profile field is capability-filtered; on exit
Bomb restores **only values Bomb set**.

---

### M9 — Bomb Rules

```
domain/.../rules/{RuleEngine, TriggerMatcher, ConditionEvaluator,
        ConflictResolver, CooldownTracker, RulePriority, ActionPlan}.kt
domain/src/test/.../rules/{TriggerMatcherTest, ConditionEvaluatorTest,
        ConflictResolverTest, CooldownTrackerTest, LoopPreventionTest}.kt
system-service/.../automation/{RuleScheduler, TriggerSources/*, ActionExecutor,
        ExecutionHistory}.kt
app/.../ui/automation/{RuleListScreen, RuleEditorScreen, RuleTemplates}.kt
```

Precedence enforced as: manual user action > safety policy > per-app rule >
global automation.

---

### M10 — Log Governor

```
domain/.../logs/{LogProfile, LogTagRule, LogProfileValidator}.kt
system-service/.../logs/{LogGovernor, LogBackend, PropertyLogBackend,
        LogRateEstimator}.kt
app/.../ui/logs/{LogGovernorScreen, LogGovernorViewModel}.kt
sepolicy/property_contexts, sepolicy/bomb_app.te (edit)
```

Backend generalizes the ROM's existing `persist.sys.zk.minimal_logging` mechanism
into typed profiles under a Bomb-owned property namespace. Security-relevant,
fatal and SELinux-denial logging is never reduced.

---

### M11 — Bomb Bridge

```
core-api/.../model/BombLiveEvent.kt (edit)
system-service/.../bridge/{BridgeNormalizer, EventSource, sources/*,
        renderer/{BridgeRenderer, HyperOsRenderer, LiveUpdateRenderer,
        NotificationRenderer}, RendererCapabilityProbe}.kt
app/.../ui/bridge/{BridgeScreen, BridgeRuleEditor}.kt
```

Sources and renderers are independent; the notification renderer is the always-
available fallback when the HyperOS path probes unsupported.

---

### M12 — Battery Lab + Power Activity

```
system-service/.../battery/{BatteryRepository, BatteryCapabilities,
        ChargingBackend, QcomChargingBackend}.kt
system-service/.../power/{WakelockReader, JobReader, AlarmReader}.kt
app/.../ui/battery/{BatteryLabScreen, PowerActivityScreen}.kt
```

Metrics that cannot be obtained reliably are shown as unavailable — explicitly
required by the spec, and the reason Power Activity ships after Battery Lab.

---

### M13 — Network Control

```
system-service/.../network/{NetworkTelemetryCollector, NetworkStatsReader,
        NetworkPolicyController, NetworkCapabilities}.kt
app/.../ui/network/{NetworkScreen, AppNetworkPolicyScreen}.kt
```

No packet interception, no credential capture — stats and policy only.

---

### M14 — Call Recording

```
core-api/.../model/RecordingSession.kt
system-service/.../recorder/{RecorderManager, CaptureBackend,
        PlatformCaptureBackend, CaptureCapabilityProbe, RetentionWorker,
        RecordingMetadataStore}.kt
app/.../ui/recorder/{RecorderScreen, RecordingListScreen}.kt
```

Only platform/vendor audio paths that probe as genuinely available are used. No
bypass techniques. Visible indicator, typed retention, encrypted metadata,
no audio content ever logged. If a source is unsupported, the app says so.

---

### M15 — Polish

Performance passes, UX, coverage gaps, SELinux hardening review (re-audit every
rule added in M2–M14 against actual denials), `bomb.sh` integration finalization.

---

## 6. Dependencies (all verified reachable — §4.1 of ARCHITECTURE.md)

```toml
agp = "9.3.0"            kotlin = "2.1.0"        composeBom = "2026.06.01"
compileSdk = 37          minSdk = 35             targetSdk = 37     jvm = 17
miuix = "0.8.8"          # top.yukonga.miuix.kmp:miuix  (Maven Central)
room = "2.8.4"           # androidx.room (Google Maven)
datastore = "1.1.x"      navigation-compose = "2.9.8"
kotlinx-coroutines       ksp = "2.1.0-1.0.29"
junit = "4.13.2"         kotlinx-coroutines-test  turbine (Flow tests)
```

Deliberately **not** added: DI framework (manual construction is sufficient and
avoids a compile-time dependency in the privileged process), WorkManager (the
service owns its own scheduling), any charting library (`BombChart` is Compose
Canvas), any HTTP client.

`minSdk 35` because the ROM targets Android 15/16 (`bomb.sh` detects A15/A16) —
Bomb is a ROM-integrated system app, so supporting older platforms has no value.

---

## 7. SELinux integration plan

Authoring format lives in `sepolicy/` as `.te`-style sources for reviewability;
the ROM patcher consumes them. Domains:

| Type | Purpose |
| --- | --- |
| `bomb_app` | Bomb APK domain (UI + `:core` process) |
| `bombd` | native daemon domain (M8+) |
| `bomb_data_file` | `/data/system/bomb(/.*)?` |
| `bomb_service` | Binder service label (only if D1-C is taken) |

Labels:

```
/system/bin/bombd            u:object_r:bombd_exec:s0
/data/system/bomb(/.*)?      u:object_r:bomb_data_file:s0
```

**Two delivery paths, one source.** `sepolicy/` is authored once and emitted as:

1. **CIL** appended to `vendor_sepolicy.cil` by the extended `selinuxpatch.py` — ROM mode.
2. **`sepolicy.rule`** inside the Magisk/KernelSU module, loaded by `magiskpolicy`
   at boot — root mode. This is the escape hatch for rules the image patcher
   cannot add safely, and it iterates in seconds instead of a repack cycle.

A rule that cannot be expressed in both is a design smell: it means Bomb is
asking for access one of the two modes should not have.

Work required in `hzz/tools/py/selinuxpatch.py` (M2 for `bomb_app`, M8 for `bombd`):

1. Emit CIL **type declarations** before rules — the current script only emits
   `(allow …)` and silently drops rules whose types are unknown, so Bomb's
   domains would vanish without this change.
2. Emit `typeattributeset` membership (`domain`, `appdomain`/`coredomain`,
   `file_type`, `data_file_type`) so the base policy's constraints are satisfied.
3. Emit the domain transition for `bombd` (init → `bombd` via `bombd_exec`).
4. Write `seapp_contexts` entry mapping the Bomb package to `bomb_app`,
   `file_contexts` entries above, and `property_contexts` for the Bomb property
   namespace (M10).
5. Implement the dead `bomb.sh selinux` subcommand as the entry point for the above.

Rule discipline:

- Rules are added per feature, in the phase that needs them, scoped to Bomb's own
  types and the specific nodes involved (e.g. `bomb_app sysfs_kgsl:file read open`,
  not `sysfs:file *`).
- No `audit2allow` output applied unreviewed. For each denial: identify source,
  target, class, exact operation; decide whether the access is genuinely required;
  add the minimal rule or redesign.
- Never `allow bombd *:* *` / `allow bomb_app *:* *`.
- Enforcing is never relaxed, not even temporarily during bring-up.
- Hard precondition: `_runtime_policy_compile_available()` must hold on the target
  ROM (`secilc` + `plat_sepolicy.cil` + `plat_sepolicy_vers.txt`), otherwise the
  patcher skips custom rules **silently** — M2 must add a loud failure instead.

---

## 8. Binder / AIDL plan

`core-api` owns the contract. Stability rules: append-only methods, never
reorder, never repurpose a transaction; every model is a versioned `Parcelable`
with an explicit `version` field.

```aidl
interface IBombService {
    int getApiVersion();
    BombCapabilities getCapabilities();

    // telemetry — push, not poll
    void registerTelemetryCallback(IBombTelemetryCallback cb, in TelemetryConfig cfg);
    void unregisterTelemetryCallback(IBombTelemetryCallback cb);
    TelemetrySnapshot getSystemSnapshot();

    // processes
    List<ProcessInfo> getRunningProcesses(in ProcessQuery query);
    ProcessDetails getProcessDetails(int pid);
    BombResult forceStopPackage(String pkg);

    // freeze
    BombResult freezePackage(String pkg, int mode);
    BombResult unfreezePackage(String pkg);
    List<String> getFrozenPackages();

    // app + profiles
    AppState getAppState(String pkg);
    AppProfile getAppProfile(String pkg);
    BombResult setAppProfile(in AppProfile profile);
    BombResult setPerformanceProfile(in PerformanceProfile profile);
    PerformanceState getPerformanceState();

    // power / battery / network / logs / bridge / automation
    BatterySnapshot getBatteryStats();
    NetworkStats getNetworkStats(in NetworkQuery query);
    PowerActivity getPowerActivity(String pkg);   // wakelocks/jobs/alarms
    BombResult setLogProfile(in LogProfile profile);
    List<BridgeRule> getBridgeRules();
    BombResult setBridgeRule(in BridgeRule rule);
    List<AutomationRule> getAutomationRules();
    BombResult setAutomationRule(in AutomationRule rule);
}
```

Explicitly absent, permanently: `executeShell`, `writeFile`, `writeSysfs`,
`setSystemProperty`. Each privileged capability is a typed method with a bounded
argument domain.

Service side, every call: validate caller (UID/package/signature) → validate
arguments in `:domain` validators → check capability → execute → return
`BombResult`. Telemetry is delivered by callback with a config-driven interval,
and the engine collects only while at least one callback is registered.

---

## 9. Risks

| # | Risk | Impact | Mitigation |
| --- | --- | --- | --- |
| R1 | **No platform key** ⇒ no system UID; some framework APIs unreachable (esp. Deep Freeze, some AppOps) | Reduced — root mode reaches these without the key | Two execution modes (ARCHITECTURE §5b); capability probe degrades to `Unsupported` rather than breaking |
| R12 | Root mode depends on a root manager staying installed and granting Bomb | Capabilities vanish mid-session | Re-probe on resume; report `BackendUnavailable` visibly; never cache a capability as permanently available |
| R13 | Two SELinux delivery paths (CIL + `sepolicy.rule`) drift apart | A rule works in one mode only | Author once in `sepolicy/`, generate both; CI check that the emitted rule sets match |
| R2 | **NDK not installed**; no AOSP tree | Blocks M8 `bombd` | Defer `bombd` to M8 (D3); install NDK before M8; build standalone via CMake |
| R3 | `selinuxpatch.py` cannot declare new types today | Bomb's domains would be silently dropped | Extend the patcher in M2 (§7) and fail loudly when policy recompilation is unavailable |
| R4 | HyperOS live-update APIs are undocumented/unstable | M11 renderer may break across ROM versions | Probe at runtime; notification renderer as guaranteed fallback; renderers isolated from sources |
| R5 | Call recording may have no legitimate capture path on this ROM | M14 may ship as "unsupported" | Capability probe first; do not claim support that cannot be demonstrated; no bypass attempts |
| R6 | Vendor sysfs paths differ per SoC (QCOM vs MTK) | Wrong values or write failures | All device specifics behind `PerformanceBackend`/`GpuBackend`/`ChargingBackend`; probe before use |
| ~~R7~~ | ~~JDK 26 daemon vs AGP 9.x support window~~ | **Resolved in M0.6** | Gradle 9.6.1 + JDK 21 builds the Android UI cleanly. |
| R8 | ROM blanks `ro.control_privapp_permissions` | Allowlist not actually enforced; over-permission goes unnoticed | Keep the allowlist minimal and reviewed by hand; do not rely on the ROM setting |
| R9 | Telemetry polling regresses battery | Contradicts a core requirement | Subscriber-aware engine from M4; a test asserts zero reads with zero subscribers |
| R10 | Freeze/rule interaction oscillation | Device instability | Cooldowns, debouncing, execution history, precedence — all unit-tested in `:domain` |
| R11 | Scope creep into excluded areas (Guard, HyperOS Tweaks, Diagnostics) | Violates the spec | Excluded features are not scaffolded, not stubbed, not referenced |

---

## 10. Testing strategy

- **`:domain` (JUnit, no Android)** — carries the required coverage:
  `FreezePolicyEngine` (delay logic, critical-package exclusion, manual override),
  Rule Engine (trigger matching, condition evaluation, priority, cooldown,
  conflict resolution, loop prevention), telemetry math (rate/delta, counter
  reset, overflow, invalid counters), profile capability filtering and restore
  behaviour, validators (package/PID/UID/enum/numeric range).
- **`:core-api`** — Parcelable round-trip tests; AIDL surface stability check.
- **`:system-service`** — Robolectric/JVM tests for parsers (`/proc/stat`,
  `/proc/<pid>/stat`, meminfo, thermal), capability detection against fixture
  trees, subscriber-count behaviour of the telemetry engine.
- **`:app`** — Compose UI tests for the design system's three mandatory states
  (loading / error / unsupported) and for navigation.
- **Device verification** — freeze/unfreeze, force stop, overlay, and profile
  apply/restore are confirmed on a real ROM build; results reported honestly,
  including what could not be verified.

Every phase's exit gate: relevant tests run, lint/build run, and the actual
commands and outcomes reported — including failures.

---

## 11. Implementation checklist

**M0**
- [x] Repository, toolchain, dependency and ROM-integration audit
- [x] `docs/ARCHITECTURE.md`
- [x] `docs/BOMB_PLAN.md`
- [ ] Review; answer D1–D5

**Foundation**
- [x] M0.6 Android UI shell (real MIUIX, predictive back, persisted settings)
- [ ] M0.5 app-module skeleton (`:app`, `:core-api`, `:domain`, `:system-service`), git init
- [ ] M1 navigation + Bomb design system (dark/light, no stock components leaking)
- [ ] M2 AIDL + `BombCoreService` + capability registry + validators (+ tests)

**Core features**
- [ ] M3 Task Manager
- [ ] M4 Stats core (subscriber-aware telemetry)
- [ ] M5 Stats advanced + overlay
- [ ] M6 Freeze Engine (+ policy tests)
- [ ] M7 App Control
- [ ] M8 Performance Profiles + `bombd` + first SELinux domain for the daemon
- [ ] M9 Bomb Rules (+ engine tests)

**Extended**
- [ ] M10 Log Governor
- [ ] M11 Bomb Bridge
- [ ] M12 Battery Lab + Power Activity
- [ ] M13 Network Control
- [ ] M14 Call Recording (capability-gated)
- [ ] M15 Polish + SELinux hardening review

**Continuous invariants**
- [ ] SELinux enforcing; no broad rules; no unreviewed `audit2allow`
- [ ] No generic shell/file/sysfs/property API on any interface
- [ ] Every hardware feature capability-detected; unsupported states explicit
- [ ] No fabricated metrics anywhere
- [ ] No main-thread I/O; no uncontrolled polling
- [ ] Excluded features (Bomb Guard, HyperOS Tweaks, Diagnostics) not implemented

---

## 12. Stop point

Per instruction, implementation stops here pending review. The only work proposed
before approval is **M0.5** — the minimal buildable skeleton — because the
repository currently cannot build anything at all. Confirm D1–D5 (or approve the
recommended defaults) and M0.5 to proceed.
