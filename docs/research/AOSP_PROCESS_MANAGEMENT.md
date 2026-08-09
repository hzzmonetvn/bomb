# Research — AOSP process management (AM)

```text
Reference:
platform/frameworks/base @ 1cdfff555f4a21f71ccc978290e2e212e2f8b168
License: Apache-2.0

Relevant source:
services/core/java/com/android/server/am/ActivityManagerService.java  (19483 lines)
services/core/java/com/android/server/am/ProcessList.java             (5966)
services/core/java/com/android/server/am/ProcessRecord.java           (1722)
services/core/java/com/android/server/am/PhantomProcessList.java      (620)
services/core/java/com/android/server/am/CachedAppOptimizer.java      (2686)
services/core/java/com/android/server/am/Freezer.java
services/core/java/com/android/server/am/OomAdjuster.java             (4392)
```

Scope note: `core/java/android/os/` is **not** in this sparse checkout, so
`android.os.Process.setProcessFrozen`'s own permission/SELinux requirements were
not verified here. Everything below is verified in the files listed above.

---

## 1. Force stop — what Bomb's Task Manager action actually is

`ActivityManagerService.forceStopPackage(String, int)` — line 3792, delegating to
the 4-arg `forceStopPackage` at line 3801:

```java
if (checkCallingPermission(android.Manifest.permission.FORCE_STOP_PACKAGES)
        != PackageManager.PERMISSION_GRANTED) {
    throw new SecurityException(msg);   // :3803-3811
}
...
if (getPackageManagerInternal().isPackageStateProtected(packageName, user)) {
    Slog.w(TAG, "Ignoring request to force stop protected package " ...);
    return;                              // :3824-3830
}
```

**Bomb implications:**

1. `FORCE_STOP_PACKAGES` is a signature|privileged permission — a priv-app in
   `system_ext` can hold it. Force stop is therefore reachable in ROM mode without
   the platform key. It belongs in Bomb's `privapp-permissions` allowlist with this
   citation as its justification.
2. The platform **silently ignores** protected packages (device-owner/policy
   protected). A `void` return means Bomb cannot distinguish "stopped" from
   "refused" by return value. Bomb's `BombResult` must be derived from observed
   state (process gone / still present after the call), not from the call
   returning — otherwise Bomb reports success for a no-op.
3. `forceStopPackageEvenWhenStopping` (:3797) passes `FLAG_OR_STOPPED`; the
   distinction matters for already-stopped packages and is a separate typed
   operation, not a hidden flag on the same API.

---

## 2. Framework spawn policy — the single funnel

All application process creation converges on:

```java
// ProcessList.java:2650
ProcessRecord startProcessLocked(String processName, ApplicationInfo info,
        boolean knownToBeDead, int intentFlags, HostingRecord hostingRecord,
        int zygotePolicyFlags, boolean allowWhileBooting, boolean isolated, int isolatedUid,
        boolean isSdkSandbox, int sdkSandboxUid, String sdkSandboxClientAppPackage,
        String abiOverride, String entryPoint, String[] entryPointArgs, Runnable crashHandler)
```

Observed behaviour in the first 60 lines of that method:

- an existing bad process is silently refused when the start comes from the
  background (`intentFlags & Intent.FLAG_FROM_BACKGROUND` + `mAppErrors.isBadProcess`,
  :2664-2671) — **AOSP already has a "refuse to spawn" precedent returning `null`**,
  and callers tolerate it;
- explicit user-initiated starts clear the bad-process state (:2672-2687);
- `HostingRecord` carries *why* the process is being started (broadcast, service,
  provider, activity, …) — this is the discriminator Bomb's Process Control needs
  to distinguish "user launched the app" from "a receiver woke :push".

**Bomb implication:** framework spawn policy is a single, well-defined patch point
with an existing refusal contract. `HostingRecord` + `ApplicationInfo.processName`
gives (target process, reason, caller) — enough for a typed policy without any
kill-on-spawn loop. This confirms master plan §9's preference order:

```text
Component blocking  >  Framework spawn policy  >  Runtime kill-on-spawn
```

Kill-on-spawn is provably the worst option here: by the time the process exists,
the zygote fork, the ART startup and the binder registration have already been paid
for. Bomb should not implement it at all while the other two exist.

Master plan §37 Phase 6 says framework spawn blocking must not be implemented until
this flow is documented. It is now documented — but note it is a `services.jar`
patch, i.e. **ROM mode only**.

---

## 3. Freeze — the platform mechanism, and the trap

`CachedAppOptimizer.freezeProcess(ProcessRecord)` — CachedAppOptimizer.java:2292.
The essential sequence (:2337-2361):

```java
// Freeze binder interface before the process, to flush any
// transactions that might be pending.
if (mFreezer.freezeBinder(pid, true, FREEZE_BINDER_TIMEOUT_MS) != 0) {
    handleBinderFreezerFailure(proc, "outstanding txns");
    return;
}
...
mFreezer.setProcessFrozen(pid, proc.uid, true);
opt.setFrozen(true);
mFrozenProcesses.put(pid, proc);
```

`Freezer` (Freezer.java:30) is a thin wrapper:

| Method | Line | Backing |
| --- | --- | --- |
| `setProcessFrozen(pid, uid, frozen)` | :39 | `android.os.Process.setProcessFrozen` |
| `freezeBinder(pid, freeze, timeoutMs)` | :59 | `nativeFreezeBinder` — returns 0 or `-EAGAIN` for pending transactions |
| `getBinderFreezeInfo(pid)` | :70 | `nativeGetBinderFreezeInfo` |
| `isFreezerSupported()` | :78 | `nativeIsFreezerSupported` |

Freezer availability is probed, not assumed: `mUseFreezer = mFreezer.isFreezerSupported()`
(CachedAppOptimizer.java:1070), exposed as `useFreezer()` (:729).

**The trap, stated explicitly because it is the most likely Bomb bug:**
freezing the cgroup without first freezing binder loses or stalls in-flight binder
transactions. AOSP freezes binder first *and* treats failure as fatal for that
process — `handleBinderFreezerFailure` ultimately kills with
`ApplicationExitInfo.REASON_FREEZER` / `SUBREASON_FREEZER_BINDER_IOCTL` (:2345-2351).
A `bombd` that writes `cgroup.freeze` directly, skipping the binder step, will
produce exactly the ANRs and "app not responding to intents" reports that make
freeze features feel unreliable.

**Bomb design consequences:**

1. `DeepFreeze` capability = `Freezer.isFreezerSupported()` equivalent probe. If it
   probes false, `BombCapabilities.DeepFreeze` is unsupported — no substitute, no
   silent fallback to force-stop.
2. Bomb must not fight `CachedAppOptimizer`. The platform freezes cached apps on
   its own schedule driven by `OomAdjuster`; two independent freezers on the same
   pid will race on `mFrozenProcesses` state. The correct Bomb design is to express
   intent through the **framework's** freezer (a `services.jar` hook that marks a
   process as Bomb-frozen and lets `CachedAppOptimizer` own the mechanism), not to
   duplicate the mechanism in `bombd`.
3. `opt.shouldNotFreeze()` (:2315) and `mFreezerOverride` (:2306) are existing
   platform veto points — Bomb's exclusion list should feed the same concept rather
   than inventing a parallel one.
4. Freeze state is **per-pid**, and `UidRecord.setFrozen` only flips when
   `areAllProcessesFrozen()` (:2371-2375). Bomb's per-*app* freeze must therefore
   resolve to the full process set of that app (see §4) or it will report an app as
   frozen while `:push` still runs.

---

## 4. Process discovery and native children

`PhantomProcessList` is the authoritative technique for the "native child process"
class that master plan §9 requires Bomb to classify:

- child pids are read from the app's **cgroup procs file**, path resolved natively:
  `getCgroupFilePath(uid, pid)` → `nativeGetCgroupProcsPath(uid, pid)`
  (PhantomProcessList.java:204-206), read incrementally with a cached fd
  (`mCgroupProcsFds`, :190-201);
