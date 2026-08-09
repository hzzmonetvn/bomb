# Patching Bomb into the ROM

How Bomb becomes an integrated component of the HyperOS build produced by `hzz/`.

Companions: [`ARCHITECTURE.md`](./ARCHITECTURE.md),
[`BOMB_PLAN.md`](./BOMB_PLAN.md),
[`SYSTEM_PATCHES.md`](./SYSTEM_PATCHES.md) (per-feature patch contract),
[`../rom/README.md`](../rom/README.md) (the artifact manifest this document
explains).

**Revised 2026-08-09.** The previous revision described a design that no longer
exists — it had the app writing properties directly, put `bombd` in `/system`,
and listed thirteen privileged permissions. All three are wrong now. Where this
document and the files disagree, **the files are correct**; they are the thing
that ships.

---

## 0. What exists today

| Artifact | Path | State |
| --- | --- | --- |
| APK | built by `:app-preview` | release, R8, debug-signed |
| Privileged service | `system-service/` → `BombCoreService`, `android:process=":core"` | implemented, reads real, **writes route through bombd** |
| Daemon | `native/bombd/bombd.c` | implemented; two defects found and fixed (§7), **never compiled** — blocker 1 |
| init | `rom/system_ext/etc/init/bomb.rc` | implemented |
| SELinux | `sepolicy/private/` (6 files) | implemented; narrowed in this revision (§7.1), **never compiled** — blocker 2 |
| Permissions | `permissions/privapp-permissions-…xml` | 3 permissions, each justified |
| Property defaults | `rom/system_ext/etc/bomb/build.prop.fragment` | implemented |
| Framework markers | `rom/framework/…prop.fragment` | both `0`, correctly fail-closed |
| Framework patches | — | **not written**; markers stay `0` |

---

## 1. The control plane

The single most important thing to understand before touching any file: **the
app never writes a system property, and never runs a command.**

```
BombCoreService            validates caller, arguments, capability
      │  AF_UNIX, fixed grammar: PING | LOG <enum> <int> | MEM <field> <int>
      ▼
bombd                      re-validates everything, writes ONE request property
      │  persist.sys.bomb.log.request / .memory.*
      ▼
init property trigger      performs the fixed service and sysfs operations
      ▼
status property            persist.sys.bomb.log.level — read back by the app
```

Why this shape rather than letting the app set properties:

- Android SELinux does not give app domains generic `property_service set`. An
  app-writable property type would have to be created, and anything that could
  write one could write all of them.
- The privileged operations here are `stop logd`, `write /proc/sys/vm/...` —
  **init already does exactly these, declaratively, from a trigger.** Adding a
  daemon that shells out would re-implement init badly.
- The grammar is closed. `bombd` accepts three verbs; there is no path from a
  compromised app process to an arbitrary property, file or command.

Command and status properties are **separate SELinux types**
(`bomb_control_prop`, `bomb_status_prop`) so that a compromised app cannot forge
"applied" state or clear the SELinux bring-up guard.

---

## 2. Where the artifacts go

Bomb lives in **`system_ext`**, not `system`. That is not cosmetic: the ROM
already carries `system_ext_sepolicy.cil`, `system_ext_property_contexts`,
`system_ext_seapp_contexts` and `system_ext_file_contexts`, and policy must live
on the same partition as the thing it labels.

| Source | Destination |
| --- | --- |
| release APK | `/system_ext/priv-app/Bomb/Bomb.apk` |
| built `bombd` | `/system_ext/bin/bombd` (mode 0755, `root:shell`) |
| `permissions/privapp-permissions-*.xml` | `/system_ext/etc/permissions/` |
| `rom/system_ext/etc/init/bomb.rc` | `/system_ext/etc/init/bomb.rc` |
| `rom/system_ext/etc/sysconfig/*.xml` | `/system_ext/etc/sysconfig/` |
| `build.prop.fragment` ×2 | append to `/system_ext/build.prop` |
| `sepolicy/private/*.te` | append to `system_ext_sepolicy.cil` (compiled, §3) |
| `sepolicy/private/property_contexts` | append to `system_ext_property_contexts` |
| `sepolicy/private/seapp_contexts` | append to `system_ext_seapp_contexts` |
| `sepolicy/private/file_contexts` | append to `system_ext_file_contexts` |

