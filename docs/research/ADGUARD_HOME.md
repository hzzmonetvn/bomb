# Research — AdGuard Home (filter source and update pipeline)

```text
Reference:
https://github.com/AdguardTeam/AdGuardHome.git @ b2e25729e118754fecc06a0c8c0fff8fe569c548
(commit date 2026-08-06)

License: GPL-3.0 (LICENSE.txt)
Language: Go. Relevant packages:
  internal/filtering/filter.go        source model + update pipeline
  internal/filtering/filtering.go     match path, allowlist precedence
  internal/filtering/rulelist/parser.go   line parser
  internal/home/config.go             on-disk YAML config shape
```

License verdict: GPL-3.0 — **research only, no code reuse.** Bomb does not embed
AdGuard Home; master plan §31.5 already states this. Recorded in
[`REFERENCES.md`](./REFERENCES.md).

---

## 1. Required flow (master plan §32)

> source → download → parse → normalize → compile → DNS query match → allow/block

Traced:

```text
FilterYAML{Enabled, URL, Name, RulesCount, LastUpdated, checksum, white}
        internal/filtering/filter.go:33
   |
tryRefreshFilters(block, allow, force)                       filter.go:265
   -> listsToUpdate(filters, force)     // interval + enabled gating   :277
   -> refreshFiltersArray(...)                                          :313
      -> updateFilterList / update(filter)                              :339 / :480
         -> updateIntl(ctx, flt)                                        :501
            tmpFile := aghrenameio.NewPendingFile(flt.Path(dataDir), perm)
            defer finalizeUpdate(ctx, tmpFile, flt, res, err, ok)       :585
            |
            +-- filepath.IsAbs(URL) ? readFromFile(...)  :558
            |                       : readFromHTTP(...)  :532
            |     status must be 200
            |     ioutil.LimitReader(resp.Body, conf.MaxHTTPSize)
            |     rulelist.NewParser().Parse(tmpFile, body, buf)
            |
            +-- ok = (res.Checksum != flt.checksum)
   |
finalizeUpdate:
   !updated -> file.Cleanup()            // previous file untouched
   updated  -> file.CloseReplace()       // atomic rename
               flt.checksum, flt.RulesCount, flt.ensureName(res.Title)
   |
load(ctx, flt) -> rulelist engine built  -> DNSFilter.filteringEngine
   |
match: matchHost -> urlfilter.DNSRequest -> MatchRequest       filtering.go:890-950
```

---

## 2. Update-pipeline mechanics Bomb should copy (behaviour, not code)

Every one of master plan §16's update-validation requirements has a concrete
implementation here:

| Master plan requirement | AdGuard Home mechanism | Source |
| --- | --- | --- |
| HTTP result check | `resp.StatusCode != http.StatusOK` → error | filter.go:545-547 |
| file size limit | `ioutil.LimitReader(resp.Body, d.conf.MaxHTTPSize.Bytes())` | filter.go:555 |
| parser output validity | `rulelist.Parser` rejects HTML (`ErrHTML`) and binary bytes | rulelist/parser.go:96-113, 160-163 |
| checksum / revision | `crc32` over each accepted rule line; update applied only when `res.Checksum != flt.checksum` | parser.go:122, filter.go:526 |
| **failed update keeps previous blockset** | download parses into a *pending* temp file; only `CloseReplace()` on success, `Cleanup()` otherwise | filter.go:505-511, 585-624 |
| update intervals | `listsToUpdate(filters, force)` gates on enabled + elapsed interval | filter.go:277 |

Two extra safeguards worth adopting that the master plan does not mention:

1. **HTML detection.** `isHTMLLine` (parser.go:131) rejects a body starting with
   `<html` / `<!doctype`. This is the captive-portal / error-page case: without it,
   a hotel Wi-Fi login page silently becomes a "blocklist" of garbage.
2. **Local file path allowlist.** `readFromFile` refuses any path not matching
   `d.safeFSPatterns` (filter.go:562-564). Bomb's `LOCAL_FILE` source type must do
   the same — this is a privileged service reading a user-supplied path, and an
   unrestricted read is exactly the kind of generic file API `CLAUDE.md` forbids.

The line parser is also the normalization step, and it is strict but small
(parser.go:149-158):

```go
func parseLine(line []byte) (badIdx int, isRule bool) {
	if len(line) == 0 || line[0] == '#' || line[0] == '!' {
		return -1, false
	}
	badIdx = slices.IndexFunc(line, likelyBinary)
	return badIdx, badIdx == -1
}
```

Comments are `#` and `!`; a line containing a control byte aborts the whole
update rather than being skipped — a corrupt download fails loudly.

---

## 3. Allowlist precedence — checked first, and it short-circuits

