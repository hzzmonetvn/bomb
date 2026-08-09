# Research — AirDrop interop ("mosey") on HyperOS

```text
Sources:
https://github.com/thelok1s/mosey-extended        — GPL-2.0, KSU/Magisk module (README read)
saygex.zip                                        — partition extract from a Xiaomi 17T Pro,
                                                    supplied by the project owner
ZKOS_NUWA_OS3.0.310.0.WMBCNXM_260808              — the target ROM, extracted locally
                                                    (system_a / system_ext_a / vendor_a / product_a)
```

"mosey" is Google's internal codename for the Quick Share ↔ AirDrop interoperability
introduced on Pixel 10. This note establishes what the feature actually consists of,
what the **target device already has**, and what is genuinely missing.

---

## 1. The decisive finding: this is not a Pixel port

`mosey-extended` builds its module by lifting `mosey_server` out of a **Pixel 10**
factory image and pairing it with `wonder_mosey_wild.ko`, a virtual `mac80211`
driver that fakes the Wi-Fi phy the daemon expects. Its own README is blunt about
the result:

> "Without native BCM wondertap, real 802.11 frame I/O will not work — proximity
> discovery via 802.11 scanning is unavailable."
> Full frame I/O requires BCM4398 hardware (Pixel 9+ only).

If that were the only option, the feature would be non-functional on a Snapdragon
Xiaomi and there would be nothing to plan.

**It is not the only option.** The Xiaomi 17T Pro extract contains a complete
**first-party Xiaomi/HyperOS mosey stack**:

```text
vendor/bin/mosey_server                                    965 KB, ARM64, Android 36, stripped
vendor/lib64/libmosey_daemon_ffi.so                        2.5 MB, Rust
vendor/etc/init/mosey.rc
vendor/etc/vintf/manifest/manifest_mosey.xml
mi_ext/system_ext/priv-app/MiExt_GMS_MoseyApp/MoseyApp.apk
mi_ext/system_ext/etc/permissions/privapp-permissions-mosey.xml
mi_ext/product/etc/default-permissions/default-permissions-mosey.xml
```

This is a vendor-integrated build, not a lifted Pixel binary, and it is
structurally cleaner than the module approach:

```
# vendor/etc/init/mosey.rc
on boot
    start mosey_server

service mosey_server /vendor/bin/mosey_server
    user system
    group system inet
    disabled
    capabilities NET_ADMIN NET_RAW
```

`user system`, not root. Bounded capabilities. A normal init service.

```xml
<!-- vendor/etc/vintf/manifest/manifest_mosey.xml -->
<!-- Input: vendor/google/service/QuickShareExtension/manifest_mosey.xml -->
<manifest version="9.0" type="device">
    <hal format="aidl">
        <name>com.google.android.moseyservice</name>
        <fqname>IMoseyService/default</fqname>
    </hal>
</manifest>
```

The retained comment names the upstream path — Google ships this as
`vendor/google/service/QuickShareExtension`, and OEMs integrate it through the
vendor tree. That is why a Xiaomi build exists at all.

Permissions the app side needs (`privapp-permissions-mosey.xml`, package
`com.google.android.mosey`): `BLUETOOTH_PRIVILEGED`, `LOCAL_MAC_ADDRESS`,
**`MANAGE_WIFI_INTERFACES`**, `NETWORK_FACTORY`, `CREATE_APP_SPECIFIC_NETWORK`,
`CONNECTIVITY_USE_RESTRICTED_NETWORKS`, `LOCATION_HARDWARE`.

`MANAGE_WIFI_INTERFACES` is the platform API for creating Wi-Fi interfaces — the
supported route the `wonder_mosey_wild.ko` hack exists to avoid.

---

## 2. What the target ROM already has

Checked in the extracted `ZKOS_NUWA_OS3.0.310.0.WMBCNXM_260808` partitions:

| Component | Present on nuwa? | Evidence |
| --- | --- | --- |
| **VINTF HAL declaration** | **YES** | `system/etc/vintf/compatibility_matrix.device.xml` declares `com.google.android.moseyservice` / `IMoseyService` / `default` |
| **Hidden-API allowlist entry** | **YES** | `system/etc/sysconfig/google-hiddenapi-package-allowlist.xml`: `<hidden-api-whitelisted-app package="com.google.android.mosey" />` |
| **GMS client support** | **YES** | `product/priv-app/GmsCore/GmsCore.apk` references mosey |
| `vendor/bin/mosey_server` | **NO** | absent |
| `MoseyApp.apk` / `mi_ext` component | **NO** | absent |
| `mosey.rc`, VINTF *manifest* entry | **NO** | absent |

