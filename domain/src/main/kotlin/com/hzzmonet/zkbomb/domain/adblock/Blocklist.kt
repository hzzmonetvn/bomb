package com.hzzmonet.zkbomb.domain.adblock

/**
 * A compiled, immutable block/allow set with label-suffix matching.
 *
 * Matching is defined in `docs/research/ADGUARD_FILTER_FORMAT.md` §1: blocking
 * `example.com` blocks `example.com` and every subdomain, but not
 * `notexample.com` (the `||` prefix group must end in a dot) and not
 * `example.com.evil.net` (`^` excludes `.` from its separator class).
 *
 * That is exactly:
 *
 * ```
 * match(query, blocked) := query == blocked || query.endsWith("." + blocked)
 * ```
 *
 * Implemented by walking the query's own label suffixes and hashing each, which
 * is O(labels) with no allocation beyond the substrings and needs no regexp
 * engine and no trie. A hostname has at most ~127 labels and realistically 2-5.
 *
 * Allow beats block unconditionally, and the allow set is subdomain-inclusive
 * too — matching AdGuard Home, whose allow engine is consulted first and returns
 * immediately (`internal/filtering/filtering.go:913-918`).
 */
class Blocklist(
    blocked: Set<String>,
    allowed: Set<String>,
) {
    private val blocked: Set<String> = java.util.Collections.unmodifiableSet(LinkedHashSet(blocked))
    private val allowed: Set<String> = java.util.Collections.unmodifiableSet(LinkedHashSet(allowed))

    val blockedCount: Int get() = blocked.size
    val allowedCount: Int get() = allowed.size

    fun decide(rawHost: String): Decision {
        val host = BlocklistParser.normalizeHost(rawHost) ?: return Decision.NO_MATCH
        if (allowed.isNotEmpty() && matches(host, allowed)) return Decision.ALLOW
        if (matches(host, blocked)) return Decision.BLOCK
        return Decision.NO_MATCH
    }

    /**
     * True when [host] equals, or is a subdomain of, any entry in [set].
     *
     * Walks suffixes from the full name inward: `a.b.example.com` tests
     * `a.b.example.com`, `b.example.com`, `example.com`, `com`. Testing the
     * full name first means an exact rule wins without scanning the rest.
     */
    private fun matches(host: String, set: Set<String>): Boolean {
        if (host in set) return true
        var idx = host.indexOf('.')
        while (idx >= 0 && idx < host.length - 1) {
            if (host.substring(idx + 1) in set) return true
            idx = host.indexOf('.', idx + 1)
        }
        return false
    }

    companion object {
        val EMPTY = Blocklist(emptySet(), emptySet())
    }
}

enum class Decision { BLOCK, ALLOW, NO_MATCH }
