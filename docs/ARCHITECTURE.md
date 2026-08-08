# Bomb — Architecture

Status: **Phase 0 / M0 audit result.** Describes what exists today and the
proposed target architecture. No Bomb code has been written yet.

Audit date: 2026-08-07
Audited by: repository inspection (filesystem, build files, ROM tooling, artifact
availability probes). No build was executed — there is nothing to build yet.

---

## 1. Current repository state

### 1.1 `/home/hzzmonet/zk/bomb` — the Bomb repository

```
bomb/
+-- CLAUDE.md          (agent instructions, created 2026-08-07)
+-- docs/              (this audit)
```

Facts:

- **Empty project.** No Gradle build, no source, no manifest, no resources.
- **Not a git repository.** `git status` fails; no parent repo either.
- No AIDL, no native code, no `sepolicy/`, no `init/`, no `permissions/`.
- No tests, no CI configuration, no lint configuration.

Nothing in the Bomb specification is currently implemented in this repository.
Every subsystem (Task Manager, Stats, Freeze, App Control, Log Governor, Bridge,
Performance Profiles, Battery Lab, Power Activity, Network, Recorder, Rules) is
greenfield here.

### 1.2 Surrounding workspace `/home/hzzmonet/zk`

Bomb does not exist in isolation. Four sibling directories define the environment
Bomb must integrate with:

| Path | What it is | Relevance to Bomb |
| --- | --- | --- |
| `ProjectZK/` | Android app `com.zk.toolbox`, Compose, priv-app | Reference implementation; source of reusable technique, **not** a base to fork |
| `hzz/` | HyperOS ROM patch + repack toolchain (bash + python) | **This is Bomb's deployment vehicle.** Already uses "BOMB" naming |
| `zk-flash/` | Rust flasher/TUI | Downstream delivery only |
| `ColorOS15-16_Cooldown_Disabler_v1.1_no_restart/` | Third-party Magisk module | Unrelated reference |

Workspace-level `CLAUDE.md` mandates using the `code-review-graph` MCP knowledge
graph before file scanning. The graph currently has no index for `bomb/`
(`.code-review-graph/` exists in `ProjectZK`, `hzz`, `zk-flash` only), so this
audit used direct inspection. Indexing `bomb/` should happen once real source lands.

---

## 2. `ProjectZK` — what already exists, and what is reusable

`com.zk.toolbox`, a privileged Android toolbox shipped into the ROM as a
`system_ext` priv-app.

### 2.1 Build configuration (verified)

| Setting | Value |
| --- | --- |
| AGP | 9.3.0 |
| Kotlin | 2.1.0 |
| Gradle wrapper | 9.5.0 |
| Compose BOM | 2026.06.01 |
| compileSdk / targetSdk | 37 |
| minSdk | 33 |
| Java source/target | 17 |
| Config cache | enabled |
| Modules | `:app`, `:web-preview` |

Dependencies of note: `androidx.navigation:navigation-compose`,
`androidx.compose.material3`, `material-icons-extended` (pinned 1.7.8),
`io.github.d4viddf:hyperisland_kit:0.4.3`.

`HyperBridge/` and `livebridge/` inside `ProjectZK` are **separately cloned
upstream repositories** (each has its own `.git` and `settings.gradle.kts`) and
are not part of the ProjectZK Gradle build. `settings.gradle.kts` includes only
`:app` and `:web-preview`.

### 2.2 Source inventory

70 Kotlin files, ~16.8k LOC, single module, no DI framework, no Room, no
ViewModel layer, no AIDL, no native code, no service abstraction. Tests are the
unmodified Android template (`ExampleUnitTest`, `ExampleInstrumentedTest`) —
effectively zero real test coverage.

Directly relevant existing implementations:

| Area | Files | Reuse verdict |
| --- | --- | --- |
| FPS measurement + overlay | `data/fps/FpsMeter.kt`, `FpsOverlayController.kt`, `FpsOverlayService.kt` | **Technique reusable** (dumpsys gfxinfo + `SYSTEM_ALERT_WINDOW` + `DUMP`). Bomb re-implements behind `TelemetryCollector` + `BombMonitorOverlay` |
| Live-update / notification bridge | `com/appsfolder/livebridge/liveupdate/*` — `LiveUpdateNotificationListenerService`, `LiveUpdateNotifier`, `HyperBridgeAdapter`, `LiveParserDictionary`, `AppPresentationOverrides` | **Closest existing analogue to Bomb Bridge.** Confirms the notification-listener + HyperOS live-update rendering path works on this ROM. Bomb Bridge must separate source parsing from rendering, which this code does not |
| Network speed collector | `livebridge/.../networkspeed/*` (`NetworkSpeedDataSource`, `Controller`, `ForegroundService`, `Formatter`) | Rate-calculation technique reusable for Network Control telemetry |
| Installed apps | `data/spoof/InstalledAppsRepository.kt` | Pattern reusable for the Apps tab |
| System properties / settings | `data/SystemPropsReader.kt`, `data/spoof/GlobalSettingsRepository.kt`, `HiddenSettings.kt` | Reusable technique for typed property access |
| Signature gating | `data/sig/*`, `data/ZkGate.kt` | Reference for caller/integrity validation |
| Background execution | `data/AppExecutors.kt` | Executor-based; Bomb uses coroutines/Flow instead |