`DNSFilter.matchHost` (filtering.go:913-918):

```go
if setts.ProtectionEnabled && d.filteringEngineAllow != nil {
    dnsres, ok := d.filteringEngineAllow.MatchRequest(ufReq)
    if ok {
        return d.matchHostProcessAllowList(ctx, host, dnsres)
    }
}
// ... only then the blocking engine
dnsres, matchedEngine := d.filteringEngine.MatchRequest(ufReq)
```

Two separate engines — `filteringEngineAllow` and `filteringEngine` — built from
two separate source lists (`Filters` and `WhitelistFilters`, internal/home/config.go:150-151).
The allow engine is consulted first and returns immediately with reason
`NotFilteredAllowList` (filtering.go:822).

**Bomb implication:** the master plan's pipeline lists "apply allowlist" as a
*compile-time* step. AdGuard Home does it at *match* time with a second engine.
Bomb should do it at compile time — with one important consequence to accept
consciously: `@@` allow rules can be broader than the block rules they cancel
(`@@||example.com^` cancels every blocked subdomain), so compile-time subtraction
must be a **domain-tree** subtraction, not a set difference on exact strings. If
that proves fiddly, the two-engine approach is the proven fallback. Either way the
precedence is fixed: **allow wins over block, unconditionally.**

---

## 4. Source model — what Bomb's `BlocklistSourceEntity` should carry

```go
// internal/filtering/filter.go:33
type FilterYAML struct {
	Enabled     bool
	URL         string    // URL or a file path
	Name        string    `yaml:"name"`
	RulesCount  int       `yaml:"-"`
	LastUpdated time.Time `yaml:"-"`
	checksum    uint32    // checksum of the file data
	white       bool
	Filter      `yaml:",inline"`
}
```

Note what is **not** in the config file: `RulesCount`, `LastUpdated` and `checksum`
are `yaml:"-"` — runtime/derived state, recomputed from the downloaded file. The
config persists only identity and intent.

**Bomb implication, matching master plan §16 "Storage":** Room stores
`{id, name, url/path, sourceType, enabled, isAllowlist, updateIntervalHours}`.
Everything derived — rule count, last-updated, checksum, compiled data — lives in
Bomb private storage next to the downloaded file, never as one Room row per domain.
`deduplicateFilters` (filter.go:245) deduplicates **sources by URL**, which is the
right granularity; domain-level dedup happens in the compiler.

---

## 5. AdGuard Home config import (master plan §16)

The YAML shape Bomb must parse (internal/home/config.go:150-151):

```yaml
filters:
  - enabled: true
    url: https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt
    name: AdGuard DNS filter
    id: 1
whitelist_filters:
  - enabled: true
    url: ...
user_rules:
  - '||example.com^'
```

Import rules for Bomb, all derivable from the above:

1. Read `filters[]` and `whitelist_filters[]`; the second list becomes Bomb
   allowlist sources (`isAllowlist = true`).
2. Honour `enabled`; a disabled entry imports as a disabled Bomb source, not as an
   omission — the user can see and re-enable it.
3. `url` may be an absolute **path** as well as a URL (`filepath.IsAbs(flt.URL)`,
   filter.go:513). Bomb maps those to `LOCAL_FILE` sources and applies its own
   path allowlist.
4. `user_rules` imports as a Bomb-local custom rule list, not as a remote source.
5. Do **not** import AdGuard Home's `id` values — Bomb allocates its own.

The master plan's rule "do not embed/run AdGuard Home merely for config import" is
straightforward here: this is a YAML read, nothing more.

---

## 6. Things Bomb deliberately does not take

| AdGuard Home feature | Bomb stance |
| --- | --- |
| DNS rewrites (`processDNSResultRewrites`, filtering.go:927) | out of scope v1 — that is a DNS server feature, not a blocker |
| Per-client settings / client tags (`ClientTags`, `ClientIdentifiers` in `DNSRequest`) | out of scope v1; master plan §16 defers per-app DNS filtering |
| Safe browsing / parental control services | out of scope entirely |
| Its own DNS server, DHCP, web UI | Bomb is not a DNS server |
| Cosmetic / HTML filtering | explicitly forbidden by master plan §16 |

---

## 7. Testing strategy implied

- update with HTTP 500 / 404 → previous compiled blockset still active, source
  marked failed with a timestamp;
- update returning an HTML captive-portal page → rejected, previous set retained;
- update whose body exceeds the size limit → truncated read rejected, not silently
  half-applied;
- identical content → checksum equal → no swap, `LastUpdated` still advanced;
- local-file source outside the allowed path set → `InvalidArgument`, never read;
- allow rule beats block rule for the same domain and for its subdomains;
- source dedup by URL; two sources contributing the same domain compile to one
  entry.