**Xiaomi shipped the contract on this device but not the implementation.** The
framework is already prepared to bind `IMoseyService/default`; nothing provides it.
The missing pieces are exactly the seven files listed in §1.

That is a far better starting position than mosey-extended's, and it is what makes
this worth planning rather than dismissing.

---

## 3. The unresolved blocker: the Wi-Fi phy

The daemon is a thin AIDL shell — 553 strings, all NDK binder symbols
(`AParcel_*`, `AStatus_*`), no networking. The work lives in
`libmosey_daemon_ffi.so`, a 2.5 MB Rust library whose strings show nl80211
netlink, radiotap frame parsing, mDNS, and IEEE 802.11 structures.

Grepping that library for the Pixel-specific requirements:

| Token | Hits |
| --- | --- |
| `wonder` | 1 |
| `wondertap` | 0 |
| monitor-mode / `NL80211_IFTYPE_MONITOR` | 6 |
| `5745` / `149` | 3 |
| AWDL / Apple / AirDrop | 11 |

So **Xiaomi's build wants the same thing the Pixel build wants**: a `wonder`
interface in monitor mode on channel 149 (5745 MHz). It is the same upstream code,
integrated by a different OEM — not a reimplementation on top of standard Wi-Fi.

And on the target:

- nuwa's Wi-Fi is **Qualcomm** (`vendor/firmware/wlan`, `wlanmdsp.otaupdate`);
- no `wondertap`/`wonder_phy` support was found in nuwa's vendor Wi-Fi blobs (the
  only `wonder` hits were in `lib_misound_asc.so` and `libmiam.so`, audio
  libraries, i.e. false positives).

### 3.1 ANSWERED (2026-08-08, web research): the chipsets do not match

| Device | Chipset | Has mosey? |
| --- | --- | --- |
| **Xiaomi 17T Pro** — the only device shipping mosey | **MediaTek Dimensity 9500** | **yes** |
| **nuwa** (target) | Snapdragon (`vendor/firmware/wlan`, `wlanmdsp.otaupdate`) | no |
| **Xiaomi 17 Ultra** (`nezha`) | **Snapdragon 8 Elite Gen 5** | **not yet** |

So the single existing mosey build is **MediaTek**, and the target is **Qualcomm**.
`saygex.zip`'s `mosey_server` + `libmosey_daemon_ffi.so` were integrated against a
Dimensity Wi-Fi stack; nothing in them establishes that a Qualcomm driver can
provide the `wonder` monitor-mode interface they require.

Press coverage is consistent with that reading. As of the launch reporting the
feature is on the **17T Pro only**, and 9to5Google notes explicitly that other
HyperOS 3 devices may follow but that there are *"hardware-level restrictions that
could prevent that"* — which is exactly the `wonder` phy dependency seen in the
binary.

**Conclusion: a Qualcomm mosey build does not exist publicly yet.** The port cannot
be completed today, and no amount of file-shuffling changes that — the missing
piece is a Wi-Fi driver capability, not a file.

**The device to watch is the Xiaomi 17 Ultra (`nezha`)**: Snapdragon 8 Elite Gen 5,
i.e. the same vendor Wi-Fi family as nuwa. If Xiaomi extends AirDrop to it, that
firmware is the correct dump — it would be the first Qualcomm-integrated mosey
stack, and the comparison that matters. Current `nezha` builds are
`OS3.0.332.0.XPAEUXM` (EU) / `OS3.0.332.0.XPAMIXM` (global), Android 17, and they
do **not** carry the feature, so dumping one now yields nothing for this purpose.

