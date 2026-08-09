# Research — Telemetry references (OpenMonitor + AOSP cross-check)

```text
References:
https://github.com/1orz/OpenMonitor.git @ f1c9033c1c901d1f847ef837516f2730078dff40
  (commit date 2026-05-26)  License: GPL-3.0 (LICENSE)
platform/frameworks/base @ 1cdfff555f4a21f71ccc978290e2e212e2f8b168
  License: Apache-2.0
```

---

## 1. Honest result first: OpenMonitor publishes no source

The cloned repository contains **nine files**:

```text
README.md  README_zh-CN.md  README_ja.md  LICENSE
icon/openmonitor_logo.svg
.github/dependabot.yml
.github/workflows/{build,release,alpha}.yml
```

There is no `app/`, no Gradle build, no Kotlin, no Rust. The repository is a
documentation/release front-end for a binary distribution; the LICENSE file says
GPL-3.0 but no corresponding source is present at this commit.

Master plan §32 states plainly: *"Cloning a repository alone does not count as
research"* and *"README-only research is insufficient."* For this reference that
standard **cannot be met** — there is nothing to trace, no execution path to
follow, no symbol to cite.

**Consequences, recorded rather than papered over:**

1. OpenMonitor is downgraded from "secondary reference" to **feature-scope
   inspiration only**. No Bomb architecture decision may cite it.
2. Master plan §36's row `Stats | OpenMonitor + AOSP/kernel/device sources` becomes
   effectively **AOSP/kernel/device sources alone**. §34's precedence
   (`AOSP > official API > mature OSS > smaller reference > assumption`) resolves
   this without ambiguity.
3. Its README's claim list is useful as a *checklist of what a good monitor
   covers*, and nothing more.

What the README does document about its own architecture (README.md, "Architecture"
and "Tech Stack" sections), taken as claims, not as verified facts:

- a Rust privileged daemon (`daemon-rust`) with double-fork daemonization, an
  AF_UNIX socket, a length-prefixed JSON wire protocol, multi-threaded sampling,
  and **APK v2 certificate pinning for client authentication**;
- launch modes ROOT (libsu) / Shizuku (UserService) / ADB;
- FPS via SurfaceFlinger; modem data via AF_QIPCRTR/QMI (Qualcomm only);
- JNI bridges to `cpuinfo` and Vulkan for hardware enumeration;
- Compose + Material 3, Room 2.8.4, Hilt, WorkManager, Vico charts, Firebase
  Analytics.

Two of these are worth Bomb's attention as *ideas*:

- **Client authentication at the daemon boundary.** Bomb's `bombd` must
  authenticate its caller, and "APK signature pinning" is one plausible mechanism.
  Bomb's chosen mechanism is a Unix-socket peer credential check (`SO_PEERCRED` →
  uid) plus SELinux domain restriction, which is stronger and does not require
  parsing APK signatures inside a native daemon.
- **Qualcomm-only modem features.** A reminder that vendor-specific telemetry must
  sit behind a capability probe. Bomb has no modem-analysis scope at all.

Bomb explicitly does **not** copy the Firebase Analytics dependency, and does not
ship analytics of any kind.

---

## 2. What AOSP actually guarantees (the part that is verifiable)

### Memory

`core/java/android/app/ActivityManager.java`:

| API | Line | Notes |
| --- | --- | --- |
| `ActivityManager.MemoryInfo` | :3389 | fields `advertisedMem`, `availMem`, `totalMem`, `threshold`, `lowMemory` |
| `getMemoryInfo(MemoryInfo)` | :3509 | rate-limited via `mMemoryInfoCache` (:244) |
| `getProcessMemoryInfo(int[] pids)` | :4863 | returns `Debug.MemoryInfo[]` — this is where **PSS** comes from |

The `availMem` doc comment is a warning Bomb should surface rather than hide:

> This number should not be considered absolute: due to the nature of the kernel, a
> significant portion of this memory is actually in use and needed for the overall
> system to run well.

**Bomb implication:** "free RAM" is not a meaningful number and must never be
presented as headroom, nor as justification for a cleaner. Master plan §5.1 already
bans the RAM cleaner; this is the technical reason.

**PSS is expensive.** `getProcessMemoryInfo` walks smaps per pid. It must not be
called at the Realtime (500 ms) refresh level for every process. Bomb's model:
RSS from `/proc/<pid>/statm` at every tick; PSS only for the selected process or
on explicit request.

### Process list

```java
/** Rate-Limiting Cache that allows no more than 400 calls to the service per second. */
private static final RateLimitingCache<List<RunningAppProcessInfo>> mRunningProcessesCache =
        new RateLimitingCache<>(10, 4);            // ActivityManager.java:234-236
...
public List<RunningAppProcessInfo> getRunningAppProcesses() {           // :4275
    if (!Flags.rateLimitGetRunningAppProcesses()) { return getRunningAppProcessesInternal(); }
    else { return mRunningProcessesCache.get(() -> getRunningAppProcessesInternal()); }
}
```

