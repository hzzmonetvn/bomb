# Research — Log control (Log Governor)

```text
References:
/home/hzzmonet/zk/ColorOS15-16_Cooldown_Disabler_v1.1_no_restart
  module.prop: id=coloros1516_cooldown_disabler, version=v1.1-no-restart,
               versionCode=11, author=ChatGPT
  License: none stated (no LICENSE file, nothing in README.txt or module.prop)

/home/hzzmonet/zk/hzz/bomb.sh                      — `init-rc` subcommand
/home/hzzmonet/zk/hzz/mods/bomb/universal/system/system/etc/init/hw/init.rc
                                                   — the stock init.rc shipped
```

Licence note: the ColorOS module carries **no licence grant**, so under **D16** it
is reimplement-only — nothing copied. That costs almost nothing here: the useful
content is four `setprop` names and one structural idea, all of which are platform
facts rather than authored expression.

---

## 1. What already exists, in two places

### 1.1 The ROM already has tier 2

`hzz/bomb.sh` `init-rc` appends a block to `system/etc/init/hw/init.rc` under the
`# BOMB INIT PATCH` marker, keyed on a property:

```
on property:sys.boot_completed=1 && property:persist.sys.zk.minimal_logging=1
    setprop persist.logd.kernel false
    setprop persist.logd.statistics false
    setprop persist.logd.size 64K
    setprop logd.logpersistd.enable false
    setprop persist.traced.enable 0
    setprop persist.traced_perf.enable 0
    setprop persist.logd.limit Off
    stop logcatd
    stop traced
    stop traced_probes
    start logd-reinit

on property:sys.boot_completed=1 && property:persist.sys.zk.minimal_logging=0
    setprop persist.logd.kernel ""
    ... (restores each to empty, i.e. ROM default)
    start logd-reinit
    start traced
    start traced_probes
```

The source comment states the intent: *"Keep logd/logcat alive so crash logs,
tombstones and bugreports still work."*

**This is a working, typed, property-driven tier 2 that Bomb should generalise
rather than reinvent** — it is already proven on this ROM, it has a restore
branch, and it deliberately stops short of killing logd.

Note it is **appended at build time by `bomb.sh`**, not present in the shipped
`mods/bomb/.../init.rc`, which is stock.

### 1.2 The ColorOS module is tier 3

`post-fs-data.sh`, log portion only (the rest of the module is OTA/telemetry/preload
and is out of scope):

```sh
for S in logd logd-auditctl logd-reinit logcatd logpersistd; do
  setprop ctl.stop "$S"
done
sleep 1

bind_file "$STUB_DAEMON" /system/bin/logd     # no-op sleeper
bind_file "$STUB_CMD"    /system/bin/logcat   # exit 0

for P in logd logcat logpersistd; do pkill -9 "$P"; killall -9 "$P"; done

setprop persist.logd.enable 0
setprop persist.logd.logpersistd.enable 0
setprop persist.logd.flowctrl.on 0
setprop persist.logd.size 0
```

The two stubs (`common/stub_cmd.sh`, `common/stub_daemon.sh`) are three lines and
a `while true; do sleep 86400; done` loop that first closes inherited fds.

The version history is the interesting part. README.txt:

> Changes from v1.0: … Avoids logd restart loop. Uses `ctl.stop` for
> logd/update_engine services. **Uses no-op executable stubs instead of
> `/dev/null` for init-managed binaries.**

So v1.0 bind-mounted `/dev/null` over `/system/bin/logd`; init started it, exec
failed instantly, init restarted it — a hot restart loop. v1.1 replaced the target
with a binary that starts successfully and then sleeps forever, which satisfies
init and costs nothing.

**Bomb implication:** if a restarter exists, `stop` alone is insufficient and the
stub is the fix — but the stub must be a *successful* no-op, never `/dev/null`.

Restore path (`uninstall.sh`): `umount` each bind target, re-`setprop` the four
properties to `1`, `ctl.start logd`. Its own comment says *"Reboot is still
recommended after uninstall"* — an honest admission that live restoration of a
killed logd is unreliable.

---

## 2. Is a restarter actually present? (decides whether tier 3 needs root)

> **VERIFIED on the real image (2026-08-08).** Provenance is now settled. The
> earlier reading came from `hzz/mods/bomb/.../init.rc`, an unwired copy that does
> not ship. It has since been re-checked against the partitions extracted from
> `ZKOS_NUWA_OS3.0.310.0.WMBCNXM_260808.zip` (sha256 `0f53e753…0acc66`) —
> `system_a/system/etc/init/hw/init.rc`, `system_a/system/etc/init/logd.rc`,
> `vendor_a/etc/init/hw/init.target.rc`, `vendor_a/etc/init/hw/init.qcom.rc`.
> The copy was indeed a faithful stock HyperOS `init.rc`; every line quoted below
> exists in the shipping image at the stated locations. **The conclusion is
> stronger than provisional now — see §2.1 for the result that changes the
> design.**

