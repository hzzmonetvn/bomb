# Bomb — Proxy / VPN Gateway ROM integration plan

Status: **proposed, awaiting review.** Nothing implemented.
Licence strategy **decided** (§1). Sink choice **still open** (§1, G1).

Grounding: [`research/PROXY_MODULES.md`](./research/PROXY_MODULES.md) — source-level
analysis of `Kr328/vpn-gateway` @ `5bc754a` and `vincentng295/magic_v2ray` @ `84c319f`,
plus the ROM tooling in `/home/hzzmonet/zk/hzz`.

Integrated into [`BOMB_PLAN.md`](./BOMB_PLAN.md) §4.12 / M11a. Companions:
[`ARCHITECTURE.md`](./ARCHITECTURE.md), [`ROM_INTEGRATION.md`](./ROM_INTEGRATION.md),
[`SYSTEM_PATCHES.md`](./SYSTEM_PATCHES.md).

*Licence analysis below is engineering judgement, not legal advice. The
conclusions are conservative; a formal review before public distribution is
cheap insurance.*

---

## 1. Decisions

### G2 — licence handling — **RESOLVED**

Policy set by the project owner:

> Repos that grant no licence → reimplement from the mechanism.
> Repos that grant a licence with conditions → comply with the conditions and
> reuse, to save time.

Applied to the two references, with the licence facts verified against the
GitHub API, not just the working tree:

| Repo | `GET /repos/…` → `license` | Decision |
| --- | --- | --- |
| `Kr328/vpn-gateway` | **`null`** — no LICENSE file, no header, no README statement | **Reimplement.** Nothing copied. Only affects the optional P7 feature (§9) |
| `vincentng295/magic_v2ray` | **`gpl-3.0`**, README §License grants use/modify/distribute under the same licence with source availability | **Reuse under GPL-3.0 compliance** (§3) |

A note on framing, once: both repos permit *cloning* — they are public and
`allow_forking: true`. Access and licence are separate things; `vpn-gateway`'s
problem is the absent **grant**, which is what makes copying impermissible.

### G1 — which sink? — **still open**

| Option | Meaning | Cost given the reuse decision |
| --- | --- | --- |
| **A. Bomb's own tunnel** *(recommended)* | Xray + tun2socks on `bombtun0`; local apps, and tethered clients via `allowTether` | the adopted engine already implements **both**, so LAN-gateway comes free |
| **B. Third-party VpnService** | route only tethered clients into whatever app owns the system `tun0` | needs the `vpn-gateway` mechanism reimplemented — the P7 work |
| **C. Both, mutually exclusive** | A or B selectable, never simultaneous | A + B + an interlock |

The reuse decision changes the calculus: **A now costs much less than B**, because
`magic_v2ray`'s engine already contains the tether path (`HOTSPOT_PREROUTING`,
`ip rule` 5025–5050, DNS DNAT), gated by an `allowTether` setting. Recommendation
is unchanged — **A first, B as optional P7** — but the gap between them widened.

---

## 2. Target architecture

```
┌──────────────────────────────────────────────────────────────────┐
│ Bomb UI (priv-app)                          [Bomb licence]       │
│   ui/network/ProxyScreen — profiles, per-app policy, state       │
└──────────────────────────┬───────────────────────────────────────┘
                           │ AIDL — typed, validated, BombResult
┌──────────────────────────▼───────────────────────────────────────┐
│ BombCoreService (:core, u:r:bomb_app:s0)    [Bomb licence]       │
│   ProxyProfile model + validation           (:domain, pure JVM)  │
│   config.json generator — written from Xray docs (§3.3)          │
│   writes  /data/system/bomb/proxy/{config.json, settings}        │
│   writes  ONE enumerated verb into the control FIFO              │
└──────────────────────────┬───────────────────────────────────────┘
                           │ FIFO: fixed verb set, `case` dispatch,
                           │ no eval, no strings from the caller
┌──────────────────────────▼───────────────────────────────────────┐
│ bomb-proxy engine  (u:r:bombproxy:s0)       [GPL-3.0 — §3]       │
│   adapted from magic_v2ray service.sh; started by init on a      │
│   property trigger; applies rules, supervises the cores          │
└───────────┬──────────────────────────────┬───────────────────────┘
            ▼                              ▼
   xray  (u:r:bombproxy_core:s0)   hev-socks5-tunnel (u:r:bombproxy_tun:s0)
   MPL-2.0 pinned prebuilt          MIT pinned prebuilt
   no NET_ADMIN, no tun, no exec    NET_ADMIN only for TUNSETIFF (§6)
```

