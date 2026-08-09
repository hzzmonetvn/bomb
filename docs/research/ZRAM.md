# Research — ZRAM and swap tuning

```text
Verified directly:
- the zram sysfs interface on a live 6.x kernel (this workstation) — same driver
  and same attribute names as Android, though the attribute *set* varies by
  kernel version (see §1 caveat)
- /home/hzzmonet/zk/hzz/bomb.sh
- /home/hzzmonet/zk/hzz/mods/bomb/universal/system/system/etc/init/hw/init.rc

NOT verified: the target image's vendor fstab, its actual zram attribute set, and
whether the HyperOS kernel exposes writeback/recompress. No partition is
extracted (hzz/super, hzz/out are empty).
```

---

## 1. The interface, as it actually is

`/sys/block/zram0/`, observed:

```
disksize  comp_algorithm  mem_limit  max_comp_streams  mm_stat  io_stat  bd_stat
reset  compact  idle  writeback  writeback_limit  writeback_limit_enable
writeback_batch_size  backing_dev  recomp_algorithm  recompress  algorithm_params
debug_stat  initstate  mem_used_max
```

**Caveat:** `recomp_algorithm`, `recompress`, `algorithm_params` and
`compressed_writeback` are recent additions. The target's Android kernel will have
a subset. Every attribute must be probed for existence before use — this is the
`ZramControl` capability, and it is per-attribute, not a single boolean.

### `comp_algorithm` parse rule

```
lzo-rle lzo lz4 lz4hc [zstd] deflate 842
```

The full set is the available algorithms; **the one in square brackets is
current**. That single line gives both the enum domain for validation and the
present value — no second read needed.

### `mm_stat` decoding

Nine whitespace-separated fields, in the order documented by the kernel:

```
orig_data_size compr_data_size mem_used_total mem_limit mem_used_max
same_pages pages_compacted huge_pages huge_pages_since
```

Observed sample:

```
5281574912 1554040446 1587322880 0 2267963392 25787 1868524 82574 142718
```

The two numbers users actually care about are derived, not read:

- **compression ratio** = `orig_data_size / compr_data_size` = 5.28 GB / 1.55 GB
  ≈ **3.4×**
- **RAM efficiency** = `orig_data_size / mem_used_total` = 5.28 GB / 1.59 GB
  ≈ **3.3×** (lower than the ratio because of allocator overhead — this is the
  honest figure to show, and the one to use for the safety gate in §3)

`same_pages` is deduplicated zero/identical pages; counting them as "compression"
inflates the ratio, so the UI should report ratio and efficiency separately rather
than one flattering number.

---

## 2. What the ROM already does — three findings that change the design

> **VERIFIED on the real image (2026-08-08), with one finding overturned.**
> Partitions extracted from `ZKOS_NUWA_OS3.0.310.0.WMBCNXM_260808.zip`
> (sha256 `0f53e753…0acc66`), `system_a` + `vendor_a` out of `super.img`.
>
> - §2.1 and §2.2 **confirmed** — the lines below exist verbatim in the shipping
>   `system_a/system/etc/init/hw/init.rc`. The earlier framing was wrong only
>   about *who* sets them: they are stock HyperOS, not a `bomb.sh` patch.
> - §3's "patch `zramsize=` in the vendor fstab" is **wrong for this device** —
>   see §2.4. That was the plan's preferred apply path and it does not exist here.

### 2.1 swappiness is set by HyperOS, and it is per-cgroup

`system_a/system/etc/init/hw/init.rc:35-57` (verified in the shipping image):

```
write /proc/sys/vm/swappiness 100
write /dev/memcg/memory.swappiness 100
write /dev/memcg/freeze-app/memory.swappiness 60
write /dev/memcg/apps/memory.swappiness 100
write /dev/memcg/system/memory.swappiness 100
```

Two consequences:

1. Bomb setting `/proc/sys/vm/swappiness` is **modifying existing ROM behaviour**,
   not configuring virgin state. The "default" Bomb restores to is 100, not the
   kernel's 60.
2. There is a **`freeze-app` memcg with its own swappiness of 60** — HyperOS puts
   frozen apps in a dedicated cgroup and swaps them *less* aggressively than normal
   apps. Any Bomb swappiness control that only touches the global knob will be
   overridden for the cgroups that matter. And this directly couples to
   [`§4.3 Freeze Engine`](../BOMB_PLAN.md): freeze policy and swap policy are
   already entangled on this ROM.

### 2.2 zram writeback belongs to system_server — do not touch it

`init.rc:1373-1384`:

```
# System server manages zram writeback
chown root system /sys/block/zram0/idle
chmod 0220 /sys/block/zram0/idle
chown root system /sys/block/zram0/writeback
chmod 0220 /sys/block/zram0/writeback
chown root system /sys/block/zram0/writeback_limit_enable
chown root system /sys/block/zram0/writeback_limit
```

The platform explicitly claims `idle` and `writeback`. Bomb writing them would
race `system_server`'s own writeback scheduling. **Writeback is read-only
telemetry for Bomb** (`bd_stat`), never a control.

### 2.4 zram is **not** configured through fstab on this device — the plan's preferred path does not exist

Verified in the extracted image:

- `vendor_a/etc/fstab.qcom` — the only fstab present — has **no `zram` and no
  `swap` line at all**;
- **`system_a/system/bin/mmd` does not exist.** Android 15/16's Memory Management
  Daemon, which owns zram setup when present, is not in this image;
- `system_a/system/etc/init/init-mmd-prop.rc` exists and does exactly one thing:
  `on property:sys.boot_completed=1 → setprop mmd.enabled_aconfig false`. Its own
  header comment states this file "must not be in the image if mmd is built into
  the image", and explains that with the flag false the device "set[s] up zram
  with `swapon_all` init command" instead;
- but no fstab consumed by `swapon_all` carries a zram entry.

So `disksize` cannot be set by patching `zramsize=` in the vendor fstab, because
there is nothing to patch. **§3's preferred apply path is void for this device.**

### 2.5 What actually owns zram on HyperOS: two controllers, and they disagree

Pointed out by the project owner and then verified in the extracted image. Three
config files live in `system_ext/etc`:

| File | Size | Read by |
| --- | --- | --- |
| `mcd_default.conf` | 6 569 B | **`/system_ext/bin/mcd`** — confirmed by `strings`: `/system_ext/etc/mcd_default.conf`, `zram_swap_set_global_swappiness`, `zram_device_num`, `zram_size_MB` |
| `perfinit.conf` | 1 164 B | **`system_ext/framework/miui-services.jar`** — the only file in the image that references the name |
| `perfinit_bdsize_zram.conf` | 3 449 B | same framework path (no native consumer exists — there is **no `perfinit` binary** in system, system_ext, vendor or product) |

`mcd` is started twice from `system_ext/etc/init/init.miui.ext.rc`:

```
service mcd_init /system_ext/bin/mcd init      # :299, oneshot, root
on property:init.svc.zygote=running
    setprop mcd.extra.params "4"
    start mcd_init

service mcd_service /system_ext/bin/mcd        # :311
on property:sys.boot_completed=1
    start mcd_service
```

**`mcd_default.conf` — the native side:**

```json
"memory_opt": {
    "zram_device_num": 1,
    "zram_size_MB": "512 1536:1024 2560:1536 3256:2252 4915:2560 6553:4048 8892:4048 12888:0",
    "global_swappiness": 60,
    "more_memory_swappiness": 60
},
"cgroups": [{ "groupname": "sys_critical", "priority": 1, "swappiness": 0,
              "def_tasks": [ueventd, vold, netd, surfaceflinger, servicemanager] }],
"override_memory_opt": [ … per-model overrides, several with "zram_size_MB": "0" ]
```

Read the size table as `default` then `ramThresholdMB:sizeMB` pairs. The final
pair is **`12888:0`** — on a device with ≥ ~12.6 GB of RAM, `mcd` sets zram size
to **zero**. Several device models are additionally overridden to
`zram_device_num: 0`.

**`perfinit.conf` — the framework side, disagreeing on every shared value:**

```json
"swap_on": 1,
"global_swappiness": 100,
"page_cluster": -1,
"zram_size": { "def":512, "4":2252, "8":6144, "12":8192, "16":14336, "24":15360, "32":16384 },
"extm_on": 1,
"extm_size": { "def":1024, "high_device":3072, … },
"extm_file": "/data/extm/extm_file",
"swappiness_on_start":    { "def":100 },
"swappiness_on_launcher": { "def":100, "2":200 }
```

Its keys are **RAM in GB**, so a 12 GB device gets 8192 MB and a 16 GB device
14336 MB of zram — while `mcd` would give the same device **0**. And its
`global_swappiness` is 100 against `mcd`'s 60, with `init.rc:39` independently
writing 100.

**`perfinit_bdsize_zram.conf`** adds the third dimension: `auto_zram` keyed first
by **flash size** (16/32/64/128/256/512/1024 GB) and then by RAM, carrying a
`def_bdsize` in GB — the **writeback backing-device size**. That is what
`init.rc:1373-1384`'s *"System server manages zram writeback"* refers to.

