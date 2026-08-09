# Target ROM — verified facts

Base image: `ZKOS_NUWA_OS3.0.310.0.WMBCNXM_260808.zip`
sha256 `0f53e753…0acc66`, kept at `hzz/ROM-keep/`.

Everything below was read out of the image itself, not inferred from documentation
or from another device. Where a value came from parsing a binary structure, the
structure and offset are named so the read can be repeated.

---

## 1. Device identity

| Property | Value | Source |
| --- | --- | --- |
| `ro.product.odm.device` | `nuwa` | `odm/etc/build.prop` |
| `ro.product.product.name` | `miproduct_nuwa` | `product/etc/build.prop` |
| `ro.product.vendor.name` | `mivendor_sm8550_cn` | `vendor/build.prop` |
| `ro.board.platform` | `kalama` | `vendor/build.prop` |
| `ro.soc.manufacturer` | `QTI` | `vendor/build.prop` |
| `ro.build.ab_update` | `true` | `vendor/build.prop` |
| Android release | 16 (API 36) | `system/build.prop` |
| Region base | CN (`WMBCNXM`) | `mi_ext/etc/build.prop` |

SoC is **SM8550 / Snapdragon 8 Gen 2**. This matters for
[[../SCROLL_TRANSLATE_PLAN]] — the on-device AI floor sits above this part.

Note the ROM is **Qualcomm**, which is why the MediaTek feature declarations in
`product/etc/sysconfig/google_aicore.xml` are a packaging defect rather than a
configuration choice.

---

## 2. Package format — fastboot, not OTA

The build supplied via pixeldrain is a **fastboot image package**: `super.img`
plus 27 discrete partition images (`boot`, `vbmeta`, `modem`, `dtbo`, …). It
contains **no `payload.bin`**.

`build.sh:59-60` requires `payload.bin`:

```sh
"$tools_dir/payload" -o "$home/base/images" "$home/payload/payload.bin"
eval "$(python3 "$tools_dir/py/getsuper.py" "$home/payload/payload.bin")"
```

so an unmodified `build.sh` run stops at line 59. Confirmed in `hzz/build.log`:

```
2026/08/08 15:41:26 File does not exist: /home/hzzmonet/zk/hzz/payload/payload.bin
```

`getsuper.py` parses the OTA manifest and exports exactly three shell variables
(`getsuper.py:186-188`): `super_group`, `super_size`, `super_list`. All three are
recoverable from `super.img`'s own LP metadata — see §3 — so supporting a
fastboot package needs a branch, not a redesign.

---

## 3. Super image layout

`super.img` is a **raw (non-sparse)** LP image with intact metadata.
Geometry magic `0x616c4467` verified at offset **4096**; header magic
`0x414c5030` at offset **12288** (`4096 * 3` = reserved + primary + backup
geometry).

```
metadata_max_size = 65536
metadata_slot_count = 3          → matches build.sh's --virtual-ab branch
logical_block_size = 4096
header version = 10.2
block_device[0] name='super' size=9663676416
```

Values equivalent to what `getsuper.py` would emit:

```sh
super_group="qti_dynamic_partitions"    # build.sh appends _a / _b itself
super_size="9663676416"
super_list="odm product system system_dlkm system_ext vendor vendor_dlkm mi_ext"
```

Groups are `default` (max 0), `qti_dynamic_partitions_a` and
`qti_dynamic_partitions_b`, each capped at the full 9663676416 B.

### Partition table

Slot B is empty across the board — every `*_b` entry has **0 extents**. Only
slot A carries data.

| Partition | extents | size (bytes) | byte offset in super.img |
| --- | ---: | ---: | ---: |
| `odm_a` | 1 | 1 529 827 328 | 1 048 576 |
| `product_a` | 1 | 3 220 062 208 | — |
| `system_a` | 1 | 896 450 560 | — |
| `system_dlkm_a` | 1 | 102 400 | 5 647 630 336 |
| `system_ext_a` | 1 | 841 912 320 | — |
| `vendor_a` | 1 | 754 642 944 | — |
| `vendor_dlkm_a` | 1 | 40 534 016 | 7 245 660 160 |
| `mi_ext_a` | 1 | 50 229 248 | — |

