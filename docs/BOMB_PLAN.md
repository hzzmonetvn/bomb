# Bomb — Implementation Plan

Status: **Phase 0C synthesis complete. Stop point — awaiting review before M1.**

Companion documents:
[`ARCHITECTURE.md`](./ARCHITECTURE.md) (audit + patch points + capability matrix),
[`research/`](./research/) (fifteen source-level research notes),
[`research/REFERENCES.md`](./research/REFERENCES.md) (commits, licenses, verdicts),
[`../BOMB_PLAN.md`](../BOMB_PLAN.md) (the master plan this implements).

Last updated 2026-08-08 after Phase 0B research. Section 4 is new and is the
substance of this revision.

---

## 1. Current repository state

| Question | Answer |
| --- | --- |
| Existing Bomb code? | UI only — 44 Kotlin files in `:preview` (design system, navigation, feature screens) + `:app-preview` (packaging) |
| Git repo? | Yes, branch `main`, 2 commits |
| Builds? | Yes — `./gradlew --offline :app-preview:assembleDebug` → BUILD SUCCESSFUL (45 s, verified 2026-08-08) |
| Tests? | **None.** No `src/test` anywhere, no CI, no lint config |
| Native code / AIDL / sepolicy / init / permissions? | None |
| Privileged code? | None. No service, no Binder surface, no backends |
| Spec implemented? | UI shell only. Every subsystem below the UI is greenfield |

The screens render from a state holder with hand-written sample state. That was
the right call for settling the design language, and it is exactly the thing the
master plan §40 forbids shipping — **M1's first job is to replace it with real
state or explicit empty/unsupported states, not to extend it.**

---

## 2. Decisions

### Resolved by the M0.6 UI work

- **D4 — MIUIX vs in-house design system.** Resolved: depend on MIUIX, keep every
  usage behind the `Bomb*` layer. Upstream has since split into eight modules
  (`miuix-ui`, `miuix-preference`, `miuix-nav`, …); 0.8.8's single artifact still
  works, and the wrapper layer is what makes that migration contained —
  [`research/MIUIX.md`](./research/MIUIX.md) §1.
- **R7 — JDK/Gradle.** Resolved: Gradle 9.6.1 on JDK 21 builds Kotlin 2.3.20 +
  Compose 1.10.3 cleanly. The JDK 26 on `PATH` is not used.

### Resolved by Phase 0B research

- **D1 — privilege model.** Confirmed **A: priv-app + bound Binder service**, with
  the AIDL surface kept identical so a platform-key upgrade would be a signing
  change only. Research shows the platform key is *not* needed for the core
  operations: `FORCE_STOP_PACKAGES` and `setApplicationEnabledSetting` are
  priv-app reachable ([`research/HAIL.md`](./research/HAIL.md) §2,
  [`research/AOSP_PROCESS_MANAGEMENT.md`](./research/AOSP_PROCESS_MANAGEMENT.md) §1).
- **D3 — is `bombd` needed early?** Revised after Log Governor implementation:
  **yes for every ROM write**. Android SELinux forbids app domains from generic
  property-service writes, so even fixed log request properties cross `bombd`.
- **D5 — Room version.** Room 2.8.4.

### New decisions from research

| # | Decision | Grounding |
| --- | --- | --- |
| **D6** | App Visibility and Settings Virtualization are **framework patches**, not app-level features, and are **ROM-mode only**. `BombCapabilities` reports them unsupported in root mode. | [`AOSP_PACKAGE_MANAGER.md`](./research/AOSP_PACKAGE_MANAGER.md) §6, [`AOSP_SETTINGS_PROVIDER.md`](./research/AOSP_SETTINGS_PROVIDER.md) §8 |
| **D7** | Deep freeze **expresses intent through the platform freezer**; `bombd` never writes `cgroup.freeze` directly. Binder must be frozen before the process or in-flight transactions are lost. | [`AOSP_PROCESS_MANAGEMENT.md`](./research/AOSP_PROCESS_MANAGEMENT.md) §3 |
| **D8** | `DEEP_FREEZE` prefers **suspend** over **hide**. Hidden apps behave as uninstalled and break other apps' queries; suspend keeps the package queryable and attributable. | [`HAIL.md`](./research/HAIL.md) §1 |
| **D9** | Bomb Bridge's **primary** renderer is the official Live Update path (`setRequestPromotedOngoing` + `setShortCriticalText`), not HyperIsland. HyperIsland is an enhancement behind a three-signal probe; the notification renderer is the floor. | [`LIVE_UPDATES.md`](./research/LIVE_UPDATES.md) §3 |
| **D10** | Bomb ships **no bundled blocklist data**. AdGuard rule lists are GPL-3.0; Bomb ships the parser/compiler and the user adds sources. | [`REFERENCES.md`](./research/REFERENCES.md) §2 |
| **D11** | No generic shell/exec/path API on any interface, ever. Where a file is involved the caller passes an **fd it already holds**, never a path. | [`UI_REFERENCES.md`](./research/UI_REFERENCES.md) §2 |
| **D12** | Bomb does **not** become device owner, and does **not** manipulate platform permission-restriction state to grant itself access. | [`HAIL.md`](./research/HAIL.md) §2, [`BCR.md`](./research/BCR.md) §4 |
| **D13** | `minSdk` stays **33** (current build) rather than the previously planned 35. Nothing in the research requires 35, and 33 costs nothing. Revisit only if a required API forces it. | this document |
| **D16** | **Third-party code policy (decided by the project owner).** A repo that grants **no licence** → reimplement from the documented mechanism, copy nothing. A repo that grants a licence **with conditions** → comply with the conditions and reuse. Copyleft code ships as a **segregated component** under its own licence, in its own directory, behind a documented process boundary — never mixed into or ported into Bomb's own sources. | [`PROXY_GATEWAY_PLAN.md`](./PROXY_GATEWAY_PLAN.md) §1, §3 |
| **D17** | Applying D16: the proxy engine is **`magic_v2ray` adapted under GPL-3.0**, shipped as shell (source form), reached through its existing fixed-verb control FIFO. `vpn-gateway` (no licence) is **reimplemented** and only for the optional third-party-VpnService sink. | [`research/PROXY_MODULES.md`](./research/PROXY_MODULES.md), [`PROXY_GATEWAY_PLAN.md`](./PROXY_GATEWAY_PLAN.md) |

### Open, needs the user's answer

- **D14 — Bomb's own license.** *Partially resolved:* D16 settles how third-party
  code is handled, and the segregation rule keeps Bomb's own sources clean under
  any outcome. Still open: what licence **Bomb itself** ships under. Fix it before
  `framework/` is authored, since AOSP-derived patches must carry Apache-2.0
  headers.
- **D15 — scope confirmation.** The master plan grew the product substantially
  (App Visibility, Settings Virtualization, Component Explorer, Process Control,
  SDK Inspector, AdBlock/DNS/Firewall, Autostart/Doze, Clipboard/Sensor/Notification,
  Storage Analyzer, Idle App Advisor), and the proxy subsystem (§4.12) is now added
  on top. Phases 8–16 below are large. Confirm the priority order in master plan
  §38 before M1, or the plan optimises for the wrong thing.
- **D18 — proxy sink (G1).** Bomb's own tunnel (recommended — tether comes free
  with the adopted engine), a third-party VpnService only, or both with an
  interlock. See [`PROXY_GATEWAY_PLAN.md`](./PROXY_GATEWAY_PLAN.md) §1.

---

## 3. Module mapping

Gradle project `bomb`, root `/home/hzzmonet/zk/bomb`.