The permission allowlist **must** be on the same partition as the APK, or the
platform ignores it.

---

## 3. SELinux: how policy actually lands on this ROM

### 3.1 Verified: this ROM already recompiles policy at boot

Measured on the extracted image (`ZKOS_NUWA_OS3.0.310.0.WMBCNXM_260808`):

```
                source .sha256                    odm/precompiled_sepolicy.*.sha256
plat        2bafc5c3bf3e847a68f7…            36020cb9ac40874c5deb…    LỆCH
product     0802bd05607e8b62003f…            f107e91327e0f279f019…    LỆCH
system_ext  8928f2072d72c21cd0a4…            75fd7239e1e747def7a7…    LỆCH
```

All three disagree. Android uses `odm/etc/selinux/precompiled_sepolicy` **only
when every hash matches**; otherwise init compiles the CIL sources on device with
`/system/bin/secilc` (present — verified in `system_a/system/bin/`).

**So this ROM is already on the runtime-compile path**, and additions to
`system_ext_sepolicy.cil` are picked up at the next boot with no further work:

- no need to regenerate `precompiled_sepolicy`
- no need to update any `.sha256`
- cost is a few seconds of boot time, already being paid

This is considerably simpler than the previous revision assumed. It is also the
one finding here that most deserves re-checking on a booted device — a hash
comparison on an unpacked image proves the inputs differ, not that the compile
succeeds.

### 3.2 What Bomb's policy actually contains

Six files, deliberately small:

| File | Contents |
| --- | --- |
| `bomb_app.te` | the app domain; **one** capability — connect to bombd |
| `bombd.te` | daemon domain, init transition, socket, control props |
| `bomb_property.te` | two property types, command vs status |
| `property_contexts` | exact names with `enum`/`int`/`bool` validation |
| `seapp_contexts` | exact package match, not "every priv-app" |
| `file_contexts` | `bombd` entrypoint label |

`bomb_app` has **no** `property_service`, sysfs or shell permission. The whole
privileged surface of the app domain is a single `connectto`.

The framework patches (M14/M15) need no policy at all — they run inside
`system_server`, in its existing domain. Worth stating when those are reviewed:
it is a real advantage of patching over hooking.

### 3.3 Rule discipline

For every rule added: name the source domain, target type, object class, exact
permission, and the Bomb feature that requires it. If any of the five cannot be
named, the rule is not ready. A denial during bring-up is information about what
a feature does — not a prompt to run `audit2allow`.

Enforcing stays on, including during bring-up. Which is why:

> **Never run Log Governor tier `OFF` while bringing up policy.** It stops
> `logd`, and AVC denials are the instrument this whole section is verified with.
> `bomb.rc` enforces this — the `OFF` trigger requires
> `persist.sys.bomb.selinux_bringup=0`, and there is a second trigger that resets
> the request to `idle` when the guard is `1`.

---

## 4. Properties

| Property | Type | Written by | Meaning |
| --- | --- | --- | --- |
| `ro.bomb.integrated` | status | build.prop | ROM declares Bomb integrated |
| `persist.sys.zk.bomb` | status | legacy | earlier marker, read-only compatibility |
| `persist.sys.bomb.log.request` | control | bombd | the requested tier |
| `persist.sys.bomb.log.audit_rate` | control | bombd | SELinux denial cap |
| `persist.sys.bomb.log.level` | status | init | the tier actually applied |
| `persist.sys.bomb.memory.swappiness` | control | bombd | 0–200 |
| `persist.sys.bomb.memory.page_cluster` | control | bombd | 0–6 |
| `persist.sys.bomb.selinux_bringup` | status | build.prop | guard for tier `OFF` |
| `ro.bomb.framework.visibility` | status | build.prop | **stays 0** until patched |
| `ro.bomb.framework.settings` | status | build.prop | **stays 0** until patched |

`build.prop.fragment` ships `persist.sys.bomb.selinux_bringup=0` explicitly, and
that matters more than it looks: init property triggers match the **exact**
value, and an unset property is the empty string — so `on property:…=0` would
never fire if the default were merely omitted. The tier `OFF` path would be
silently unreachable.