Each partition is a single extent, so extraction is a plain `dd` at the offset
above for the length above. All eight carry EROFS magic `0xE0F5E1E2` at offset
1024 of the extracted image.

---

## 4. Eight partitions, not five

**`super_list` has eight entries.** An extraction covering only
`system` / `system_ext` / `vendor` / `product` / `mi_ext` silently omits
`odm` (1,53 GB), `vendor_dlkm` (40,5 MB) and `system_dlkm` (102 KB).

This fails **silently** rather than loudly, which is what makes it dangerous.
`build.sh:799` guards the repack loop with:

```sh
if [ -f "$work_dir/${i}.img" ]; then
```

A missing partition image is skipped without a warning, and `lpmake` produces a
structurally valid `super.img` that is simply missing those partitions. Content
that would be lost:

- `vendor_dlkm` — **291 `.ko` kernel modules**
- `system_dlkm` — 2 `.ko` modules
- `odm` — `app`, `bin`, `etc`, `firmware`, `lib`, `lib64`, `mount`, `overlay`

`build.sh:75` also reads `$odm/etc/build.prop` to derive `device_model`,
preferring it over `vendor`:

```sh
[ ! -f "$odm/etc/build.prop" ] || device_model=$(grep '^ro.product.odm.device=' ...)
```

With `odm` absent that line is skipped and the value silently falls back to
`vendor`. Here both yield `nuwa`, so the fallback masks the omission instead of
exposing it.

**Any repack must cover all eight.**

---

## 5. Naming mismatch against build.sh

Partition names inside a Virtual A/B super carry the slot suffix (`system_a`),
and both the extracted directories and the `config/` files inherit it.
`build.sh` works in the opposite direction: `partition_list` holds **unsuffixed**
names, and the suffix is appended only at `lpmake` time (`build.sh:800`).

So `build.sh` expects `images/system` + `images/config/system_fs_config`, while
extraction from a fastboot super yields `system_a` + `config/system_a_fs_config`.
Strip the suffix when staging into `work_dir`.

---

## 6. Repack path

`build.sh` repacks with, per partition (`build.sh:785-786`):

```sh
python3 tools/py/selinuxpatch.py <dir> <config>/<p>_fs_config <config>/<p>_file_contexts
tools/mkfs.erofs -zlz4hc,9 -T 1230768000 --mount-point="/<p>" \
    --fs-config-file=... --file-contexts=... <p>.img <dir>
```

then `lpmake` (`build.sh:808`). The three `config/` files per partition
(`_fs_config`, `_file_contexts`, `_fs_options`) are what make this possible;
all 24 are present.

`tools/` ships `lpmake` but **no `lpdump`** — hence the metadata in §3 was
parsed directly rather than dumped.

---

## 7. P0 findings carried from earlier verification

- `system/bin/iptables` is a real 503 KB binary, `ip6tables` a symlink, `ip`
  425 KB, no nftables → clears PR1 in [[../PROXY_GATEWAY_PLAN]].
- No `logd` restarter: `vendor/etc/init/hw/init.target.rc:78` sits inside
  `on init` (one-shot); only `logd-auditctl` carries repeating property
  triggers; `logd` is **not** `critical`. Tier OFF therefore works in ROM mode
  without root — see [[LOG_CONTROL]].
- `persist.logd.audit.rate` (default 5/sec) is an existing live-tunable
  SELinux-denial throttle.
- MIUI `/dev/ylog_buffer` is a vendor log sink that no tier currently touches.
- zram is **not** in fstab; `mmd` is absent; two controllers disagree — see
  [[ZRAM]].