**Bomb implications:**

1. The platform itself rate-limits this call (10 ms window). Polling it faster than
   the cache window returns identical data — Bomb would burn CPU for nothing.
2. It is behind an aconfig flag, so behaviour differs across builds. Bomb must not
   depend on either behaviour for correctness.
3. `RunningAppProcessInfo.importance` (and `importanceReasonCode` :4077,
   `importanceReasonPid` :4084) is the **only** reliable foreground/background
   signal — `/proc` cannot provide it. See
   [`AOSP_PROCESS_MANAGEMENT.md`](./AOSP_PROCESS_MANAGEMENT.md) §5.

### CPU

`/proc/stat` (system) and `/proc/<pid>/stat` (per process), sampled as **deltas**.
The correctness cases that must be unit-tested — counter reset on pid reuse,
32-bit wraparound, zero elapsed time, short/garbled lines — are listed as required
tests in master plan §39 and are real, not hypothetical.

Per-core frequency comes from `/sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq`.
That path is a kernel interface, not an AOSP API: it must be **probed for
existence and readability** under Bomb's SELinux domain before any UI offers it.

### Thermal, GPU, power

None of `HardwarePropertiesManager`, `PowerManager.getThermalHeadroom`, or the
vendor GPU sysfs nodes are in this sparse checkout (`core/java/android/os/` was not
included). They are therefore **unverified here** and must be confirmed against the
target ROM before Phase 4/5 claims them. Recorded as an open item rather than
assumed.

What is already known from the workspace audit (`docs/ARCHITECTURE.md` §3.3): the
ROM patches reference `/sys/class/qcom-battery/*`, `vendor_sysfs_kgsl`, and
`thermald-devices.conf` — all Qualcomm-specific, all behind
`GpuBackend`/`ChargingBackend`/thermal capability probes.

### FPS

The technique proven on this ROM already exists in the sibling project
(`ProjectZK`'s `data/fps/FpsMeter.kt`): `dumpsys gfxinfo`-style frame-stats
sampling with the `DUMP` permission. Bomb re-implements it behind
`TelemetryCollector`; it is capability-gated because frame stats are per-window and
not always populated.

---

## 3. Telemetry design rules this research produces

1. **One pipeline, subscriber-aware.** Master plan §6's single
   `TelemetryCollector → TelemetryRepository → StateFlow` fan-out to UI, overlay,
   rules and bridge. Zero subscribers ⇒ zero reads, asserted by a test.
2. **Per-metric cost classes**, because the refresh level cannot be uniform:

   | Class | Examples | Allowed at Realtime (500 ms)? |
   | --- | --- | --- |
   | cheap | `/proc/stat`, `/proc/meminfo`, per-core freq | yes |
   | moderate | per-process `/proc/<pid>/stat`, thermal zones | yes for the visible set only |
   | expensive | PSS (`getProcessMemoryInfo`), full process enumeration, `dumpsys` | no — on demand or at Balanced+ |
   | platform-limited | `getRunningAppProcesses` (10 ms cache) | pointless to exceed |

3. **Never fabricate.** A metric whose source node is missing or unreadable renders
   as an explicit unsupported state, never as `0`, never as a dash that looks like
   a value, never as a plausible-looking number.
4. **Rate maths lives in `:domain`** (`RateCalculator`, `DeltaCalculator`) so all
   the reset/overflow/invalid-input cases are JVM-testable.
5. **Device paths live behind backends** (`GpuBackend`, `ChargingBackend`,
   `ThermalBackend`), never inline in collectors.

---

## 4. Testing strategy

`:domain`, pure JVM:

- delta/rate: normal, counter reset → 0 (not negative), 32-bit and 64-bit
  wraparound, zero/negative elapsed time, missing sample;
- `/proc/stat` and `/proc/<pid>/stat` parsers against **captured real files** as
  fixtures, including a process name containing spaces and parentheses (the classic
  `comm` field parsing trap);
- `/proc/meminfo` parsing with fields absent on some kernels;
- capability filtering: a snapshot with GPU unsupported never yields a GPU value.

`:system-service`, JVM with a fixture tree:

- collector reads nothing when the subscriber count is zero;
- adding a subscriber starts collection; removing the last one stops it;
- an unreadable sysfs node degrades that metric only, not the whole snapshot.

Device (Phase 4/5 gate), reported honestly:

- which of {GPU load, GPU freq, thermal zones, battery current, thermal headroom,
  FPS} are actually available on the target ROM, and which report unsupported.