Checked in the real `system_a/system/etc/init/hw/init.rc` from the extracted image:

| Line | Context |
| --- | --- |
| `:552` | `# Start logd before any other services run to ensure we capture all of their logs.` → `start logd`, inside an early one-shot stage |
| `:915-916` | after `load_persist_props`: `start logd` / `start logd-reinit`, also a one-shot stage |

**Neither is a repeating property trigger.** `init` does not re-run a completed
action, so a runtime `stop logd` sticks until the next boot. AOSP itself does not
restart logd; the ColorOS module needed stubs because **OPlus vendor init adds its
own restarters**, not because the platform does.

### 2.1 The vendor check is done, and it comes back clean

Every `logd` reference across **both** partitions of the shipping image:

| Location | Trigger kind | Starts |
| --- | --- | --- |
| `system/etc/init/hw/init.rc:552` | one-shot early stage | `logd` |
| `system/etc/init/hw/init.rc:915-916` | one-shot, after `load_persist_props` | `logd`, `logd-reinit` |
| `vendor/etc/init/hw/init.target.rc:78` | inside `on init` — **one-shot** | `logd` |
| `system/etc/init/logd.rc:15` | `onrestart` on the logd service itself | sets `logd.ready false` |
| `system/etc/init/logd.rc:43` | `on property:sys.boot_completed=1` | `logd-auditctl` (not `logd`) |
| `system/etc/init/logd.rc:45-46` | `on property:persist.logd.audit.rate=*` | `logd-auditctl` (not `logd`) |
| `vendor/etc/init/hw/init.qcom.rc:858-883` | property triggers | `qlogd` / diag tcpdump — a separate Qualcomm logger |

**No repeating trigger restarts `logd` itself.** The only property-driven starts
target `logd-auditctl`, a `oneshot` helper. A runtime `stop logd` therefore sticks
until the next boot.

And `logd` is **not marked `critical`** (`logd.rc:1-16`), so stopping it cannot
trigger the reboot-on-critical-service-death path.

**Result: tier 3 is reachable in ROM mode with a single `stop logd` — no root, no
bind-mount stubs, no `/system/bin` overlay.** The ColorOS module needed stubs
because OPlus ships its own restarters; HyperOS on this device does not.

The requirement "tắt hẳn chỉ có root mới chạy" can therefore be **relaxed if
desired** — it is now a product decision, not a technical limit. Recommendation:
keep `OFF` behind an explicit confirmation regardless of mode (§3 is why), but drop
the root requirement, because a ROM-mode `stop` is strictly safer than a root-mode
bind-mount over `/system/bin`.

### 2.2 A knob the earlier design missed: `persist.logd.audit.rate`

`logd.rc:38-43`:

```
# Limit SELinux denial generation, defaulting to 5/second
service logd-auditctl /system/bin/auditctl -r ${persist.logd.audit.rate:-5}
    oneshot
    disabled
    user logd
    group logd
    capabilities AUDIT_CONTROL
```

re-run by `on property:persist.logd.audit.rate=*`, so changing that property takes
effect **immediately** — no reboot, no service juggling.

This matters because §3's central objection to tier 3 is that it destroys SELinux
denial visibility. There is an existing, supported, live-tunable middle ground:
**throttle** denial generation instead of killing the channel. `REDUCED` should
expose it, and it gives that tier real content beyond buffer sizes.

### 2.3 MIUI adds its own log buffer

`logd.rc` carries a Xiaomi block:

```
    # MIUI ADD: Stability_DecoupledSprd
    file /dev/ylog_buffer w
```

plus `chown system logd /dev/ylog_buffer` and `chmod 0660` under `on fs`. There is
a vendor log sink beside the AOSP buffers. Bomb's tiers do not touch it, and the
UI must not claim "logging off" while `ylog` keeps writing. Recorded as a known
gap rather than a silent one.

---

## 3. What tier 3 actually costs

Stated plainly because it conflicts with a project invariant.

**Lost:**

- **SELinux denials become invisible.** `logd` hosts the auditd component that
  consumes kernel audit records and surfaces `avc: denied` messages; `logd-auditctl`
  configures it. With logd stopped, denials are no longer readable — the kernel
  keeps generating records with no consumer. For a project whose first security
  rule is *"SELinux remains enforcing"* and whose whole `sepolicy/` workflow depends
  on reading denials, this removes the instrument used to verify the invariant.
- `logcat` in any form; bugreports become largely empty.
- Any Bomb feature that reads logs.

**Kept** (worth knowing, so the warning is accurate rather than alarmist):

- **tombstones** — written by `debuggerd` to `/data/tombstones`, not through logd;
- **ANR traces** — written to `/data/anr`;
- kernel messages in `/proc/kmsg` / `dmesg`, which logd only *mirrors*;
- `dumpsys`, which is a binder call, not a log read.

