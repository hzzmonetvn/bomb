# Reference repositories — commits, licenses, reuse verdicts

Required by master plan §33 (source trace) and §35 (license and source reuse).
Every architecture claim in `docs/research/*.md` cites a path and symbol that
exists at the commit recorded here.

**Research date:** 2026-08-08
**Clone location:** `/tmp/bomb-reference-repos/` — outside the Bomb repository,
read-only, never committed, never added as a submodule, never vendored, and not
part of any Bomb build.

---

## 1. Ledger

| Repository | Commit | Committed | License | Reuse verdict |
| --- | --- | --- | --- | --- |
| [platform/frameworks/base](https://android.googlesource.com/platform/frameworks/base) | `1cdfff555f4a21f71ccc978290e2e212e2f8b168` | 2025-03-26 | **Apache-2.0** (`MODULE_LICENSE_APACHE2`, `NOTICE`) | **Compatible.** Bomb's framework patches are derivative of AOSP and stay Apache-2.0 with headers preserved |
| [HMA-OSS](https://github.com/frknkrc44/HMA-OSS) | `72fd0f1d60b0b02ee9f2b1cf6f9d638e4d0dbeda` | 2026-07-10 | **AGPL-3.0** (`LICENSE.md`) | **Research only.** No code, no adapted snippets, no derived files |
| [blocker](https://github.com/lihenggui/blocker) | `58f16ac68c3c0e0fe6ca1c29ee785e73c2f7ab0c` | 2026-07-20 | **Apache-2.0** (`LICENSE`) | Compatible; reuse would require attribution. **None taken** — architecture only |
| [Hail](https://github.com/aistra0528/Hail) | `4aef9ab2b9e7a2d04f55e9fa665f384b1deea25b` | 2026-08-07 | **GPL-3.0** (`LICENSE`) | **Research only** |
| [AdGuardHome](https://github.com/AdguardTeam/AdGuardHome) | `b2e25729e118754fecc06a0c8c0fff8fe569c548` | 2026-08-06 | **GPL-3.0** (`LICENSE.txt`) | **Research only.** Bomb does not embed AdGuard Home |
| [AdGuardSDNSFilter](https://github.com/AdguardTeam/AdGuardSDNSFilter) | `83d62022a188eb165f53fc50b55e4d13ec8925af` | 2026-08-04 | **GPL-3.0** (`LICENSE`) | **Research only.** The rule *data* is also GPL-3.0 and is **not** bundled into Bomb |
| [urlfilter](https://github.com/AdguardTeam/urlfilter) | `dba702354cd6a5a0eed03b41da0b22dd46a42af0` | 2026-06-25 | **GPL-3.0** (`LICENSE`) | **Research only.** Used to establish rule *semantics*; Bomb implements its own matcher |
| [HyperBridge](https://github.com/D4vidDf/HyperBridge) | `c31838361b192ff87ab57283aca16105c3c67966` | 2026-07-30 | **Apache-2.0** (`LICENSE`) | Compatible with attribution. Capability-probe *technique* reimplemented independently (3 property/settings reads) |
| [livebridge](https://github.com/appsfolder/livebridge) | `11c935c0517b52cf823b3431fb9e42d9d3254c12` | 2026-07-22 | **GPL-3.0** (`LICENSE`) | **Research only.** Architecture adopted, code not |
| [android/platform-samples](https://github.com/android/platform-samples) | `16455a951b7c608b61ff2b45c17f6446384ed219` | 2026-07-27 | **Apache-2.0** (`LICENSE`) | Compatible with attribution; official API usage patterns |
| [BCR](https://github.com/chenxiaolong/BCR) | `a4a8db86d406490f46200ab35764e55b316d16ac` | 2026-08-07 | **GPL-3.0-only** (`LICENSE`, SPDX headers) | **Research only** |
| [compose-miuix-ui/miuix](https://github.com/compose-miuix-ui/miuix) | `ac7802c7bb2142cae8427b442119574b6e6901f0` | 2026-08-07 | **Apache-2.0** (`LICENSE`) | **Compatible.** Consumed as a Maven dependency (`top.yukonga.miuix.kmp:miuix:0.8.8`), not vendored |
| [InstallerX-Revived](https://github.com/wxxsfxyzm/InstallerX-Revived) | `ac0a6874688e6dc1d6a70253d3b3e3ccd3f02595` | 2026-08-06 | **GPL-3.0** (`LICENSE`) | **Research only** |
| [OpenMonitor](https://github.com/1orz/OpenMonitor) | `f1c9033c1c901d1f847ef837516f2730078dff40` | 2026-05-26 | **GPL-3.0** (`LICENSE`) | **No source published at this commit** — 9 files, all docs/CI. Downgraded to feature-scope inspiration; cannot ground any architecture decision. See [`STATS_REFERENCES.md`](./STATS_REFERENCES.md) §1 |
| [Kr328/vpn-gateway](https://github.com/Kr328/vpn-gateway) | `5bc754aead2e6d0674e8c87290793c231732861d` | — | **NONE** — no LICENSE file, no header, no statement; **GitHub API `"license": null`** | **All rights reserved. Nothing may be copied, adapted or vendored.** Per D16 the mechanism (standard `ip rule`/`iptables`) is **reimplemented** from [`PROXY_MODULES.md`](./PROXY_MODULES.md) §1.1, not from the original file. Only needed for the optional third-party-VpnService sink |
| [vincentng295/magic_v2ray](https://github.com/vincentng295/magic_v2ray) | `84c319fe6aa05f494575e58cab51e186f5dd7bc1` | 2026-08-02 | **GPL-3.0** (`LICENSE`; GitHub API `gpl-3.0`; README §License grants use/modify/distribute under the same licence) | **Vendored and adapted under GPL-3.0 compliance (D16/D17).** Ships as **shell source** in `proxy/` with LICENSE + `MODIFICATIONS.md` + per-file attribution to this commit. Source form only, so GPL-3.0 §6 is never triggered. Segregated: nothing from it is ported into Bomb's own sources — see [`../PROXY_GATEWAY_PLAN.md`](../PROXY_GATEWAY_PLAN.md) §3 |
| Xray-core (bundled in magic_v2ray `bin/`) | pinned per `module.prop` (`Xray-core@v26.7.28` at this commit) | — | **MPL-2.0** (`bin/LICENSE`) | **Redistributable as an unmodified prebuilt** with licence + NOTICE shipped alongside |
| [heiher/hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) (bundled prebuilt) | version not recorded upstream in the module | — | **MIT** | **Redistributable as an unmodified prebuilt** with licence shipped alongside |

| ColorOS 15/16 Cooldown Disabler v1.1-no-restart | local copy at `/home/hzzmonet/zk/ColorOS15-16_Cooldown_Disabler_v1.1_no_restart` (not a git repo) | — | **NONE** — no LICENSE file, nothing in `README.txt` or `module.prop` | **Reimplement-only per D16.** Read for its `logd` teardown technique; nothing copied. Its OTA / telemetry / preload / package-disable behaviour is out of Bomb's scope entirely. See [`LOG_CONTROL.md`](./LOG_CONTROL.md) |
| [thelok1s/mosey-extended](https://github.com/thelok1s/mosey-extended) | README read 2026-08-08 (not cloned) | — | **GPL-2.0** for the module scripts | **Research only.** Its payload — `mosey_server` lifted from a Pixel 10 image — is **Google proprietary and not redistributable under any licence in that repo**. Its GMS phenotype/`pixel_experience_*.xml` route is Play-Integrity-adjacent spoofing that master plan §13 forbids. See [`AIRDROP_MOSEY.md`](./AIRDROP_MOSEY.md) |
| Xiaomi 17T Pro partition extract (`saygex.zip`) | supplied by the project owner 2026-08-08; **truncated at source** (matched `Content-Length` 168 142 216 yet 7-Zip reports "Unexpected end of archive"; 66 entries readable) | — | **Proprietary** (Google + Xiaomi vendor components) | **Reference only — nothing redistributable.** Establishes that a first-party HyperOS mosey build exists and what it consists of |
| `ZKOS_NUWA_OS3.0.310.0.WMBCNXM_260808.zip` | supplied by the project owner 2026-08-08, sha256 `0f53e753583306b7d7b43ddd89ce343f01b7bdead60eaae80dc52e3e270acc66` | — | **Proprietary** (HyperOS) | The **target ROM**. Extracted locally (`system_a`, `system_ext_a`, `vendor_a`, `product_a` from `super.img`) to settle P0 questions in §4.12/§4.13/§4.14/§4.15. Not redistributed |

Commit SHAs were captured with `git -C <repo> rev-parse HEAD` at clone time.

### AOSP sparse checkout

`frameworks/base` was cloned `--depth 1 --filter=blob:none --no-checkout` with
cone-mode sparse paths:

```text
core/java/android/app
core/java/android/content/pm
core/java/android/provider
packages/SettingsProvider
services/core/java/com/android/server/am
services/core/java/com/android/server/pm
services/core/java/com/android/server/wm
```

`services/core/java/com/android/server/net` (listed in master plan §31.4) is
**not** in the checkout, and neither is `core/java/android/os`. Claims that would
depend on those paths are marked unverified in the notes that need them
(`STATS_REFERENCES.md` §2, `AOSP_PROCESS_MANAGEMENT.md` scope note) rather than
asserted.

---

## 2. License policy for Bomb

Bomb's own licence is still unfixed (**D14**). The handling of *third-party* code
is decided (**D16**):

> A repo that grants **no licence** → reimplement from the documented mechanism;
> copy nothing.
> A repo that grants a licence **with conditions** → comply with the conditions
> and reuse.
> Copyleft code may ship **as a segregated component under its own licence**, in
> its own directory, with its own LICENSE and modification notices, behind a
> documented process boundary. It may **never** be mixed into, ported into, or
> statically combined with Bomb's own sources.

This replaces the earlier blanket rule ("no copyleft source enters the
repository"), which was safe but cost more time than it saved once a
well-engineered GPL-3.0 component turned out to be directly reusable — see
[`PROXY_MODULES.md`](./PROXY_MODULES.md) and
[`../PROXY_GATEWAY_PLAN.md`](../PROXY_GATEWAY_PLAN.md) §3.

The segregation rule is what keeps Bomb's own sources clean under **any** outcome
of D14, which is why D14 stopped being blocking for everything except
`framework/`.

Practical consequences, per master plan §35's workflow
(`UNDERSTAND → REDESIGN → WRITE BOMB-SPECIFIC IMPLEMENTATION`):

1. **Nine of the fourteen references are GPL/AGPL** — HMA-OSS, Hail, AdGuardHome,
   AdGuardSDNSFilter, urlfilter, LiveBridge, BCR, InstallerX-Revived, OpenMonitor.
   These carry the highest-value architectural lessons and the strictest
   restriction, which is why every note is written as *observed behaviour +
   Bomb decision* rather than as annotated code.
2. **AOSP is the exception that matters.** Bomb's framework patches
   (`AppsFilterBase`, `SettingsProvider`, `ProcessList`) are derivative works of
   Apache-2.0 code, must keep their license headers, and must be maintained as
   minimal diffs against a known upstream commit.
3. **AdGuard filter data is GPL-3.0.** Bomb ships no bundled AdGuard rule lists.
   Users add sources themselves; Bomb ships the parser and the compiler.
4. **MIUIX is a dependency, not source.** Nothing is copied out of it; the
   `Bomb*` wrapper layer exists partly so that stays true.

If Bomb is eventually released under GPL-3.0, items 1 and 3 could be revisited —
but AGPL-3.0 (HMA-OSS) would still be incompatible with a GPL-3.0 ROM component,
and the redesign already done means nothing is lost by keeping the current rule.

---

## 3. Research notes index

| Note | Subsystem | Primary references |
| --- | --- | --- |
| [`AOSP_PACKAGE_MANAGER.md`](./AOSP_PACKAGE_MANAGER.md) | App Visibility | AOSP `pm` |
| [`AOSP_SETTINGS_PROVIDER.md`](./AOSP_SETTINGS_PROVIDER.md) | Settings Virtualization | AOSP `SettingsProvider` |
| [`AOSP_PROCESS_MANAGEMENT.md`](./AOSP_PROCESS_MANAGEMENT.md) | Task Manager, Freeze, Process Control | AOSP `am` |
| [`HMA_OSS.md`](./HMA_OSS.md) | App Visibility, Settings Virtualization | HMA-OSS + AOSP |
| [`BLOCKER.md`](./BLOCKER.md) | Component Explorer, Process Control | Blocker + AOSP |
| [`HAIL.md`](./HAIL.md) | Freeze Engine | Hail + AOSP |
| [`ADGUARD_HOME.md`](./ADGUARD_HOME.md) | AdBlock sources and update pipeline | AdGuard Home |
| [`ADGUARD_FILTER_FORMAT.md`](./ADGUARD_FILTER_FORMAT.md) | AdBlock rule syntax and matching | urlfilter + AdGuardSDNSFilter |
| [`HYPERBRIDGE.md`](./HYPERBRIDGE.md) | Bomb Bridge — HyperOS renderer | HyperBridge |
| [`LIVEBRIDGE.md`](./LIVEBRIDGE.md) | Bomb Bridge — sources and dedup | LiveBridge |
| [`LIVE_UPDATES.md`](./LIVE_UPDATES.md) | Bomb Bridge — official renderer + 3-way comparison | android/platform-samples |
| [`BCR.md`](./BCR.md) | Call Recording | BCR |
| [`MIUIX.md`](./MIUIX.md) | Design system | miuix |
| [`UI_REFERENCES.md`](./UI_REFERENCES.md) | Privileged-app architecture, AIDL review checklist | InstallerX-Revived |
| [`STATS_REFERENCES.md`](./STATS_REFERENCES.md) | Telemetry | AOSP (+ OpenMonitor, unusable) |
| [`PROXY_MODULES.md`](./PROXY_MODULES.md) | Proxy / VPN gateway (see [`../PROXY_GATEWAY_PLAN.md`](../PROXY_GATEWAY_PLAN.md)) | Kr328/vpn-gateway + magic_v2ray |
| [`LOG_CONTROL.md`](./LOG_CONTROL.md) | Log Governor (three tiers) | the ROM's own `bomb.sh init-rc` patch + ColorOS Cooldown Disabler (local, no licence) |
| [`ZRAM.md`](./ZRAM.md) | Memory / ZRAM (§4.14) | kernel zram sysfs verified live + the target ROM's `mcd` / `perfinit` config stack |
| [`AIRDROP_MOSEY.md`](./AIRDROP_MOSEY.md) | AirDrop interop (§4.15, not committed) | `thelok1s/mosey-extended` (GPL-2.0) + a first-party Xiaomi 17T Pro extract + the target ROM |

---

## 4. Reproducing this research

```bash
mkdir -p /tmp/bomb-reference-repos && cd /tmp/bomb-reference-repos

git clone --depth 1 https://github.com/frknkrc44/HMA-OSS.git HMA-OSS
git clone --depth 1 https://github.com/lihenggui/blocker.git blocker
git clone --depth 1 https://github.com/aistra0528/Hail.git Hail
git clone --depth 1 https://github.com/AdguardTeam/AdGuardHome.git AdGuardHome
git clone --depth 1 https://github.com/AdguardTeam/AdGuardSDNSFilter.git AdGuardSDNSFilter
git clone --depth 1 https://github.com/AdguardTeam/urlfilter.git urlfilter
git clone --depth 1 https://github.com/D4vidDf/HyperBridge.git HyperBridge
git clone --depth 1 https://github.com/appsfolder/livebridge.git LiveBridge
git clone --depth 1 https://github.com/android/platform-samples.git android-platform-samples
git clone --depth 1 https://github.com/chenxiaolong/BCR.git BCR
git clone --depth 1 https://github.com/compose-miuix-ui/miuix.git miuix
git clone --depth 1 https://github.com/wxxsfxyzm/InstallerX-Revived.git InstallerX-Revived
git clone --depth 1 https://github.com/1orz/OpenMonitor.git OpenMonitor

git clone --depth 1 --filter=blob:none --no-checkout \
  https://android.googlesource.com/platform/frameworks/base aosp-frameworks-base
cd aosp-frameworks-base
git sparse-checkout init --cone
git sparse-checkout set \
  services/core/java/com/android/server/pm \
  services/core/java/com/android/server/am \
  services/core/java/com/android/server/wm \
  core/java/android/content/pm \
  core/java/android/provider \
  core/java/android/app \
  packages/SettingsProvider
git checkout
```

Because these are shallow clones of moving branches, re-cloning will produce
different HEADs. To verify a citation, check out the SHA in §1 explicitly.
