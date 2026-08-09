package com.hzzmonet.zkbomb.domain.adblock

import java.net.IDN
import java.util.Locale

/**
 * Parses one line of a blocklist source into a normalized domain rule.
 *
 * Bomb supports a deliberately small subset of AdGuard/hosts syntax — the part
 * that is meaningful for DNS-level blocking and nothing else. The subset and the
 * exact semantics are derived in `docs/research/ADGUARD_FILTER_FORMAT.md`, which
 * was written against AdguardTeam/urlfilter @ dba7023.
 *
 * The key semantic, established from `urlfilter/rules/regexp.go`:
 *
 *  - `||` expands to `^(http|https|ws|wss)://([a-z0-9-_.]+\.)?` — an *optional*
 *    subdomain prefix that must end in a dot.
 *  - `^` expands to `([^ a-zA-Z0-9.%_-]|$)` — note `.` is **excluded** from the
 *    separator class.
 *
 * Together those mean `||example.com^` matches `example.com` and any subdomain,
 * but neither `notexample.com` nor `example.com.evil.net`. For DNS that is
 * exactly label-suffix matching, so Bomb needs no regexp engine — see
 * [Blocklist.decide].
 *
 * Lines whose meaning Bomb cannot honour are **counted and reported**, never
 * silently reinterpreted as a plain domain. A rule set that is 90% applied must
 * say so rather than implying full coverage.
 */
object BlocklistParser {

    /** Hosts-file boilerplate that names the local machine, not a block target. */
    private val HOSTS_BOILERPLATE = setOf(
        "localhost",
        "localhost.localdomain",
        "local",
        "broadcasthost",
        "ip6-localhost",
        "ip6-loopback",
        "ip6-localnet",
        "ip6-mcastprefix",
        "ip6-allnodes",
        "ip6-allrouters",
        "ip6-allhosts",
    )

    private val LABEL = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")

    fun parseLine(raw: String): ParseOutcome {
        val line = raw.trim()

        // Step 1-2 of the normalization order: blank and comments.
        // AdGuard Home's own parser treats both '#' and '!' as comments
        // (internal/filtering/rulelist/parser.go: parseLine).
        if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) {
            return ParseOutcome.Ignored
        }

        // Cosmetic rules are explicitly out of scope: Bomb is a DNS blocker and
        // does not touch page content. Checked before the '||' handling because
        // a cosmetic rule may carry a domain prefix (`example.com##.ad`).
        if (line.contains("##") || line.contains("#?#") || line.contains("#$#") ||
            line.contains("#@#")
        ) {
            return ParseOutcome.Unsupported(UnsupportedReason.COSMETIC, line)
        }

        // Regexp rules.
        if (line.length > 1 && line.startsWith("/") && line.endsWith("/")) {
            return ParseOutcome.Unsupported(UnsupportedReason.REGEXP, line)
        }

        var body = line
        var kind = RuleKind.BLOCK

        // Step 4: allow-rule prefix. urlfilter/rules/network.go:20 `maskWhiteList`.
        if (body.startsWith("@@")) {
            kind = RuleKind.ALLOW
            body = body.removePrefix("@@")
        }

        // Modifiers change what a rule means ($important, $dnstype=, $client=…).
        // Applying the rule while ignoring its modifier would be applying a
        // different rule than the author wrote.
        if (body.contains('$')) {
            return ParseOutcome.Unsupported(UnsupportedReason.MODIFIER, line)
        }

        val isAdblockForm = body.startsWith("||")

        // Step 5: strip the adblock anchors.
        if (isAdblockForm) body = body.removePrefix("||")
        body = body.removePrefix("|")
        body = body.removeSuffix("|")
        body = body.removeSuffix("^")

        // A hosts line carries an address plus one or more hostnames. Only the
        // plain form can be a hosts line; `||host^ 1.2.3.4` is not a thing.
        if (!isAdblockForm && body.any { it == ' ' || it == '\t' }) {
            return parseHostsLine(body, line)
        }

        // Wildcards inside the host need the regexp engine Bomb does not have.
        // The real AdGuardSDNSFilter corpus contains these (e.g.
        // `||mobileanalytics.*.amazonaws.com^`), so this path is normal, not rare.
        if (body.contains('*')) {
            return ParseOutcome.Unsupported(UnsupportedReason.WILDCARD, line)
        }

