# Bomb — System Toolbox Master Plan

> **Project:** Bomb  
> **Package:** `com.hzzmonet.zkbomb`  
> **Target:** privileged/system-level Android toolbox for custom ROM / HyperOS-oriented builds  
> **UI:** MIUIX / HyperOS first, Material 3 Expressive selectively  
> **Security:** SELinux remains enforcing

---

# 1. Product direction

Bomb is a system-level Android control plane, not a normal root utility and not a collection of unrelated toggles.

The project should provide one coherent backend for:

- process and task management;
- system telemetry and overlays;
- application freezing;
- component/process control;
- caller-aware package visibility virtualization;
- per-app Android Settings virtualization;
- app privacy and background policies;
- DNS-based ad blocking;
- firewall and network policy;
- performance and thermal profiles;
- Battery Lab;
- HyperIsland / Live Updates bridging;
- call recording where the platform/vendor audio stack supports it;
- automation via Bomb Rules;
- privileged app update fallback & System/PrivApp status preservation (§45);
- UI layout alignment polish & list performance optimization (§46).

Core architectural principle:

```text
Bomb UI
   |
   | AIDL / Binder
   v
BombCoreService
   |
   +-- App / Package Policy
   +-- Freeze Engine
   +-- Visibility Engine
   +-- Settings Virtualization
   +-- Telemetry
   +-- Network Policy
   +-- Performance
   +-- Rules
   +-- Bridge
   |
   | typed privileged IPC
   v
bombd
   |
   +-- procfs
   +-- selected sysfs
   +-- cgroups
   +-- power / thermal
   +-- supported vendor/native backends
```

The UI process must never receive unrestricted root access.

---

# 2. Explicitly out of scope

Do **not** implement or scaffold these unless explicitly requested later.

## 2.1 Bomb Guard

Do not implement:

- snapshot;
- rollback;
- Bomb safe mode;
- automatic configuration recovery.

## 2.2 HyperOS Tweaks

Do not implement:

- SystemUI modification;
- launcher modification;
- status bar modification;
- Control Center modification;
- lock-screen modification;
- general animation modification.

`Bomb Bridge` / HyperIsland integration is allowed because it is a live-event feature, not a generic SystemUI tweak subsystem.

## 2.3 Diagnostics

Do not implement:

- ANR viewer;
- tombstone viewer;
- Perfetto-lite;
- a general-purpose logcat viewer;
- automatic cloud crash reporting or silent log upload.

A bounded, local-only **App Crash Tracker** is in scope: it observes selected
applications, detects crashes, notifies the user, and stores a small diagnostic
record that the user can inspect or explicitly export. It is not a general
diagnostics suite; see §26.1.

---

# 3. Global engineering rules

## 3.1 Privileged API design

Do not expose generic privileged APIs such as:

```text
executeShell(command)
writeFile(path, value)
writeSysfs(path, value)
setSystemProperty(name, value)
```

Use small, typed operations with validation.

Every privileged request must validate:

- Binder caller;
- UID;
- `userId`;
- package name;
- PID where relevant;
- enum values;
- numeric ranges;
- target capability.

Use signature/internal permissions where appropriate.

## 3.2 SELinux

SELinux remains enforcing.

Conceptual domains:

```text
bomb_app
bombd
```

Conceptual private data type:

```text
bomb_data_file
```

Never add broad rules such as:

```text
allow bombd *:* *
allow bomb_app *:* *
```

Do not blindly paste `audit2allow` output.

For every policy addition, identify:

- source domain;
- target type;
- object class;
- exact permission;
- Bomb feature requiring it.

## 3.3 Capability-first design

Never assume a device supports a feature.

Create a central capability model, conceptually:

```text
BombCapabilities

TaskManager
ProcessCpuTelemetry
ThreadTelemetry
GpuTelemetry
FpsTelemetry
ThermalTelemetry
PowerTelemetry

SoftFreeze
DeepFreeze
ComponentControl
FrameworkProcessControl

PackageVisibilityVirtualization
SettingsVirtualization

AdBlock
DnsControl
Firewall

PerformanceControl
ChargeControl

HyperIslandBridge
LiveUpdateBridge

PhoneRecording
VoipRecording
```

Unsupported controls must be hidden, disabled, or clearly marked unsupported.

Never fabricate support or telemetry values.

## 3.4 Concurrency

Use Kotlin coroutines, `Flow`, `StateFlow`, and structured concurrency.

Do not create unmanaged infinite loops.

Do not perform blocking I/O on the main thread.

Telemetry collectors must be subscriber/lifecycle aware. If no active consumer needs an expensive collector, reduce or stop its polling.

## 3.5 Result model

Privileged operations must return explicit results, conceptually:

```text
BombResult.Success
BombResult.Unsupported
BombResult.PermissionDenied
BombResult.InvalidArgument
BombResult.BackendUnavailable
BombResult.Failed
```

Do not silently swallow failures.

---

# 4. UI and design system

Bomb should visually belong to HyperOS.

Primary direction:

- MIUIX-first;
- HyperOS-like spacing;
- large page titles;
- rounded cards;
- preference-row based settings;
- dark mode as a first-class experience;
- restrained blur;
- smooth spring-style motion;
- haptic feedback;
- Material 3 Expressive mainly for charts, telemetry, chips, segmented controls, transitions, and responsive stat cards.

Do not randomly mix raw MIUIX and stock Material components per screen.

Create Bomb wrappers:

```text
BombScaffold
BombTopAppBar
BombLargeTitle
BombCard
BombPreference
BombSwitchPreference
BombSliderPreference
BombSegmentedButton
BombAppRow
BombProcessRow
BombStatCard
BombChart
BombDialog
BombBottomSheet
BombMonitorOverlay
```

Recommended primary navigation:

```text
Home
Apps
Monitor
Automation
More
```

---

# 5. Core feature scope

## 5.1 Task Manager

Build a proper Android/Linux process viewer.

Fields where available:

- app icon/name;
- package;
- process name;
- PID;
- UID;
- process state;
- foreground/background state;
- CPU %;
- RSS;
- PSS;
- thread count;
- network activity.

Sorting:

- CPU;
- RAM;
- PID;
- name.

Search:

- package;
- process;
- app display name.

Actions where supported:

- force stop;
- freeze;
- unfreeze;
- restart app;
- open App Control;
- open Process Control;
- assign performance profile.

Do not implement an aggressive automatic RAM cleaner.

Refresh policy must be configurable and efficient, e.g.:

```text
Realtime   500 ms
Fast       1000 ms
Balanced   2000 ms
Battery    5000 ms
```

Pause or reduce refresh when the UI is not visible.

---

# 6. Bomb Stats

Use one unified telemetry pipeline.

```text
TelemetryCollector
        |
        v
TelemetryRepository
        |
      StateFlow
    /    |      \
   UI  Overlay  Rules/Bridge
```

Do not create duplicate expensive collectors for different screens.

Metrics where reliable:

## CPU

- total utilization;
- per-core utilization;
- per-core frequencies;
- cluster state.

## GPU

Where supported:

- load;
- frequency;
- memory.

## Memory

- total RAM;
- available memory;
- cache;
- RSS;
- PSS;
- swap;
- zram.

## Graphics

Where measurable:

- FPS;
- frame time;
- jank;
- 1% low.

## Battery / power

- current;
- voltage;
- estimated wattage.

## Thermal

- CPU;
- GPU;
- battery;
- skin/device temperature;
- Android thermal state/headroom where available.

## Network

- upload rate;
- download rate.

Monitor modes:

```text
Classic Monitor
Process Monitor
Thread Monitor
Mini Monitor
FPS Monitor
Temperature Monitor
Gaming Monitor
```

Overlay must support:

- drag;
- edge snapping;
- opacity;
- scale;
- refresh interval;
- metric selection;
- orientation;
- per-app presets;
- remembered position.

Examples:

```text
118 FPS | 41 C | 5.7 W
```

and:

```text
FPS     118
CPU      38%
GPU      72%
RAM     6.4G
TEMP     41C
POWER   5.7W
```

---

# 7. Freeze Engine

Freeze modes:

```text
NORMAL
SOFT_FREEZE
DEEP_FREEZE
DISABLED
```

Architecture:

```text
FreezeManager
    |
    +-- FreezePolicyEngine
    +-- FreezeScheduler
    +-- FreezeRepository
    +-- FreezeBackend
```

Do not hardcode one mechanism into UI/domain code.

Treat mechanisms such as `suspend`, `disable`, `hide`, `force stop`, framework cached-app freezing, and device-specific backends as different capabilities.

Per-app options:

- manual freeze/unfreeze;
- auto freeze;
- delay after leaving foreground;
- freeze when screen off;
- unfreeze on explicit user launch;
- mode selection;
- whitelist/exclusion.

Example policy:

```text
App leaves foreground
       |
       | +30 seconds
       v
Background restricted
       |
       | +5 minutes
       v
Deep Freeze
```

Default critical exclusions must include at least:

- Bomb;
- SystemUI;
- launcher;
- current IME;
- essential framework packages;
- vendor/device-critical packages.

---

# 8. Component Explorer

Implement Blocker-style component inspection.

Component types:

- Activity;
- Service;
- BroadcastReceiver;
- ContentProvider.

Display:

- class name;
- enabled state;
- exported state;
- permission;
- process;
- intent filters where relevant.

Grouping:

```text
By Type
By Process
By State
By Detected SDK
```

Advanced Mode may allow enabling/disabling components.

Normal mode should avoid exposing destructive component controls too prominently.

---

# 9. Process Control

The preferred order is:

```text
Component blocking
        >
Framework spawn policy
        >
Runtime kill-on-spawn fallback
```

Do not prefer repeated kill-on-spawn if the manifest component responsible for the child process can be blocked instead.

Resolve process groups such as:

```text
com.foo.app
com.foo.app:push
com.foo.app:analytics
com.foo.app:remote
```

Then map components to them:

```text
:analytics
├-- AnalyticsService
├-- MetricsReceiver
└-- AnalyticsProvider
```

Suggested runtime classification:

```text
MAIN
APP_COMPONENT
ISOLATED
WEBVIEW
NATIVE_CHILD
UNKNOWN
```

Warn more strongly for WebView, isolated, native-child, and unknown processes.

A native `.so` loaded into a process is not itself a process and must never be represented as one.

---

# 10. SDK / Protection Inspector

Implement generic inspection, not product-specific bypass buttons.

Detection may use:

- component class prefixes;
- provider authorities;
- manifest metadata;
- package/class signatures;
- native library names.

Potential detected families:

- Firebase;
- AppLovin;
- AppsFlyer;
- Facebook SDK;
- PAIRIP.

Example:

```text
PAIRIP
├-- LicenseActivity
└-- libpairipcore.so
```

A manifest component may be controllable by the generic component subsystem.

A native library inside the main process is not separately blockable as a child process.

Do not add one-click features named like `Bypass PAIRIP`.

---

# 11. App Control

Each app gets one central control page.

Suggested layout:

```text
Overview

Runtime
  Processes
  Process Control
  Components
  Freeze

Privacy
  App Visibility
  Per-App Settings
  Permissions
  Clipboard
  Sensors

Network
  Firewall
  Data Policy
  AdBlock integration

Power
  Background
  Wakelocks
  Jobs
  Alarms

Performance
  Profile
  Monitor preset

Automation
  Bomb Rules
```

---

# 12. App Visibility — HMA-style caller-aware package hiding

This feature is **not** equivalent to Android 11 package visibility permission management.

Do not implement it by simply revoking `QUERY_ALL_PACKAGES`.

A target app may retain broad PackageManager query capabilities while Bomb makes specific configured packages behave as if they do not exist for that caller.

Example:

```text
Caller:
com.example.target

Hidden:
com.example.hidden
```

For that caller, the hidden package must be consistently filtered from PackageManager-visible surfaces where practical.

Other callers continue to see the real package.

Core policy concept:

```text
shouldHidePackage(
    callingUid,
    userId,
    targetPackage
)
```

The same policy must be reused through common PackageManager internal filtering paths.

Audit at minimum:

## Enumeration

- installed packages;
- installed applications;
- packages holding permissions where applicable.

## Direct lookup

- package info;
- application info;
- package UID/GID-related lookups.

Direct lookup of a hidden package should behave like package-not-found for the configured caller where appropriate.

## Intent resolution

Audit paths equivalent to:

- query activities;
- query services;
- query receivers;
- resolve activity;
- resolve service.

## Providers

Provider enumeration/resolution must obey the same visibility policy.

## UID mappings

Audit relevant mappings such as:

- UID -> package names;
- package -> UID.

A hidden package should not disappear from `getInstalledPackages()` but remain trivially discoverable from another PackageManager path.

## Performance

Package Manager is a hot path.

Never perform Room/disk/network/remote Binder I/O for each visibility check.

Compile configuration into an immutable in-memory snapshot, conceptually:

```text
VisibilityPolicySnapshot
  revision
  userPolicies
  callerPolicies
  hiddenSets
  whitelistSets
  trustedExemptions
```

## Modes

```text
BLACKLIST
WHITELIST
```

Blacklist: configured packages are hidden.

Whitelist: only configured packages are visible, subject to mandatory system/framework exemptions.

## Multi-user

Policy identity must include `userId`.

Do not key policy only by package name.

## Shared UID

Do not assume one UID always maps to exactly one package.

Detect conflicting policies among packages that share a UID and either reject or normalize them consistently.

## Exemptions

Bomb itself must retain complete package visibility.

Do not accidentally apply user visibility filtering to internal `system_server` package-management operations.

Centralize exemptions rather than sprinkling UID checks throughout the framework.

---

# 13. Per-App Settings Virtualization