**Verdict: ProjectZK is a reference, not a base.** Different package, different
product focus (spoofing / Play Integrity / device props), single-module
architecture with privileged work inside the UI process — exactly the shape the
Bomb specification forbids. Bomb starts fresh and borrows proven techniques.

---

## 3. `hzz` — the ROM integration environment (most important finding)

`hzz/` patches **prebuilt HyperOS images** — it is not an AOSP source tree.
`build.sh` extracts `super.img`, `bomb.sh` mutates the extracted partitions, then
`build.sh` repacks EROFS + super.

The "Bomb" name already exists here as ROM-level patch markers:
`# BOMB INIT PATCH`, `# BOMB I/O SCHEDULER PATCH`, `; BOMB SELINUX PATCH`.

### 3.1 How a privileged app reaches the device today

```
mods/zk_mods/universal/system_ext/priv-app/ProjectZK/ProjectZK.apk
mods/zk_mods/universal/system_ext/etc/permissions/privapp-permissions-com.zk.toolbox.xml
```

`bomb.sh zk-mods` copies `mods/zk_mods/universal/*` over the extracted image tree.
This is the exact slot Bomb will occupy:
`system_ext/priv-app/Bomb/Bomb.apk` +
`system_ext/etc/permissions/privapp-permissions-com.hzzmonet.zkbomb.xml`.

### 3.2 SELinux mechanism (`tools/py/selinuxpatch.py`)

Runs per-partition during repack, before `mkfs.erofs`:

1. `patch_vendor_sepolicy()` appends CIL `(allow …)` rules to
   `vendor/etc/selinux/vendor_sepolicy.cil` under the `; BOMB SELINUX PATCH` marker.
2. Rules are **validated against symbols already present** in the ROM's `.cil`
   files — `_CIL_SYMBOL_RE` collects `(type …)` / `(typeattribute …)` and
   `_CIL_CLASS_RE` collects classes/permissions. Rules referencing unknown types
   are dropped.
3. Stale `vendor|odm/etc/selinux/precompiled_sepolicy` is deleted so the device
   recompiles policy at boot. This requires `system/bin/secilc`,
   `system/etc/selinux/plat_sepolicy.cil` and `vendor/etc/selinux/plat_sepolicy_vers.txt`
   to exist (`_runtime_policy_compile_available()`); if they don't, custom rules
   are **silently skipped**.
4. It then regenerates `fs_config` and `file_contexts` for the partition, deriving
   contexts automatically (`_CONTEXT_MAP`, HAL heuristics, fallback
   `u:object_r:system_file:s0`).

Consequences for Bomb, and they are significant:

- The current tool can only emit `allow` rules over **pre-existing** types. It
  cannot declare `bomb_app`, `bombd`, `bomb_data_file`, `bomb_service`.
  Creating those domains requires extending `selinuxpatch.py` to emit type
  declarations, attribute sets, domain-transition rules, and to write
  `file_contexts` / `service_contexts` / `seapp_contexts` entries.
- A binary dropped at `/system/bin/bombd` would be auto-labeled `system_file` —
  which cannot serve as a domain entrypoint. Explicit labeling is mandatory.
- The existing rule set is `init`-heavy and permissive-ish in places
  (`init sysfs:file write`, `init proc:file write`). Bomb must not extend that
  pattern; Bomb's own rules must be scoped to Bomb's own types.
- The `selinux` subcommand appears in `bomb.sh`'s usage string (line 655) but has
  **no `case` implementation** — a dead entry point that Bomb can legitimately claim.

### 3.3 Existing ROM behaviours that overlap Bomb features

- **Log Governor already has a ROM-side prototype.** `bomb.sh` `init-rc` installs
  property-triggered blocks keyed on `persist.sys.zk.minimal_logging` (0/1) that
  set `persist.logd.*`, `persist.traced*`, and stop/start `logcatd`/`traced`.
  The comment explicitly preserves crash logs/tombstones/bugreports. This is a
  ready-made, typed, property-driven backend for the Log Governor `QUIET` profile
  and should be generalized rather than reinvented.