| Module | Type | Package root | Contains |
| --- | --- | --- | --- |
| `:app` | `com.android.application` | `com.hzzmonet.zkbomb` | Compose UI, `ui/design`, navigation, feature screens, overlay service, `data/` (Room, DataStore, service client). **Renamed from today's `:preview` + `:app-preview`** |
| `:core-api` | `com.android.library` | `…zkbomb.api` | AIDL, `Parcelable` models, `BombResult`, `BombCapabilities`, client. The only module shared by `:app` and `:system-service` |
| `:domain` | `java-library` (pure JVM) | `…zkbomb.domain` | `FreezePolicyEngine`, `RuleEngine`, `VisibilityPolicy`, `SettingsPolicy`, `BlocklistCompiler`, `ProfileResolver`, telemetry math, validators. **No Android imports** |
| `:system-service` | `com.android.library` | `…zkbomb.core` | `BombCoreService`, caller validation, capability detection, per-feature managers, backend interfaces + device backends |
| `framework/` | patch set, not Gradle | — | minimal diffs against AOSP `1cdfff5`: `AppsFilterBase.java`, `SettingsProvider.java`, `ProcessList.java`, `CachedAppOptimizer.java` + the Bomb policy classes they call |
| `native/bombd/` | NDK/CMake | — | typed property-request daemon used by Log Governor and live memory controls |
| `proxy/` | files, **GPL-3.0** | — | the adapted `magic_v2ray` engine (`bomb-proxy.sh`, `bomb-proxyctl.sh`) + `LICENSE` + `MODIFICATIONS.md` + upstream licence notices. **Segregated per D16** — nothing here is imported by any other module, and nothing from here is ported into Bomb's sources |
| `sepolicy/` | files | — | `bomb_app.te`, `bombd.te`, `file_contexts`, `service_contexts`, `seapp_contexts`, `property_contexts` |
| `module/` | files | — | root-mode Magisk/KernelSU package built from the same `sepolicy/` + `daemon/` sources |
| `init/`, `permissions/`, `docs/` | files | — | `bombd.rc`; `privapp-permissions-com.hzzmonet.zkbomb.xml`; this plan + research |

`framework/` is new and is the structural consequence of D6. Keeping the patches
in-repo as reviewable diffs — rather than as edits made inside the ROM tree — is
what makes rule "minimal diff against a known upstream commit" enforceable.

`:domain` stays a pure `java-library` because every priority test target in master
plan §39 lives there: visibility policy, settings policy, freeze policy, rule
engine, blocklist compiler, telemetry math, validators. All JUnit, no emulator.

---

## 4. Per-subsystem synthesis

Format required by master plan §37 Phase 0C:
**requirement → AOSP mechanism → reference(s) → Bomb design → risk → testing.**

### 4.1 Task Manager

- **Requirement** (master plan §5.1): process viewer with CPU/RSS/PSS/threads,
  sort, search, typed actions; configurable refresh; no RAM cleaner.
- **AOSP mechanism**: `/proc/stat` + `/proc/<pid>/stat` deltas;
  `ActivityManager.getRunningAppProcesses()` for `importance` (rate-limited by a
  10 ms cache, `ActivityManager.java:234`); `getProcessMemoryInfo` for PSS
  (expensive); `forceStopPackage` behind `FORCE_STOP_PACKAGES`, returns `void` and
  silently ignores protected packages; phantom/native children discovered via the
  app's cgroup procs file (`PhantomProcessList.java:204`).
- **References**: [`AOSP_PROCESS_MANAGEMENT.md`](./research/AOSP_PROCESS_MANAGEMENT.md),
  [`STATS_REFERENCES.md`](./research/STATS_REFERENCES.md).
- **Bomb design**: `/proc` read in `:system-service`; CPU delta and classification
  in `:domain`; PSS only for the selected process; refresh levels
  500/1000/2000/5000 ms with collection paused when not resumed; force-stop result
  derived from **observed state**, never from the call returning.
- **Risk**: polling faster than the platform's own cache wastes CPU; PSS at
  realtime would be a battery regression.
- **Testing**: delta calculator (reset, overflow, zero elapsed), `/proc` parsers
  against captured fixtures incl. `comm` with spaces/parens, classification from
  (pid, uid, cgroup, appZygote, isolated) fixtures.

### 4.2 Bomb Stats / telemetry

- **Requirement** (§6): one pipeline; CPU/GPU/memory/graphics/battery/thermal/
  network; overlay with drag, snapping, presets; subscriber-aware.
- **AOSP mechanism**: `ActivityManager.MemoryInfo` (whose own doc warns `availMem`
  is not headroom); cpufreq sysfs; thermal/GPU/charge nodes **unverified in the
  sparse checkout** and therefore probe-only.
- **References**: [`STATS_REFERENCES.md`](./research/STATS_REFERENCES.md) — note
  OpenMonitor publishes no source, so this subsystem rests on AOSP alone.
- **Bomb design**: `TelemetryEngine` with a subscriber registry; per-metric cost
  classes (cheap/moderate/expensive/platform-limited) gating what each refresh
  level may sample; all device paths behind `GpuBackend`/`ThermalBackend`/
  `ChargingBackend`; unsupported metrics render an explicit unsupported state.
- **Risk**: fabricated metrics are the single easiest way to violate the project
  rules; vendor node paths differ per SoC.
- **Testing**: zero subscribers ⇒ zero reads (asserted); rate/delta cases; an
  unreadable node degrades one metric, not the snapshot.

### 4.3 Freeze Engine

- **Requirement** (§7): four modes, backends, per-app policy, tiered auto-freeze,
  mandatory exclusions.
- **AOSP mechanism**: four *different* states from four different
  `ApplicationInfo` bits — enabled, `PRIVATE_FLAG_HIDDEN`, `FLAG_STOPPED`,
  `FLAG_SUSPENDED`; `setApplicationHiddenSettingAsUser` refuses device admins and
  `"android"`; `setPackagesSuspended` returns the **failed package names**;
  `CachedAppOptimizer.freezeProcess` freezes binder *before* the cgroup and kills
  on binder-freeze failure; `Freezer.isFreezerSupported()` is the capability probe.
- **References**: [`HAIL.md`](./research/HAIL.md),
  [`AOSP_PROCESS_MANAGEMENT.md`](./research/AOSP_PROCESS_MANAGEMENT.md).
- **Bomb design**: `FreezeMode` → mechanism resolution driven by a capability
  matrix (D8: suspend preferred for `DEEP_FREEZE`); force stop is a Task Manager
  *action*, never a freeze state; disable is preceded by force stop; results are
  re-read from platform state; exclusions (Bomb, SystemUI, resolved launcher,
  **current** IME, framework, vendor) enforced in `:domain`; per-app freeze resolves
  to the full process set, including `:remote`.
- **Risk**: racing `CachedAppOptimizer`; oscillation with Rules; an app reported
  frozen while `:push` still runs.
- **Testing**: exclusion rejection with typed reasons; tier escalation; manual
  override precedence; deadline recomputation after service restart; per-mechanism
  device verification that the four bits are not conflated.

### 4.4 Component Explorer / Process Control

- **Requirement** (§8, §9): component inspection and control; prefer component
  blocking > framework spawn policy > kill-on-spawn; process classification.
- **AOSP mechanism**: `setApplicationEnabledSetting`/component enabled settings;
  Intent Firewall XML under `<secure data>/system/ifw/`;
  `ProcessList.startProcessLocked` :2650 as the single spawn funnel, with an
  existing "return null" refusal contract and a `HostingRecord` explaining *why*
  the process is starting.