`ro.bomb.integrated=1` is what puts the app into ROM mode. It is a claim by the
image builder — the only party who can know whether placement, allowlist and
policy were all applied. It decides **which mode is reported**, and nothing else:
every capability is still probed, so an image that sets the flag with an
incomplete integration reports the missing pieces instead of claiming them.

---

## 5. Privileged permissions

Three, each traceable to an implemented feature:

| Permission | Feature |
| --- | --- |
| `FORCE_STOP_PACKAGES` | force stop, which must precede disabling a package |
| `SUSPEND_APPS` | `DEEP_FREEZE`'s preferred mechanism (D8) |
| `CHANGE_COMPONENT_ENABLED_STATE` | `FreezeMode.DISABLED`, Component Explorer |

The XML records what is **deliberately absent** and why — `MANAGE_USERS`,
`WRITE_SECURE_SETTINGS`, `INTERACT_ACROSS_USERS`, the Task Manager group. They
arrive with the feature that needs them, not before.

Measured on this ROM: `ro.control_privapp_permissions` is **empty**, so the
allowlist is **not enforced** and a priv-app receives whatever its manifest
requests. Two consequences:

1. Manifest discipline matters more than the XML here. An over-broad manifest
   request is granted silently.
2. Ship the XML anyway. A ROM update can turn enforcement on, and if the manifest
   ever requests something the XML omits while enforcement is `enforce`, the
   device **boot-loops**. A checked cross-reference of manifest against allowlist
   belongs in the build.

---

## 6. Integrating into `hzz`

**Done 2026-08-09.** A `build.sh` run now applies Bomb without manual steps.

Note the peer session that owns `hzz` had just removed the entire `OPTION_*`
system, so there is deliberately **no `OPTION_BOMB`** — Bomb applies
unconditionally like the other bomb mods. Re-adding a toggle would recreate what
was just deleted.

| What | Where | Mechanism |
| --- | --- | --- |
| APK, `bombd`, `bomb.rc`, permissions, sysconfig | `mods/bomb/universal/system_ext/…` | already copied by `build.sh:148` — no code change |
| 6 properties | `bomb.sh` `props-add`, `BOMB_SYSTEM_EXT_PROPS` heredoc | existing dedupe-safe `set_prop` |
| CIL + seapp_contexts + property_contexts | `bomb.sh` `selinux` case | new, marker-guarded, idempotent |
| `bombd_exec` label | `selinuxpatch.py` `HARDCODED_RULES` | regenerated on every run, so it must live here |

Three things worth recording because they are easy to get wrong:

- **`mods/bomb/universal`, not `mods/zk_mods/universal`.** Both technically work;
  the first is the one that exists for this purpose. Debloat runs at line 89,
  before the copy at 148, and carries no `Bomb*` pattern — so it cannot remove
  these files.
- **`bomb.sh selinux` had no case body.** It was in the usage string, so calling
  it fell through to `*)` and exited 1. That latent bug is now the home for the
  policy work rather than something to route around.
- **The label path is `/system_ext/bin/bombd`, not `/system_ext_a/…`.** The
  build pipeline names partitions without the slot suffix (`part_name` is
  `system_ext`), so a `_a` in the regex matches nothing. My manual patch needed
  the suffix because I had extracted with slot-suffixed directory names; the two
  contexts differ and only one is the build path.

`fs_config` needs no hand-seeding: `selinuxpatch.py` gives anything under `bin/`
`0 2000 0755`, which is exactly what `bombd` needs. Only the non-default label
has to be declared.

### Verified

```
bomb.sh selinux on a clean tree   → 3 files patched
run again                         → "already present" (idempotent)
compile the result                → exit 0, 1 626 893 B
```

Byte-identical to the manually patched policy, which is the point: the automated
path and the hand path produce the same thing.

### What the compile does *not* cover

`secilc` validates the CIL. It never reads `property_contexts`, so a Bomb
property shipped without a label compiles cleanly and then fails on device: the
property falls back to `default_prop`, `bombd` is denied writing it, and the
feature does nothing — no crash, no error, no log line pointing at the cause.

`bomb.sh selinux` therefore ends with its own check, cross-referencing every
`ro.bomb.*` / `persist.sys.bomb.*` in `build.prop` against the labels. Verified
by deleting a label and confirming it reports:

```
> WARNING: ro.bomb.integrated has no property_contexts label
```

That test mattered: the first version of the check passed while a label was
missing, because it ran before the properties were in `build.prop` and so had
nothing to inspect. A check that never fires is worse than no check — it reads
as evidence.

### The base-ROM dependency

Bomb's CIL references types owned by the base ROM — `appdomain_tmpfs`,
`privapp_data_file`, `app_api_service`, `system_linker_exec`, `property_socket`
and others. On this ROM they all resolve. On a different base, a missing type
would surface **when init compiles policy at boot**, not during the build: a
bootloop with no build-time signal. This is the same exposure the existing
`zk_exec` patch carries, so it is a known property of the approach rather than
something new. The host-side compile in this section is the mitigation — run it
against any new base before flashing.

The 9.66 GB `super.img` built by hand is **not** an input to `build.sh` — that
script extracts and rebuilds super itself. It exists only to flash and test the
current state.

---

## 7. Defects found in the current implementation

Found by reading `bombd.c` against `bombd.te`. Both are **fixed in source in this
revision**, and both are **unverified**: blocker 1 means the daemon cannot be
compiled or run yet. They pass `gcc -fsyntax-only` and nothing more.

### 7.1 `bombd` cannot read the peer's `/proc` entry — every command is refused

`peer_process_matches()` opens `/proc/<pid>/cmdline`. The policy grants:

```
allow bombd proc:dir search;
allow bombd proc:file { open read getattr };
```

But procfs **pid** inodes do not carry the `proc` type — the kernel labels them
with the *task's* context (`security_task_to_inode`). Reading a `bomb_app`
process's `cmdline` therefore needs `bomb_app:file read`, which is not granted.

Effect: `authenticated_peer()` returns false for every connection, every command
answers `ERR peer`, and the only clue is an AVC denial — which is invisible if
someone has run tier `OFF`.

**Fixed by using `SO_PEERSEC` instead.** It returns the peer's SELinux context straight
from the socket. That removes the `/proc` access entirely, and removes a TOCTOU
at the same time — the pid from `SO_PEERCRED` can be recycled between the
credential read and the `/proc` read, whereas the peer context is captured at
connect.

### 7.2 One silent client wedges the daemon permanently

`handle_client()` does a blocking `read()` with no timeout, and `main()` accepts
sequentially. A client that connects and never writes blocks the loop forever;
no further request is served until `bombd` is restarted.

The Kotlin client sets `soTimeout`, but that is the *client's* read timeout and
does nothing for the server.

**Fixed** with `SO_RCVTIMEO` and `SO_SNDTIMEO` on the accepted fd, set before the peer check so the reply path is bounded too. `restart_period 5`
in `bomb.rc` limits the damage only if the daemon actually exits, which in this
case it does not.

### 7.3 Two tier implementations that can drift

The ROM applies tiers through `bomb.rc`; the root module applies them through
`module/common.sh`. They already differ: `common.sh` snapshots the original
property values and restores exactly those, while `bomb.rc`'s `default` branch
resets to `""`.

For the ROM that difference is **defensible** — `build.prop` re-supplies the
values at every boot, so `""` plus an explicit `start` is equivalent. But it is
defensible by accident right now. It should be stated in both files, and the two
tier tables should be generated from one source or checked against each other.

---

## 7b. Applying it: `rom/apply.sh`

```sh
rom/apply.sh <unpacked-dir>
```

Idempotent — every step checks its own marker, because these files are appended
to rather than replaced and a doubled `seapp_contexts` line is a boot failure
that looks like a policy bug.

Three things it got wrong on the first run, all found by verifying rather than
by reading:

1. **CIL comments start with `;`, not `#`.** The marker `# BOMB` made the whole
   policy fail to compile with *"Symbol not inside parenthesis"*. The other three
   context files do use `#`, so the comment character is now per-file.
2. **`fs_config`/`file_contexts` key on the staging directory name, slot suffix
   included** — `system_ext_a/bin/bombd`, not `system_ext/bin/bombd`. Entries
   with the mount-point prefix match nothing, and `mkfs` then fails on the first
   file it cannot find a config for.