- **I/O scheduler tuning** exists as a static `init.qti.kernel.rc` patch — a
  precedent for Performance Profile backends, and a conflict source to respect.
- **Charging/thermal sysfs paths** appear in the ROM patches
  (`/sys/class/qcom-battery/*`, `vendor_sysfs_kgsl`, `thermald-devices.conf`) —
  useful capability-probe candidates for Battery Lab and GPU telemetry, all
  Qualcomm-specific and therefore backend-gated.
- `bomb.sh` blanks `ro.control_privapp_permissions` in `vendor/build.prop`
  (line 516), i.e. **privapp permission enforcement is disabled on this ROM**.
  Bomb still ships a minimal allowlist — it is correctness, not the enforcement
  boundary, and the ROM setting may change.

### 3.4 Anti-pattern to avoid

`privapp-permissions-com.zk.toolbox.xml` requests **~115 privileged permissions**,
including `MASTER_CLEAR`, `INSTALL_PACKAGES`, `DELETE_PACKAGES`,
`MANAGE_DEVICE_ADMINS`, `MOUNT_UNMOUNT_FILESYSTEMS`, `RECOVERY`, plus OPPO/OPlus
vendor permissions irrelevant to a Xiaomi ROM. This is precisely the
"request every privileged permission just in case" pattern the Bomb spec forbids.
Bomb's allowlist is derived per-feature and justified in-file.

---

## 4. Toolchain reality

| Item | Status | Impact |
| --- | --- | --- |
| JDK | 26.0.2 on PATH | Newer than AGP 9.x's supported daemon JVM. Must be verified; pin a JDK 17/21 toolchain (foojay resolver is already the workspace convention) |
| Gradle wrapper dists cached | 9.5.0, 9.6.1 | Bomb can pin 9.5.0 to match ProjectZK |
| Android SDK | `~/Android/Sdk`, platforms **android-35, android-37.0**, build-tools 35.0.1 / 36.0.0 | compileSdk 37 available; build-tools 37 not installed |
| **NDK** | **not installed** | **`bombd` cannot be compiled today.** Blocking for the daemon phase only |
| AOSP source tree | **absent** | No Soong/`Android.bp`. `bombd` must be an NDK standalone build; no platform-key signing; no `system_server` service registration |
| Network | reachable (Maven Central + Google Maven) | Dependency resolution works |

### 4.1 Dependency availability (probed, not assumed)

| Artifact | Availability |
| --- | --- |
| `top.yukonga.miuix.kmp:miuix` | **available**, Maven Central, latest 0.8.8 (metadata updated 2026-03-22) |
| `top.yukonga.miuix.kmp:miuix-android` | available |
| `androidx.room:room-runtime` | available on Google Maven, latest 2.8.4 |
| `androidx.room3:room3-runtime` | available, 3.0.1 |
| `io.github.d4viddf:hyperisland_kit:0.4.3` | already in the local Gradle cache |

No MIUIX dependency exists anywhere in the workspace today — ProjectZK is pure
Material 3. Bomb introduces MIUIX for the first time.

---

## 5. Constraints this environment imposes

1. **No platform signing key.** `ProjectZK.apk` is signed with a project keystore.
   Therefore `android:sharedUserId="android.uid.system"` and any
   `signatureOrSystem` platform permission are unavailable unless the ROM's
   platform key is obtained. This is the single largest architectural
   determinant — see `BOMB_PLAN.md` §"Open decision D1".
2. **No system_server integration.** Without an AOSP tree, `BombCoreService`
   cannot be a real framework service inside `system_server`. It must be a
   process Bomb owns.
3. **Prebuilt-image patching only.** Every system-side artifact (APK, daemon,
   `.rc`, permissions XML, SELinux rules) ships as a file copied into an extracted
   partition by `bomb.sh`, then repacked.
4. **SELinux stays enforcing**, and new domains require extending
   `selinuxpatch.py` — a real work item, not a config flag.
5. **HyperOS-specific APIs are undocumented.** Anything touching HyperOS live
   updates / Focus notifications must be capability-probed at runtime with a
   notification fallback.

---

## 5b. Execution modes (added 2026-08-07)

Some operations are impractical to ship inside the ROM: they need SELinux types
or rules that `selinuxpatch.py` cannot add safely today (§3.2), and every change
costs a full extract → patch → repack → flash cycle. Bomb therefore supports
two **execution modes**, resolved at runtime by capability detection.

