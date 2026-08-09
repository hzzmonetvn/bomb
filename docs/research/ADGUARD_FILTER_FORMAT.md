# Research — AdGuard rule syntax (the DNS subset Bomb needs)

```text
References:
https://github.com/AdguardTeam/urlfilter.git          @ dba702354cd6a5a0eed03b41da0b22dd46a42af0
https://github.com/AdguardTeam/AdGuardSDNSFilter.git  @ 83d62022a188eb165f53fc50b55e4d13ec8925af

Licenses: both GPL-3.0 (LICENSE in each) — research only, no code reuse.
The SDNSFilter *rule data* is likewise GPL-3.0 and is NOT bundled into Bomb.

Relevant source:
urlfilter/rules/regexp.go     mask -> regexp translation (authoritative semantics)
urlfilter/rules/network.go    @@ handling, host-level rule detection
urlfilter/rules/host.go       hosts-file rule matching
AdGuardSDNSFilter/Filters/{rules,exceptions,exclusions}.txt   real-world corpus
```

---

## 1. The authoritative semantics of `||domain^`

`urlfilter/rules/regexp.go:7-48` defines each mask and its regexp translation:

| Mask | Constant | Regexp | Meaning |
| --- | --- | --- | --- |
| `\|\|` | `MaskStartURL` | `^(http\|https\|ws\|wss)://([a-z0-9-_.]+\.)?` | start of address, **optional subdomain prefix ending in a dot** |
| `^` | `MaskSeparator` | `([^ a-zA-Z0-9.%_-]\|$)` | any character that is not a letter, digit, `_`, `-`, `.`, `%` — **or end of string** |
| `\|` | `MaskPipe` | `^` at the start, `$` at the end | anchor |
| `*` | `MaskAnyCharacter` | `.*` | any characters, including none |
| `@@` | `maskWhiteList` (network.go:20) | — | allow (exception) rule prefix |

This settles master plan §16's "Domain matching" requirement precisely. For
`||example.com^`:

| Hostname | Matches? | Why |
| --- | --- | --- |
| `example.com` | yes | subdomain group `([a-z0-9-_.]+\.)?` is optional; `^` matches end-of-string |
| `www.example.com` | yes | `www.` satisfies the group (note the required trailing dot) |
| `a.b.example.com` | yes | the group allows dots inside |
| `notexample.com` | **no** | `notexample` would have to be consumed by the group, which must end in `.` |
| `example.com.evil.net` | **no** | `^` after `com` requires a separator or end-of-string; `.` is *not* a separator |

The last two rows are the whole point of master plan §16's "Never use naive
substring matching". Note carefully that `.` is **excluded** from the separator
class — that is what stops `example.com.evil.net` from matching.

**Bomb implication:** Bomb does not need regexps. The same semantics are expressed
exactly by **domain-label suffix matching**:

```text
match(query, blocked) :=
       query == blocked
    || query.endsWith("." + blocked)
```

with both sides lower-cased, IDN-normalised (punycode) and trailing-dot-stripped
first. This is O(1) per label with a reversed-label trie, allocation-free, and
provably equivalent to `||blocked^` for the DNS case. Bomb implements this, not a
regexp engine.

---

## 2. Hosts-file rules are **exact match**, and that is a trap

`urlfilter/rules/host.go:115-117`:

```go
// Match returns true if this filtering rule matches the specified hostname.
func (f *HostRule) Match(hostname string) (ok bool) {
	return slices.Contains(f.Hostnames, hostname)
}
```

A hosts-format line `0.0.0.0 ads.example.com` matches **only** `ads.example.com`.
It does **not** match `sub.ads.example.com`. This is faithful to how a real
`/etc/hosts` behaves, and it differs from `||ads.example.com^`.

**Decision for Bomb (deliberate divergence, stated because it is user-visible):**
Bomb normalises *all* input formats — hosts lines, plain domains, and
`||domain^` rules — into one `BlockedDomain` entry with **subdomain-inclusive**
matching, because that is what master plan §16 specifies:

> Blocking `example.com` should block `example.com`, `www.example.com`,
> `ads.example.com`, `a.b.example.com`, but not `notexample.com`.

Consequence to document in the UI: importing a hosts file into Bomb blocks
slightly more than the same file would block on a desktop. That is the intended
behaviour for a DNS blocker, but it must be stated, not discovered.

---

## 3. The supported subset — and the explicit refusals

Real corpus, `AdGuardSDNSFilter/Filters/` at the researched commit:

```text
rules.txt        ||abcounter.de^
                 ||mobileanalytics.*.amazonaws.com^
exceptions.txt   @@||partner.o2online.de^|
                 @@|cdn.taboola.com^|
exclusions.txt   ||data.email.tangerine.ca^
```

Bomb's parser accepts exactly this much:

| Form | Example | Bomb handling |
| --- | --- | --- |
| plain domain | `ads.example.com` | block, subdomain-inclusive |
| hosts line | `0.0.0.0 ads.example.com`, `127.0.0.1 t.example.com` | block; multiple hostnames per line allowed (`HostRule.Hostnames` is a list) |
| basic block rule | `\|\|ads.example.com^` | block, subdomain-inclusive |
| basic block rule, anchored | `\|\|ads.example.com^\|` | same — trailing `\|` is redundant for DNS |
| basic allow rule | `@@\|\|allowed.example.com^` | allow, subdomain-inclusive, wins over any block |
| comment | `! …`, `# …` | ignored (AdGuard Home parser: `parser.go:150`) |
| blank | | ignored |

Rejected — logged and counted, never silently treated as a domain:

| Form | Why rejected |
| --- | --- |
| `\|\|mobileanalytics.*.amazonaws.com^` | wildcard **inside** the host; needs the regexp engine. Real rules use it (see corpus above), so the parser must recognise and *count* it, not choke |
| any rule with `$` modifiers (`$important`, `$dnstype=`, `$client=`) | modifier semantics out of scope; a rule whose meaning Bomb cannot honour must not be applied as if it were a plain block |
| cosmetic rules (`##`, `#?#`, `#$#`) | explicitly forbidden by master plan §16 |
| regexp rules (`/…/`) | out of scope |
| `0.0.0.0` / `::` / `localhost` / `broadcasthost` as a hostname | hosts-file boilerplate, not a target |
| IP literals as the blocked target | a DNS name blocker blocks names |

The "recognise and count" rule matters: the SDNSFilter corpus contains wildcard
rules, so a list will *always* have unsupported lines. Bomb reports
`parsed / applied / unsupported / invalid` per source so the user sees that a list
is partially applied instead of assuming full coverage.

---

## 4. Normalization steps, in order

Derived from the parser (`AdGuardHome/internal/filtering/rulelist/parser.go`) plus
DNS requirements:

1. trim whitespace (`bytes.TrimSpace`, parser.go:95);
2. drop empty, `#`, `!` (parser.go:150);
3. reject the whole file if it starts with `<html` / `<!doctype` (parser.go:131)
   or contains a control byte (parser.go:160);
4. strip the `@@` prefix → mark as allow (network.go:1240);
5. strip `||` prefix, trailing `^` and trailing `|`;
6. for hosts lines: split on whitespace, discard the IP, keep every remaining
   hostname (`splitNextByWhitespace`, host.go:39);
7. lower-case; strip a trailing `.`; convert Unicode to punycode;
8. validate as a DNS name: ≤253 bytes total, each label 1..63 bytes, labels of
   `[a-z0-9-]` not starting or ending with `-`; reject anything else;
9. deduplicate;
10. compile: build the block trie and the allow trie;
11. atomic swap of the compiled set.

Step 8 is where "false-positive prevention" from master plan §39 is actually
enforced — a malformed line becomes an `invalid` count, never a blocked domain.

---

## 5. Allow-rule precedence

`@@||allowed.example.com^` must beat any block rule, including a broader one, and
must cover subdomains of the allowed name. Implementation: check the allow trie
first; on hit, return allow immediately. This mirrors AdGuard Home's two-engine
order (see [`ADGUARD_HOME.md`](./ADGUARD_HOME.md) §3) while keeping Bomb's single
compile-time structure.

Redundancy note: an allow rule with no matching block rule is not an error — the
SDNSFilter `exceptions.txt` is full of them, because the sources are compiled
independently. Bomb keeps them.

---

## 6. Test matrix (master plan §39 "AdBlock")

Every row below is a unit test in `:domain`, with no Android dependency:

| Case | Input | Query | Expected |
| --- | --- | --- | --- |
| exact | `example.com` | `example.com` | block |
| subdomain | `example.com` | `www.example.com` | block |
| deep subdomain | `example.com` | `a.b.example.com` | block |
| prefix trap | `example.com` | `notexample.com` | allow |
| suffix trap | `example.com` | `example.com.evil.net` | allow |
| hosts line | `0.0.0.0 ads.example.com` | `ads.example.com` | block |
| hosts multi-host | `0.0.0.0 a.example.com b.example.com` | both | block |
| hosts boilerplate | `127.0.0.1 localhost` | `localhost` | not added |
| adblock basic | `\|\|ads.example.com^` | `ads.example.com` | block |
| adblock anchored | `\|\|ads.example.com^\|` | `ads.example.com` | block |
| allow beats block | `\|\|example.com^` + `@@\|\|ok.example.com^` | `ok.example.com` | allow |
| allow covers subdomains | same | `deep.ok.example.com` | allow |
| wildcard unsupported | `\|\|a.*.example.com^` | — | counted unsupported, not blocked |
| modifier unsupported | `\|\|x.com^$important` | — | counted unsupported |
| cosmetic ignored | `example.com##.ad` | — | counted unsupported |
| case/idn | `EXAMPLE.COM`, `bücher.de` | `example.com`, `xn--bcher-kva.de` | block |
| trailing dot | `example.com` | `example.com.` | block |
| invalid label | `-bad-.com`, 300-char name | — | counted invalid |
| dedup | same domain from two sources | | one entry |
| empty result | list that parses to zero valid rules | | update rejected, previous set kept |

The last row is master plan §16's "minimum valid-rule threshold" — a list that
parses to nothing is a failed update, not an empty blockset.