        return toRule(kind, body, line)
    }

    /**
     * `0.0.0.0 ads.example.com tracker.example.com` — discard the address, keep
     * every remaining hostname. Mirrors `urlfilter/rules/host.go`, whose
     * `HostRule.Hostnames` is likewise a list.
     */
    private fun parseHostsLine(body: String, original: String): ParseOutcome {
        val fields = body.split(' ', '\t').filter { it.isNotEmpty() }
        if (fields.size < 2) return ParseOutcome.Invalid(InvalidReason.MALFORMED, original)

        // fields[0] is the address; everything after is a hostname.
        val hosts = fields.drop(1)
            .map { normalizeHost(it) }
            .filter { it != null && it !in HOSTS_BOILERPLATE }
            .filterNotNull()

        if (hosts.isEmpty()) return ParseOutcome.Ignored
        val valid = hosts.filter { isValidDomain(it) }
        if (valid.isEmpty()) return ParseOutcome.Invalid(InvalidReason.BAD_DOMAIN, original)
        return ParseOutcome.Accepted(RuleKind.BLOCK, valid)
    }

    private fun toRule(kind: RuleKind, host: String, original: String): ParseOutcome {
        val normalized = normalizeHost(host)
            ?: return ParseOutcome.Invalid(InvalidReason.BAD_DOMAIN, original)
        if (normalized in HOSTS_BOILERPLATE) return ParseOutcome.Ignored
        if (looksLikeIpLiteral(normalized)) {
            return ParseOutcome.Unsupported(UnsupportedReason.IP_LITERAL, original)
        }
        if (!isValidDomain(normalized)) {
            return ParseOutcome.Invalid(InvalidReason.BAD_DOMAIN, original)
        }
        return ParseOutcome.Accepted(kind, listOf(normalized))
    }

    /**
     * Step 7: lower-case, strip a trailing root dot, convert IDN to punycode.
     * Returns null when the input cannot be represented as a DNS name at all.
     */
    fun normalizeHost(input: String): String? {
        var s = input.trim().lowercase(Locale.ROOT)
        if (s.isEmpty()) return null
        while (s.endsWith(".")) s = s.dropLast(1)
        if (s.isEmpty()) return null
        // Strip a port if one slipped in; DNS names carry none.
        if (s.contains(':') && !looksLikeIpv6(s)) s = s.substringBefore(':')
        return try {
            IDN.toASCII(s, IDN.ALLOW_UNASSIGNED).lowercase(Locale.ROOT)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun looksLikeIpv6(s: String) = s.count { it == ':' } > 1

    private fun looksLikeIpLiteral(s: String): Boolean {
        if (looksLikeIpv6(s)) return true
        val parts = s.split('.')
        if (parts.size != 4) return false
        return parts.all { p -> p.isNotEmpty() && p.all { it.isDigit() } && p.toIntOrNull() in 0..255 }
    }

    /**
     * Step 8: the check that keeps a malformed line from becoming a blocked
     * domain. ≤253 bytes overall, each label 1..63 bytes of `[a-z0-9-]` not
     * starting or ending with `-`, at least two labels.
     */
    fun isValidDomain(host: String): Boolean {
        if (host.isEmpty() || host.length > 253) return false
        val labels = host.split('.')
        if (labels.size < 2) return false
        return labels.all { it.matches(LABEL) }
    }
}

enum class RuleKind { BLOCK, ALLOW }

enum class UnsupportedReason { WILDCARD, MODIFIER, COSMETIC, REGEXP, IP_LITERAL }

enum class InvalidReason { BAD_DOMAIN, MALFORMED }

sealed interface ParseOutcome {
    /** Blank line or comment — not an error, not a rule. */
    data object Ignored : ParseOutcome

    /** One line may yield several hostnames (a hosts-format line). */
    data class Accepted(val kind: RuleKind, val domains: List<String>) : ParseOutcome {
        constructor(kind: RuleKind, domain: String) : this(kind, listOf(domain))
    }

    /** Recognised syntax whose meaning Bomb cannot honour. Counted, never applied. */
    data class Unsupported(val reason: UnsupportedReason, val line: String) : ParseOutcome

    /** Syntax that should have been a domain and is not. */
    data class Invalid(val reason: InvalidReason, val line: String) : ParseOutcome
}