| Mode | How privilege is obtained | SELinux | When it applies |
| --- | --- | --- | --- |
| **ROM** | priv-app in `system_ext` + `bombd` labelled at build time | Bomb's own domains compiled into the ROM policy | The integrated build |
| **Root** | Normal APK + a Magisk/KernelSU module | Module ships `sepolicy.rule`; `magiskpolicy` loads Bomb's types at boot — no partition repack | Tweaks that are hard to integrate; development; devices on a stock ROM |

Root mode is a **backend, not an API**. The AIDL surface is byte-for-byte
identical across modes — same typed calls, same validation, same `BombResult`.
What changes is which backend answers, and `BombCapabilities` reports the
difference so the UI can disable what the current mode cannot do.

This preserves the rules in `CLAUDE.md`: the UI still never receives root, and
no `executeShell` / `writeSysfs` / `setProperty` entry point exists in any mode.
`bombd` runs as its own service started by the module's `service.sh`, speaking
the same typed opcode protocol it speaks under ROM integration.

Practical consequences:

- **The platform-key problem (§5.1) softens.** Operations that need more than
  priv-app can be reached through the root backend without the ROM's platform
  key, so Deep Freeze and parts of App Control no longer hinge on obtaining it.
- **SELinux iteration gets cheap.** `sepolicy.rule` is edited and reloaded at
  boot, instead of re-running the whole image pipeline for one rule.
- **New dependency.** Root mode's availability depends on a root manager staying
  installed and granting Bomb. Losing it reports `BackendUnavailable` visibly;
  the app does not silently switch execution modes.
- SELinux stays **enforcing** in both modes. Root mode is not permission to
  relax policy; it is a different way to install the same minimal rules.

## 6. Proposed target architecture

```
+--------------------------------------------------------------+
|  Bomb UI  (com.hzzmonet.zkbomb, priv-app, Compose + MIUIX)    |
|  ui/design  ui/navigation  ui/<feature>                       |
|  never touches sysfs/procfs, never runs shell                 |
+---------------------------+----------------------------------+
                            | domain use cases (pure Kotlin)
+---------------------------v----------------------------------+
|  domain/   use cases, policy engines, validation              |
|  FreezePolicyEngine  RuleEngine  ProfileResolver  Validators  |
+---------------------------+----------------------------------+
                            | repositories (Flow)
+---------------------------v----------------------------------+
|  data/     Room, DataStore, BombServiceClient                 |
+---------------------------+----------------------------------+
                            | AIDL / Binder  (core-api)
+---------------------------v----------------------------------+
|  BombCoreService  (privileged process)                        |
|  caller validation | capability registry | framework APIs     |
|  freeze | appcontrol | performance | battery | network | logs  |
|  bridge | recorder | automation scheduling                     |
+---------------------------+----------------------------------+
                            | typed IPC, restricted socket
+---------------------------v----------------------------------+
|  bombd  (native, minimal, own SELinux domain)                 |
|  CommandDispatcher -> Telemetry/Performance/Process/Battery/  |
|  Network commands; every arg validated; no shell API          |
+---------------------------+----------------------------------+
                            |
        procfs | sysfs | cgroups | thermal | power | vendor
```

Layer contracts:

- **UI** renders immutable state, emits intents. No privileged call originates
  in a composable.
- **domain** is pure Kotlin (JVM-testable, no Android imports where avoidable) —
  this is where `FreezePolicyEngine`, the Rule Engine, capability filtering and
  all validation live, so they are unit-testable without a device.
- **data** owns Room + DataStore and the `BombServiceClient` Binder wrapper;
  every privileged call returns `BombResult<T>`.
- **core-api** is the only shared surface between UI and service: AIDL +
  `Parcelable` models + client. Versioned.
- **system-service** validates every caller and every argument, owns the
  capability registry, and is the only place framework privileged APIs are called.
- **bombd** exists only for what the framework genuinely cannot do. Every command
  is an explicit typed opcode with validated arguments and a bounded value range.

Cross-cutting:

- `BombResult` = `Success | Unsupported | PermissionDenied | InvalidArgument |
  BackendUnavailable | Failed`. No silent swallowing.
- `BombCapabilities` gates every hardware-dependent control; unsupported controls
  render an explicit unsupported state, never fake data.
- Telemetry is subscriber-aware: zero collection with zero consumers.

Module mapping, phase-by-phase file lists, the SELinux and AIDL plans, risks and
the test strategy are in [`BOMB_PLAN.md`](./BOMB_PLAN.md).
