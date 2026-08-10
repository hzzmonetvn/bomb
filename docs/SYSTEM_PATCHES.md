# Bomb system patch requirements

This is the contract between the Bomb APK and the HyperOS image patcher. It
lists only the framework, init and SELinux work required by each feature.
SELinux must remain enforcing.

Bomb supports two privileged backends:

- **ROM**: privileged APK plus `bombd`, installed and labelled in the image.
- **Root**: normal APK plus the same typed daemon and narrow policy delivered by
  a Magisk/KernelSU module.

If neither backend is available, Bomb reports `BackendUnavailable`; it does not
invent a third execution mode.

## Common foundation

Every privileged feature depends on the same foundation:

1. Install `com.hzzmonet.zkbomb` under
   `system_ext/priv-app/Bomb/Bomb.apk` for ROM mode.
2. Grant only feature-traceable permissions in
   `privapp-permissions-com.hzzmonet.zkbomb.xml`.
3. Start `/system_ext/bin/bombd` from `/system_ext/etc/init/bomb.rc` in its own
   `u:r:bombd:s0` domain. The UI never runs shell commands and never runs as root.
4. Label `/data/system/bomb(/.*)?` as `bomb_data_file` and keep persistent daemon
   state there.
5. Expose typed, versioned Binder/daemon operations. Validate caller UID,
   signature, package names, user IDs, numeric ranges and paths.

Minimum types:

```text
bomb_app       privileged APK domain
bombd          native daemon domain
bombd_exec     /system_ext/bin/bombd entry point
bomb_data_file /data/system/bomb files
bomb_prop      persist.sys.bomb.* properties
```

Required labels:

```text
/system_ext/bin/bombd    u:object_r:bombd_exec:s0
/data/system/bomb(/.*)?  u:object_r:bomb_data_file:s0
persist.sys.bomb.        u:object_r:bomb_prop:s0
```

Never add broad rules such as `allow bomb_app *:* *`, generic shell execution,
arbitrary file/sysfs writes, or unreviewed `audit2allow` output.

## Feature matrix

| Feature | Framework / permission patch | init patch | SELinux scope |
| --- | --- | --- | --- |
| Task Manager | `DUMP`, `FORCE_STOP_PACKAGES`, `KILL_BACKGROUND_PROCESSES`; typed process API | Start `bombd` only when process telemetry has subscribers | Read selected `/proc/<pid>` files; never broad proc write |
| Stats / overlay | `DUMP`, `PACKAGE_USAGE_STATS`, `SYSTEM_ALERT_WINDOW` | No always-on poller; collector follows subscribers | Read selected procfs, thermal and GPU nodes; overlay stays in app domain |
| Freeze / App Control | `FORCE_STOP_PACKAGES`, `CHANGE_APP_IDLE_STATE`, `CHANGE_COMPONENT_ENABLED_STATE`, `MANAGE_APP_OPS_MODES`; cross-user access only when required | Optional reconciliation after PackageManager and user unlock | Binder calls to ActivityManager/package/app-ops services; do not use the daemon for framework operations |
| Performance profiles | Typed framework service plus allowlisted device backend | Restore safe defaults at boot and after daemon failure | Write only probed CPU/GPU/cgroup nodes with bounded values |
| Battery Lab | Read BatteryManager/health; privileged writes use a charging backend | Restore stock charging settings on boot and service stop | Write only capability-probed battery nodes; separate Qualcomm/MTK allowlists |
| Network Control | Typed per-UID policy API; never accept raw iptables commands | Restore persisted policy after netd is ready | Binder access to network policy or narrowly scoped daemon networking capability |
| Log Governor | `READ_LOGS`, `WRITE_SECURE_SETTINGS`; own `persist.sys.bomb.logs.*` keys | Property triggers adjust levels while preserving logd, tombstones and bugreports | `set` only on `bomb_prop`; no generic property-service access |
| Bomb Bridge | Notification-listener permission and capability-gated HyperOS renderer | None | Normally no daemon rule; add only observed, necessary service access |
| Call recording | Platform-supported source and explicit user consent | Lifecycle-owned recorder service, never a hidden boot recorder | Audio service/device access only after the framework path is proven |
| Rules | Schedule exact work only when a rule requires it | Restore after user unlock with bounded retries | Union of invoked typed operations, never a generic automation domain |

## Package Inspector contract (API v5)

API v5 appends three typed Binder operations without changing earlier method
ordering:

- `getPackageSnapshot(packageName, userId)` returns bounded package metadata,
  certificate SHA-256 digests and at most 512 manifest components, with a second
  aggregate string budget to stay below Binder's transaction ceiling. It reports
  the real component total and a truncation flag.
- `forceStopPackage(packageName, userId)` refuses protected packages and only
  succeeds after the package has `FLAG_STOPPED` and no observed running process.
- `setComponentState(packageName, userId, className, state)` accepts only
  `DEFAULT`, `ENABLED` or `DISABLED`, verifies the component against the target
  package's complete manifest, applies the override and reads it back.

All three are current-user only. Cross-user support remains unavailable until a
separate user-scoped design and permission review exist. The existing manifest
and privapp allowlist already contain `FORCE_STOP_PACKAGES` and
`CHANGE_COMPONENT_ENABLED_STATE`; this API does not require a new init service,
property or SELinux allow rule.

## Visibility, settings and firewall contract (API v6)

API v6 keeps the Binder surface typed. Every write follows the same order:
validate the real Binder caller, validate and bound every argument, validate the
current user, then probe capability and execute. UID-scoped inputs must identify
an application UID and the UID's encoded user must equal `userId`; cross-user
writes remain unsupported.

The server-side limits are part of the contract:

- visibility policy: at most 512 validated package names, 2,048 caller policies;
- settings: profile ID 1..128 (`[A-Za-z0-9._-]`), profile name 1..256, key
  1..256, value at most 4,096 characters, 128 profiles, 512 overrides per
  profile and 2,048 assignments;
- firewall: note at most 256 printable characters and at most 4,096 UID rules.

Settings virtualization is fail-closed. Only keys classified `APP_READ` in
`SettingsKeyPolicy` may be stored; `SYSTEM_READ` (`font_scale`), forbidden
identity keys (`secure/android_id`), per-app-configuration keys and unknown keys
are refused. Typed values use canonical platform wire strings: booleans are
`"0"`/`"1"`, NULL has a null payload, integer/long/float strings are canonical,
and non-finite floats are rejected. Overrides and assignments require an existing
profile. The hot-path snapshot always passes through for system UIDs, a UID/user
mismatch, an empty caller-package set, or conflicting/incomplete shared-UID
assignments.

These API guards add no generic daemon interface and require no new init trigger,
property namespace or SELinux allow rule. The actual visibility/settings
enforcement remains behind the separately reviewed framework hooks and their ROM
marker capabilities. A ROM/property marker is declaration evidence only: it does
not make either capability `SUPPORTED`. Until a real system_server bridge has
completed a handshake and acknowledged the applied policy revision, both
visibility and settings virtualization remain fail-closed as `UNSUPPORTED`, and
Binder writes return before mutating the service-local cache. Firewall remains
unavailable unless its typed platform backend is present.

## Selected-process memory contract (API v7)

API v7 appends `getSelectedProcessMemory(pid)` after every v1–v6 transaction and
does not change an existing Parcelable layout. `getProcessSnapshot()` remains
wire-compatible but is now a cheap process list: PSS and private-dirty are null,
while RSS and proc counters are read independently and remain nullable when
procfs or SELinux withholds them.

The selected-memory call accepts one positive PID only after confirming that it
belongs to the same bounded 192-process inventory used by the list. The backend
passes exactly that one PID to `ActivityManager.getProcessMemoryInfo`, verifies
the PID is still visible after sampling, and never falls back to another process.
Its typed status distinguishes available, invalid, not-visible, disappeared,
unavailable and permission-denied results. Zero/unreadable platform counters are
reported as null, never fabricated as zero. `PROCESS_PSS_TELEMETRY` is supported
only when a real one-PID probe of a non-service process returns at least one
usable memory metric.

This read-only API requires no new permission, init trigger, property or SELinux
allow rule beyond the process visibility and ActivityManager access already
required by Task Manager.

## Predictive back

### Inside Bomb

Bomb opts in with `android:enableOnBackInvokedCallback="true"` and consumes
AndroidX predictive-back progress. A gesture pops Bomb's own back stack; at a tab
root the callback is disabled so Android may animate the app to Home. This path
requires no privileged permission, init rule or SELinux rule.

### System-wide HyperOS restoration

The referenced
[`MiuiBackGestureHook`](https://github.com/wxxsfxyzm/MiuiBackGestureHook) is an
LSPosed research module, not an application library. It scopes hooks to
`system`, `com.android.systemui` and `com.miui.home`, restores the WM Shell AOSP
back-animation path, coordinates edge input, handles cross-activity and
return-to-launcher transitions, and can opt selected packages into predictive
back.

A ROM-native implementation must port behavior at source or bytecode patch
points; do not bundle the Xposed runtime into Bomb:

- **framework/system_server**: preserve per-activity
  `enableOnBackInvokedCallback` metadata and the standard
  `OnBackInvokedDispatcher` path. Forced opt-in must use an explicit package
  allowlist, never a global flag.
- **SystemUI / WM Shell**: keep `EdgeBackGestureHandler`,
  `BackAnimationController`, Shell transition ownership and finish callbacks on
  the AOSP path. Guard each private patch point by exact ROM build and class
  signature; fail closed to the stock gesture path.
- **MiuiHome**: coordinate return-home animation state with Shell and do not
  steal Back while Launcher is in edit, drawer or recents states.
- **init**: no persistent gesture daemon is needed. If a resource overlay or
  property selects the implementation, set it before SystemUI starts and apply
  it idempotently.
- **SELinux**: no new allow rule is expected for an in-process framework patch.
  A separate controller service would require a dedicated Binder service type
  and signature permission; it must not receive input injection or unrestricted
  SurfaceFlinger access.

Compatibility must be verified per SystemUI/MiuiHome build. The reference module
states that its launcher integration best matches MiuiHome `7.50.xx`; copying
private names across versions without probes can boot-loop SystemUI.

## Image-patcher work

`tools/py/selinuxpatch.py` in the ROM build project must be able to:

- emit type declarations and type-attribute membership before `allow` rules;
- add `seapp_contexts`, `file_contexts`, `property_contexts` and, if needed,
  `service_contexts` entries;
- create the init-to-`bombd` transition through `bombd_exec`;
- delete stale precompiled policy only when runtime compilation is available;
- fail the build when a requested Bomb type, rule or label was not emitted.

`bomb.sh` must install the APK, permission XML, daemon and rc file through one
idempotent option. It must not append duplicate init blocks on repeated builds.

## Verification after flashing

```sh
adb shell pm path com.hzzmonet.zkbomb
adb shell getenforce
adb shell ps -AZ | grep -E 'bomb_app|bombd'
adb shell ls -Z /system_ext/bin/bombd
adb shell dmesg | grep 'avc: denied' | grep -i bomb
adb shell dumpsys package com.hzzmonet.zkbomb
```

Verify each enabled capability, reboot, and verify restoration. Review every AVC
against the typed operation that triggered it; a missing rule is not permission
to widen the domain.