**Direct conflict to resolve:** `BOMB_PLAN.md` §5's Log Governor entry previously
asserted *"Security-relevant, fatal and SELinux-denial logging is never reduced."*
Tier 3 breaks that. The resolution taken (§5) is not to weaken the invariant but to
scope it: it holds for `DEFAULT` and `REDUCED`, and `OFF` is an explicit, root-only,
warned, user-chosen override of it.

---

## 4. Transition costs

Not all tier changes are live, and the plan must say which is which:

| From → To | Live? | Mechanism |
| --- | --- | --- |
| `DEFAULT` → `REDUCED` | yes | props + `stop logcatd/traced/traced_probes` + `start logd-reinit` to apply the buffer size |
| `REDUCED` → `DEFAULT` | yes | props reset to `""` + `start logd-reinit` + restart traced |
| `* ` → `OFF` | yes | `ctl.stop` chain (+ stubs where a restarter exists) |
| `OFF` → anything | **reboot required** | umount stubs and cleanly restart logd live is unreliable — upstream's own uninstall says *"Reboot is still recommended"*. Bomb states this in the UI rather than pretending otherwise |

`persist.logd.size` only takes effect after `logd --reinit`, which is what
`start logd-reinit` does. Setting the property without it is a silent no-op — a
mistake that is easy to make and hard to notice.

---

## 5. Bomb design

### Three tiers, one Bomb-owned property

```text
persist.sys.bomb.log.level = default | reduced | off
```

Bomb-owned namespace with its own `property_contexts` type (`bomb_prop`), settable
only by `bomb_app`. This is a **single typed property behind a typed API**, not a
generic `setSystemProperty(name, value)` — the distinction `CLAUDE.md` draws.

| Tier | Backend | Mode | Preserves |
| --- | --- | --- | --- |
| `DEFAULT` | none — Bomb sets nothing | any | everything |
| `REDUCED` | init.rc property trigger, generalised from §1.1 | **ROM** | logd alive: crash logs, tombstones, bugreports, **SELinux denials** |
| `OFF` | `ctl.stop` chain + no-op stub bind-mounts where a restarter exists | **root** (per requirement; see §2 for the possible ROM backend) | tombstones, ANR traces, kmsg |

The ROM-mode `REDUCED` block is the existing one, renamed off
`persist.sys.zk.minimal_logging` onto the Bomb property, and extended with an
explicit `off` branch that does the `stop` chain — so that if §2's verification
comes back clean, `OFF` gains a ROM backend by adding one trigger, with no
redesign.

### API

```aidl
LogCapabilities getLogCapabilities();   // ReduceSupported, DisableSupported, per mode
LogProfile      getLogProfile();        // current level + whether a reboot is pending
BombResult      setLogProfile(int level);
```

`level` is an enum (`DEFAULT`/`REDUCED`/`OFF`), validated in `:domain`. `OFF`
returns `Unsupported` when the root backend is absent rather than silently doing
nothing or half-applying.

### Mandatory safety behaviour

1. **`OFF` requires an explicit confirmation** that names what is lost — in
   particular SELinux denial visibility — not a generic "are you sure".
2. **Boot-time reconcile.** The engine compares the property against actual state
   (is logd running? are stubs mounted?) and converges, so a crash mid-transition
   cannot leave a half-applied tier.
3. **Recovery without the UI**: `setprop persist.sys.bomb.log.level default` +
   reboot restores, exactly as with the proxy subsystem's kill switch.
4. **Bomb refuses `OFF` while a Bomb SELinux bring-up flag is set** (the phases
   where new policy is being validated) — turning off the denial channel during
   the work that depends on reading denials is a foot-gun worth blocking.
5. `REDUCED` never touches `debuggerd`, tombstones, or ANR collection.

### Explicitly not done

- No stopping of `debuggerd` or tombstone collection at any tier.
- No `/dev/null` bind-mounts — a stub must exit or sleep *successfully* (§1.2).
- Nothing from the ColorOS module's OTA / telemetry / preload / package-disable
  behaviour: that is a different product, much of it is OPlus-specific, and
  disabling OTA on someone's phone is not log governance.
- No log *reading* / viewer. `CLAUDE.md` puts crash centre, ANR viewer and
  tombstone viewer explicitly out of scope; Log Governor governs volume, it does
  not display logs.

---

## 6. Testing

`:domain`, pure JVM:

- tier enum validation; `OFF` rejected without the capability;
- transition table: which transitions are live and which set `rebootPending`;
- reconcile logic: (property, logd running?, stubs mounted?) → the converging
  action, for every combination including the half-applied ones.

Device:

- `REDUCED`: `logcat` still works, buffer size actually shrank (verify with
  `logcat -g`, which is what proves `logd-reinit` ran), `avc` denials still visible;
- `OFF`: logd stopped and **stays** stopped across ~10 minutes and a screen-off
  cycle — this is the test that detects a vendor restarter (§2);
- `OFF` → `DEFAULT` + reboot returns the device to a working `logcat`;
- tombstones still produced under `OFF` (force a native crash and check
  `/data/tombstones`);
- no boot loop under any tier, verified from a cold boot rather than a live toggle.
