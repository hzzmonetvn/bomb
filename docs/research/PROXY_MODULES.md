# Research — vpn-gateway and Magic V2Ray (transparent-proxy modules)

```text
References:
https://github.com/Kr328/vpn-gateway.git       @ 5bc754aead2e6d0674e8c87290793c231732861d
  License: NONE — no LICENSE file, no licence header, no statement in README
           or module.prop. All rights reserved by default.

https://github.com/vincentng295/magic_v2ray.git @ 84c319fe6aa05f494575e58cab51e186f5dd7bc1
  (commit date 2026-08-02)   License: GPL-3.0 (LICENSE)
  Bundled: Xray-core (MPL-2.0, bin/LICENSE + bin/README.md),
           hev-socks5-tunnel (MIT), curl, geoip.dat + geosite.dat

Cross-checked against: /home/hzzmonet/zk/hzz (ROM patch tooling)
```

> **License verdict up front.** `vpn-gateway` carries **no licence at all** —
> not GPL, not "unlicensed-but-free": absent a licence grant, copying any part of
> it is not permitted. Its mechanism (130 lines of standard `ip rule` /
> `iptables`) is public networking knowledge and may be reimplemented, but the
> file may not be vendored, adapted, or pasted from.
> `magic_v2ray` is GPL-3.0: vendoring its scripts into a ROM image **is
> distribution**, and the resulting combined work carries copyleft obligations.
> This collides with open decision **D14** (Bomb's licence, still unfixed).
> Recorded in [`REFERENCES.md`](./REFERENCES.md).

---

## 1. What each one actually does

They sound complementary. They are not — see §4.

### 1.1 `vpn-gateway` — LAN clients → a third-party VpnService

Four functional files: `module.prop`, `service.sh`, `Makefile`, `META-INF/`.
`service.sh` is the whole product.

Purpose (README): *"enable natless routing LAN traffic to VpnService"* — i.e. the
phone becomes a gateway so that **other devices** on its hotspot/LAN reach the
internet through whatever VPN app owns `tun0`, **without NAT**, so the VPN app
sees the clients' original source addresses.

Mechanism, verbatim from `service.sh`:

```text
ip rules (ip_rules_for):
    iif lo goto 6000                                    pref 5000
    iif tun0 lookup main suppress_prefixlength 0        pref 5010
    iif tun0 goto 6000                                  pref 5020
    from 10.0.0.0/8      lookup <tun0 table>            pref 5030
    from 172.16.0.0/12   lookup <tun0 table>            pref 5040
    from 192.168.0.0/16  lookup <tun0 table>            pref 5050
    nop                                                 pref 6000

iptables (iptables_rules_for):
    FORWARD -s {10/8,172.16/12,192.168/16} -o tun0 -j ACCEPT
    FORWARD -i tun0 -j ACCEPT
    nat PREROUTING ! -i tun0 -s <each private range> -p udp --dport 53 \
        -j DNAT --to 1.1.1.1

ip6tables:
    -I FORWARD -j REJECT --reject-with icmp6-no-route
```

Three details worth carrying forward as *behaviour*:

1. **IPv6 fails closed.** The module proxies IPv4 only, so it rejects forwarded
   IPv6 outright instead of letting it leak around the tunnel. Correct default.
2. **The routing-table index is discovered, not assumed.** `read_table_index`
   parses `/data/misc/net/rt_tables` for the `tun0` entry, and an
   `inotifyd - /data/misc/net::w` loop re-reads it and re-applies the rule set
   whenever the network changes. Android renumbers these tables freely; a
   hardcoded table id would silently stop working.
3. **`ip_forward` is defended by deception:**

```sh
echo 1 > /proc/sys/net/ipv4/ip_forward
echo 0 > /dev/ip_forward_stub
chown $(stat -c '%u:%g' /data/misc/net/rt_tables) /dev/ip_forward_stub
chcon $(stat -Z -c '%C' /data/misc/net/rt_tables) /dev/ip_forward_stub
mount -o bind /dev/ip_forward_stub /proc/sys/net/ipv4/ip_forward
```

Forwarding is switched on, then a dummy file containing `0` is bind-mounted over
the sysctl so that when `netd` later writes `0` there, the write lands on the
stub and the kernel keeps forwarding. The owner and SELinux context are copied
from `rt_tables` so netd's write still succeeds and nothing is logged as denied.

**This is a deliberate deception of `netd`, and Bomb must not ship it.** It
leaves the system in a state where `cat /proc/sys/net/ipv4/ip_forward` reports the
opposite of reality — undiagnosable for anyone who did not write it, and directly
against the project rule that state must be observable and honest. §5 gives the
supported alternative.

Weaknesses: `tun0` is hardcoded; DNS is hardcoded to `1.1.1.1`; there is no
teardown path other than a rule-change (uninstall leaves rules behind until
reboot); rules are re-split by the shell (`while read -r rule; do $iptables -I
$rule; done`) which relies on word splitting and breaks on any argument
containing a space.

### 1.2 `magic_v2ray` — full transparent proxy engine

| File | Lines | Role |
| --- | --- | --- |
| `service.sh` | 1084 | the engine: boot wait, runtime dirs, FIFO control loop, process supervision, all networking |
| `proxy_control.sh` | 128 | thin client — writes commands into the control FIFO with a liveness check and `timeout 10` |
| `action.sh` | 45 | manager "Action" button toggle |
| `customize.sh` | 95 | installer: per-arch extraction, payload verification, permissions, `0700` data dir |
| `uninstall.sh` | 147 | removal |
| `webroot/*.js` | 6 199 | WebUI (`main.js` 3 507, `helper.js` 1 399, `i18n.js` 1 137, `vars.js` 156) |
| `bin/` | — | `xray` (fetched at build time), `hev-socks5-tunnel`, `curl`, `geoip.dat`, `geosite.dat` |

**Traffic path (verified in `service.sh`):**

```text
app socket
  → mangle OUTPUT -j XRAY_MARK
       RETURN for: mark 255 (xray's own traffic), 127/8, 10/8, 172.16/12,
                   192.168/16, 169.254/16, 224/4, 240/4
       otherwise MARK --set-xmark 1   (per-UID or global, per networkMode)
  → ip rule fwmark 1 table 100 pref 1010
  → table 100: default dev xraytun0
  → hev-socks5-tunnel (TUN → SOCKS5)
  → SOCKS5 127.17.1.3:808
  → xray inbound → outbound, socket marked FWMARK=255
  → ip rule fwmark 255 table <physical iface> pref 1000   (loop broken)
```

Constants: `TUN_NAME=xraytun0`, `TUN_ADDR=127.17.1.3`, `TUN_PORT=808`,
`FWMARK=255`, `RULE_PRIORITY=1000`, tunnel MTU 8500, MSS clamp 1350.

Hardening the module already does, all worth keeping:

- `-I OUTPUT -p tcp --dport 808 -d 127.17.1.3 -m owner --uid-owner 9999-2147483647 -j REJECT --reject-with tcp-reset`
  — hides the SOCKS inbound from ordinary apps;
- fake ICMP: `nat XRAY_FAKE_ICMP` DNATs outgoing pings to `127.0.0.1` because
  hev-socks5-tunnel does not proxy ICMP, so pings would otherwise leak out the
  real interface or hang;
- `rp_filter=0` only on the tun, with `lo` and the tun excluded from the loop that
  touches the others;
- IPv6 off by default, and when off `mangle XRAY_MARK` **drops** DNS over IPv6
  rather than letting it bypass;
- MSS clamping on `FORWARD -o xraytun0`;
- `/dev/net/tun` created `0600` if missing (comment notes it used to be `0666`).

**Process supervision** is genuinely good and is the one idea worth adopting
directly:

```sh
mount --bind "/proc/$pid" "$PROC_DIR/$name"     # mount_proc_with_name
is_proc_running() { [ -e "$PROC_DIR/$1/exe" ]; }
```

Liveness is the existence of `…/exe` inside a bind-mounted `/proc/<pid>`, which is
immune to PID reuse — a dead process leaves an empty directory. Far more reliable
than a pidfile.

**Hotspot / tether path** (this is the overlap with `vpn-gateway`):

```text
mangle HOTSPOT_PREROUTING   RETURN for 10/8, 172.16/12, 192.168/16, 127/8
                            ! -i xraytun0 -p tcp -j MARK --set-xmark 1
                            ! -i xraytun0 -p udp -j MARK --set-xmark 1
nat PREROUTING ! -i xraytun0 -d <private ranges> -p udp --dport 53 -j DNAT --to 1.1.1.1
ip rule  iif lo goto 6000                              pref 5000
         iif xraytun0 lookup main suppress_prefixlength 0  pref 5010
         iif xraytun0 goto 6000                        pref 5020
         to 10.0.0.0/8      lookup main                pref 5025
         to 172.16.0.0/12   lookup main                pref 5026
         to 192.168.0.0/16  lookup main                pref 5027
         from 10.0.0.0/8    lookup 100                 pref 5030
         from 172.16.0.0/12 lookup 100                 pref 5040
         from 192.168.0.0/16 lookup 100                pref 5050
         nop                                           pref 6000
```

gated by the `allowTether` setting (default `true`).

---

## 2. The control model — and why it cannot ship as-is

`webroot/main.js:52-63`:

```js
function execShell(command, callback) {
    if (typeof ksu === "object" && typeof ksu.exec === "function") {
        const cbId = `cb_${Date.now()}_${Math.random().toString(36).substr(2, 5)}`;
        window[cbId] = (errno, stdout, stderr) => { … };
        ksu.exec(command, "{}", cbId);
    } …
}
```

**A WebView page holds an unrestricted root shell.** Every operation — writing
`config.json`, starting the engine, reading status — is a shell string:

```js
`umask 077; printf '%s' ${shQuote(encoded)} | base64 -d > ${p} && chmod 600 ${p}`
```

The authors are aware of the exposure and added a quoting layer (`shQuote`, with a
comment explaining that subscription payloads and node fields are
attacker-controlled). That mitigates injection; it does not change the trust
model.

This is precisely what `CLAUDE.md` forbids, twice over:

> The UI must never be given unrestricted root access.
> Do not expose generic privileged APIs such as: arbitrary shell execution…

So the WebUI + `ksu.exec` bridge is **out** regardless of licence. Bomb's control
surface is typed AIDL into the privileged service; the engine is driven by
`init` and a typed applier, never by a string from a renderer.

Two further findings in the same area:

- **Credentials are stored base64-encoded, not encrypted.** `profiles.base64`,
  `settings.base64`, `config.json` under `/data/adb/magic_v2ray` (`0700` dir,
  `0600` files, root-owned) hold every server address, UUID, password and
  WireGuard private key the user imports. Base64 is encoding. File permissions are
  the only actual protection.
- **`bin/` is fetched at build time with no verification.** `build.sh`
  `fetch_xray()` does `curl -fsSL -o … "$url"` from
  `https://github.com/XTLS/Xray-core/releases/download/${XRAY_VERSION}/…`, unzips,
  installs — **no checksum, no signature check**. Acceptable for a hobby module;
  not acceptable for something baked into a system image.

### 2.1 The control FIFO *is* a typed channel

The WebUI's shell bridge is the problem; the **engine's own control channel is
not**. `proxy_control.sh` writes a single word into a named pipe, and
`service.sh`'s loop dispatches it with a fixed `case`:

```text
apply_cur_iface | start | stop | wait | start_monitor | stop_monitor |
start_monitor_latency | stop_monitor_latency | latency_heartbeat |
reset_mobile_network
*) return 1
```

Verified: **`service.sh` contains no `eval` and no `sh -c`.** A writer of the FIFO
can therefore express exactly these verbs and nothing else — the channel cannot be
made to execute an arbitrary command.

**Bomb implication:** this is the boundary. Bomb's privileged service writes an
enumerated verb into the FIFO and spawns **no process at all** — stronger than the
`execv`-of-`iptables` design that would otherwise be needed, and fully compatible
with `CLAUDE.md`'s ban on arbitrary shell execution.

### 2.2 Portability: already written for toybox, one real gap

Checked rather than assumed, because "it is a Magisk module" would normally imply
a busybox dependency:

- `grep_prop()` carries the comment *"dos2unix is not present on every toybox
  build; tr is."*, and a second comment names `/system/bin/sh` / mksh explicitly —
  the authors targeted a plain Android userspace deliberately;
- **`jq` was removed upstream** (*"jq was a 2 MB dependency for this single lookup;
  sed does it adequately"*); `query_settings` is `base64 -d | tr | sed | head`;
- `curl` is a **bundled** binary (`$BINDIR/curl`), not a system dependency;
- the remaining external set is `cat chmod date echo grep head kill mkdir mkfifo
  mknod mv printf rm sed sleep stat timeout touch tr mount umount getprop` — all
  toybox, plus `ip` / `iptables` / `ip6tables`;
- `inotifyd` is **not** used (that was `vpn-gateway`); interface changes come from
  an `ip monitor` child.

**One gap:** `sysctl -w net.ipv4.conf.{all,default}.rp_filter=2` at
`service.sh:545-546`. Android ships no `sysctl` binary. Two lines, and the script
already uses the correct pattern elsewhere (`echo "0" >
/proc/sys/net/ipv4/conf/$TUN_NAME/rp_filter`, `:514`).

This materially changes the cost of reuse: the engine is close to ROM-portable as
written, and the port is a short, enumerable list of changes rather than a rewrite.

---

## 3. SELinux behaviour today

`service.sh:38`:

```sh
mount -t tmpfs -o "mode=0755,context=u:object_r:proc_net:s0" proc "$STUB_DIR"
```

A tmpfs at `/dev/sysctl_stubs` is labelled **`proc_net`** — deliberately
mislabelled so that bind mounts and reads against it are permitted by rules
written for real procfs. Combined with `vpn-gateway`'s `chcon`-copy trick, both
modules survive enforcing SELinux by **borrowing labels that belong to something
else**.

For a ROM this is both unnecessary and wrong: the ROM can declare its own types.
This is a strong argument for the ROM-native design in the plan rather than
vendoring either module.

---

## 4. The two modules collide

| Resource | `vpn-gateway` | `magic_v2ray` |
| --- | --- | --- |
| `ip rule` pref 5000 | `iif lo goto 6000` | `iif lo goto 6000` |
| pref 5010 / 5020 | `iif tun0 …` | `iif xraytun0 …` |
| pref 5030 / 5040 / 5050 | `from <private> lookup <tun0 table>` | `from <private> lookup 100` |
| pref 6000 | `nop` | `nop` |
| `nat PREROUTING` DNS | DNAT udp/53 → 1.1.1.1 | DNAT udp/53 → 1.1.1.1 |
| `ip_forward` | set 1, then bind-mount stub | set 1 |
| forwarding sink | third-party VpnService `tun0` | own `xraytun0` |

**Identical rule priorities, different tables, neither aware of the other.**
Installing both leaves whichever ran last in charge, and removing one deletes
rules the other still needs (`ip rule del` matches on the selector, not on who
added it).

**Conclusion: they are alternatives, not complements.** They answer the same
question — "which tun do LAN clients' packets go into?" — with different sinks.
`magic_v2ray` already contains the entire LAN-gateway feature of `vpn-gateway`,
under `allowTether`. There is no configuration in which shipping both is correct.

`vpn-gateway` is only the right choice if the sink must be a **third-party VPN
app's** tun (WireGuard, a corporate client, Cloudflare WARP). Bomb should treat
that as a distinct, later capability: *"route tethered clients through whatever
app currently owns the system VPN"*, not as part of the proxy engine.

---

## 5. Compatibility risks specific to Android

1. **fwmark collision.** Android's `netd` owns the socket mark: the low 16 bits
   are the netId, with `explicitlySelected` / `protectedFromVpn` / permission bits
   above (`system/netd`). `magic_v2ray` uses raw marks `1` and `255`, both inside
   netId space. It works today only because its `ip rule` entries sit at
   priorities 1000/1010, ahead of netd's own rules. That is a collision waiting for
   a netId to match. **Bomb should use a mark in a bit range netd does not use for
   netId **or netd's flag bits above it**, applied with an explicit mask
   (`--set-xmark <bit>/<bit>`; the free bit must be read off the target's
   `system/netd` `fwmark.h`, not guessed —
   style), so Bomb's marking and netd's remain independent.
2. **`/data/misc/net/rt_tables` is the only supported way to learn a table id**,
   and it changes across network events. Both modules read it; Bomb must too, and
   must re-apply on change.
3. **Per-UID rules use `-m owner --uid-owner`**, which does not apply to forwarded
   traffic (there is no local socket), so tethered clients can only be selected by
   source subnet or input interface — which is exactly why both modules use a
   separate PREROUTING chain.
4. **`iptables` / `ip` availability.** Both modules call `/system/bin/iptables`,
   `/system/bin/ip6tables`, `/system/bin/ip`. This has **not** been verified on the
   target image — no partition is currently extracted under `hzz/`
   (`super/`, `out/` are empty; `ROM/sv29.zip` is the payload). Verification step,
   not an assumption. Recent Android images have been moving to `nftables` with
   `iptables` as a compatibility shim; if the shim is absent the whole approach
   changes.
5. **"Cannot be turned off" is a footgun in a ROM.** The upstream project markets
   itself as *"always working in the background, cannot be turned off"* and
   *"completely invisible to the OS"*. Baked into a system image, an
   always-on-by-default proxy with a bad config means **no network and no obvious
   way to recover**. Bomb must ship the opposite property: off by default, visibly
   indicated when on, and with a kill path that works before the UI is usable
   (§ plan P7).

---

## 6. What Bomb takes

Dispositions below reflect **D16/D17**: `magic_v2ray` is adopted and adapted under
GPL-3.0 as a segregated shell component; `vpn-gateway` is reimplemented because it
grants no licence. Full plan: [`../PROXY_GATEWAY_PLAN.md`](../PROXY_GATEWAY_PLAN.md).

| Behaviour | Bomb decision |
| --- | --- |
| The engine as a whole (`service.sh`, `proxy_control.sh`) | **Adopt under GPL-3.0**, adapted via nine numbered modifications, shipped as source |
| `vpn-gateway`'s LAN→VpnService routing | **Reimplement** from §1.1 of this note — no licence grant exists |
| `ip rule` + fwmark + policy routing to a tun | Adopted with the engine, with a masked mark outside netId space |
| Reading `/data/misc/net/rt_tables` and re-applying on change | Adopt |
| IPv6 fail-closed when the tunnel is IPv4-only | Adopt |
| Bypass sets (loopback, RFC1918, link-local, multicast, class E) | Adopt |
| Hiding the SOCKS inbound from app UIDs | Adopt |
| Fake-ICMP DNAT, MSS clamp, `rp_filter=0` on the tun only | Adopt |
| `/proc/<pid>` bind-mount liveness tracking | Adopt (concept) |
| `hev-socks5-tunnel` (MIT) as the tun2socks engine | Adopt as a pinned, hash-verified prebuilt |
| Xray-core (MPL-2.0) as the proxy core | Adopt as a pinned, hash-verified prebuilt, with NOTICE |
| Bind-mount stub over `/proc/sys/net/ipv4/ip_forward` | **Reject** — deceives netd, unobservable state |
| tmpfs mislabelled `proc_net` | **Reject** — Bomb declares its own SELinux types |
| WebUI holding `ksu.exec` root shell | **Reject** — typed AIDL only |
| Build-time binary download without checksum | **Reject** — pin + verify, vendored |
| base64 "protection" of credentials | **Reject as protection** — treat as plaintext; permissions + SELinux are the control |
| Shipping both modules | **Reject** — they collide; pick one sink |
| "Invisible / cannot be turned off" | **Reject** — off by default, visible, recoverable |
| GPL-3.0 scripts vendored into the image | **Blocked on D14** |

---

## 7. Open questions for the plan

**Resolved since this note was first written:**

- ~~Licence handling~~ — **D16/D17**. `magic_v2ray` is reused under GPL-3.0 as a
  segregated shell component; `vpn-gateway` is reimplemented. Shipping the covered
  work in **source form** keeps GPL-3.0 §6 (Installation Information) out of scope
  entirely, which is what makes this cheap for a ROM.

**Still open:**

- **Which sink (D18)?** Bomb's own Xray tunnel, or an arbitrary third-party
  VpnService (`vpn-gateway`'s use case). Only one can be active. The reuse decision
  tilts this hard toward Bomb's own tunnel, because the adopted engine already
  implements the tether path under `allowTether` — the LAN-gateway feature comes
  free, while the third-party-VpnService sink still needs the reimplementation.
- **Is `iptables` present on the target image, or has it moved to `nftables`?**
  Unverified — `hzz/super` and `hzz/out` are empty, so no partition has been
  extracted. This is a hard gate: if `iptables` is gone, the adopted engine does
  not run and the subsystem must be re-planned.
- **Do the ~30 MB of geo databases and the 2.9 MB bundled `curl` belong in
  `system_ext`** for an opt-in feature, or should they be fetched on first enable?