Goal: selected applications may observe virtual Android Settings values without changing real global settings.

Example:

```text
Real font_scale = 1.10

App A sees 1.00
App B sees 1.10
System remains 1.10
```

Do not implement by running `settings put ...` when foreground app changes.

That would mutate shared state and introduce races.

Required conceptual flow:

```text
Settings read
   |
caller identity
   |
BombSettingsPolicy
   |
override?
 /      \
yes      no
 |        |
virtual   real
```

Namespaces:

```text
SYSTEM
SECURE
GLOBAL
```

Typed values:

```text
STRING
INTEGER
LONG
FLOAT
BOOLEAN
NULL
```

Suggested models:

```text
SettingsProfile

SettingsOverride
- profileId
- namespace
- key
- valueType
- value
- enabled

SettingsAssignment
- userId
- targetPackage
- profileId
```

Prefer allowlisted/capability-aware keys.

Unknown or dangerous settings require Advanced Mode.

Do not use this subsystem to spoof:

- hardware-backed attestation;
- Verified Boot;
- Play Integrity;
- hardware security properties.

---

# 14. App Privacy Profiles

App Visibility and Settings Virtualization should support reusable profiles.

Example:

```text
Privacy Profile

App Visibility: BLACKLIST
Settings: Custom
Clipboard: Restricted
Sensors: Foreground only
Network: Filtered
```

Allow one profile to be assigned to multiple apps/users.

---

# 15. Autostart / Wakeup / Doze

## Autostart Manager

Observe/manage startup sources where supported:

- boot;
- package events;
- network changes;
- unlock;
- Bluetooth;
- scheduled jobs;
- receivers/services.

Policies:

```text
ALLOW
RESTRICT
BLOCK
```

Integrate with Freeze.

## Wakeup / power activity

Per-app information where reliable:

- wakelocks;
- wake duration;
- alarms;
- jobs;
- wakeups/hour;
- background activity.

Impact classification may be:

```text
LOW
MEDIUM
HIGH
```

Do not silently modify app behavior based only on a heuristic score.

## Doze control

Profiles:

```text
DEFAULT
BALANCED
AGGRESSIVE
CUSTOM
```

Allow exclusions.

Bomb Rules may coordinate screen-off policies such as:

```text
Screen off 15 minutes
    -> aggressive idle
    -> freeze eligible apps
    -> restrict selected background network
```

---

# 16. Bomb AdBlock

Bomb AdBlock is a domain-based DNS blocker.

Do not implement:

- cosmetic filtering;
- WebView DOM manipulation;
- UI/ad-view hooking;
- TLS interception;
- HTTP payload inspection.

## Sources

Support:

```text
LOCAL_FILE
REMOTE_URL
ADGUARD_HOME_CONFIG
```

Input formats:

### hosts

```text
0.0.0.0 ads.example.com
127.0.0.1 tracker.example.com
```

### plain domains

```text
ads.example.com
tracker.example.com
```

### basic DNS-style AdBlock rules

```text
||ads.example.com^
```

### basic allow rule

```text
@@||allowed.example.com^
```

Do not implement a complete browser AdBlock engine.

## AdGuard Home config import

For an AdGuard Home configuration source:

- parse the config;
- discover enabled filter entries;
- extract filter URLs;
- import them as child sources;
- do not embed/run AdGuard Home merely for config import.

## Pipeline

```text
raw input
 -> parse
 -> normalize
 -> validate
 -> deduplicate
 -> apply allowlist
 -> compile
 -> atomic swap
```

## Domain matching

Blocking:

```text
example.com
```

should block:

```text
example.com
www.example.com
ads.example.com
a.b.example.com
```

but not:

```text
notexample.com
```

Never use naive substring matching.

## Storage

Do not create one Room row per domain.

Room stores source metadata.

Downloaded/compiled rule data belongs in Bomb private/system storage.

## Update intervals

```text
Manual
6 hours
12 hours
24 hours
3 days
7 days
```

Update validation should include:

- HTTP result;
- file size limit;
- parser output validity;
- minimum valid-rule threshold;
- checksum/revision where useful.

If update fails, keep the previous working blockset.

V1 scope: global DNS filtering only. Per-app DNS filtering can be a later capability.

---

# 17. DNS Manager

Support where platform integration allows:

- system resolver;
- custom DNS;
- DoT;
- DoH;
- Bomb filtered DNS.

Potential per-network profiles:

```text
Home Wi-Fi
Mobile
Public Wi-Fi
```

---

# 18. Firewall / Network Monitor

Per-app/UID policy where supported:

```text
Wi-Fi
Mobile data
Roaming
Background data
Screen-off data
```

Telemetry:

- upload;
- download;
- connection count where reliable;
- data history;
- hotspot clients where supported.

Do not inspect packet payloads or credentials.

## Data Usage Guard

Per-app quota support may provide:

- warning;
- background restriction;
- mobile-data block.

Integrate with Bomb Rules.

---

# 19. Clipboard / Permission / Sensor / Notification controls

## Clipboard Guard

Possible features:

- configurable auto-clear;
- detect likely sensitive clipboard content;
- avoid persisting sensitive clipboard data;
- observe access when platform support allows it.

## Permission / Sensor Control

Expose only capabilities the ROM can actually enforce.

Possible policies:

- camera;
- microphone;
- location;
- motion sensors;
- background access;
- AppOps/permission state.

## Notification Control

Do not implement through SystemUI modification.

Possible policies:

- mute app/channel;
- rate limit;
- spam detection;
- auto-silence;
- controlled auto-dismiss rules.

---

# 20. Performance Profiles

Profiles:

```text
ECO
BALANCED
PERFORMANCE
GAMING
SUSTAINABLE
CUSTOM
```

Potential fields:

- refresh rate;
- CPU strategy;
- GPU strategy;
- thermal strategy;
- monitor preset;
- network priority;
- freeze policy.

Architecture:

```text
PerformanceController
PerformanceProfileRepository
PerformanceBackend
DeviceCapabilities
```

Device-specific paths must live behind backends.

Do not scatter Qualcomm/MediaTek/Xiaomi sysfs paths through general app code.

---

# 21. Thermal Guardian

Runtime policy example:

```text
>= 42 C -> SUSTAINABLE
>= 45 C -> ECO
<= 39 C -> restore
```

Use hysteresis and cooldowns to prevent rapid profile oscillation.

---

# 22. Battery Lab

Display where reliable:

- battery level;
- temperature;
- voltage;
- current;
- estimated power;
- charge counter;
- cycle count;
- estimated capacity/health where valid.

Optional capabilities only when hardware/backend proves support:

- charge limit;
- charging policy;
- night charging;
- thermal charging control.

---

# 23. Bomb Bridge

Build a normalized live-event layer.

```text
Sources
   |
   v
BombLiveEvent
   |
   +-- HyperIsland renderer
   +-- Android Live Update renderer
   +-- notification fallback
```

Conceptual fields:

```text
id
sourcePackage
type
title
subtitle
icon
progress
state
timestamp
actions
```

Suggested event types:

```text
MEDIA
DOWNLOAD
UPLOAD
NAVIGATION
TIMER
CALL
CALL_RECORDING
CHARGING
HOTSPOT
VPN
FILE_TRANSFER
GAME_STATS
APP_INSTALL
CUSTOM
```

Examples:

```text
120 FPS | 41 C | 5.8 W
```

```text
67% | 28 W | 37 C
```

```text
Recording | 08:42
```

Do not tightly couple source parsing to the HyperIsland renderer.

---

# 24. Call Recorder

Capability-first implementation.

Possible sources:

- standard phone calls;
- third-party VoIP only when the integrated platform/vendor audio path explicitly supports it.

Modes:

```text
OFF
ASK
AUTOMATIC
```

Requirements:

- visible recording indication;
- explicit enable/disable settings;
- configurable format;
- configurable retention;
- safe failure when capture is unavailable;
- no unsupported compatibility claims.

Do not implement arbitrary capture-protection bypass logic.

---

# 25. Package Inspector

Display:

- package;
- version/version code;
- UID;
- target SDK;
- min SDK;
- ABI;
- signature/installer metadata where appropriate;
- activities;
- services;
- receivers;
- providers;
- permissions;
- processes;
- native libraries;
- detected SDKs.

Link directly to:

- Component Explorer;
- Process Control;
- App Visibility;
- Freeze;
- Network.

---

# 26. Storage Analyzer / Idle App Advisor

## Storage Analyzer

Do not create fake "RAM boost" functionality.

Useful categories:

- large files;
- downloads;
- obsolete APK files;
- app cache;
- thumbnails;
- old files.

Use dry-run previews before destructive cleanup.

## Idle App Advisor

Signals may include:

- time since last use;
- background activity;
- data usage;
- wakeups.

Possible suggestions:

- freeze;
- restrict network;
- uninstall;
- ignore.

Never automatically uninstall apps based on a heuristic.

---

# 26.1 Logging and App Crash Tracker

This subsystem has two independent, user-controlled switches:

1. **System logging profile** — `DEFAULT`, `REDUCED`, or `OFF`.
2. **App Crash Tracker** — a master enable switch plus an allowlist of apps to
   observe. It is disabled by default.

## Log OFF without root mode

On a Bomb-integrated ROM, `OFF` must work through the typed `BombCoreService` /
init backend and must **not require Magisk, KernelSU, Shizuku, ADB, or a root-mode
session**. Research on the target HyperOS image found no repeating trigger that
restarts `logd`, so the ROM backend can stop it directly. This does not imply that
a normal, non-ROM APK can stop system logging.

The UI must warn that `OFF` removes logcat and SELinux-denial visibility, makes
bugreports incomplete, and may require a reboot to restore cleanly. Tombstones and
ANR traces remain separate. The backend must reconcile state at boot and provide
an out-of-app recovery property plus reboot path. It must also capability-probe
MIUI/vendor sinks such as `ylog`; if any remain active, the UI must say **partial
off**, never claim that all logging is disabled.

## Crash tracking

