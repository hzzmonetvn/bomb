# Plan — enable "scroll to translate" on the China ROM

Status: **proposed.** Grounded entirely in the extracted target ROM
(`ZKOS_NUWA_OS3.0.310.0.WMBCNXM_260808`, sha256 `0f53e753…0acc66`), partitions
`system_a` / `system_ext_a` / `vendor_a` / `product_a` / `mi_ext_a`.

---

## 1. What the feature actually is

Not a Xiaomi AI feature. "Scroll to translate" is **Google Circle to Search →
Live Translate**, and the ROM names it explicitly:

```xml
<!-- product/etc/sysconfig/sysconfig_contextual_search.xml -->
<config>
    <!-- enable contextual search features -->
    <feature name="com.google.android.feature.CONTEXTUAL_SEARCH" />
    <feature name="com.google.android.feature.CONTEXTUAL_SEARCH_LIVE_TRANSLATE" />
</config>
```

plus a second, redundant declaration in
`product/etc/sysconfig/sysconfig_live_translate.xml`.

This matters because it redirects the whole effort: the gate is **not** a Xiaomi
region check. `mi_ext/product/etc/cust_features/cust_features.xml` — the file
`bomb.sh cust-features` already patches for eSIM — contains only telecom arrays
(`country_support_esim`, `config_*` app lists). There is **no translate-related
`country_support_*` array anywhere in it.** Searching for `translat` across
`product/etc`, `mi_ext/*/etc` and `system_ext/etc` returns only the two sysconfig
files above, the GMS permission XMLs, and shader caches.

So the China/Global difference is not a flag Xiaomi flips — it is which Google
components ship.

---

## 2. Component audit on this ROM

The stack Circle to Search + Live Translate needs, checked one by one:

| Component | Required for | Present? | Evidence |
| --- | --- | --- | --- |
| `CONTEXTUAL_SEARCH` feature | Circle to Search at all | **YES** | `sysconfig_contextual_search.xml` |
| `CONTEXTUAL_SEARCH_LIVE_TRANSLATE` feature | the scroll/live translate mode | **YES** | same file + `sysconfig_live_translate.xml` |
| Velvet (`com.google.android.googlequicksearchbox`) | hosts the Circle to Search UI | **YES** | present in `product/priv-app` |
| Velvet privileged permissions | Velvet's system access | **YES** | `product/etc/permissions/com.google.android.googlequicksearchbox.xml`, 12 118 B |
| GmsCore | account/feature delivery | **YES** | `product/priv-app/GmsCore/GmsCore.apk` |
| **AICore** (`com.google.android.aicore`) | **on-device models that do the translating** | **NO** | absent from `product/priv-app` and `product/app` |
| AICore hardware feature flags | AICore's SoC gate | **PRESENT BUT WRONG** | see §3 |

**One component is missing and one is misconfigured.** Everything else is already
in place — which is why this is worth doing rather than a rewrite.

---

## 3. The misconfiguration, and it is unambiguous

`product/etc/sysconfig/google_aicore.xml` declares:

```xml
<feature name="com.google.android.feature.AICORE_MT_MT6991" />
<feature name="com.google.android.feature.AICORE_MT" />

<string name="config_defaultOnDeviceIntelligenceService">
    com.google.android.aicore/com.google.android.apps.aicore.service.isolated.AiCoreIntelligenceService</string>
<string name="config_defaultOnDeviceSandboxedInferenceService">
    com.google.android.aicore/com.google.android.apps.aicore.service.isolated.AiCoreIsolatedService</string>
```

But the device is Qualcomm:

```
vendor/build.prop:  ro.board.platform=kalama
vendor/build.prop:  ro.soc.manufacturer=QTI
```

`AICORE_MT` is MediaTek and `MT6991` is a Dimensity part number. **This sysconfig
was taken from a MediaTek build and shipped unchanged on a Qualcomm device.**

Two consequences:

1. AICore gates its on-device model availability on these `AICORE_*` hardware
   features. Declaring a MediaTek part on a Qualcomm SoC is at best meaningless
   and at worst actively wrong — it advertises an accelerator profile the hardware
   does not have.
2. `config_defaultOnDeviceIntelligenceService` points at
   `com.google.android.aicore`, **a package that is not installed on this image**.
   The framework will resolve that service to nothing.

Live Translate runs its translation models through AICore. With AICore absent and
its feature gate naming the wrong silicon, the feature cannot work regardless of
how many flags are set.

---

## 4. The plan

### P1 — confirm the failure mode on device (before changing anything)

On a booted phone running this ROM:

```sh
pm list packages | grep -E 'aicore|googlequicksearchbox'
pm list features | grep -iE 'contextual_search|aicore'
dumpsys package com.google.android.aicore | head
```

Expected: the two `CONTEXTUAL_SEARCH*` features present, Velvet installed,
`com.google.android.aicore` **absent**, and `AICORE_MT*` listed as features.
If reality differs, the rest of this plan is re-derived rather than applied.

### P2 — correct `google_aicore.xml` for Qualcomm

