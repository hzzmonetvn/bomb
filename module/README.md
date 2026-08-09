# Bomb Root Backend

Magisk / KernelSU module. Executes the log-tier and swap policy that `:domain`
decides and that its JUnit tests cover — `LogTransitionPlanner` (33 tests) and
`ZramConfigValidator` (24 tests). Where this module and those tests could
disagree, **the tests are the specification and this module is the bug.**

## Install

Flash in Magisk or KernelSU, reboot. Install-time checks print what the device
looks like and refuse if init service properties are unreadable.

**Installing changes nothing on its own.** The tier starts at `default`, so a
reboot straight after installing leaves the device exactly as it was.

## Use

```
bombctl status                    # logging + memory state
bombctl log reduced               # shrink log volume, keep logd alive
bombctl log off                   # stop logd  (see the warning below)
bombctl log default               # put back the captured values
bombctl swappiness 60
bombctl page-cluster 0
bombctl restore
```

## The three tiers

| Tier | What happens | What survives |
| --- | --- | --- |
| `default` | nothing is set | everything |
| `reduced` | 64K buffers, kernel log and statistics off, `logcatd`/`traced`/`traced_probes` stopped | **logd stays alive** — crash logs, bugreports and SELinux denials all still work |
| `off` | everything above, plus `logd` itself stopped | tombstones, ANR traces, kmsg |

`reduced` also exposes `persist.logd.audit.rate` — the SELinux denial cap, 5/sec
by default. That is a middle ground between every denial and none, and it is
live: `logd.rc` re-runs `logd-auditctl` whenever the property changes.

## Read this before using `off`

**SELinux denials become invisible.** This project's own rule is that SELinux
stays enforcing, and denials are how that is verified. Turning off the channel
you check compliance through, during the work that depends on reading it, is a
foot-gun — do not use `off` while bringing up new policy.

**Leaving `off` needs a reboot.** Restarting a killed `logd` cleanly at runtime is
unreliable. `bombctl log default` while at `off` records the intent and applies
it on the next boot; it does not pretend to have done it now.

**MIUI keeps logging anyway.** `/dev/ylog_buffer` is a vendor sink no tier here
touches. `bombctl status` says so when it is present, and the module never claims
"logging off" while it exists.

## What this module refuses to do

**Resize zram, or change its compression algorithm.** Both require the device to
be empty, which means `swapoff`, which faults every swapped page back into RAM at
once — 5.28 GB in the sample measured for `docs/research/ZRAM.md`. On a loaded
phone that is an immediate OOM storm or a multi-minute freeze. There is no flag
to force it.

**Write `sys_critical` swappiness.** That cgroup sits at 0 and pins ueventd,
vold, netd, surfaceflinger and servicemanager out of swap. `bombctl status` shows
its value and labels it as never written.

**Run arbitrary commands.** No `bombctl exec`, no `bombctl setprop`, no verb
taking a filesystem path. Same rule as the Binder API, for the same reason: one
generic escape hatch makes every other control here decorative.

## Two implementation details worth knowing

**`persist.logd.size` does nothing until `logd --reinit` runs.** Setting it alone
is a silent no-op — the buffer keeps its old size and nothing reports a problem.
Every path that sets it here follows with `ctl.start logd-reinit`.

**Restore uses captured values, not defaults.** The original values are snapshotted
once, before the first change, into `/data/adb/bomb/snapshot.conf`. There is no
safe guess for `persist.traced.enable`: writing `1` enables Perfetto on a device
that shipped with it off, and writing `""` leaves the init trigger unable to start
`traced` on the next boot.

## Recovery

If the device is in a state you do not want and the app is unavailable:

```
setprop persist.sys.bomb.log.level default
reboot
```

The boot script reconciles to whatever the tier says. Removing the module runs
`uninstall.sh`, which restores the snapshot.

## Files

```
/data/adb/bomb/config.conf     what you asked for
/data/adb/bomb/snapshot.conf   what the device looked like first
/data/adb/bomb/bomb.log        what actually happened
```

The log is kept on uninstall on purpose — if the module is being removed because
something went wrong, its record is the one thing worth keeping.