- **References**: [`BLOCKER.md`](./research/BLOCKER.md),
  [`AOSP_PROCESS_MANAGEMENT.md`](./research/AOSP_PROCESS_MANAGEMENT.md).
- **Bomb design**: component state is a **set of blocking reasons**
  (`pmDisabled`, `ifwBlocked`, `bombPolicy`), not a boolean; enable clears every
  reason or reports which it could not; providers are PM-only (IFW cannot block
  them); component names validated against the target manifest before any
  privileged call; batch returns per-item `BombResult`; kill-on-spawn is **not
  implemented at all**.
- **Risk**: spawn policy is a `services.jar` patch (ROM-mode only); a wrong
  component block can brick an app.
- **Testing**: state-composition truth table; enable clears both mechanisms;
  provider→PM routing; IFW XML round-trip and corrupt-XML degradation; manifest
  validation rejects unknown components.

### 4.5 App Visibility

- **Requirement** (§12): caller-aware virtual package view, consistent across
  enumeration, direct lookup, intent resolution, providers and UID mappings;
  immutable snapshot; multi-user; shared-UID handling.
- **AOSP mechanism**: `AppsFilterBase.shouldFilterApplication` :333 is the single
  chokepoint; `ComputerEngine` funnels 104 call sites into it, and
  `applyPostResolutionFilter` :1277 / `applyPostServiceResolutionFilter` :1311 call
  `mAppsFilter` **directly** — which is why patching `ComputerEngine` alone would
  leak through intent resolution.
- **References**: [`AOSP_PACKAGE_MANAGER.md`](./research/AOSP_PACKAGE_MANAGER.md),
  [`HMA_OSS.md`](./research/HMA_OSS.md) (independent confirmation of the same hook).
- **Bomb design**: one patch in `AppsFilterBase`, inserted *before* AOSP's early
  returns so a caller holding `QUERY_ALL_PACKAGES` cannot bypass it; policy keyed
  by `(userId, callerPackage)` — a deliberate improvement over HMA, which keys by
  package alone; exemptions centralized (appId < 10000, caller == Bomb, caller ==
  target); `VisibilityPolicySnapshot` immutable, `volatile`-published, O(1),
  allocation-free.
- **Risk**: hottest path in `system_server`; system-app *targets* cannot be hidden
  through AOSP's own early return unless Bomb inserts above it and then owns the
  exemption burden; documented leak surfaces remain (archived packages, UsageStats,
  AccountManager) and must be stated, not implied away.
- **Testing**: blacklist/whitelist, caller isolation, user isolation, shared-UID
  union + conflict rejection, exemptions, snapshot atomicity; device verification
  across **all thirteen surfaces** listed in the research note.

### 4.6 Per-App Settings Virtualization

- **Requirement** (§13): per-caller virtual reads, no mutation of real settings,
  typed values, three namespaces, allowlisted keys.
- **AOSP mechanism**: `SettingsProvider.getSystemSetting` :1916 /
  `getSecureSetting` :1654 / `getGlobalSetting` :1465. AOSP **already** virtualizes
  per caller here for SSAID (`isNewSsaidSetting` :1692, `getSsaidSettingLocked`
  :1698) and already returns a null-setting to fake absence (:1670).
- **References**: [`AOSP_SETTINGS_PROVIDER.md`](./research/AOSP_SETTINGS_PROVIDER.md),
  [`HMA_OSS.md`](./research/HMA_OSS.md) §5.
- **Bomb design**: hook after `enforceSettingReadable`, before the SSAID branch;
  caller from `Binder.getCallingUid()`, never from an `AttributionSource` string;
  **omit generation data for virtualized keys** so the client never caches a
  per-caller value (adopted from HMA, verified against `Settings.java`); typed
  values serialized exactly as the platform does; `NULL` → `getNullSetting()`.
- **Risk**: the master plan's own `font_scale` example does not work — it is read
  by system_server and pushed via `Configuration`, and `PackageConfigurationUpdater`
  has no `setFontScale`. Keys must be classified `APP_READ` / `SYSTEM_READ` /
  `PER_APP_CONFIG` / `FORBIDDEN`, and only `APP_READ` is virtualizable.
- **Testing**: caller-specific override, real value for others, system caller
  exempt, typed round-trip incl. boolean `"0"`/`"1"`, user isolation, validator
  rejects `SYSTEM_READ` keys.

### 4.7 AdBlock / DNS

- **Requirement** (§16, §17): DNS/domain blocking only; hosts + plain + basic
  adblock syntax; allowlist; update pipeline that preserves the previous blockset.
- **Mechanism**: `||domain^` semantics are exactly domain-label suffix matching —
  `query == blocked || query.endsWith("." + blocked)` — because `||` translates to
  `^(http|https|ws|wss)://([a-z0-9-_.]+\.)?` and `^` excludes `.` from its
  separator class. Hosts-format rules are **exact match** in AdGuard.
- **References**: [`ADGUARD_FILTER_FORMAT.md`](./research/ADGUARD_FILTER_FORMAT.md),
  [`ADGUARD_HOME.md`](./research/ADGUARD_HOME.md).
- **Bomb design**: no regexp engine — a reversed-label trie; all input formats
  normalized to subdomain-inclusive entries (a documented divergence from strict
  hosts semantics); parse into a **pending temp file**, swap atomically only on
  success; HTTP 200 + size limit + HTML detection + control-byte rejection +
  checksum + minimum-valid-rule threshold; local-file sources restricted to an
  allowed path set; unsupported lines counted and reported, never silently blocked.
- **Risk**: naive substring matching is the classic false-positive source; a
  captive-portal page becoming a "blocklist"; per-domain Room rows.
- **Testing**: the 20-row matrix in the research note, including `notexample.com`
  and `example.com.evil.net` negative cases and failed-update retention.

### 4.8 Bomb Bridge

- **Requirement** (§23): normalized `BombLiveEvent`, three renderers, no coupling
  between source parsing and the HyperIsland renderer.
- **Mechanism**: official — `setRequestPromotedOngoing(true)`,
  `setShortCriticalText(...)`, `NotificationCompat.ProgressStyle` with points and
  segments, `POST_NOTIFICATIONS` only. HyperOS — an ongoing notification carrying
  `miui.focus.param` JSON + a resource `Bundle`, gated by three probes
  (`persist.sys.feature.island`, `Settings.System.notification_focus_protocol`,
  `canShowFocus` via `content://miui.statusbar.notification.public`).
- **References**: [`LIVE_UPDATES.md`](./research/LIVE_UPDATES.md),
  [`HYPERBRIDGE.md`](./research/HYPERBRIDGE.md),
  [`LIVEBRIDGE.md`](./research/LIVEBRIDGE.md).
- **Bomb design** (D9): notification renderer first, Live Update renderer second,
  HyperOS third; first-party sources (telemetry, charging, recording, hotspot, VPN)
  before notification mirroring; mirroring uses **structured extras only**;
  self-dismiss guard and connect-time reconcile are requirements, not polish; the
  renderer that actually handled an event is recorded and shown.
- **Risk**: `miui.focus.param` is undocumented and version-dependent; HyperOS gates
  focus notifications by package (HyperBridge resorts to toggling XMSF networking —
  Bomb will not); promotion is a *request* the system may decline.
- **Testing**: renderer-selection matrix; declined promotion still visible; event
  identity across repeated updates; progress mapping incl. indeterminate;
  never persist or log mirrored text.

### 4.9 Performance / Thermal / Battery

- **Requirement** (§20–22): profiles, capability filtering, restore only what Bomb
  set, hysteresis, Battery Lab with charge control where proven.