**Consequences for Bomb, and they are large:**

1. **The build-time apply path exists after all — it is just not fstab.** These are
   plain JSON in `system_ext/etc`, a partition `hzz` already patches. Editing
   `perfinit.conf`'s `zram_size` / `global_swappiness`, or `mcd_default.conf`'s
   `zram_size_MB`, changes zram sizing with **no `swapoff`, no runtime risk, and no
   reboot loop** — exactly the property §3 wanted. This replaces the void fstab
   path and is now the recommended mechanism.
2. **Which controller wins is undetermined from the image alone.** Two independent
   consumers, contradictory values, and ordering that depends on `zygote` vs
   `boot_completed`. On a high-RAM device the answer decides whether zram exists at
   all. `cat /proc/swaps` + `cat /sys/block/zram0/disksize` on the booted phone
   settles it, and until then `ZramPresent` may legitimately be **false** — the
   likely case on this hardware, not an edge case.
3. **`extm_on: 1` contradicts the ROM patcher.** `bomb.sh:517` disables Memory
   Extension by rewriting `persist.miui.extm.enable=1` → `0` in
   `product/etc/build.prop`, but `perfinit.conf` still declares `extm_on: 1` with an
   `extm_file` under `/data/extm`. Two switches for one feature. Bomb must read and
   report both rather than trusting either, and §2.3's earlier claim that "the ROM
   already turns it off" is only half true.
4. **swappiness has a fourth writer.** Beyond `init.rc` (100), `mcd` (60) and
   `perfinit` (100), `swappiness_on_launcher` can push **200**. Bomb's "restore to
   the value Bomb found" rule must capture at the right moment, and Bomb must not
   present a single global swappiness as if it were stable.
5. **A `sys_critical` cgroup with `swappiness 0`** pins ueventd, vold, netd,
   surfaceflinger and servicemanager out of swap. Bomb must never write swappiness
   into that group.

### 2.3 MIUI Memory Extension is already disabled by the ROM

`bomb.sh:517`:

```sh
sed -i 's/persist.miui.extm.enable=1/persist.miui.extm.enable=0/g' "$product/etc/build.prop"
```

Xiaomi's "Memory extension" swaps to UFS, which competes with zram for the same
pressure and wears flash. The ROM already turns it off, so zram is the only swap
backend — good, and it means Bomb's zram numbers are the whole picture. Bomb must
not re-enable extm, and should surface its state so the user is not tuning zram
while a second swap backend is silently active on some other build.

---

## 3. The danger: resizing requires `swapoff`, and `swapoff` can OOM the device

To change `disksize` **or** `comp_algorithm`, the device must be empty:

```text
swapoff /dev/block/zram0     # ← the dangerous step
echo 1 > /sys/block/zram0/reset
echo <bytes> > /sys/block/zram0/disksize
mkswap /dev/block/zram0
swapon -p <prio> /dev/block/zram0
```

`swapoff` reads **every swapped page back into RAM**. From the observed sample:
5.28 GB of `orig_data_size` would have to be faulted back in. On a phone with most
of its RAM already in use, that is an immediate OOM storm or a multi-minute freeze
— and it happens *while the user is looking at a settings screen*.

This is the single most important fact about the feature, and it dictates the
architecture rather than being a footnote.

**Design consequence — two layers, and the risky one is not live:**

| Change | When | How |
| --- | --- | --- |
| `disksize` | **build time** (preferred) | patch `zramsize=` in the vendor `fstab` via a `bomb.sh` subcommand; `init`/`fs_mgr` sets zram up at boot with no runtime swapoff at all |
| `disksize`, `comp_algorithm` | **next boot** (runtime request) | Bomb stores the desired config; `bombd` applies it early in boot, before meaningful swap accumulates. `ProfileState` reports `rebootPending` |
| `swappiness` (global + per-memcg) | live | bounded int write |
| `page-cluster` | live | bounded int write (0–3; **0** is the usual choice for zram — decompression is cheap, so reading extra pages is wasted work. Observed default on a zram-tuned system: 0) |
| `mem_limit` | live | bounded, and can be lowered without swapoff |
| `compact` | live, on demand | write-1 trigger; costs CPU, so user-initiated only |
| `idle` / `writeback` / `writeback_limit*` | **never** | owned by system_server (§2.2) |

A live resize path may exist later, but only behind a hard gate:
refuse unless `orig_data_size < MemAvailable × safety_factor`, computed at the
moment of the request, with the factor conservative (start at 0.5) and the refusal
returning a typed reason that states the actual numbers.

---

## 4. Bomb design

### Capability probing, per attribute