Replace the MediaTek feature declarations with the Qualcomm equivalents for
`kalama`, or — safer, since the exact Qualcomm feature name must be taken from a
real Snapdragon build rather than guessed — **copy the whole file from a Global
Snapdragon ROM of the same platform**. The `config_default*` service strings stay
as they are; they are package references, not SoC-specific.

Do **not** invent a feature name. If no Snapdragon reference is available, the
correct interim action is to **remove** the two `AICORE_MT*` lines rather than
leave a false hardware claim, and re-test.

### P3 — ship AICore

Add `com.google.android.aicore` to `product/priv-app/` alongside Velvet, with its
permission XML, using the same mechanism `hzz` already uses for the other Google
components (`bomb.sh zk-mods` unpacks `advanced/GmsCore` and `advanced/Velvet`
into `product/priv-app` for non-Global builds). AICore becomes a third entry in
that path.

Version-match matters: AICore, Velvet and GmsCore negotiate feature availability
between themselves. Take all three from the **same Global ROM build** rather than
mixing versions.

### P4 — verify end to end

1. `pm list features | grep -i contextual_search` — both present;
2. `pm list packages | grep aicore` — installed;
3. AICore reports models downloaded (it fetches them post-boot over network);
4. Long-press home / nav → Circle to Search appears;
5. Select translate mode, scroll the page — the translated overlay follows.

Step 3 is where this most plausibly stalls: AICore downloads its models from
Google, and that delivery is account- and region-conditioned server-side.

---

## 5. Honest risk assessment (revised 2026-08-08)

### 5.1 Cleared: the feature is real on official Global

The project owner has **tested scroll-to-translate working on a Xiaomi 15 Ultra
running official Global**. That settles the earlier doubt.

The xiaomi.eu community reports quoted previously described the **xiaomi.eu ROM**,
a third-party build — not official Global. The distinction matters and the earlier
framing conflated them.

### 5.2 The dominant risk is now hardware, and the gap is two generations

The target of this plan is not the device that was tested:

| | Tested working | This ROM |
| --- | --- | --- |
| Device | Xiaomi 15 Ultra | **`nuwa`** — `ro.product.product.name=miproduct_nuwa` |
| SoC | Snapdragon 8 Elite | **SM8550 / `kalama` — Snapdragon 8 Gen 2** (`ro.product.vendor.name=mivendor_sm8550_cn`) |
| Year | 2025 | 2022 |

Google's on-device AI stack has a hardware floor. Current reporting puts Gemini
Nano / AICore on **Tensor G3-G4, Snapdragon 8 Gen 3, Dimensity 9400 and newer**;
**Snapdragon 8 Gen 2 does not appear on the supported list.**

So a successful test on an 8 Elite device says little about an 8 Gen 2 device. If
Live Translate's translation runs through AICore, `nuwa` is below the floor and no
amount of correct packaging changes that.

### 5.3 The open technical question

Does Circle to Search's Live Translate translate **on-device via AICore**, or
**server-side**? The ROM gives evidence both ways: it declares the
`CONTEXTUAL_SEARCH_LIVE_TRANSLATE` feature (which needs no AICore to be declared)
*and* ships `google_aicore.xml` wiring `config_defaultOnDeviceIntelligenceService`
to a package it does not install.

- If **server-side**: flags + Velvet + GmsCore may be nearly enough, and the
  missing AICore is a red herring for this particular feature.
- If **on-device**: `nuwa` is out, permanently.

This is not answerable from the image. It is answerable in ten minutes on a booted
phone.

### 5.4 Still present: server-side gating

AICore model delivery and Circle to Search enablement are controlled by Google
phenotype flags keyed on device, region and account. A China-region device may
simply not be served. Forcing it would mean spoofing device identity to GMS —
which master plan §13 forbids and this plan excludes.

---

## 5b. Revised approach — diff the *same device*, not a different one

The single most useful artifact is the **official Global ROM for `nuwa` itself**.
Comparing CN-`nuwa` against Global-`nuwa` isolates the **region** delta with the
hardware held constant — which comparing against a 15 Ultra cannot do.

Concretely, diff between the two builds:

1. `product/etc/sysconfig/` — especially `google_aicore.xml`, `sysconfig_contextual_search.xml`,
   `sysconfig_live_translate.xml`;
2. the Google package set in `product/priv-app` and `product/app` — is AICore
   present on Global-`nuwa`? **If Global-`nuwa` also lacks AICore and the feature
   still works there, §5.3 is answered: it is server-side, and the port is
   realistic.**
3. `product/etc/permissions/` for the Google components;
4. `ro.product.*` region markers and anything in `mi_ext` that differs.

That comparison is cheap, decisive, and replaces most of the guesswork in §4.

**Order of work: P1 (boot the current CN ROM and observe) → obtain Global-`nuwa`
and diff → only then P2/P3.**

---

## 6. What this plan explicitly does not do

- No spoofing of `ro.product.*`, region, or model to convince GMS the device is
  something else. That is Play-Integrity-adjacent and out of bounds.
- No phenotype/`pixel_experience_*.xml` flag planting.
- No modification of Velvet or GmsCore APKs.
- No claim that the feature will work — the plan produces the *correct
  configuration*, and §5 states plainly why that may still be insufficient.