- **Mechanism**: device sysfs — Qualcomm-specific on this ROM
  (`/sys/class/qcom-battery/*`, `vendor_sysfs_kgsl`, `thermald-devices.conf` per
  `ARCHITECTURE.md` §3.3). This is the first subsystem that *writes* device nodes.
- **Bomb design**: `bombd` introduced here and only here, with typed opcodes and
  bounded argument ranges; `PerformanceBackend`/`ChargingBackend`/`GpuBackend`
  interfaces; restore tracks Bomb-set values only; thermal guardian uses hysteresis
  and cooldowns.
- **Risk**: NDK not installed (blocks `bombd`); wrong node writes can damage
  thermal/charging behaviour; conflicts with the ROM's existing static I/O-scheduler
  patch.
- **Testing**: capability filtering drops unsupported fields; restore behaviour;
  hysteresis prevents oscillation across a temperature sequence.

### 4.10 Call Recording

- **Requirement** (§24): capability-first, visible indication, safe failure, no
  bypass techniques, no unsupported claims.
- **Mechanism**: `CAPTURE_AUDIO_OUTPUT` + `CONTROL_INCALL_EXPERIENCE` (both
  privileged) + `InCallService`; capture via `AudioRecord` on
  `MediaRecorder.AudioSource.VOICE_CALL` (or the uplink/downlink pair).
- **References**: [`BCR.md`](./research/BCR.md).
- **Bomb design**: stop triggered from **both** the state transition and
  `onCallRemoved`, idempotent (firmware bugs are real); hold is a state, not a stop;
  conference calls walk `parentCall.children`; capability = an actual capture probe
  yielding non-silent frames, not a permission check; recordings default to
  Bomb-private storage; no network permission in the recorder path.
- **Risk**: `VOICE_CALL` may be silent on this ROM; stereo separation is
  Pixel-specific; **VoIP recording has no reference implementation and starts
  `Unsupported`**; legality varies by jurisdiction, so the feature ships disabled.
- **Testing**: call state machine incl. the missing-`onCallRemoved` case; retention;
  device probe reported honestly including failure.

### 4.11 Bomb Rules

- **Requirement** (§27): triggers, conditions, actions, precedence, cooldown,
  restore, loop prevention.
- **Mechanism**: Bomb-specific. Its inputs are the subsystems above.
- **Bomb design**: whole engine in `:domain`; precedence
  `manual > safety > per-app > global`; every action reversible with recorded
  restore state; debounce + cooldown + repeated-action suppression; the bridge's
  self-dismiss problem is the same class of loop and shares the suppression concept.
- **Risk**: interaction with Freeze and Thermal Guardian producing oscillation.
- **Testing**: trigger matching, condition evaluation, priority, cooldown, conflict
  resolution, loop prevention, restore — all pure JVM.

### 4.12 Proxy / VPN Gateway

- **Requirement**: a system-level transparent proxy under Network Control —
  per-app selection, optional LAN/tether gateway, no packet inspection beyond byte
  counters. Not in the master plan's original scope; added by request and folded
  in here.
- **Mechanism**: policy routing. Marked sockets → `ip rule fwmark <m> table N` →
  default route on a tun → tun2socks → SOCKS5 → proxy core; the core's own
  outbound is marked differently so a second `ip rule` sends it out the physical
  interface and the loop is broken. Tether adds a `PREROUTING` mangle chain and
  `from <RFC1918> lookup N` rules, because `-m owner --uid-owner` cannot match
  forwarded traffic (there is no local socket).
- **References**: [`PROXY_MODULES.md`](./research/PROXY_MODULES.md) — `magic_v2ray`
  @ `84c319f` (GPL-3.0) and `Kr328/vpn-gateway` @ `5bc754a` (**no licence**).
- **Bomb design** (full plan: [`PROXY_GATEWAY_PLAN.md`](./PROXY_GATEWAY_PLAN.md)):
  the engine is `magic_v2ray` adapted under GPL-3.0 and shipped **as shell source**
  in `proxy/`; Bomb owns the typed `ProxyProfile` model, validation, config
  generation and UI. The boundary is the engine's existing control FIFO, whose
  loop is a fixed `case` over ~11 verbs with no `eval` — so Bomb writes an
  enumerated verb and **executes no shell at all**. Eleven numbered modifications
  (M1–M11) cover init lifecycle, a properly labelled runtime dir, pid+starttime
  liveness instead of `/proc` bind-mounts (which drops the need for
  `CAP_SYS_ADMIN`), a masked fwmark outside `netd`'s netId bits, a Bomb-written
  settings file, replacing the absent `sysctl` binary, ROM paths, teardown +
  watchdog, deleting every Magisk/`ksu.exec` surface, exposing Android's `/dev/tun`
  via an init symlink instead of a runtime `mknod` (dropping `CAP_MKNOD`), and
  quarantining the one remaining `sh -c` in the upstream control CLI.
- **Risk**: GPL boundary creep is the dominant one — porting the upstream WebUI's
  Xray config builder into Kotlin would make `:system-service` a derivative, so
  Bomb's generator is written from Xray-core's own documentation. Compiling the
  engine into a binary would trigger GPL-3.0 §6 on a consumer device; shipping it
  as source keeps §6 out of scope permanently. Then: `iptables` may be gone on the
  target image, fwmark may collide with netd, and a bad profile can leave the
  device with no network — hence off-by-default, fail-open-by-default, and a
  property kill switch that works without opening the UI.
- **Testing**: validator and config-generator golden files in `:domain`; a
  compile-time enum proving only the known verbs can be emitted; engine teardown
  restoring the exact pre-start rule set; on device, DNS-leak and IPv6-leak checks,
  network-switch survival, watchdog recovery, and the arm's-length test that the
  engine still runs with Bomb uninstalled.

### 4.13 Log Governor

- **Requirement** (`CLAUDE.md` main features; three tiers set by the project
  owner): `DEFAULT` / `REDUCED` / `OFF`. On the integrated ROM, **`OFF` must not
  require root mode**; it is executed by the typed Bomb service/init backend.
- **AOSP mechanism**: `logd` owns the log buffers *and* hosts the auditd component
  that surfaces `avc: denied`. Volume is controlled by `persist.logd.*` properties,
  but `persist.logd.size` only takes effect after `logd --reinit` (`start
  logd-reinit`) — setting it alone is a silent no-op. `logcatd`, `traced`,
  `traced_probes` are separate services. Tombstones (`debuggerd` →
  `/data/tombstones`) and ANR traces (`/data/anr`) do **not** go through logd and
  survive every tier.
- **References**: [`LOG_CONTROL.md`](./research/LOG_CONTROL.md) — the ROM's existing
  tier-2 patch (`bomb.sh init-rc`, keyed on `persist.sys.zk.minimal_logging`) and
  the ColorOS disabler module (no licence → reimplement-only under D16).
- **Bomb design**: one Bomb-owned property `persist.sys.bomb.log.level` behind a
  typed enum API — not a generic property write. `REDUCED` generalises the ROM's
  existing, already-proven init.rc block, which deliberately keeps logd alive so
  crash logs, bugreports and **SELinux denials** still work. On the verified target
  image, `OFF` is a direct init `ctl.stop` chain with no root-mode session and no
  bind mount. Other ROM backends must capability-probe restarters before claiming
  support; any required no-op stub is installed by the ROM, must exit or sleep
  successfully, and must never be `/dev/null`. Boot-time reconcile converges a
  half-applied tier; `setprop … default` + reboot recovers without the UI.
