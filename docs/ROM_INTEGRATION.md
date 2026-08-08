# Patching Bomb into the ROM

Everything that must happen to turn Bomb from an installable APK into an
integrated system component of the HyperOS build produced by `hzz/`.

Companions: [`ARCHITECTURE.md`](./ARCHITECTURE.md) (audit + target design),
[`BOMB_PLAN.md`](./BOMB_PLAN.md) (milestones), and
[`SYSTEM_PATCHES.md`](./SYSTEM_PATCHES.md) (feature-by-feature patch contract).

> **Status.** The APK uses the product package `com.hzzmonet.zkbomb`, but the
> privileged service, daemon and ROM policy are not implemented in this repo yet.

---

## 0. Decide the execution mode first

| Mode | Do sections | Skip |
| --- | --- | --- |
| **ROM integrated** | 1 → 8 | — |
| **Root module** (Magisk/KernelSU) | 4, 5, 9 | 1, 2, 3 (no image patching) |

Root mode exists because some rules are impractical to add to a prebuilt image.
It reaches the same capabilities through `sepolicy.rule` + a module service, and
iterates in seconds instead of a repack cycle. See `ARCHITECTURE.md` §5b.

---

## 1. Place the APK

Bomb ships as a **privileged app** in `system_ext`:

```
mods/zk_mods/universal/system_ext/priv-app/Bomb/Bomb.apk
```

This mirrors how `ProjectZK.apk` is delivered today, so `bomb.sh zk-mods`
already copies it — the `cp -rf "$zk_mods/universal"/* "$work_dir"` step picks it
up with no script change.

Checklist:

- [ ] APK built in **release** mode, minified, and signed with a stable key
- [ ] Path is `system_ext/priv-app/Bomb/Bomb.apk` (directory name = app name)
- [ ] Native libraries extracted to `Bomb/lib/arm64/` if any are bundled
- [ ] `android:sharedUserId` **not** set unless the ROM platform key is available
      (see §7)

---

## 2. Privileged permission allowlist

```
mods/zk_mods/universal/system_ext/etc/permissions/privapp-permissions-com.hzzmonet.zkbomb.xml
```

Derive this per feature. Do **not** copy the ~115-permission list from
`privapp-permissions-com.zk.toolbox.xml` — it requests `MASTER_CLEAR`,
`INSTALL_PACKAGES`, `MANAGE_DEVICE_ADMINS` and OPPO vendor permissions that have
nothing to do with this ROM, and it is exactly the pattern `CLAUDE.md` forbids.

Minimum set, each tied to one feature:

| Permission | Needed by |
| --- | --- |
| `DUMP` | FPS via `dumpsys gfxinfo`, meminfo |
| `PACKAGE_USAGE_STATS` | foreground-app detection for Freeze + Rules |
| `FORCE_STOP_PACKAGES` | Task Manager force stop |
| `KILL_BACKGROUND_PROCESSES` | Task Manager |
| `CHANGE_APP_IDLE_STATE` | Freeze (soft) |
| `CHANGE_COMPONENT_ENABLED_STATE` | Freeze (deep), App Control |
| `MANAGE_APP_OPS_MODES` | App Control background policy |
| `WRITE_SECURE_SETTINGS` | Performance profiles, Log Governor |
| `READ_LOGS` | Log Governor rate estimation |
| `BATTERY_STATS` | Battery Lab, Power Activity |
| `INTERACT_ACROSS_USERS` | package state for the primary user |
| `QUERY_ALL_PACKAGES` | Apps list |
| `SYSTEM_ALERT_WINDOW` | Stats overlay |

- [ ] Every entry has a comment naming the feature that needs it
- [ ] Anything not traceable to a feature is deleted
- [ ] Note: `bomb.sh` blanks `ro.control_privapp_permissions` in
      `vendor/build.prop`, so this file is **not currently enforced**. Ship it
      correct anyway — the ROM setting can change, and it documents intent.

---

## 3. Native daemon `bombd`

Only needed from M8 (first write to a device node).

```
mods/zk_mods/universal/system/system/bin/bombd          (arm64, NDK-built)
mods/zk_mods/universal/system/system/etc/init/bombd.rc
```

`bombd.rc`:

```
service bombd /system/bin/bombd
    class late_start
    user system
    group system
    seclabel u:r:bombd:s0
    oneshot
    disabled

on property:sys.boot_completed=1
    start bombd
```

Checklist:

- [ ] Built with the NDK (**not installed on the build host yet** — `~/Android/Sdk/ndk` is absent)
- [ ] arm64-v8a only; statically checked for unexpected `dlopen`
- [ ] Binary mode `0755`, owner `root:shell`
- [ ] `fs_config` entry added if the default from `selinuxpatch.py` is wrong

---

## 4. SELinux

**This is the part that actually bites.** `hzz/tools/py/selinuxpatch.py` today
appends CIL `allow` rules to `vendor/etc/selinux/vendor_sepolicy.cil` and
**validates them against types that already exist** — rules naming unknown types
are silently dropped. It cannot declare Bomb's domains.

### 4.1 Types to add

| Type | Purpose |
| --- | --- |
| `bomb_app` | the APK's domain |
| `bombd` / `bombd_exec` | daemon domain + its entrypoint label |
| `bomb_data_file` | `/data/system/bomb(/.*)?` |
| `bomb_service` | Binder service label (only if registering with servicemanager) |

### 4.2 Work required in `selinuxpatch.py`

- [ ] Emit CIL **type declarations** before rules
- [ ] Emit `typeattributeset` membership (`domain`, `appdomain`/`coredomain`,
      `file_type`, `data_file_type`)