```text
ZramPresent          /sys/block/zram0 exists and initstate == 1
ZramResize           disksize writable AND a supported apply path exists
                     (build-time fstab patch, or boot-time bombd)
ZramAlgorithm        comp_algorithm readable; enum domain parsed from the line
ZramMemLimit         mem_limit writable
ZramCompact          compact writable
ZramWriteback        read-only — bd_stat readable (never a control)
SwappinessGlobal     /proc/sys/vm/swappiness writable
SwappinessPerCgroup  /dev/memcg/*/memory.swappiness present
PageCluster          /proc/sys/vm/page-cluster writable
```

Anything absent renders an explicit unsupported state. No fabricated values —
`mm_stat` missing means the ratio is unavailable, not zero.

### API

```aidl
ZramCapabilities getZramCapabilities();
ZramState        getZramState();      // sizes, ratio, efficiency, algorithm,
                                      // swappiness (global + per-cgroup), rebootPending
BombResult       setZramConfig(in ZramConfig config);
BombResult       compactZram();
```

`ZramConfig` is typed and validated in `:domain`:

- `disksizeBytes` — bounded to a sane fraction of total RAM (reject 0, reject
  > 4× RAM, warn above 2×);
- `algorithm` — must be a member of the **probed** set, never a free string;
- `swappiness` 0–200 (the kernel accepts up to 200 on modern versions; the ROM
  ships 100), `pageCluster` 0–3, `memLimitBytes` ≥ 0.

No `writeSysfs(path, value)` anywhere — this is exactly the typed-API rule from
`CLAUDE.md`, and zram is the textbook case for it: the same write that is harmless
at one value bricks the session at another.

### Where it runs

`bombd` (M9's first sysfs *write* need) applies the live knobs and the boot-time
config. Device-specific paths stay behind a `MemoryBackend` interface, like every
other hardware-touching subsystem.

### Interaction with Performance Profiles

`swappiness` and `pageCluster` become fields of a `PerformanceProfile`
(`ECO`/`BALANCED`/`PERFORMANCE`/`GAMING`/…), restored on profile exit like every
other profile field — and Bomb restores **only values Bomb set**, which here means
remembering the ROM's 100 rather than assuming the kernel default of 60.
`disksize` and `algorithm` are **not** profile fields: a profile switch must never
trigger a swapoff.

---

## 5. Risks

| # | Risk | Mitigation |
| --- | --- | --- |
| ZR1 | Live resize OOMs or freezes the device | resize is build-time or boot-time; any live path gated on `orig_data_size` vs `MemAvailable`, refusing with the actual numbers |
| ZR2 | Bomb fights `system_server` over writeback | writeback is read-only telemetry, never a control (§2.2) |
| ZR3 | Global swappiness change silently overridden by per-memcg values | control both, and show both; treat `freeze-app`'s 60 as ROM-owned unless deliberately changed |
| ZR4 | Restoring to the kernel default (60) instead of the ROM's value (100) | capture the ROM's values at first run and restore to those |
| ZR5 | Oversized zram makes things worse — more compressed data than the CPU can decompress under pressure | warn above 2× RAM; do not present "bigger is better" |
| ZR6 | Target kernel lacks attributes seen here | per-attribute probing (§4), not a single capability flag |
| ZR7 | A second swap backend (MIUI extm) active on some build | surface `persist.miui.extm.enable`; never re-enable it |
| ZR8 | Bad `disksize` in fstab → boot without swap, or boot loop | build-time patch validated against total RAM; a boot-time apply must fail safe by leaving the existing swap alone |

---

## 6. Testing

`:domain`, pure JVM:

- `mm_stat` parser: 9 fields, short/garbled lines, division-by-zero when
  `compr_data_size` is 0 (fresh zram) → ratio unavailable, not `Infinity`;
- `comp_algorithm` parser: extract available set and the bracketed current value,
  including the case where nothing is bracketed;
- `ZramConfig` validator: algorithm outside the probed set rejected; disksize
  bounds; swappiness/page-cluster ranges;
- the resize safety gate: given (`orig_data_size`, `MemAvailable`, factor), the
  decision and the reported numbers.

Device:

- read-only telemetry correct against `/proc/swaps` and `free`;
- swappiness change visible in both global and per-memcg paths, and restored
  exactly to the ROM's captured values;
- `page-cluster` 0 applied and surviving a reboot only if Bomb re-applies it
  (it is not persistent);
- a boot-time `disksize` change comes up with the new size **and** working swap;
- a deliberately absurd `disksize` request is refused before it reaches the fstab;
- `compact` completes without stalling the UI thread (it is a blocking write).