- **Risk**: **`OFF` makes SELinux denials invisible**, which is the instrument this
  project uses to verify its own "SELinux remains enforcing" invariant. The
  invariant is therefore scoped rather than weakened — it holds for `DEFAULT` and
  `REDUCED`; `OFF` is an explicit, warned override, and Bomb refuses it
  while a Bomb SELinux bring-up is in progress. Secondary: `OFF` → any other tier
  needs a **reboot** (live restoration of a killed logd is unreliable — upstream's
  own uninstall admits this).
- **Resolved 2026-08-08 by extraction** (`ZKOS_NUWA_OS3.0.310.0.WMBCNXM_260808`,
  `system_a`+`vendor_a`): the vendor `.rc` sweep is done. `vendor/etc/init/hw/init.target.rc:78`
  starts `logd` inside `on init` — one-shot; the only *repeating* property triggers
  (`logd.rc:43,45`) start `logd-auditctl`, not `logd`; and `logd` is **not
  `critical`**. **So `OFF` is reachable in ROM mode with a single `stop logd`** —
  no root, no bind-mount stubs. The product decision is now explicit: integrated
  ROM mode provides `OFF` without a separate root mode. Two additions found in
  the same pass: `persist.logd.audit.rate`
  is an existing, live-tunable throttle on SELinux-denial generation (a middle
  ground `REDUCED` should expose instead of the all-or-nothing choice), and MIUI
  adds its own `/dev/ylog_buffer` sink that no tier currently touches — so the UI
  must not claim "logging off" while `ylog` still writes.
- **Testing**: transition table and reconcile combinations in `:domain`; on device,
  `logcat -g` proves the buffer actually shrank (i.e. that `logd-reinit` ran),
  denials still visible under `REDUCED`, logd **stays** stopped for ten minutes
  under `OFF` (this is what detects a vendor restarter), tombstones still produced
  under `OFF`, and no boot loop from a cold boot at any tier.

#### 4.13.1 App Crash Tracker

- **Scope**: an opt-in, local crash observer inspired by LogFox, not a general
  logcat viewer. It has a master toggle and a per-app allowlist; disabling it
  stops its reader/service completely.
- **Events**: correlate Java/Kotlin fatal exceptions, native/JNI crashes and ANRs
  to the selected package/process, then notify with app, time and crash kind.
  Store a bounded context window, stack trace, package/version, process, ABI and
  build metadata. Deduplicate crash loops and rate-limit notifications.
- **Privacy**: local storage only, bounded retention, best-effort secret/PII
  redaction, and user-initiated ZIP/text export. No analytics or automatic upload.
- **Profile interaction**: tracking cannot operate with `logd` stopped. Enabling
  it under `OFF` offers `REDUCED` (recommended) or `DEFAULT`; refusing leaves it
  disabled. Choosing `OFF` stops tracking first. `REDUCED` must preserve fatal
  records needed by the tracker.
- **Privilege model**: the integrated ROM supplies the narrow log-read capability;
  no Magisk/KernelSU/Shizuku/ADB session is part of the product flow. Capability
  absence is shown as unsupported rather than silently collecting partial data.