Sources: [9to5Google](https://9to5google.com/2026/06/01/xiaomi-17t-pro-airdrop-support/),
[GSMArena — 17T Pro specs](https://www.gsmarena.com/xiaomi_17t_pro_5g-14651.php),
[GSMArena — 17 Ultra specs](https://www.gsmarena.com/xiaomi_17_ultra_5g-14380.php),
[HardwareZone](https://www.hardwarezone.com.sg/mobile/smartphones/xiaomi-17t-pro-apple-airdrop-file-transfer).

*(The supplied `saygex.zip` is truncated at source — the download matched
`Content-Length` 168 142 216 exactly, yet 7-Zip reports "Unexpected end of
archive". 66 entries were readable, which covered every mosey component, but the
Wi-Fi blobs needed for the comparison above may be in the missing tail.)*

---

## 4. Licensing and policy — three hard constraints

1. **`mosey_server` and `libmosey_daemon_ffi.so` are Google's proprietary
   binaries**, delivered to OEMs through `vendor/google/service/QuickShareExtension`.
   Neither Xiaomi's integration nor mosey-extended's GPL-2.0 module scripts grant
   any right to redistribute them. mosey-extended sidesteps this by requiring the
   user to supply the binary. **A public ROM that bakes them in is redistributing
   Google's proprietary code** — a materially different problem from the GPL
   questions already settled in D16, and not one D16 answers.
2. **`MoseyApp.apk` is likewise a Google/Xiaomi component**, not Bomb's to ship.
3. **The GMS feature-flag route is out of bounds.** mosey-extended enables the
   feature by planting `pixel_experience_*.xml` and phenotype flags so GMS believes
   it is on a Pixel, and its README warns users to keep TrickyStore and
   PlayIntegrityFork intact. That is Play-Integrity-adjacent spoofing, which the
   master plan §13 forbids Bomb from doing outright. **Bomb must not take that
   route.** On nuwa the flags may not even be needed — the hidden-API allowlist and
   the compat-matrix entry are already there — but the distinction has to be
   deliberate.

---

## 5. What Bomb can and cannot do here

**Cannot:** ship the binaries, spoof GMS into believing the device is a Pixel, or
provide the Wi-Fi driver capability if the chipset lacks it.

**Can, and it is a real contribution:**

1. **Capability detection.** `AirDropInterop` as a probed capability: is
   `IMoseyService/default` declared in the compat matrix, is a service actually
   registered, does `MoseyApp` exist, does the Wi-Fi driver expose a `wonder`
   interface. On nuwa today that resolves to *declared but unimplemented*, which is
   an honest, useful state to show instead of a dead toggle.
2. **The integration recipe as ROM tooling, not as bundled payload.** A `bomb.sh`
   subcommand that installs a user-supplied mosey bundle into the right partitions
   with the right contexts — vendor daemon + `mosey.rc` + VINTF manifest entry +
   `mi_ext` app + both permission XMLs. The user supplies the files they are
   entitled to; Bomb supplies the correctness.
3. **SELinux.** The service needs `mosey_exec` labelling, a domain, and the
   `IMoseyService/default` → service-context mapping. `hzz/tools/py/selinuxpatch.py`
   cannot declare new types today — the same blocker as `bomb_app`/`bombd`
   (`BOMB_PLAN.md` §7), so this rides on that work rather than duplicating it.
4. **An honest UI state.** "Supported by the framework, implementation not
   installed" / "installed but Wi-Fi driver lacks the required interface" — never a
   toggle that silently does nothing.

**Recommendation: keep this out of the committed milestone list until §3 is
answered.** It is well-researched now, its blockers are specific rather than vague,
and the cheapest next step is a chipset comparison, not code.

---

## 6. Next steps — revised after §3.1

The chipset comparison is done and it came back negative, so steps 1–2 of the
original plan are moot. What remains:

1. **Wait for a Snapdragon device to ship mosey.** `nezha` (Xiaomi 17 Ultra,
   Snapdragon 8 Elite Gen 5) is the one to watch. Re-check its release notes each
   HyperOS 3.x cycle; the moment AirDrop appears there, dump it — that build is the
   first Qualcomm mosey integration and the only one relevant to nuwa.
2. **Optional, cheap, and worth doing now:** on a booted nuwa, check whether the
   Qualcomm driver can create a monitor-mode interface on channel 149 at all
   (`iw list` / `iw phy phy0 info`, look for `monitor` in supported interface modes
   and 5745 MHz in the channel list). A negative answer closes the question
   permanently for this hardware; a positive one means only the userspace side is
   missing.
3. Keep `AirDropInterop` in `BombCapabilities` as a probe that reports **"declared
   by the framework, implementation not present"** on nuwa today. That is accurate,
   costs nothing, and is exactly the honest-unsupported-state rule.
4. Do **not** build the `bomb.sh mosey` install path yet — there is nothing valid
   to install for this chipset.
5. The redistribution question (§4) still has to be settled before anything ships
   publicly, whenever a valid build appears.