Why the FIFO is an acceptable boundary under `CLAUDE.md`: `service.sh`'s control
loop dispatches with a fixed `case` over exactly **ten** verbs — `wait` (:934),
`apply_cur_iface` (:937), `start` (:947), `stop` (:951), `start_monitor` (:955),
`stop_monitor` (:963), `start_monitor_latency` (:968), `stop_monitor_latency`
(:976), `latency_heartbeat` (:981), `reset_mobile_network` (:987) — and returns
`1` for anything else — **verified, and there is no `eval` or `sh -c` anywhere in
`service.sh`.** Bomb therefore never executes a shell, never passes a string that
becomes a command, and cannot express an operation outside the enumerated set.
That is a typed IPC channel that happens to be implemented with a named pipe.

Note this is *stronger* than the `execv`-of-`iptables` design the previous draft
proposed: Bomb spawns no process at all.

---

## 3. GPL-3.0 compliance model

### 3.1 Why this is cheap here

The covered work is **shell scripts**. We convey them **in source form**, which
means:

- **§5 (Conveying Modified Source Versions)** — the Corresponding Source *is* the
  shipped file. Nothing to build, nothing to host, nothing to reconstruct.
- **§6 (Conveying Non-Source Forms / Installation Information)** — **never
  triggered**, because no object code of the covered work is conveyed. This is the
  clause that would otherwise be painful for a ROM on a consumer device, and
  keeping the engine as scripts avoids it entirely. **Do not compile the engine
  into a binary** — that is the one change that would pull §6 in.
- The rest of the image is, on the standard reading of §5's final paragraph, an
  **aggregate**: independent works on one distribution medium. Shipping a GPL-3.0
  component does not relicense the ROM, the Bomb app, or the Apache-2.0 framework
  patches. This reading depends on §3.3 holding — if Bomb's own code became a
  derivative, the aggregation argument would not save it.
- Xray-core (MPL-2.0) and hev-socks5-tunnel (MIT) run as **separate processes**,
  not linked — no combination question arises at all.

### 3.2 What we actually must do

1. Ship `LICENSE` (full GPLv3 text) in `system_ext/etc/bomb/proxy/`.
2. Preserve attribution. The upstream `.sh` files carry **no copyright headers**
   (verified — only `LICENSE`, `README.md` and `module.prop`'s
   `author=HuskyDG @vincentng295`). Bomb therefore *adds* a header to each
   adapted file naming the upstream project, its URL, the exact commit
   (`84c319fe6aa05f494575e58cab51e186f5dd7bc1`), and `SPDX-License-Identifier: GPL-3.0`.
3. **Mark every modified file** with a prominent notice of change and date (§5a).
   The concrete change list is §4 — it doubles as the `MODIFICATIONS.md` shipped
   alongside.
4. Keep the modified sources publicly available — satisfied by their presence in
   the image *and* by keeping them in `hzz/mods/bomb/proxy/` as the canonical
   copy.
5. Keep the "no warranty" notice intact.

### 3.3 The GPL firewall — the one rule that matters

**Nothing derived from `magic_v2ray` may enter the Bomb repository.**

Two specific creep vectors, both real:

- **The Xray config builder.** `webroot/main.js` (~3 500 lines) contains exactly
  the JSON-generation logic Bomb needs (`main.js:2810` builds `inbounds`, and the
  outbound/routing builders sit near it). Porting it to Kotlin would make
  `:system-service` a GPL-3.0 derivative, which then argues over the app.
  **Bomb's generator must be written from Xray-core's own documentation and JSON
  schema** (MPL-2.0, and a config schema is not `magic_v2ray`'s expression anyway).
  This is the single highest-risk temptation in the whole plan.
- **Coupling.** If Bomb's service and the engine become inseparable they risk
  being read as one work. The practical test, and it must stay true:
  **the engine runs standalone.** `echo start > <fifo>` from `adb shell` works
  with Bomb never installed, and Bomb capability-probes for the engine and reports
  `Unsupported` when it is absent.

Everything the WebUI did that Bomb still needs — profile storage, validation,
config generation — is **new Bomb code under Bomb's licence**, not a port.

### 3.4 Consequence for `research/REFERENCES.md` §2

The blanket rule *"no copyleft source enters the Bomb repository"* is amended to:

> Copyleft code may ship **as a segregated component under its own licence**, in
> its own directory, with its own LICENSE and modification notices, behind a
> documented process boundary. It may never be mixed into, ported into, or
> statically combined with Bomb's own sources.

`REFERENCES.md` has been updated to match.

---

## 4. The adopted engine, and every modification to it

Reused, with modification:

| File | Lines | Fate |
| --- | --- | --- |
| `service.sh` | 1084 | **adapted** → `bomb-proxy.sh` (M1–M8) |
| `proxy_control.sh` | 128 | **adapted** → `bomb-proxyctl.sh`, retained mainly as a debug/recovery CLI; Bomb writes the FIFO directly |
| `customize.sh` | 95 | **deleted** — Magisk installer framework |
| `action.sh` | 45 | **deleted** — root-manager button |
| `uninstall.sh` | 147 | **deleted** — replaced by ROM removal |
| `webroot/*` | 6 199 | **deleted** — this is where `ksu.exec` lives (§3.3) |

Portability was verified rather than assumed. The scripts are already written for
a plain Android userspace, not for Magisk's busybox:

- `grep_prop()` carries the comment *"dos2unix is not present on every toybox
  build; tr is."*, and another comment names `/system/bin/sh` / mksh explicitly;
- `jq` was **removed** upstream (*"jq was a 2 MB dependency for this single
  lookup; sed does it adequately"*); settings parsing is `base64 -d | tr | sed`;
- `curl` is a **bundled** binary (`$BINDIR/curl`), not a system dependency;
- the external command set is otherwise `cat chmod date echo grep head kill mkdir
  mkfifo mknod mv printf rm sed sleep stat timeout touch tr mount umount getprop`
  — all toybox.

**One real gap found:** `sysctl -w net.ipv4.conf.{all,default}.rp_filter=2` at
`service.sh:545-546`. Android does not ship `sysctl`; toybox does not enable it.
Two lines, and the script already uses the correct pattern elsewhere
(`echo "0" > /proc/sys/net/ipv4/conf/$TUN_NAME/rp_filter` at :514).

### Modification list (this is the shipped `MODIFICATIONS.md`)

| # | Change | Reason |
| --- | --- | --- |
| **M1** | Replace the `until [ "$(getprop sys.boot_completed)" = "1" ]` busy-wait (up to 600 s) with an `init` trigger gated on `sys.boot_completed`. **Keep a bounded wait for `/data/misc/net/rt_tables`.** | `init` owns the lifecycle, so polling `getprop` in a loop is wasted. But `class late_start` does **not** guarantee that `netd` has published `rt_tables`, and the engine cannot resolve a routing-table id without it — deleting that wait (as an earlier draft of this plan proposed) would have introduced a first-boot race. The `rt_tables` wait is cheap, correct, and stays |
| **M2** | Replace the `/dev/sysctl_stubs` tmpfs mounted `context=u:object_r:proc_net:s0` with `/dev/bomb_proxy`, created by `init` in the `.rc` with a proper `bomb_proxy_runtime` label | mislabelling a tmpfs as `proc_net` borrows a type that belongs to procfs; and creating it in `init` removes the daemon's need for mount capability |
| **M3** | Replace `/proc/<pid>` bind-mount liveness tracking with **pid + starttime** (`/proc/<pid>/stat` field 22) for all four tracked children (`xray`, `hev-socks5-tunnel`, the interface monitor, the latency monitor) | same immunity to PID reuse, **without `CAP_SYS_ADMIN`** — this is what lets §7 drop that capability. **Parsing pitfall:** field 2 (`comm`) may contain spaces and parentheses, so field 22 must be taken from the substring *after the last* `)`, never by naive whitespace splitting — the same trap documented in [`research/AOSP_PROCESS_MANAGEMENT.md`](./research/AOSP_PROCESS_MANAGEMENT.md) §7 |
| **M4** | Raw marks `1` and `255` → a single **masked** mark in a bit range that `netd` does not use | `netd` owns the socket mark: netId in the low 16 bits, plus flag bits above it (`explicitlySelected`, `protectedFromVpn`, permission bits). Upstream's raw `1`/`255` sit inside netId space and work only because its `ip rule` priorities (1000/1010) are evaluated ahead of netd's. **The exact free bit must be read off the target's `system/netd` `fwmark.h` at P0 — do not pick a constant from memory**, and once chosen, record it here |
| **M5** | Settings source: `$DATADIR/settings.base64` parsed with `sed` → a Bomb-written `key=value` file, read with the same `grep_prop` helper | Bomb owns validation; base64-of-JSON parsed by `sed` is a fragile contract |
| **M6** | `sysctl -w …rp_filter=2` (`:545-546`) → `echo 2 > /proc/sys/net/ipv4/conf/{all,default}/rp_filter` | `sysctl` does not exist on the target userspace |
| **M7** | Paths: `MODDIR=/data/adb/modules/magic_v2ray` → `/system_ext/etc/bomb/proxy`; `DATADIR=/data/adb/magic_v2ray` → `/data/system/bomb/proxy` | ROM layout; `bomb_proxy_data_file` label |
| **M8** | **Add**: `SIGTERM` teardown, a `--teardown` one-shot mode for boot cleanup, a bounded restart watchdog that gives up and clears the enable property, fail-open default | upstream has no teardown-on-stop and no give-up path; §7 |
| **M9** | Delete `customize.sh`, `action.sh`, `uninstall.sh`, `webroot/` | Magisk/KernelSU-specific; `webroot` is the `ksu.exec` root-shell bridge |
| **M10** | Remove the runtime `mknod /dev/net/tun c 10 200` (`service.sh:1041-1046`). Android already exposes the tun device as **`/dev/tun`** (`0660 system vpn` from `ueventd`); `hev-socks5-tunnel` wants the Linux-standard path. Create it once from `init`: `mkdir /dev/net 0755 root root` + `symlink /dev/tun /dev/net/tun` | removes the need for `CAP_MKNOD` in the engine domain entirely. If the symlink is absent at start, the engine reports the capability unsupported rather than creating device nodes at runtime |
| **M11** | `proxy_control.sh` is retained as a **debug/recovery CLI only**, and its `send_cmd()` — which does `timeout 10 sh -c "echo '$cmd' > '$PIPE_FILE'"` (`proxy_control.sh:57`) — must never be invoked with a caller-derived argument | today `$cmd` is always one of the script's own `case` literals, so it is safe; interpolating anything external into that `sh -c` would turn a typed channel back into shell injection. Bomb writes the FIFO **directly** and never execs this script |

Behaviour deliberately **kept** because it encodes edge cases that take weeks to
rediscover on a device: fake-ICMP DNAT to `127.0.0.1` (hev does not proxy ICMP),
MSS clamp 1350 on `FORWARD -o <tun>`, hiding the SOCKS inbound from app UIDs
(`--uid-owner 9999-2147483647 -j REJECT`), `rp_filter=0` on the tun only, IPv6
DNS dropped when the tunnel is IPv4-only, bypass sets for loopback/RFC1918/
link-local/multicast/class-E, reading the routing table id from
`/data/misc/net/rt_tables` and re-applying on interface change.

**Not adopted from either project:** the bind-mount stub over
`/proc/sys/net/ipv4/ip_forward`. That is `vpn-gateway`'s trick (`magic_v2ray` does
not do it) and it makes `cat` of that sysctl report the opposite of reality. Bomb
sets forwarding when tether mode is on and re-applies on the netlink event if
`netd` resets it; if that proves insufficient, the answer is `write
/proc/sys/net/ipv4/ip_forward 1` from `init`, not a file that lies.

---

## 5. Component inventory and provenance

| Artifact | Source | Licence | Ships at | Verification |
| --- | --- | --- | --- | --- |
| `bomb-proxy.sh`, `bomb-proxyctl.sh` | adapted from `magic_v2ray` @ `84c319f` | **GPL-3.0** | `system_ext/etc/bomb/proxy/` | in-repo canonical copy |
| `LICENSE`, `MODIFICATIONS.md` | GPLv3 text + §4 | — | same dir | present-file check |
| `xray` | XTLS/Xray-core, pinned release | MPL-2.0 | `system_ext/bin/bomb/xray` | SHA-256 in `MANIFEST.sha256`, checked by `bomb.sh` |
| `hev-socks5-tunnel` | heiher, pinned release | MIT | `system_ext/bin/bomb/` | same |
| `curl` | bundled by upstream | curl licence | `system_ext/bin/bomb/` | same — or drop it, see below |
| `geoip.dat` / `geosite.dat` | pinned release | per source | `system_ext/etc/bomb/proxy/` | same |
| upstream licences + NOTICE | — | — | `system_ext/etc/bomb/proxy/licenses/` | present-file check |

**No build-time downloads.** Upstream's `build.sh` fetches Xray with
`curl -fsSL` and **no checksum**; Bomb vendors the binaries under
`hzz/mods/bomb/proxy/arm64-v8a/` with a checked-in `MANIFEST.sha256`, and
`bomb.sh` refuses to build the image on a hash mismatch.

Two size/scope calls to make in P1:

- `geoip.dat` + `geosite.dat` are **~30 MB** together. That is a lot of
  `system_ext` for an opt-in feature. Consider shipping without them and letting
  Bomb download them into `bomb_proxy_data_file` on first enable.
- The bundled `curl` (~2.9 MB) exists only for the latency probe
  (`curl --socks5-hostname …`). Consider dropping it and either removing the
  latency feature from v1 or reimplementing the probe in Bomb.

---

## 6. SELinux plan

New types:

```text
bombproxy              engine domain (the adapted shell script)
bombproxy_tun          hev-socks5-tunnel
bombproxy_core         xray
bombproxy_exec         label for the engine script
bombproxy_tun_exec     label for hev-socks5-tunnel
bombproxy_core_exec    label for xray
bomb_proxy_data_file   /data/system/bomb/proxy(/.*)?
bomb_proxy_runtime     /dev/bomb_proxy(/.*)?  — dir, files and the control FIFO
                       (one type, per-class rules for dir / file / fifo_file)
bomb_prop              persist.sys.bomb.* property namespace
```

**Three domains, not two.** The naive split ("applier" vs "cores") is wrong:
`hev-socks5-tunnel` creates the tun device itself, and `TUNSETIFF` requires
`CAP_NET_ADMIN` — so it cannot be confined alongside `xray`. The split that
actually buys something is isolating **`xray`, the process that parses hostile
input from the network**, from everything holding `NET_ADMIN`:

| Domain | Needs | Must **not** have |
| --- | --- | --- |
| `bombproxy` (engine) | `net_admin`, `net_raw`; `execute_no_trans` on `/system/bin/{ip,iptables,ip6tables}` and `/system/bin/sh`; read `/data/misc/net/rt_tables`; netlink route socket; rw `bomb_proxy_data_file` + `bomb_proxy_runtime`; transition to the two core domains | `sys_admin` (M3 removes the need), `mknod` (M10 removes the need) |
| `bombproxy_tun` (`hev`) | `net_admin` (TUNSETIFF); rw `tun_device`; connect to the SOCKS port; read its `tunnel.yml` | exec of anything; iptables; `bomb_proxy_data_file` |
| `bombproxy_core` (`xray`) | plain network sockets; read `config.json` + geo data | `net_admin`, `net_raw`, tun access, exec, write anywhere |
| `bomb_app` | write the control FIFO; rw `bomb_proxy_data_file`; `set_prop` on `bomb_prop` | everything above |

Explicitly never granted: `sys_admin`, `mknod`, generic `sysfs:file write`,
generic `proc:file write`, `allow bombproxy *:* *`.

### Open design gap — how does a priv-app write the FIFO?

SELinux can permit `bomb_app` → `bomb_proxy_runtime:fifo_file write`, but **DAC
still applies**, and Bomb is an ordinary priv-app uid, not `system`. A FIFO created
`root:root 0600` by the engine is unwritable by Bomb regardless of policy. The
plan does not currently resolve this. Options, best first:

1. **A Bomb GID granted through a permission→group mapping.** The ROM defines a
   Bomb AID in the OEM-reserved range and maps it in
   `/system/etc/permissions/platform.xml`:
   `<permission name="com.hzzmonet.zkbomb.permission.PROXY_CONTROL"><group gid="bomb_proxy"/></permission>`,
   granted to Bomb via its privapp allowlist. The app then receives the GID as a
   supplementary group and the FIFO is `root:bomb_proxy 0620`. This is the
   platform's own supported mechanism for exactly this problem, and it layers DAC
   and MAC correctly.
2. **A file drop instead of a FIFO.** Bomb writes a small command file in
   `bomb_proxy_data_file` (which it already owns); the engine watches it. Removes
   the permission puzzle, costs a watch loop, and loses the FIFO's natural
   serialisation.
3. **Widen the FIFO to `0666` and rely on SELinux alone.** Works, but DAC-open
   channels into a `NET_ADMIN` service are the kind of thing that ages badly.

**Decide this in P2**, before the policy is authored — it determines whether the
control channel is a FIFO at all.

### Verify on target, do not assume

- `seclabel` on a service whose exec is `/system/bin/sh` — this is what the keyword
  exists for, but confirm on the target Android version that the forced transition
  is accepted rather than silently ignored.
- `execute_no_trans` vs a transition for `ip`/`iptables`: depends on how those
  binaries are labelled on this image.

### Work required in `hzz/tools/py/selinuxpatch.py`

Unchanged from [`BOMB_PLAN.md`](./BOMB_PLAN.md) §7 — the patcher today emits only
`(allow …)` over **pre-existing** types (`_read_cil_metadata` collects `(type …)` /
`(typeattribute …)`; `_format_allow_rule` emits allow rules), and silently drops
rules naming unknown types. All eight types above would vanish. Required:

1. emit CIL **type declarations** before rules;
2. emit `typeattributeset` membership (`domain`, `coredomain`, `file_type`,
   `data_file_type`);
3. emit the `init → bombproxy` transition and `bombproxy → bombproxy_core`;
4. write `file_contexts`, `seapp_contexts`, `property_contexts` entries;
5. implement the dead `bomb.sh selinux` subcommand as the entry point;
6. **fail loudly** when `_runtime_policy_compile_available()` is false instead of
   printing a line and skipping.

Until (1)–(3) exist the feature cannot ship. Running the engine in a borrowed
existing domain is precisely the outcome this plan exists to avoid.

---

## 7. Lifecycle, failure modes and recovery

`system_ext/etc/init/bombproxy.rc`:

```
on post-fs-data
    # runtime dir; ownership/mode depend on the FIFO decision in §6
    mkdir /dev/bomb_proxy 0750 root root
    restorecon /dev/bomb_proxy
    # M10: expose Android's /dev/tun at the Linux-standard path so the engine
    # never needs CAP_MKNOD
    mkdir /dev/net 0755 root root
    symlink /dev/tun /dev/net/tun

service bombproxy /system/bin/sh /system_ext/etc/bomb/proxy/bomb-proxy.sh
    class late_start
    user root
    group root net_admin net_raw inet
    capabilities NET_ADMIN NET_RAW
    seclabel u:r:bombproxy:s0
    disabled

service bombproxy_teardown /system/bin/sh /system_ext/etc/bomb/proxy/bomb-proxy.sh --teardown
    class late_start
    seclabel u:r:bombproxy:s0
    oneshot
    disabled

on property:sys.boot_completed=1 && property:persist.sys.bomb.proxy.enabled=1
    start bombproxy

on property:persist.sys.bomb.proxy.enabled=1 && property:sys.boot_completed=1
    start bombproxy

on property:persist.sys.bomb.proxy.enabled=0
    stop bombproxy
    start bombproxy_teardown
```

Both orderings of the compound trigger are listed deliberately: `init` fires a
compound trigger only when the *last* of its properties changes, so a single form
would miss whichever event happens second (enable-at-runtime vs
enabled-across-reboot).

`seclabel` forces the domain despite the script being exec'd through
`/system/bin/sh`, which is what that keyword exists for. `capabilities` replaces
"run as full root" — M3 is what makes dropping `CAP_SYS_ADMIN` possible.

Failure handling, none of which upstream has in a ROM-appropriate form:

1. **Off by default.** `persist.sys.bomb.proxy.enabled` defaults to `0`; the ROM
   ships the engine installed and inert.
2. **Fail-open by default.** If the tunnel dies, tear down and restore direct
   connectivity. A "block traffic if the tunnel drops" option exists, opt-in and
   clearly labelled, because its failure mode is *no network at all*.
3. **Bounded watchdog.** After N failed starts in a window, tear down, set
   `persist.sys.bomb.proxy.enabled=0`, and record the reason in `ProxyState`.
   No infinite restart loop.
4. **Recovery without the UI.** Because the switch is a property with an `init`
   trigger, `adb shell setprop persist.sys.bomb.proxy.enabled 0` recovers a device
   whose config broke networking — the UI never has to open.
5. **Teardown on stop and at boot.** `--teardown` clears rules left by an unclean
   shutdown. (`vpn-gateway` has no teardown at all; that is a bug, not a design.)
6. **Visible while active.** Persistent notification, and later a Bomb Bridge live
   event. Upstream markets itself as *"cannot be turned off"* and *"invisible to
   the OS"*; a system component must be the opposite.
7. **Coexistence with a real `VpnService`.** If the user starts an ordinary VPN
   app while Bomb's proxy is running, the platform installs its own routing and
   both claim the traffic. Bomb registers for VPN state and, on a system VPN
   coming up, **stops and tears down** with a typed reason rather than
   interleaving. The user's explicitly-started VPN wins — it is a foreground,
   deliberate action, and Bomb's engine is the background one.
8. **Refuse to fight another proxy.** A side-loaded Magisk/KernelSU proxy module
   claims the same `ip rule` priority band (both references use 5000–6000, and
   `magic_v2ray` also uses 1000/1010 — see
   [`research/PROXY_MODULES.md`](./research/PROXY_MODULES.md) §4). Before applying,
   the engine checks whether rules already exist at its priorities or whether
   another tun holds the default route in its table, and **refuses to start with a
   typed reason** rather than interleaving two rule sets that neither side will
   clean up. Bomb keeps upstream's priority numbers — they are proven against
   `netd`'s own ordering — and treats them as owned, rather than renumbering into
   an untested band.

---

## 8. Bomb's typed control surface

Added to `IBombService` (each method passes the five-point AIDL checklist in
[`BOMB_PLAN.md`](./BOMB_PLAN.md) §8):

```aidl
ProxyCapabilities getProxyCapabilities();

List<ProxyProfile> getProxyProfiles();
BombResult         upsertProxyProfile(in ProxyProfile profile);
BombResult         deleteProxyProfile(long profileId);
BombResult         setActiveProxyProfile(long profileId);

BombResult         setProxyEnabled(boolean enabled);
ProxyState         getProxyState();          // stopped/starting/running/failed + reason
BombResult         setProxyAppPolicy(in ProxyAppPolicy policy);
BombResult         setTetherProxyEnabled(boolean enabled);
ProxyStats         getProxyStats();          // byte counters + uptime only
```

`ProxyProfile` is a **typed model** — protocol enum, host, port, transport enum,
TLS settings, credentials — never a JSON blob. A free-form JSON parameter would be
`writeFile` wearing a costume. `:domain`'s `ProxyProfileValidator` enforces:
hostname-or-IP, port 1–65535, enum membership, and total length bounds.

### Per-app policy is keyed by (userId, package), never by uid

`-m owner --uid-owner` matches a **full Android uid** (`userId * 100000 + appId`),
so a rule written for user 0 does not cover the same app in a work profile or
secondary user, and a package reinstall can change the uid under a stored rule —
silently proxying nothing, or the wrong app.

`ProxyAppPolicy` therefore stores `(userId, packageName)` pairs and resolves them
to uids **at apply time**, re-resolving on `PACKAGE_ADDED` / `PACKAGE_REPLACED` /
`PACKAGE_REMOVED` and on user add/remove. Storing raw uids would repeat exactly the
mistake this project criticised HMA-OSS for in
[`research/HMA_OSS.md`](./research/HMA_OSS.md) §3 — keying policy by an identity
that is not stable.

### The FIFO is write-only — state comes from elsewhere

Two consequences the control design must handle explicitly:

- **No return value.** A verb written to the FIFO produces no response on that
  channel. `getProxyState()` reads the engine's state files under
  `bomb_proxy_runtime` / `bomb_proxy_data_file` (the engine already maintains
  liveness and status there), never the FIFO.
- **Completion barrier.** Upstream's `send_cmd_sync` writes the command and then a
  second `wait` verb, which the loop only consumes after finishing the previous
  one. Bomb must replicate that two-write barrier, with a timeout, or it will
  report state that the engine has not yet reached.

`ProxyCapabilities`, all probed and honestly reported:

```text
EnginePresent       engine script + both cores present and hash-matched
TunSupported        /dev/net/tun openable
NetfilterSupported  a probe rule actually applies and can be removed
TetherSupported     forwarding can be enabled and survives a network event
Ipv6Supported       v6 tunnel usable
```

Anything unsupported renders an explicit unsupported state — never a dead toggle,
never a fabricated "connected".

---

## 9. Phases

| Phase | Content | Exit criteria |
| --- | --- | --- |
| **P0** ✅ *(partly)* | **Done 2026-08-08** on `ZKOS_NUWA_OS3.0.310.0.WMBCNXM_260808` (`system_a`+`vendor_a` from `super.img`): `iptables`/`ip6tables`/`ip` all present, no `nftables` — PR1 cleared. **Still open:** `/dev/tun` node presence and `sysctl` absence (both need the booted device or a wider image sweep), and G1/D18 remains a product decision | the iptables answer is now a verification log, not an assumption |
| **P1** | Vendor pinned cores + engine under `hzz/mods/bomb/proxy/arm64-v8a/`, `MANIFEST.sha256`, `LICENSE`, `MODIFICATIONS.md`, licences dir; `bomb.sh` hash gate. Decide the geo-data and `curl` questions (§5) | build fails on a tampered binary; licence files present in the image |
| **P2** | Extend `selinuxpatch.py` (§6 1–6); implement `bomb.sh selinux` | all eight types exist in the compiled policy on a flashed image; a missing-recompiler ROM fails the build loudly |
| **P3** | Port the engine: M1–M11. `init` rc, teardown, watchdog | engine starts on property, applies rules, tears down cleanly; runs standalone with Bomb absent (the §3.3 arm's-length test) |
| **P4** | Bomb service: `ProxyProfile` + validator, **config generator written from Xray docs**, settings writer, FIFO client, capability probes | selected-uid traffic egresses through the tunnel; disabling restores direct traffic completely |
| **P5** | Bomb UI: profile CRUD, per-app policy, state, stats | no fabricated state; unsupported capabilities visibly unsupported |
| **P6** | Tether mode — expose the engine's `allowTether` through the typed API | hotspot client traffic proxied; disabling leaves the hotspot working |
| **P7** *(optional, G1-B)* | Reimplement `vpn-gateway`'s mechanism: route tethered clients into a third-party VpnService `tun0`. **Written from `PROXY_MODULES.md` §1.1, not from the original file.** Rules renumbered into a Bomb-owned band (7000–7100), interlocked against P6 | enabling both returns `InvalidArgument`; the two rule sets are provably disjoint |

---

## 10. Risks

| # | Risk | Impact | Mitigation |
| --- | --- | --- | --- |
| ~~PR1~~ | ~~`iptables` absent / replaced by `nftables`~~ | **CLEARED 2026-08-08** | Verified in the extracted image: `system/bin/iptables` is a real 503 KB binary, `ip6tables`/`iptables-save`/`iptables-restore` are symlinks to it, `ip` is present (425 KB), and the `netutils-wrapper-1.0` wrappers exist. No `nft`/`nftables` anywhere. The adopted engine's mechanism is viable on this device |
| PR2 | `selinuxpatch.py` cannot declare types today | engine has no domain; unshippable | P2, shared with `BOMB_PLAN.md` §7 |
| PR3 | fwmark collision with `netd` netId space | intermittent, hard-to-debug routing failures | M4 — masked mark outside netId bits; record the chosen bit |
| PR4 | `netd` resets `ip_forward` / rewrites rules on network events | tether breaks silently | netlink-driven re-apply; **no** stub bind-mount |
| PR5 | Bad profile ⇒ no network and no UI access | user-visible brick | off by default, fail-open default, property kill switch, bounded watchdog (§7) |
| **PR6** | **GPL boundary creep** — porting `webroot/main.js`'s config builder into Kotlin, or coupling Bomb to the engine so tightly they read as one work | `:system-service`, and by argument the app, become GPL-3.0 derivatives | §3.3: generator written from Xray docs; engine must stay standalone-runnable; a review checklist item on every PR touching `proxy/` |
| **PR10** | **Compiling the engine into a binary** at some later "cleanup" | triggers GPL-3.0 §6 Installation Information on a User Product | recorded here as a standing constraint: the covered work ships as source, permanently |
| PR7 | 30 MB geo data + 2.9 MB curl in `system_ext` for an opt-in feature | image size | decide in P1; prefer on-demand download into `bomb_proxy_data_file` |
| PR8 | Pinned cores go stale (CVEs in Xray / hev / curl) | security exposure baked into the image | versions recorded in `MANIFEST.sha256`; core updates are a tracked ROM update item |
| PR9 | Scope drift into a full VPN-client product | violates `CLAUDE.md` scope discipline | v1 has no subscription management, no node marketplace, no auto-updating rule sets |
| **PR11** | Upstream `magic_v2ray` moves on; Bomb's fork diverges | losing upstream bug fixes | pin the base commit in the header of every adapted file; keep M1–M11 as a rebasable patch series, not a rewrite |

---

## 11. Testing

`:domain`, pure JVM:

- `ProxyProfileValidator`: host/port/enum/uid bounds; rejection of every malformed
  field, including values that would be dangerous if they ever reached a config;
- config generator: golden-file tests — a given `ProxyProfile` produces an exact
  `config.json`, so any change is a reviewable diff;
- settings writer: golden-file, same reason;
- verb set: the FIFO client can emit **only** the enumerated verbs (a compile-time
  enum, asserted in test).

Engine — **mostly device-only, and this plan should not pretend otherwise.** The
script depends on `getprop`, `/data/misc/net/rt_tables`, Android's `ip`/`iptables`
builds and `netd`'s rule ordering; a Linux netns container reproduces none of that
faithfully. What *is* host-testable:

- the pure helpers extracted into functions — `read_table_index` parsing,
  pid+starttime parsing (M3, including a `comm` containing spaces and
  parentheses), settings parsing (M5);
- rule-template generation, if the rule construction is factored out of the apply
  path into a printable form — worth doing purely so it can be golden-file tested.

Everything else is device verification:

- teardown restores the exact pre-start rule set: capture → apply → teardown →
  compare;
- watchdog gives up after N failures and clears the property;
- pid+starttime liveness (M3) survives PID reuse.

Device, reported honestly including failures:

- selected-app traffic egresses through the tunnel, non-selected does not;
- **DNS does not leak** — query with the tunnel up, capture at the router;
- **IPv6 does not leak** when the tunnel is IPv4-only;
- Wi-Fi ↔ mobile switch keeps the tunnel and re-applies rules;
- killing a core restores connectivity within the watchdog window;
- tether mode proxies a second device; disabling it leaves that device online;
- `setprop persist.sys.bomb.proxy.enabled 0` recovers a device with a broken
  profile, UI never opened;
- the engine runs with Bomb uninstalled (arm's-length test).

---

## 12. Known unresolved consequences

Found on a second review pass. None of these blocks the plan; all of them would be
dishonest to leave unstated.

1. **This adds an architectural layer `CLAUDE.md` does not list.** The prescribed
   separation is UI → domain/data → Bomb core service → AIDL → minimal native
   `bombd` → device backends. A root-privileged **shell engine** is none of those.
   It is justified by D16 (reuse to save time) and contained by §3.3 and the FIFO
   boundary, but it is a deliberate deviation and should be approved as one rather
   than discovered later.
2. **Per-app data accounting will be wrong while the proxy is on.** Traffic
   egresses through the tun owned by the engine, so Android's per-uid network
   statistics — and therefore Bomb's own Network Control telemetry and the system
   Data Usage screen — will attribute it to the tunnel, not to the originating
   app. Bomb must not present per-app data figures as accurate in this state; the
   honest UI is to mark them unavailable while the proxy is active.
3. **Credentials are plaintext at rest, and Bomb cannot fix that.** `xray` needs
   the server address, UUID and keys in clear in `config.json`. Android Keystore
   can protect Bomb's own profile store, but the generated config must be readable
   by the core, so file permissions plus SELinux remain the only real control —
   the same position upstream is in. The plan should claim no better.
4. **`ProxyStats`' data source is unspecified.** Byte counters must come from a
   named source (the tun's `/proc/net/dev` line, or `iptables -nvx` chain
   counters) with defined reset semantics on restart. Decide in P4.
5. **Battery cost of the always-on monitors is unmeasured.** The engine keeps an
   interface-monitor child alive for its whole lifetime. The latency probe is
   already heartbeat-gated upstream, which is correct; the interface monitor is
   not, and arguably cannot be. `CLAUDE.md`'s "no expensive polling without active
   consumers" needs an explicit measurement before this is called compliant.

---

## 13. Explicitly not built

- No WebUI, no `ksu.exec`-style bridge in any form.
- No subscription / node-list management, no remote node import in v1.
- No bind-mount stub over any `/proc/sys` path.
- No mislabelled tmpfs — Bomb declares its own types.
- No "invisible" or "cannot be disabled" behaviour.
- No traffic inspection beyond byte counters: no payload capture, no TLS
  interception (consistent with master plan §16 and §18).
- No compiling the GPL engine into a binary (PR10).
- No shipping both sinks simultaneously.