- **Reference/licence**: [LogFox](https://github.com/F0x1d/LogFox) documents the
  desired crash/ANR notification behaviour but is GPL-3.0; source tracing is
  required and Bomb reimplements the design unless licence compatibility is
  explicitly accepted.

### 4.14 Memory / ZRAM

- **Requirement**: adjust zram and swap behaviour. Adjacent to Performance
  Profiles (§4.9) and to Stats (master plan §6 already lists swap and zram as
  memory metrics), but split out because its risk profile is different from every
  other knob in the project.
- **Mechanism**: `/sys/block/zram0/{disksize,comp_algorithm,mem_limit,mm_stat,
  compact,…}`, `/proc/sys/vm/{swappiness,page-cluster}`, `/dev/memcg/*/memory.swappiness`.
  `comp_algorithm` reports the available set with the current value in brackets —
  one read gives both the enum domain and the present value. `mm_stat` is nine
  fields; compression ratio and RAM efficiency are **derived**, and differ (3.4×
  vs 3.3× in the verified sample) because of allocator overhead.
- **References**: [`ZRAM.md`](./research/ZRAM.md) — interface verified on a live
  kernel; ROM state read from `hzz`.
- **Bomb design**: typed `ZramConfig` with the algorithm validated against the
  **probed** set, never a free string; per-attribute capability probing rather than
  one flag. **`disksize` and `comp_algorithm` are never changed live** — they are
  a boot-time apply, because both require `swapoff`, and `swapoff` faults every
  swapped page back into RAM. **The apply path is a HyperOS config patch, not
  fstab** (verified 2026-08-08): `vendor/etc/fstab.qcom` has no zram or swap line,
  `mmd` is absent from the image, and `init-mmd-prop.rc` forces
  `mmd.enabled_aconfig false`. Instead **two independent controllers own zram** —
  the native `/system_ext/bin/mcd` reading `system_ext/etc/mcd_default.conf`, and
  `system_ext/framework/miui-services.jar` reading `system_ext/etc/perfinit.conf`
  + `perfinit_bdsize_zram.conf`. Both are plain JSON in a partition `hzz` already
  patches, so sizing can be changed at build time with **no `swapoff` and no
  runtime risk** — this replaces the void fstab path.
- **Risk (updated)**: the two controllers **disagree**. `mcd` sets
  `global_swappiness: 60` and a size table ending `12888:0` — zram **disabled** on
  devices with ≥ ~12.6 GB RAM — while `perfinit.conf` sets `global_swappiness: 100`
  and `zram_size` `12:8192, 16:14336`, and `init.rc:39` writes 100 independently.
  Which wins is undetermined from the image; on this hardware `ZramPresent` may
  legitimately be **false**, and that is the likely case rather than an edge case.
  `cat /proc/swaps` + `/sys/block/zram0/disksize` on the booted device settles it.
  Two more: `perfinit.conf` declares `extm_on: 1` even though `bomb.sh:517`
  disables Memory Extension via `persist.miui.extm.enable=0` in `build.prop` — two
  switches, one feature, so Bomb reports both rather than trusting either; and
  `mcd_default.conf` defines a `sys_critical` cgroup at `swappiness 0` pinning
  ueventd/vold/netd/surfaceflinger/servicemanager, which Bomb must never write to. `swappiness` and `page-cluster` are live and become `PerformanceProfile`
  fields; `disksize` is deliberately **not** a profile field, so a profile switch
  can never trigger a swapoff.
- **Risk**: a live resize on a loaded device is an immediate OOM storm or a
  multi-minute freeze — 5.28 GB would have had to be faulted back in on the sample
  measured. Any future live path is gated on `orig_data_size` vs `MemAvailable` and
  refuses with the actual numbers. Secondary: the ROM **already** sets swappiness
  to 100 globally *and* per-memcg (`freeze-app` at 60 — which couples swap policy
  to §4.3 Freeze), so Bomb's "restore default" must restore the ROM's captured
  values, not the kernel's 60; and `/sys/block/zram0/{idle,writeback,…}` are chowned
  to `system` with the comment *"System server manages zram writeback"*, so
  writeback is **read-only telemetry for Bomb, never a control**.
- **Testing**: `mm_stat` and `comp_algorithm` parsers (including fresh-zram
  division by zero → unavailable, not `Infinity`); validator bounds; the resize
  safety gate; on device, swappiness applied to both global and per-memcg paths and
  restored exactly, a boot-time size change coming up with working swap, and an
  absurd size refused before it reaches the fstab.

### 4.15 AirDrop interop ("mosey") — researched, **not committed**

- **Requirement**: Quick Share ↔ AirDrop interoperability with iPhone, Android 16+.
- **Mechanism**: Google's `mosey` stack, shipped to OEMs as
  `vendor/google/service/QuickShareExtension` — a `vendor/bin/mosey_server` AIDL
  service (`com.google.android.moseyservice` / `IMoseyService/default`, `user
  system`, `NET_ADMIN`+`NET_RAW`), a 2.5 MB Rust `libmosey_daemon_ffi.so` doing
  nl80211/radiotap/mDNS work, plus an `mi_ext` privileged app and two permission
  XMLs.
- **References**: [`AIRDROP_MOSEY.md`](./research/AIRDROP_MOSEY.md) — a first-party
  Xiaomi build (17T Pro extract) plus `thelok1s/mosey-extended` (GPL-2.0), both
  read; target ROM inspected.
- **Where the target already stands**: nuwa **declares the HAL** in
  `system/etc/vintf/compatibility_matrix.device.xml`, whitelists
  `com.google.android.mosey` in the hidden-API allowlist, and its GmsCore knows the
  feature — but ships **no `mosey_server` and no `MoseyApp`**. Xiaomi shipped the
  contract without the implementation, which is a much better starting point than
  the Pixel-lift approach.
- **Blocker — RESOLVED, and it is negative.** The daemon wants a `wonder` interface
  in **monitor mode on channel 149 / 5745 MHz** (verified by strings in the Xiaomi
  library — same requirement as the Pixel build, not a reimplementation over
  standard Wi-Fi). The chipset comparison is now done: the **Xiaomi 17T Pro, the
  only device shipping mosey, is MediaTek Dimensity 9500**, while nuwa is
  Snapdragon. So the one existing build was integrated against a MediaTek Wi-Fi
  stack and proves nothing for Qualcomm. Press coverage matches — the feature is
  17T Pro only, with 9to5Google noting *"hardware-level restrictions"* may block
  other devices. **A Qualcomm mosey build does not exist publicly yet**, so the
  port cannot be completed today; the missing piece is a driver capability, not a
  file. The device to watch is **Xiaomi 17 Ultra (`nezha`, Snapdragon 8 Elite
  Gen 5)** — it does *not* have the feature as of `OS3.0.332.0.*` / Android 17, so
  dumping it now yields nothing, but if AirDrop reaches it that dump is the first
  Qualcomm integration and the only relevant one.
- **Hard constraints**: `mosey_server`, `libmosey_daemon_ffi.so` and `MoseyApp.apk`
  are **Google/Xiaomi proprietary** — D16 does not cover them and Bomb cannot ship
  them; and mosey-extended's route of planting `pixel_experience_*.xml` +
  phenotype flags to convince GMS the device is a Pixel is **Play-Integrity-adjacent
  spoofing that master plan §13 forbids outright**.
- **What Bomb can legitimately do**: probe and report `AirDropInterop` honestly
  (on nuwa today: *declared but unimplemented*), and offer a `bomb.sh` install path
  for a **user-supplied** bundle with correct partitions, contexts, VINTF entry and
  SELinux domain — the user supplies files they are entitled to, Bomb supplies
  correctness. The SELinux side rides on the `selinuxpatch.py` type-declaration work
  in §7 rather than duplicating it.
- **Status**: deliberately **not** in the milestone list. Revisit after the chipset
  comparison in [`AIRDROP_MOSEY.md`](./research/AIRDROP_MOSEY.md) §6 step 1.

---

## 5. Phases

Aligned to master plan §37. Phase 0 is complete; everything below is proposed.

| Phase | Content | Gate |
| --- | --- | --- |
| **0A/0B/0C** ✅ | audit, external research, this synthesis | **review — you are here** |
| **M0.5** | rename `:preview`/`:app-preview` → `:app`; add `:core-api`, `:domain`, `:system-service`; first `src/test`; CI-able `test` + `lint` tasks | `assembleDebug` + `test` both run |
| **M1** | design system completion (`BombChart`, `BombStatCard`, `BombSegmentedButton`, `BombEmptyState`, `BombUnsupportedState`), ViewModels replacing the sample state holder | no screen shows fabricated data; dark/light both correct |
| **M2** | `:core-api` AIDL + `BombCoreService` + caller validation + `BombCapabilities` + validators | UI binds, receives real capabilities, unsupported controls visibly disabled |
| **M3** | Task Manager | real `/proc` data, tested delta math, typed actions |
| **M4** | Stats core (CPU/mem/battery/thermal) | one snapshot stream; zero subscribers ⇒ zero reads |
| **M5** | Stats advanced + overlay (FPS/GPU/process/thread) | every advanced metric capability-probed |
| **M6** | Freeze Engine | exclusions enforced in `:domain`; four mechanisms not conflated |
| **M7** | Component Explorer + Process Control | blocking-reason set model; manifest validation |
| **M8** | App Control + Package Inspector | one page per app, linking the above |
| **M9** | Performance Profiles + `bombd` + first daemon SELinux domain. Includes **Memory/ZRAM** (§4.14) — same `bombd` sysfs-write need, and `swappiness`/`page-cluster` are profile fields | restore only Bomb-set values (for swappiness that means the ROM's 100, not the kernel's 60); NDK installed; no live resize path shipped |
| **M10** | Bomb Rules | precedence + cooldown + loop prevention tested |
| **M11** | Network Control + AdBlock/DNS | 20-row matching matrix green; failed update retains blockset |
| **M11a** | Proxy / VPN Gateway (§4.12) — P0–P6 of [`PROXY_GATEWAY_PLAN.md`](./PROXY_GATEWAY_PLAN.md) | engine runs standalone and under Bomb; no DNS/IPv6 leak; `setprop …enabled 0` recovers a bricked config; GPL boundary checklist clean |
| **M11b** | Log Governor + App Crash Tracker (§4.13) — three tiers, no-root ROM `OFF`, typed property, reconcile/recovery, opt-in per-app crash notifications | `logcat -g` proves `REDUCED`; logd stays stopped under ROM `OFF`; tombstones survive; Java/native/ANR fixtures correlate correctly; tracker/`OFF` conflict is enforced; cold boot clean |
| **M12** | Battery Lab + Power Activity | unavailable metrics explicitly unavailable |
| **M13** | Bomb Bridge (notification → Live Update → HyperOS, in that order) | renderer actually used is reported |
| **M14** | **App Visibility** (framework patch) | thirteen-surface consistency verified on device |
| **M15** | **Settings Virtualization** (framework patch) | key classification enforced; no real-setting mutation |
| **M16** | Autostart/Doze, privacy utilities | only what the ROM can enforce |
| **M17** | Call Recording | capture probe passes, or ships as unsupported |
| **M18** | Polish, SELinux hardening re-audit, migration, multi-user | every rule added in M2–M17 re-justified |

Ordering note: the two framework patches moved **late** (M14/M15) relative to the
master plan's §37 (Phase 8/9). Rationale — they require a working `sepolicy`
pipeline, a ROM build/flash loop, and the extended `selinuxpatch.py`; doing them
before Bomb has a testable privileged service would mean debugging two unproven
layers at once. If master plan §38's P1 priority must be honoured literally, this
is the one place this plan deliberately deviates and needs your decision (D15).

---

## 6. Dependencies

Current, verified building (2026-08-08):

```toml
agp = "9.3.0"          kotlin = "2.3.20"     composeMultiplatform = "1.10.3"
miuix = "0.8.8"        gradle wrapper 9.6.1  JDK 21 (from ~/.gradle/jdks)
compileSdk = 37        minSdk = 33           targetSdk = 37
```

To be added at M0.5/M2: `androidx.room:room-runtime:2.8.4` + KSP, DataStore,
`kotlinx-coroutines-test`, `turbine`, JUnit 4.13.2.

Deliberately **not** added: DI framework, WorkManager, any charting library
(`BombChart` is Compose `Canvas`), any HTTP client beyond what AdBlock source
fetching needs, any analytics.

MIUIX/Kotlin/Compose versions move together — the comment already in
`libs.versions.toml` is correct and must stay.

---

## 7. SELinux integration plan

Unchanged in substance from the previous revision; domains `bomb_app`, `bombd`,
`bomb_data_file`, `bomb_service`; authored once in `sepolicy/` and emitted both as
CIL (ROM mode) and `sepolicy.rule` (root mode); the extended `selinuxpatch.py` must
emit type declarations, `typeattributeset` membership, the `bombd` domain
transition, and `seapp_contexts`/`file_contexts`/`property_contexts` entries — and
must **fail loudly** when `_runtime_policy_compile_available()` does not hold
instead of silently skipping rules.

Additions from this research:

- **`bombd` caller authentication** is `SO_PEERCRED` (uid) + SELinux domain
  restriction on the socket. Not APK-signature parsing inside the daemon
  ([`STATS_REFERENCES.md`](./research/STATS_REFERENCES.md) §1).
- The framework patches need **no new SELinux rules at all** — they run inside
  `system_server`. This is a genuine advantage of D6 over a hooking approach and
  should be stated when the patches are reviewed.
- Rules are added **per feature, in the phase that needs them**, scoped to Bomb's
  own types and the specific nodes involved. No `audit2allow` output applied
  unreviewed. Enforcing is never relaxed, not even during bring-up.

---

## 8. AIDL plan

`:core-api` owns the contract. Append-only methods; never reorder or repurpose a
transaction; every model a versioned `Parcelable`.

Permanently absent: `executeShell`, `writeFile`, `writeSysfs`, `setSystemProperty`,
and any method taking a filesystem **path** from the caller.

Every new method must answer yes to all five
([`UI_REFERENCES.md`](./research/UI_REFERENCES.md) §5):

1. named for what it does, not the mechanism it uses;
2. every argument bounded — enum, id, validated package, numeric range;
3. files passed as an **fd the caller already holds**, never a path;
4. no combination of legitimate calls yields an unintended effect;
5. returns a `BombResult` distinguishing unsupported / denied / invalid / failed.

Service side, every call: validate caller (UID/package/signature) → validate
arguments via `:domain` validators → check capability → execute → return
`BombResult`. Telemetry is delivered by callback with a config-driven interval, and
the engine collects only while at least one callback is registered.

---

## 9. Risks

| # | Risk | Impact | Mitigation |
| --- | --- | --- | --- |
| R1 | No platform key ⇒ no system UID | Reduced — core operations are priv-app reachable | capability probe degrades to `Unsupported` |
| R2 | **NDK not installed**; no AOSP build tree | Blocks producing the arm64 `bombd` artifact locally | standalone CMake source/build script is present; ROM build host must supply NDK |
| R3 | `selinuxpatch.py` cannot declare new types today | Bomb's domains silently dropped | extend the patcher; fail loudly when policy recompilation is unavailable |
| R4 | HyperOS live-update APIs undocumented and version-gated | M13 renderer may break | notification renderer is primary; three-signal probe; renderer used is reported |
| R5 | Call recording may have no capture path on this ROM | M17 may ship as unsupported | probe actual capture, not permission |
| R6 | Vendor sysfs differs per SoC | wrong values or failed writes | everything behind backends; probe before use |
| R8 | ROM blanks `ro.control_privapp_permissions` | over-permission goes unnoticed | keep the allowlist minimal and hand-reviewed |
| R9 | Telemetry polling regresses battery | contradicts a core requirement | subscriber-aware engine + cost classes; test asserts zero reads at zero subscribers |
| R10 | Freeze/Rules oscillation | device instability | cooldowns, debounce, history, precedence — unit-tested |
| R11 | Scope creep into excluded areas | violates the spec | excluded features not scaffolded, stubbed or referenced |
| R12 | Root mode depends on a root manager staying granted | capabilities vanish mid-session | re-probe on resume; report `BackendUnavailable`; never cache as permanently available |
| R13 | Two SELinux delivery paths drift | a rule works in one mode only | author once, generate both; CI check the emitted sets match |
| **R14** | Framework patches drift from upstream AOSP | patches stop applying on a ROM update | pin the upstream commit; keep diffs minimal; re-verify line anchors each ROM rebase |
| **R15** | App Visibility on a hot `system_server` path | system-wide slowdown | immutable snapshot, O(1), allocation-free, no locking; benchmark before enabling |
| **R16** | Documented visibility leak surfaces (archived packages, UsageStats, AccountManager) | users assume coverage that does not exist | state the boundary in the UI, not only in docs |
| **R17** | Bomb's license not fixed while copyleft references dominate | accidental contamination | **amended by D16**: copyleft ships only as a segregated component under its own licence, never mixed into or ported into Bomb's sources; decide D14 before `framework/` is authored |
| **R18** | GPL boundary creep in the proxy subsystem — porting the upstream config builder, or coupling Bomb to the engine until they read as one work | `:system-service`, and by argument `:app`, become GPL-3.0 derivatives | generator written from Xray-core docs; engine must stay standalone-runnable; a review checklist item on every change touching `proxy/` |
| **R19** | The GPL engine gets "cleaned up" into a compiled binary later | triggers GPL-3.0 §6 Installation Information on a consumer device | standing constraint recorded in `PROXY_GATEWAY_PLAN.md` §3.1 / PR10: the covered work ships as source, permanently |
| **R20** | Target image has no `iptables` (moved to `nftables`), or no `sysctl` | the adopted engine does not run | P0 verification gate on an extracted image before any porting work; `sysctl` gap already found and scoped (M6) |
| ~~R7~~ | ~~JDK 26 vs AGP 9~~ | resolved | Gradle 9.6.1 + JDK 21 |

---

## 10. Testing strategy

- **`:domain` (JUnit, no Android)** carries the required coverage: visibility
  policy, settings policy, freeze policy + critical packages, rule engine,
  blocklist parser/compiler/matcher, telemetry math, profile capability filtering
  and restore, validators.
- **`:core-api`** — `Parcelable` round-trips; AIDL surface stability check.
- **`:system-service`** — JVM/Robolectric tests for `/proc` and cgroup parsers
  against captured fixtures, capability detection against fixture trees,
  subscriber-count behaviour.
- **`:app`** — Compose tests for the three mandatory states (loading / error /
  unsupported), navigation, logging-profile warnings and crash-tracker toggles.
- **`framework/`** — the policy classes the patches call are plain JVM classes in
  `:domain` and are tested there; the patches themselves are verified on device.
- **Device verification** — freeze/unfreeze, force stop, overlay, profile
  apply/restore, the thirteen-surface visibility matrix, the settings virtualization
  round-trip, and the call-capture probe. Results reported honestly, including what
  could not be verified.

Every phase gate: relevant tests run, lint/build run, and the **actual commands and
outcomes** reported — including failures.

---

## 11. Checklist

**Phase 0**
- [x] 0A repository, toolchain, dependency and ROM-integration audit
- [x] 0B external research — 14 repos cloned, source traced, commits and licenses recorded, 15 notes written
- [x] 0C synthesis — `ARCHITECTURE.md` §7/§8, this document §4
- [ ] **Review; answer D14 (license) and D15 (scope/priority order)**

**Foundation**
- [x] M0.6 Android UI shell (real MIUIX, predictive back, persisted settings)
- [~] M0.5 module split + first tests + lint/CI tasks — `:domain` exists and
      carries 143 passing tests; `:core-api` / `:system-service` not yet created;
      module rename still open (see the note below)
- [ ] M1 design system completion + ViewModels replacing sample state
- [ ] M2 AIDL + `BombCoreService` + capability registry + validators

**Core** — [ ] M3 Task Manager · [ ] M4 Stats core · [ ] M5 Stats advanced ·
[~] M6 Freeze · [ ] M7 Component/Process Control · [ ] M8 App Control

**Extended** — [~] M9 Performance + `bombd` · [ ] M10 Rules · [ ] M11 Network/AdBlock ·
[ ] M11a Proxy/VPN Gateway · [~] M11b Log Governor + Crash Tracker · [ ] M12 Battery · [ ] M13 Bridge

`[~]` marks a phase whose `:domain` layer is complete and tested but whose
privileged/Android half is not written yet — the policy decides correctly, but
nothing executes it on a device.

### Progress log — 2026-08-08

| Subsystem | Delivered | Tests |
| --- | --- | --- |
| §4.7 AdBlock | `BlocklistParser`, `Blocklist`, `BlocklistCompiler` | 33 |
| §4.3 Freeze | `FreezeModel`, `ProtectedPackages`, `PackageNameValidator`, `FreezePolicyEngine`, `FreezeScheduler`, `FreezePolicyValidator` | 53 |
| §4.13 Log Governor | `LogGovernorModel`, `LogSnapshot`, `LogTransitionPlanner` | 33 |
| §4.14 Memory/ZRAM | `ZramMmStat`, `ZramAlgorithms`, `ZramCapabilities`, `ZramConfigValidator`, `ZramResizeSafety`, `SwappinessTarget` | 24 |
| M2a `:core-api` | `BombResult`, `BombCapabilities`, `FreezeStatus`, `LogStatus`, `MemoryStatus`/`MemoryConfig`, `IBombService.aidl` (8 methods) | 16 |
| M2b `:system-service` | `BombCoreService`, `CallerPolicy`/`CallerValidator`, `CapabilityProbe`, `ZramReader`, `LogStateReader`, `SystemPropertyReader` | 11 |

**170 tests, 0 failures** across `:domain`, `:core-api`, `:system-service`.
`./gradlew :app-preview:assembleDebug` → BUILD SUCCESSFUL (baseline, 6 min).

**M0.5's module merge cannot be done as written.** §3's mapping table says `:app`
is "renamed from today's `:preview` + `:app-preview`", i.e. one module. The two
are separate for a reason already recorded in `preview/build.gradle.kts`: AGP 9
does not allow `com.android.application` alongside the Kotlin Multiplatform
plugin, so the shared UI must stay an Android library and `:app-preview` must
stay its packaging shell. Merging them means dropping KMP — a real decision, not
a rename, and one nothing in the plan currently requires. Proposed instead:
rename `:app-preview` → `:app` and `:preview` → `:ui`, which removes the
misleading "preview" naming (the thing the rename was for) without fighting the
toolchain. Flagged rather than done, because it churns every import in 44 files
and is not worth doing twice.

Two design points were corrected while implementing, and both are worth keeping:

1. **`PackageNameValidator` initially required a separating dot**, which rejected
   `"android"` — a real package on every device, the head of
   `CORE_FRAMEWORK`, and one the platform itself refuses to hide. The effect was
   not merely a wrong label: the user would have been told "malformed package
   name" instead of "this is a framework package, protected". The dot requirement
   was dropped; the character set already excludes traversal and metacharacters,
   so nothing was given up.
2. **The Log Governor's restore path was guessing platform defaults.** Writing
   `persist.traced.enable=1` would enable Perfetto on a device that shipped with
   it off, and writing `""` would leave the init trigger unable to start `traced`
   on the next boot — there is no safe guess. Replaced with `LogSnapshot`:
   capture the values before the first change, restore exactly those. This is the
   same rule §4.9 states for Performance Profiles, and it applies here for the
   same reason.

### M2 constraints found while implementing

- **`org.jetbrains.kotlin.android` cannot be applied.** AGP 9 ships Kotlin
  support itself and fails the build if that plugin is present. `:core-api` and
  `:system-service` therefore apply `com.android.library` alone (plus
  `kotlin-parcelize`, which is still a separate compiler plugin).
- **Enum arguments cross Binder as names, not ordinals.** An ordinal is a number
  whose meaning shifts the moment a constant is inserted, and app and service are
  updated separately — in ROM mode the service ships with the ROM. A name that no
  longer exists fails validation loudly and returns `INVALID_ARGUMENT`; an
  ordinal that has shifted silently means something else. Same reasoning applies
  to `BombCapabilities`, which crosses as a `Map<String, String>`.
- **`android.os.SystemProperties` is not public SDK**, and reflection onto
  non-SDK interfaces is restricted from API 28. `SystemPropertyReader` probes
  once and reports `available = false` when blocked, so the Log Governor degrades
  to "not readable" rather than defaulting to `DEFAULT`. Shelling out to
  `getprop` was rejected: `CLAUDE.md` forbids arbitrary shell execution, and a
  read-only exception would still put a general-purpose exec path inside the
  privileged module.
- **Root and system UIDs are refused by `CallerPolicy`.** A privileged shell can
  already do everything Bomb does without going through Bomb, so admitting it
  adds an unaudited path into every typed API and gains nothing.
- **A shared UID must be allowlisted in full.** An "any package matches" rule
  would let an unlisted package act through the UID it shares with a permitted
  one, and every later check would see a legitimate caller.
- **Every write path currently returns `UNSUPPORTED`.** This build is an ordinary
  app with no privileged backend; the reads are real. The asymmetry is encoded in
  the service rather than described in a roadmap, so the UI reports today's truth
  instead of a promise.

**Framework** — [ ] M14 App Visibility · [ ] M15 Settings Virtualization

**Late** — [ ] M16 Autostart/Doze/privacy · [ ] M17 Call Recording · [ ] M18 Polish

**Continuous invariants**
- [ ] SELinux enforcing; no broad rules; no unreviewed `audit2allow`
- [ ] No generic shell/file/sysfs/property API, and no path-taking privileged API
- [ ] Every hardware feature capability-detected; unsupported states explicit
- [ ] No fabricated metrics anywhere
- [ ] No main-thread I/O; no uncontrolled polling
- [ ] Excluded features (Bomb Guard, HyperOS Tweaks, Diagnostics) not implemented
- [ ] Copyleft only as a segregated component under its own licence (D16); never
      ported into or combined with Bomb's own sources; GPL engine ships as source

---

## 12. Stop point

Per master plan §37 ("After Phase 0C: **STOP and report findings before starting
M1**"), implementation stops here.

Needed to proceed:

1. **D14** — Bomb's own license (D16 settled third-party handling; this is the
   remaining half).
2. **D15** — confirm the phase order, in particular whether the two framework
   patches stay at M14/M15 or move earlier to match master plan §38's P1, and
   where M11a sits now that the proxy subsystem is in scope.
3. **D18** — the proxy sink (G1).
4. Approval of **M0.5** (module split + first tests), which is the only pre-M1 work
   proposed — the repository currently has zero tests, and every subsequent phase
   gate depends on a `test` task existing.

Independently of the above, the proxy work has its own hard gate: **P0 cannot be
skipped.** `hzz/super` and `hzz/out` are empty, so nothing has verified that the
target image still ships `iptables`/`ip`. If it does not, §4.12's mechanism
changes entirely and M11a must be re-planned before any porting effort is spent.