3. **`mkfs --mount-point` must match that same prefix** (`/system_ext_a`), since
   it is what builds the lookup key. With `/system_ext` it fails on
   `system_ext/apex`.

Run `selinuxpatch.py <part-dir> <fs_config> <file_contexts>` **before** `mkfs`,
as `build.sh` does: the extracted `fs_config` is incomplete relative to the tree
(it has no `apex` entry), and that script fills the gaps. Files under `bin/`
correctly get `0 2000 0755` from it, so only `bombd`'s non-default
`bombd_exec` label has to be seeded by hand.

### Verified on the extracted image

| Step | Result |
| --- | --- |
| Host `secilc` built | SELinux userspace 3.7 |
| Baseline policy compile | exit 0, 1 623 243 B |
| Compile with `bomb.cil` | exit 0, **1 626 893 B** (+3 650) |
| Compile from the *patched image* files | exit 0 |
| `bombd` cross-compile | 7 648 B arm64 PIE, no warnings |
| `system_ext` repack | 843 542 528 B (was 841 912 320) |

The policy compiling is the meaningful one: it proves the new domain satisfies
every neverallow in this exact ROM's policy, which is the failure that would
otherwise show up as a device that does not boot.

---

## 8. Verification after flashing

In order; each step catches a different failure.

```sh
adb shell getenforce                     # Enforcing — if Permissive, stop
adb shell pm list packages -s | grep zkbomb
adb shell pm path com.hzzmonet.zkbomb    # must be /system_ext/priv-app/...
adb shell getprop ro.bomb.integrated     # 1
adb shell ps -AZ | grep zkbomb           # u:r:bomb_app:s0 — not priv_app
adb shell ps -AZ | grep bombd            # u:r:bombd:s0
adb shell ls -Z /system_ext/bin/bombd    # u:object_r:bombd_exec:s0
adb shell dumpsys package com.hzzmonet.zkbomb | grep -E 'SUSPEND_APPS|FORCE_STOP'
adb shell dmesg | grep -i 'avc.*denied' | grep -iE 'bomb'
```

The two that actually prove integration rather than placement:

- **Bomb → More → Mode** reports `ROM mode`, service **Connected**, and Freeze
  as **Supported** rather than *Needs root*.
- Setting a log tier from the app changes `persist.sys.bomb.log.level`, and
  `logcat -g` shows the buffer actually shrank. A tier that "applies" without the
  buffer changing means `logd-reinit` did not run.

---

## 9. Blockers

| # | Blocker | State |
| --- | --- | --- |
| 1 | `bombd` cannot be compiled | **Resolved 2026-08-09.** NDK completed via `sdkmanager`; built with clang directly (no `cmake` on host, and one C file does not need it). 7 648 B arm64 PIE, `-Wall -Wextra -Werror` clean, links only `libc`/`libdl` |
| 2 | `selinuxpatch.py` cannot declare types | **Worked around.** Bomb ships hand-written CIL (`sepolicy/cil/bomb.cil`) appended directly to `system_ext_sepolicy.cil` by `rom/apply.sh`, so the patcher's allow-only path is not on the critical path. Extending it remains desirable for other rules |
| 3 | No ROM platform key | Open, and by design not required |
| 4 | `bomb.sh selinux` unimplemented | **Open** |
| 5 | Silent policy skip when compile unavailable | **Open** |
| 6 | `bombd` peer auth broken (§7.1) | **Fixed and compiled.** Runtime behaviour still unverified — needs a booted device |
| 7 | `bombd` DoS on silent client (§7.2) | **Fixed and compiled.** Same caveat |
| 8 | No host `secilc` | **Resolved.** Built from SELinux userspace 3.7 into the scratchpad; the full ROM policy now compiles on the host, with and without Bomb's CIL |
| 9 | Framework patches unwritten | Open by design — markers stay `0` |

Blockers **1 and 2** are the ones that stop a first working ROM: without a
compiler there is no `bombd`, and without type declarations the policy that lets
anything reach it is dropped. 4 and 5 make failure silent, which is worse than
stopping.

6 and 7 were found and fixed in this revision — see §7 — but the fixes are
**unverified**: they cannot be compiled or run until blocker 1 is cleared. They
pass a host syntax check and nothing more.