Use [LogFox](https://github.com/F0x1d/LogFox) as a behaviour and UX reference for
observing Java/JNI crashes and ANRs, filtering, notifications, bounded context,
and explicit export. LogFox is GPL-3.0: inspect and document its execution path,
but reimplement Bomb's tracker behind Bomb's typed APIs unless a deliberate
licence decision says otherwise.

When enabled, the tracker:

- observes only user-selected packages, with an optional "all user apps" mode;
- detects Java/Kotlin fatal exceptions, native/JNI crashes and ANRs where the ROM
  exposes enough evidence;
- posts a notification containing app name, time, crash kind and a link to the
  local crash detail;
- keeps a bounded ring buffer of relevant lines before and after the event, plus
  package/version, process, ABI, Android/build fingerprint and stack trace;
- deduplicates repeated crash loops and rate-limits notifications;
- stores reports locally with configurable retention/count limits;
- supports manual redacted ZIP/text export, never automatic upload;
- can be disabled globally or per app, after which its reader/service stops and
  consumes no background resources.

Crash tracking depends on a working log source. Enabling it while the logging
profile is `OFF` must offer to switch to `REDUCED` (recommended) or `DEFAULT`;
declining leaves tracking disabled. Selecting `OFF` while tracking is active must
stop tracking first and state why. `REDUCED` must preserve fatal/crash records.

The tracker must not retain unrelated application logs. Redact common secrets
(tokens, cookies, authorization headers, email/phone identifiers and filesystem
paths where practical) before display/export, and show that redaction is best
effort rather than a privacy guarantee.

---

# 27. Bomb Rules

Bomb Rules connects Bomb subsystems.

Model:

```text
Trigger
+
Conditions
+
Actions
+
RestorePolicy
```

Triggers may include:

```text
APP_FOREGROUND
APP_BACKGROUND
SCREEN_ON
SCREEN_OFF
BATTERY_LEVEL
BATTERY_TEMPERATURE
CHARGING
UNPLUGGED
NETWORK_CHANGED
TIME
THERMAL_STATE
DEVICE_IDLE
```

Actions may include:

```text
SET_PERFORMANCE_PROFILE
START_STATS
STOP_STATS
SET_STATS_PRESET
FREEZE_APP
UNFREEZE_APP
SET_NETWORK_POLICY
SET_LOG_PROFILE
POST_BRIDGE_EVENT
CHANGE_DOZE_PROFILE
```

Required safety:

- debounce;
- cooldown;
- priority;
- conflict resolution;
- repeated-action suppression;
- restore tracking;
- loop prevention.

Suggested precedence:

```text
MANUAL USER ACTION
    >
SAFETY POLICY
    >
PER-APP RULE
    >
GLOBAL RULE
```

---

# 28. Bomb Action History

This is not Diagnostics.

Only record meaningful actions performed by Bomb, e.g.:

```text
01:20 TikTok frozen
01:21 ads.example.com blocked
01:24 Genshin -> Gaming
01:33 Thermal Guardian -> Sustainable
```

Use bounded retention.

Do not record sensitive payload content.

---

# 29. Persistence

Use Room for configuration/metadata unless the existing repository already has a better supported persistence layer.

Suggested entities:

```text
AppProfileEntity
VisibilityProfileEntity
VisibilityAssignmentEntity
SettingsProfileEntity
SettingsOverrideEntity
FreezePolicyEntity
PerformanceProfileEntity
AutomationRuleEntity
BlocklistSourceEntity
BridgeRuleEntity
MonitorPresetEntity
NetworkPolicyEntity
BombActionEntity
```

Do not write high-frequency telemetry samples continuously to Room.

---

# 30. Mandatory reference repository research

Bomb must **not** be implemented purely from assumptions, README summaries, previous knowledge, or this document.

Before implementing a subsystem, Codex must clone and inspect the actual source code of the relevant reference repositories.

Cloning alone does not count as research.

## 30.1 Reference workspace

Clone references outside the Bomb repository, for example:

```bash
mkdir -p /tmp/bomb-reference-repos
```

Reference repos are read-only research material.

Do not:

- commit cloned references into Bomb;
- add them as submodules unless explicitly requested later;
- vendor their source automatically;
- modify them;
- make Bomb build depend on them without approval.

Use shallow clones by default and record the exact researched commit:

```bash
git -C /tmp/bomb-reference-repos/<repo> rev-parse HEAD
```

Create research notes under:

```text
docs/research/
```

Even though the current plan is intentionally a single master Markdown file, Codex may create research notes during actual research/implementation phases.

---

# 31. Reference repositories

## 31.1 HMA-OSS

Repository:

```text
https://github.com/frknkrc44/HMA-OSS.git
```

Clone:

```bash
git clone --depth 1 \
  https://github.com/frknkrc44/HMA-OSS.git \
  /tmp/bomb-reference-repos/HMA-OSS
```

Primary reference for:

- caller-aware app-list hiding;
- PackageManager filtering;
- direct package lookup hiding;
- UID/package visibility;
- per-target visibility profiles;
- Android Settings virtualization;
- installer-information virtualization;
- Zygote/framework interception concepts;
- multi-user/work-profile/private-space handling;
- cache/config architecture.

Mandatory source investigation:

- `app/`;
- `common/`;
- `zygote/`;
- symbols/flows involving `shouldHide`, caller UID, `Binder.getCallingUid`, PackageManager, Settings, installer information, `userId`, shared UID, presets, config cache.

Bomb must not simply copy HMA's Zygisk implementation.

Research HMA to understand leak surfaces and behavior, then redesign for ROM/framework integration.

Expected research note:

```text
docs/research/HMA_OSS.md
```

---

## 31.2 Blocker

Repository:

```text
https://github.com/lihenggui/blocker.git
```

Clone:

```bash
git clone --depth 1 \
  https://github.com/lihenggui/blocker.git \
  /tmp/bomb-reference-repos/blocker
```

Primary reference for:

- component enumeration;
- Activity/Service/Receiver/Provider control;
- PackageManager component state;
- Intent Firewall concepts;
- component grouping;
- component search/filter UI;
- backend abstraction.

Trace at least one complete component state-change flow from UI/model to backend and refreshed state.

Expected research note:

```text
docs/research/BLOCKER.md
```

---

## 31.3 Hail

Repository:

```text
https://github.com/aistra0528/Hail.git
```

Clone:

```bash
git clone --depth 1 \
  https://github.com/aistra0528/Hail.git \
  /tmp/bomb-reference-repos/Hail
```

Primary reference for:

- freeze UX;
- disable/hide/suspend/force-stop distinctions;
- backend capability abstraction;
- state management;
- system-app behavior;
- exclusions.

Codex must explicitly document why `suspend`, `hide`, `disable`, and `force stop` are not equivalent.

Expected research note:

```text
docs/research/HAIL.md
```

---

## 31.4 AOSP frameworks/base

Repository:

```text
https://android.googlesource.com/platform/frameworks/base
```

This is the authoritative platform reference for ROM-level architecture.

Use a shallow partial/sparse clone rather than blindly downloading everything when possible.

Example:

```bash
cd /tmp/bomb-reference-repos

git clone \
  --depth 1 \
  --filter=blob:none \
  --no-checkout \
  https://android.googlesource.com/platform/frameworks/base \
  aosp-frameworks-base

cd aosp-frameworks-base

git sparse-checkout init --cone

git sparse-checkout set \
  services/core/java/com/android/server/pm \
  services/core/java/com/android/server/am \
  services/core/java/com/android/server/wm \
  services/core/java/com/android/server/net \
  core/java/android/content/pm \
  core/java/android/provider \
  core/java/android/app \
  packages/SettingsProvider

git checkout
```

### Package visibility research

Inspect at minimum:

```text
services/core/java/com/android/server/pm/
```

Search for relevant current-version equivalents of:

```text
PackageManagerService
AppsFilter
AppsFilterBase
Computer
ComputerEngine
PackageState
PackageSetting
SharedUserSetting
queryIntentActivities
queryIntentServices
queryIntentReceivers
resolveIntent
getPackageInfo
getApplicationInfo
getInstalledPackages
getInstalledApplications
getPackagesForUid
```

Research question:

> Where can Bomb insert one shared caller-aware visibility policy with the fewest, safest framework patches?

Expected note:

```text
docs/research/AOSP_PACKAGE_MANAGER.md
```

### Settings virtualization research

Inspect:

```text
packages/SettingsProvider/
core/java/android/provider/
```

Search for relevant current-version equivalents of:

```text
SettingsProvider
SettingsRegistry
SettingsState
Settings.System
Settings.Secure
Settings.Global
NameValueCache
query
call
getString
```

Research question:

> Where can Bomb virtualize reads according to Binder caller without mutating the real system setting?

Expected note:

```text
docs/research/AOSP_SETTINGS_PROVIDER.md
```

### Process management research

Inspect:

```text
services/core/java/com/android/server/am/
```

Search for current-version equivalents of:

```text
ActivityManagerService
ProcessList
ProcessRecord
PhantomProcessList
CachedAppOptimizer
OomAdjuster
forceStopPackage
startProcess
```

Research:

- normal app processes;
- isolated processes;
- child/native processes;
- cached processes;
- process start requests;
- framework process-start decision points.

Do not implement framework spawn blocking until this flow is documented.

Expected note:

```text
docs/research/AOSP_PROCESS_MANAGEMENT.md
```

---

## 31.5 AdGuard Home

Repository:

```text
https://github.com/AdguardTeam/AdGuardHome.git
```

Clone:

```bash
git clone --depth 1 \
  https://github.com/AdguardTeam/AdGuardHome.git \
  /tmp/bomb-reference-repos/AdGuardHome
```

Primary architecture reference for:

- filter-source representation;
- local/remote filters;
- filter update pipeline;
- DNS filtering;
- allowlist precedence;
- config representation;
- failed-update behavior.

Bomb does not embed AdGuard Home.

Expected note:

```text
docs/research/ADGUARD_HOME.md
```

---

## 31.6 AdGuardSDNSFilter

Repository:

```text
https://github.com/AdguardTeam/AdGuardSDNSFilter.git
```

Clone:

```bash
git clone --depth 1 \
  https://github.com/AdguardTeam/AdGuardSDNSFilter.git \
  /tmp/bomb-reference-repos/AdGuardSDNSFilter
```

Reference for DNS-level rule syntax and normalization expectations.

Bomb only needs a safe DNS/domain subset, not browser cosmetic filtering.

Expected note:

```text
docs/research/ADGUARD_FILTER_FORMAT.md
```

---

## 31.7 HyperBridge

Repository:

```text
https://github.com/D4vidDf/HyperBridge.git
```

Clone:

```bash
git clone --depth 1 \
  https://github.com/D4vidDf/HyperBridge.git \
  /tmp/bomb-reference-repos/HyperBridge
```

Primary reference for HyperOS/HyperIsland event rendering concepts.

Research:

- notification observation;
- conversion pipeline;
- HyperOS capability detection;
- progress/event representation;
- per-app config;
- fallback behavior.

Bomb adaptation must be:

```text
Source Event
   -> BombLiveEvent
   -> HyperIslandRenderer
```

Expected note:

```text
docs/research/HYPERBRIDGE.md
```

---

## 31.8 LiveBridge

Repository:

```text
https://github.com/appsfolder/livebridge.git
```

Clone:

```bash
git clone --depth 1 \
  https://github.com/appsfolder/livebridge.git \
  /tmp/bomb-reference-repos/LiveBridge
```

Reference for:

- Android live updates;
- notification conversion;
- media/network events;
- event deduplication;
- Android/HyperOS compatibility handling.

Expected note:

```text
docs/research/LIVEBRIDGE.md
```

---

## 31.9 Android platform samples

Repository:

```text
https://github.com/android/platform-samples.git
```

Clone:

```bash
git clone --depth 1 \
  https://github.com/android/platform-samples.git \
  /tmp/bomb-reference-repos/android-platform-samples
```

Inspect official Live Updates samples and compare them with HyperBridge/LiveBridge.

When official Android API behavior differs from third-party assumptions, prefer official Android contracts.

Expected note:

```text
docs/research/LIVE_UPDATES.md
```

---

## 31.10 Basic Call Recorder (BCR)

Repository:

```text
https://github.com/chenxiaolong/BCR.git
```

Clone:

```bash
git clone --depth 1 \
  https://github.com/chenxiaolong/BCR.git \
  /tmp/bomb-reference-repos/BCR
```

Primary open-source call-recorder reference for:

- call-state lifecycle;
- recording session handling;
- storage;
- notification;
- format handling;
- custom-firmware/root deployment;
- failure behavior.

Do not assume this provides third-party VoIP capture.

Expected note:

```text
docs/research/BCR.md
```

---

## 31.11 Compose MIUIX

Repository:

```text
https://github.com/compose-miuix-ui/miuix.git
```

Clone:

```bash
git clone --depth 1 \
  https://github.com/compose-miuix-ui/miuix.git \
  /tmp/bomb-reference-repos/miuix
```

Primary UI reference for:

- scaffold;
- app bars;
- preference rows;
- switches;
- dialogs;
- bottom sheets;
- theme tokens;
- shapes/spacing;
- dark mode;
- motion.

Bomb still needs its own wrapper design system.

Expected note:

```text
docs/research/MIUIX.md
```

---

## 31.12 InstallerX-Revived

Repository:

```text
https://github.com/wxxsfxyzm/InstallerX-Revived.git
```

Clone:

```bash
git clone --depth 1 \
  https://github.com/wxxsfxyzm/InstallerX-Revived.git \
  /tmp/bomb-reference-repos/InstallerX-Revived
```

Secondary UI/architecture reference for combining modern Android UI, MIUIX/M3E concepts, privileged/package-management workflows, and HyperOS-like presentation.

Do not copy its feature scope into Bomb.

Expected note:

```text
docs/research/UI_REFERENCES.md
```

---

## 31.13 OpenMonitor

Repository:

```text
https://github.com/1orz/OpenMonitor.git
```

Clone:

```bash
git clone --depth 1 \
  https://github.com/1orz/OpenMonitor.git \
  /tmp/bomb-reference-repos/OpenMonitor
```

Secondary reference for:

- CPU/GPU metrics;
- battery/thermal;
- memory;
- process stats;
- FPS concepts;
- monitor presentation;
- refresh architecture.

Do not trust every metric blindly. Cross-check against AOSP, kernel/device sources, and runtime capabilities.

Expected note:

```text
docs/research/STATS_REFERENCES.md
```

---

## 31.14 LogFox

Repository:

```text
https://github.com/F0x1d/LogFox.git
```

Primary behavioural reference for the App Crash Tracker: Java/JNI crash and ANR
observation, filtering, notification, bounded surrounding logs and export UX.
Record the exact reader, parser, event-correlation, deduplication and lifecycle
paths in `docs/research/LOGFOX.md`. Its GPL-3.0 licence makes it
**research/reimplement-only by default**; do not copy code without a deliberate
licence decision.

---

# 32. Mandatory repository research protocol

Cloning a repository alone does not count as research.

For each relevant reference repository, Codex must:

1. read README;
2. read LICENSE;
3. inspect module structure;
4. inspect build files;
5. identify feature entry points;
6. trace at least one complete execution path;
7. inspect configuration/data models;
8. inspect important error handling;
9. inspect caching/concurrency where relevant;
10. identify platform/version-specific behavior;
11. record exact files/classes/methods;
12. compare the implementation with Bomb requirements.

Examples:

## HMA research is not complete until Codex can explain

```text
target app
   -> framework/API interception
   -> calling UID resolution
   -> visibility/settings policy lookup
   -> decision
   -> filtered/virtualized result
```

## Blocker research is not complete until Codex can explain

```text
selected component
   -> component model
   -> backend
   -> PackageManager/IFW operation
   -> state refresh
```

## Hail research is not complete until Codex can explain

```text
disable != hide != suspend != force-stop
```

## AdBlock research is not complete until Codex can explain

```text
source
 -> download
 -> parse
 -> normalize
 -> compile
 -> DNS query match
 -> allow/block result
```

## Bomb Bridge research is not complete until Codex compares

- HyperBridge;
- LiveBridge;
- official Android Live Updates.

---

# 33. Source trace requirement

Whenever an architecture decision is based on a reference repo, planning documentation must identify the exact source location.

Required format:

```text
Reference:
<repo> @ <commit SHA>

Relevant source:
<path>
Class: <class>
Method: <method>

Observed behavior:
...

Bomb implication:
...
```

Do not write vague claims such as "HMA hides apps using hooks" without identifying the actual flow used by the researched commit.

All paths/symbols recorded in research notes must exist in that exact commit.

---

# 34. Cross-reference rule

Do not base a critical Bomb architecture decision on a single third-party repo when an authoritative Android source exists.

Priority:

```text
AOSP / official Android source
        >
official API/library source/documentation
        >
mature open-source implementation
        >
smaller reference implementation
        >
assumption
```

Required comparisons:

```text
App Visibility
  HMA-OSS + AOSP PackageManager/AppsFilter

Settings Virtualization
  HMA-OSS + AOSP SettingsProvider

Freeze
  Hail + AOSP PM/AM behavior

Component Control
  Blocker + AOSP PackageManager

Process Control
  Blocker + AOSP ActivityManager/ProcessList

AdBlock
  AdGuard Home + AdGuardSDNSFilter

Bomb Bridge
  HyperBridge + LiveBridge + Android platform samples

Call Recorder
  BCR + actual ROM/vendor audio capabilities

Stats
  OpenMonitor + AOSP/kernel/device sources
```

---

# 35. License and source reuse rule

Reference repositories are primarily for research.

Before copying any non-trivial source code:

1. inspect the repository LICENSE;
2. determine compatibility with Bomb's intended license;
3. record source and license;
4. preserve attribution where required;
5. comply with all license terms.

Default workflow:

```text
UNDERSTAND IMPLEMENTATION
        ->
REDESIGN FOR BOMB
        ->
WRITE BOMB-SPECIFIC IMPLEMENTATION
```

Do not mechanically copy code.

AGPL/GPL/copyleft code must not be copied casually into Bomb without deliberate license compatibility review.

Record license findings in:

```text
docs/research/REFERENCES.md
```

---

# 36. Feature-to-reference matrix

| Bomb subsystem | Mandatory primary references |
|---|---|
| App Visibility | HMA-OSS + AOSP PackageManager/AppsFilter |
| Per-App Settings | HMA-OSS + AOSP SettingsProvider |
| Component Explorer | Blocker + AOSP PackageManager |
| Process Control | Blocker + AOSP ActivityManager/ProcessList |
| Freeze | Hail + AOSP |
| Package Inspector | Blocker + HMA-OSS + AOSP |
| Task Manager | AOSP ActivityManager/ProcessList + OpenMonitor |
| Stats | OpenMonitor + AOSP/kernel/device sources |
| AdBlock | AdGuard Home + AdGuardSDNSFilter |
| DNS Manager | AdGuard Home + Android resolver sources as needed |
| Bomb Bridge | HyperBridge + LiveBridge + Android platform samples |
| Call Recorder | BCR + actual ROM/vendor audio stack |
| MIUIX UI | compose-miuix-ui/miuix |
| MIUIX + M3E UX | MIUIX + InstallerX-Revived |
| Bomb Rules | Bomb-specific; first understand the modules it orchestrates |
| Logging / App Crash Tracker | LogFox + AOSP logd/logcat, ActivityManager and debuggerd/tombstone paths |

---

# 37. Implementation phases

Do not implement everything at once.

## Phase 0A — Bomb repository audit

Before broad changes:

1. inspect repo structure;
2. read `AGENTS.md` if present;
3. inspect Gradle/build files;
4. inspect manifest;
5. identify Android target/min SDK;
6. identify MIUIX/Compose/Material versions;
7. identify current architecture;
8. identify native code;
9. identify AIDL/Binder code;
10. identify SELinux policy;
11. identify init rc files;
12. identify privileged permissions;
13. identify persistence;
14. identify tests/CI;
15. identify already implemented Bomb features.

Create/update:

```text
docs/ARCHITECTURE.md
```

Do not perform broad architecture changes yet.

## Phase 0B — mandatory external research

Clone and inspect relevant reference repositories.

At minimum for the initial architecture, inspect:

- HMA-OSS;
- Blocker;
- Hail;
- AOSP frameworks/base;
- AdGuard Home;
- AdGuardSDNSFilter;
- HyperBridge;
- LiveBridge;
- Android platform samples;
- BCR;
- MIUIX.

Use InstallerX-Revived/OpenMonitor when relevant to the current milestone.

Create research notes under:

```text
docs/research/
```

README-only research is insufficient.

## Phase 0C — architecture synthesis

Only after 0A + 0B:

Create/update:

```text
docs/BOMB_PLAN.md
```

For each subsystem document:

```text
Bomb requirement
   + AOSP mechanism
   + reference implementation(s)
   + Bomb-specific design
   + risk/compatibility
   + testing strategy
```

After Phase 0C: **STOP and report findings before starting M1.**

## Phase 1 — UI foundation

Implement:

- Bomb design system;
- navigation;
- Home shell;
- Apps shell;
- Monitor shell;
- Automation shell;
- More shell;
- capability-aware states.

## Phase 2 — Core service / IPC

Implement:

- BombCoreService;
- typed AIDL;
- caller validation;
- BombResult;
- capability registry;
- `bombd` skeleton only if actually required.

## Phase 3 — Task Manager

Implement:

- process discovery;
- models;
- search;
- sort;
- filters;
- details.

## Phase 4 — Telemetry

Start with reliable metrics:

- CPU;
- memory;
- battery;
- thermal.

Then add process CPU, threads, GPU, FPS, power only when real backends exist.

## Phase 5 — Freeze

Implement:

- FreezeBackend;
- manual freeze/unfreeze;
- exclusions;
- auto-freeze;
- lifecycle handling.

## Phase 6 — Component / Process Control

Implement:

- Component Explorer;
- process grouping;
- component -> process mapping;
- ComponentBlockBackend;
- Process Control model.

Only then consider framework process-start policy.

## Phase 7 — App Control / Package Inspector

Combine:

- overview;
- runtime;
- components;
- freeze;
- package metadata;
- power/network state.

## Phase 8 — App Visibility

Implement HMA-style caller-aware framework filtering.

Requirements:

- enumeration consistency;
- direct lookup consistency;
- intent resolution filtering;
- provider filtering;
- UID mapping audit;
- shared UID behavior;
- multi-user support;
- immutable runtime cache.

Do not substitute permission revocation.

## Phase 9 — Settings Virtualization

Implement:

- profiles;
- typed values;
- System/Secure/Global namespaces;
- caller-aware reads;
- no global setting mutation.

## Phase 10 — Network / AdBlock

Implement:

- blocklist sources;
- parsers;
- normalization;
- compiler;
- DNS blocker;
- allowlist;
- source updates;
- network telemetry.

Then firewall policy.

## Phase 11 — Performance / Battery

Implement:

- performance profiles;
- generic backend;
- capabilities;
- Battery Lab;
- Thermal Guardian.

## Phase 12 — Bomb Rules

Implement:

- triggers;
- conditions;
- actions;
- priorities;
- cooldown;
- restore behavior;
- conflict/loop handling.

## Phase 13 — Background Management

Implement:

- autostart;
- wakelock/job/alarm views;
- Wakeup Guard;
- Doze integration.

## Phase 14 — Bomb Bridge

Implement `BombLiveEvent` first, then sources, then renderers.

Do not tie domain logic directly to HyperIsland internals.

## Phase 15 — Privacy utilities

Implement supported clipboard/sensor/permission/notification policies.

Also implement Logging and App Crash Tracker (§26.1): ROM-backed `OFF` without
root mode, opt-in per-app crash observation, local notification/history/export,
and explicit conflict handling between tracking and the `OFF` profile.

## Phase 16 — Call Recording

Only after actual ROM/vendor audio capabilities are identified and tested.

## Phase 17 — Polish

Focus on:

- performance;
- memory;
- UI consistency;
- capability handling;
- SELinux minimization;
- tests;
- migration;
- multi-user behavior.

---

# 38. Priority order

## P0

- Task Manager;
- Stats Core;
- Freeze;
- App Control;
- Component / Process Control;
- Package Inspector.

## P1

- App Visibility;
- Per-App Settings;
- AdBlock;
- Firewall;
- Performance Profiles;
- Bomb Rules.

## P2

- Battery Lab;
- Autostart/Wakeup/Doze;
- Bomb Bridge;
- Logging / App Crash Tracker;
- privacy utilities.

## P3

- Call Recorder;
- Storage Analyzer;
- Idle App Advisor.

---

# 39. Testing requirements

Add tests for non-trivial domain logic.

## App Visibility

Test:

- blacklist;
- whitelist;
- caller isolation;
- user isolation;
- trusted/system exemptions;
- shared UID behavior;
- conflicting policy handling.

## Settings Virtualization

Test:

- caller-specific override;
- fallback to real value;
- value typing;
- user isolation;
- disabled override;
- unknown/unsupported key handling.

## Freeze

Test:

- critical exclusions;
- delay logic;
- manual override;
- background timing;
- restore/unfreeze.

## Bomb Rules

Test:

- trigger matching;
- conditions;
- cooldown;
- priority;
- conflict resolution;
- restore behavior;
- loop prevention.

## AdBlock

Test:

- hosts parsing;
- plain domain parsing;
- supported AdBlock-domain syntax;
- allow rules;
- subdomain matching;
- false-positive prevention;
- invalid lines/domains;
- deduplication;
- failed update preserving previous blockset.

## Telemetry

Test:

- rate calculations;
- counter reset;
- overflow;
- invalid source values;
- unavailable metrics.

## Performance profiles

Test:

- capability filtering;
- unsupported fields;
- restore behavior.

## Logging / App Crash Tracker

Test:

- `OFF` works on the integrated ROM without a root backend and stays stopped;
- recovery to `DEFAULT` after reboot;
- vendor sinks are reported as partial-off when they cannot be disabled;
- Java, native and ANR fixtures are classified and correlated to the right app;
- package allowlist, global/per-app toggles and service shutdown;
- crash-loop deduplication and notification rate limiting;
- bounded retention, redaction and explicit export;
- tracker enable while `OFF`, and selecting `OFF` while the tracker is active.

---

# 40. Code quality requirements

Production-quality code only.

Do not:

- fake telemetry;
- hardcode fake device support;
- present TODO placeholders as working features;
- swallow exceptions silently;
- block main thread;
- scatter vendor-specific paths throughout general code;
- refactor unrelated areas without reason;
- add unrestricted root/shell helpers.

Always:

- handle nullability;
- validate privileged inputs;
- model loading/error/unsupported states;
- keep device-specific logic behind backends;
- preserve repository conventions;
- add tests for non-trivial domain logic;
- report exactly which tests/build/lint commands were run.

---

# 41. First Codex task

**Do not start implementing all features immediately.**

First execute the research/audit phase.

Required sequence:

```text
Bomb repo audit
    -> clone reference repos
    -> read actual source
    -> trace implementations
    -> record commit SHAs/licenses
    -> write docs/research/*
    -> cross-check against AOSP
    -> design Bomb architecture
    -> write docs/BOMB_PLAN.md
    -> STOP FOR REVIEW
```

The first Codex run should:

1. inspect the Bomb repository;
2. read all existing build/config/instruction files;
3. clone mandatory references outside the repo;
4. inspect actual source, not only READMEs;
5. trace relevant architecture and implementation paths;
6. record exact commit SHAs;
7. record licenses;
8. create research notes;
9. cross-check critical third-party ideas with AOSP;
10. create/update `docs/ARCHITECTURE.md` and `docs/BOMB_PLAN.md`;
11. report blockers and unknown platform dependencies;
12. stop before broad implementation.

Do not invent file/class/method names in research notes. Every cited symbol must exist in the researched commit.

---

# 42. Core architectural invariants

These decisions should remain stable unless deliberate research proves a better design.

## App Visibility

Must be a caller-aware virtual package view, not `QUERY_ALL_PACKAGES` permission management.

```text
Caller A -> hidden package B behaves absent
Caller C -> package B remains visible
```

## Settings Virtualization

Must virtualize read results per caller, not mutate real Settings values as apps enter/leave foreground.

## Process Control

Prefer prevention at the component/framework source over repeated reactive killing.

## Telemetry

Use one shared collector/repository pipeline consumed by UI, overlays, rules, and bridge.

## AdBlock

Use domain/DNS filtering. Do not perform TLS or payload interception.

## System security

SELinux remains enforcing, privileged APIs remain typed, and Bomb UI never becomes an unrestricted root shell.

---

# 43. Definition of done for a feature

A feature is not complete merely because a screen exists.

Completion requires, where relevant:

1. a real backend;
2. capability detection;
3. loading state;
4. error state;
5. unsupported state;
6. input validation;
7. no main-thread blocking;
8. no uncontrolled polling;
9. no broad SELinux workaround;
10. no unrestricted shell API;
11. relevant tests;
12. documented device/version limitations.

---

# 44. Summary

Bomb should become one coherent system control layer built around these high-value foundations:

```text
Task Manager
+ Stats
+ Freeze
+ App Control
+ Component / Process Control
+ Package Inspector
+ HMA-style App Visibility
+ Per-App Settings Virtualization
+ AdBlock / DNS / Firewall
+ Performance / Thermal / Battery
+ Bomb Rules
+ Bomb Bridge
```

---

# 45. Privileged App Update & System Status Preservation

When Bomb is installed in `/system/priv-app` or `/product/priv-app` and later updated via sideload/OTA (`/data/app` update):

1. **Privileged Status Detection (`RuntimeModeDetector` & `CapabilityProbe`):**
   - Ensure Package Manager flags (`FLAG_SYSTEM` or `PRIVATE_FLAG_PRIVILEGED`) are correctly evaluated even after an update to `/data/app`.
   - Protect signature permission checks so updated APKs retaining the original system certificate do not lose privileged IPC capabilities or get demoted to `NORMAL` mode.
2. **System XML Whitelist Validation:**
   - Verify `/system/etc/permissions/privapp-permissions-com.hzzmonet.zkbomb.xml` remains respected during runtime permission checks.

---

# 46. UI Alignment Polish & List Virtualization Optimization

1. **App Selector List Lag Fix:**
   - Optimize Compose `LazyColumn` keying and item rendering for application selection lists (`AppsScreen`, `AppVisibilityScreen`, `FirewallScreen`).
   - Use `derivedStateOf`, async icon fetching (`rememberDrawablePainter` / bitmap cache), and payload-based recomposition to prevent UI stutters when scrolling 200+ installed packages.
2. **MIUIX Layout Alignment Polish:**
   - Fix card padding inconsistencies, title clipping, and slider alignment issues on specific DPI screens.


Research is mandatory before implementation. Reference projects exist to teach architecture and edge cases, not to be copied blindly.