- [ ] Emit the init → `bombd` domain transition via `bombd_exec`
- [ ] Write `seapp_contexts` (package → `bomb_app`), `file_contexts`
      (`/system/bin/bombd`, `/data/system/bomb`), `property_contexts`
      (Bomb property namespace for Log Governor)
- [ ] **Fail loudly** when `_runtime_policy_compile_available()` is false —
      today it prints a line and silently skips every custom rule, so a ROM
      without `secilc` would boot with none of Bomb's policy
- [ ] Implement the `bomb.sh selinux` subcommand — it is listed in the usage
      string (line 655) but has no `case` body

### 4.3 Rule discipline

- [ ] Rules scoped to Bomb's own types and the specific nodes involved
      (`bomb_app sysfs_kgsl:file read open`, never `sysfs:file *`)
- [ ] No `audit2allow` output applied unreviewed — for each denial: identify
      source, target, class, exact operation, then decide if it is *required*
- [ ] Never `allow bombd *:* *` / `allow bomb_app *:* *`
- [ ] **Enforcing stays on**, including during bring-up

### 4.4 Labels

```
/system/bin/bombd        u:object_r:bombd_exec:s0
/data/system/bomb(/.*)?  u:object_r:bomb_data_file:s0
```

Without an explicit label, `selinuxpatch.py` auto-labels the binary
`u:object_r:system_file:s0`, which cannot serve as a domain entrypoint — the
daemon would run in `init`'s domain or fail to start.

---

## 5. Property namespace (Log Governor)

The ROM already carries a property-driven logging profile in `init.rc`, keyed on
`persist.sys.zk.minimal_logging`. Generalise it rather than reinventing it:

- [ ] Bomb-owned namespace `persist.sys.bomb.*`
- [ ] `property_contexts` entry: `persist.sys.bomb. u:object_r:bomb_prop:s0`
- [ ] `allow bomb_app bomb_prop:property_service set;`
- [ ] Keep the existing guarantee: logd, tombstones and bugreports stay alive;
      only verbose/debug volume is reduced

---

## 6. `bomb.sh` integration

- [ ] Bomb files land under `mods/zk_mods/universal/…` so the existing
      `zk-mods` step copies them — no new copy logic needed
- [ ] Add a `bomb-app` option flag (`OPTION_BOMB=true`) mirroring `OPTION_TOOLBOX`
- [ ] Implement the dead `selinux` subcommand (§4.2)
- [ ] Do **not** add another `init.rc` block if `bombd.rc` covers it — the
      existing `BOMB INIT PATCH` marker guards against double-append, a new
      block needs its own marker

---

## 7. Signing and UID

- [ ] Sign with a stable release key (not the debug key the preview uses)
- [ ] `sharedUserId="android.uid.system"` **only** if the ROM platform private
      key is available. It is not in this workspace — `ProjectZK.apk` is signed
      with a project keystore. Without it, Bomb runs as a normal priv-app UID
      and reaches privileged operations through permissions + `bombd`
- [ ] If the platform key does appear later, this is a manifest + signing
      change; the AIDL surface does not move

---

## 8. Verify after flashing

In order — each step catches a different failure:

- [ ] `adb shell pm list packages -s | grep zkbomb` — installed as system app
- [ ] `adb shell dumpsys package com.hzzmonet.zkbomb | grep -A5 "granted=true"` —
      privileged permissions actually granted
- [ ] `adb shell getenforce` → **Enforcing** (if it says Permissive, stop and fix)
- [ ] `adb shell dmesg | grep -i "avc: denied" | grep -i bomb` — should be empty;
      every denial gets a scoped rule, not a broad one
- [ ] `adb shell ps -A | grep bombd` — daemon running in its own domain
- [ ] `adb shell ls -Z /system/bin/bombd` → `u:object_r:bombd_exec:s0`
- [ ] Open Bomb → **Execution mode** screen: it should report ROM, bombd
      running, and the capability matrix should be green where §2 granted access
- [ ] Task Manager lists **other** processes (the unprivileged build cannot —
      this is the single clearest signal that integration worked)
- [ ] Reboot once and re-check: policy is recompiled at first boot after
      `precompiled_sepolicy` is dropped

---

## 9. Root-module delivery (alternative to 1–3)

```
module/
├── module.prop
├── service.sh          starts bombd late
├── post-fs-data.sh
├── sepolicy.rule       Bomb's types + minimal allows, loaded by magiskpolicy
├── system/bin/bombd
└── customize.sh
```

- [ ] `sepolicy.rule` generated from the same `sepolicy/` sources as the CIL —
      one definition, two delivery paths, with a check that both emit the same
      rule set
- [ ] `service.sh` starts `bombd` as its own user; it must never become a root
      shell for the UI
- [ ] APK installed normally (not priv-app); capabilities come from the daemon
- [ ] Losing the root manager must report `BackendUnavailable` visibly and
      disable privileged actions without inventing a third execution mode

---

## Known blockers

| # | Blocker | Effect |
| --- | --- | --- |
| 1 | NDK not installed on the build host | `bombd` cannot be compiled |
| 2 | `selinuxpatch.py` cannot declare new types | Bomb's domains silently dropped |
| 3 | No ROM platform key in this workspace | No system UID; some framework APIs out of reach |
| 4 | `bomb.sh selinux` subcommand is not implemented | No entry point for the policy work |
| 5 | Policy skip is silent when `secilc` is missing | A ROM can boot with none of Bomb's rules and look fine |