- names come from `/proc/<pid>/cmdline` read with a NUL terminator and basename
  extraction — `getProcessName(int pid)` (:209-223);
- `getOrCreatePhantomProcessIfNeededLocked` (:278) first calls `isAppProcess(pid)`
  and returns `null` for real app processes, i.e. phantom == "in the app's cgroup
  but not an AM-managed process";
- app-zygote forks are distinguished by `ProcessRecord.appZygote`
  (`addChildPidLocked`, :305-315).

**Bomb implications:**

1. Bomb's process classification (`MAIN` / `APP_COMPONENT` / `ISOLATED` /
   `WEBVIEW` / `NATIVE_CHILD` / `UNKNOWN`) maps onto real platform distinctions:
   AM-known process vs cgroup-only phantom vs `appZygote` fork vs isolated uid.
   None of it needs to be guessed from process-name heuristics.
2. Reading the app cgroup procs file is far cheaper than scanning all of `/proc`
   for a parent chain, and it is the same source AM itself trusts.
3. Master plan §9's rule "a native `.so` loaded into a process is not itself a
   process" is confirmed structurally — phantom processes are pids in
   `cgroup.procs`; a loaded library never appears there.
4. Killing a phantom process kills a *pid*, not a package. Bomb's Task Manager must
   present that distinction rather than implying package-level action.

---

## 5. What Bomb reads for the Task Manager

For Phase 3, Bomb reads `/proc` itself (priv-app domain + narrow SELinux rules) and
does **not** need AM internals. This research changes only two things:

- **CPU%** must be a delta between two samples of `/proc/<pid>/stat` utime+stime
  against a delta of `/proc/stat` total jiffies. Counter reset (pid reuse) and
  32-bit overflow both need handling — the master plan already lists these as unit
  test targets, and they are real: `ProcessCpuTracker` in AOSP exists precisely
  because naive sampling is wrong.
- **Foreground/background** is `OomAdjuster` state (`ProcessRecord.mState.getCurAdj()`,
  referenced at CachedAppOptimizer.java:2318), not something derivable from `/proc`.
  Bomb gets it from `ActivityManager.RunningAppProcessInfo.importance` (public API,
  no patch) and should mark it as such rather than pretending `/proc` provides it.

---

## 6. Mode split (decisive for Phase 5/6 planning)

| Capability | Mechanism | ROM mode | Root mode |
| --- | --- | --- | --- |
| Force stop | `FORCE_STOP_PACKAGES` priv permission | yes | yes (via shell-equivalent typed op) |
| Soft freeze / background restrict | AppOps + `ActivityManager` APIs | yes | yes |
| Deep freeze (cgroup freezer) | `CachedAppOptimizer` hook | yes | mechanism reachable, but racing the platform freezer — **not recommended** |
| Framework spawn policy | `ProcessList.startProcessLocked` patch | yes | **no** |
| Phantom process enumeration | app cgroup procs file | yes | yes |

`BombCapabilities` must express this per mode. Reporting `FrameworkProcessControl`
as available in root mode would be a fabricated capability.

---

## 7. Testing strategy

Pure-JVM (`:domain`):

- CPU delta calculator: normal delta, counter reset → 0 not negative, 32-bit
  overflow, zero elapsed time → no division by zero, invalid/short `/proc` lines;
- process classification from (pid, uid, cgroup membership, appZygote flag,
  isolated uid range) fixtures;
- freeze target resolution: package → full process set, including `:remote`
  processes, and refusal for excluded/critical packages.

Fixture-based (`:system-service`, JVM):

- `/proc/<pid>/stat` and `/proc/stat` parsers against captured real files;
- cgroup procs parsing including the incremental-read case.

Device (Phase 5/6 exit gate):

- force stop of a protected package reports a typed failure, not success;
- freeze → binder transaction to the frozen app behaves as AOSP does (blocked with
  error), and unfreeze restores it;
- an app with `:push` reports frozen only when every process is frozen.
