package com.hzzmonet.zkbomb.domain.adblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The test matrix specified in `docs/research/ADGUARD_FILTER_FORMAT.md` §6,
 * which is itself derived from AdguardTeam/urlfilter @ dba7023's mask-to-regexp
 * translation. Each row here corresponds to a row there.
 *
 * The two rows that matter most are the negative ones: `notexample.com` and
 * `example.com.evil.net`. Both are what a naive `contains()` or `endsWith()`
 * implementation gets wrong, and getting them wrong means blocking sites the
 * user never asked to block.
 */
class BlocklistMatchingTest {

    private fun compiled(vararg lines: String) = BlocklistCompiler.compile(lines.asSequence())

    private fun decide(rule: String, query: String): Decision =
        compiled(rule).blocklist.decide(query)

    // ---- subdomain semantics -------------------------------------------------

    @Test
    fun `exact host is blocked`() {
        assertEquals(Decision.BLOCK, decide("example.com", "example.com"))
    }

    @Test
    fun `subdomain is blocked`() {
        assertEquals(Decision.BLOCK, decide("example.com", "www.example.com"))
    }

    @Test
    fun `deep subdomain is blocked`() {
        assertEquals(Decision.BLOCK, decide("example.com", "a.b.example.com"))
    }

    @Test
    fun `prefix trap is not blocked`() {
        // `||example.com^` expands to a group that must end in a dot, so
        // "notexample" cannot be consumed by it.
        assertEquals(Decision.NO_MATCH, decide("example.com", "notexample.com"))
    }

    @Test
    fun `suffix trap is not blocked`() {
        // `^` excludes '.' from its separator class, so the rule cannot match
        // when more labels follow.
        assertEquals(Decision.NO_MATCH, decide("example.com", "example.com.evil.net"))
    }

    @Test
    fun `parent of a blocked host is not blocked`() {
        assertEquals(Decision.NO_MATCH, decide("ads.example.com", "example.com"))
    }

    // ---- input forms ---------------------------------------------------------

    @Test
    fun `hosts line is blocked`() {
        assertEquals(Decision.BLOCK, decide("0.0.0.0 ads.example.com", "ads.example.com"))
    }

    @Test
    fun `hosts line with multiple hostnames blocks each`() {
        val list = compiled("0.0.0.0 a.example.com b.example.com").blocklist
        assertEquals(Decision.BLOCK, list.decide("a.example.com"))
        assertEquals(Decision.BLOCK, list.decide("b.example.com"))
        assertEquals(2, list.blockedCount)
    }

    @Test
    fun `hosts boilerplate is not added`() {
        val r = compiled("127.0.0.1 localhost", "::1 ip6-localhost")
        assertEquals(0, r.blocklist.blockedCount)
    }

    @Test
    fun `adblock basic form is blocked`() {
        assertEquals(Decision.BLOCK, decide("||ads.example.com^", "ads.example.com"))
    }

    @Test
    fun `adblock anchored form is blocked`() {
        assertEquals(Decision.BLOCK, decide("||ads.example.com^|", "ads.example.com"))
    }

    @Test
    fun `plain domain and adblock form are equivalent`() {
        assertEquals(
            decide("ads.example.com", "deep.ads.example.com"),
            decide("||ads.example.com^", "deep.ads.example.com"),
        )
    }

    // ---- allow rules ---------------------------------------------------------

    @Test
    fun `allow beats block`() {
        val list = compiled("||example.com^", "@@||ok.example.com^").blocklist
        assertEquals(Decision.ALLOW, list.decide("ok.example.com"))
        assertEquals(Decision.BLOCK, list.decide("bad.example.com"))
    }

    @Test
    fun `allow covers subdomains of the allowed name`() {
        val list = compiled("||example.com^", "@@||ok.example.com^").blocklist
        assertEquals(Decision.ALLOW, list.decide("deep.ok.example.com"))
    }

    @Test
    fun `allow rule with no matching block rule is kept, not an error`() {
        // AdGuardSDNSFilter's exceptions.txt is full of these because sources
        // are compiled independently.
        val r = compiled("@@||allowed.example.com^|")
        assertEquals(1, r.blocklist.allowedCount)
        assertEquals(0, r.invalidCount)
    }

    // ---- normalization -------------------------------------------------------

    @Test
    fun `case is normalized`() {
        assertEquals(Decision.BLOCK, decide("EXAMPLE.COM", "example.com"))
        assertEquals(Decision.BLOCK, decide("example.com", "WWW.EXAMPLE.COM"))
    }

    @Test
    fun `trailing root dot is stripped on both sides`() {
        assertEquals(Decision.BLOCK, decide("example.com", "example.com."))
        assertEquals(Decision.BLOCK, decide("example.com.", "example.com"))
    }

    @Test
    fun `idn is converted to punycode`() {
        // bücher.de -> xn--bcher-kva.de
        val list = compiled("bücher.de").blocklist
        assertEquals(Decision.BLOCK, list.decide("xn--bcher-kva.de"))
        assertEquals(Decision.BLOCK, list.decide("bücher.de"))
    }

    // ---- comments and blanks -------------------------------------------------

    @Test
    fun `comments and blank lines are ignored, not counted as rules`() {
        val r = compiled("! title: something", "# a comment", "", "   ", "example.com")
        assertEquals(1, r.blocklist.blockedCount)
        assertEquals(4, r.ignored)
        assertEquals(0, r.invalidCount)
    }

    // ---- unsupported forms are counted, never silently applied ---------------

    @Test
    fun `wildcard inside host is unsupported, not blocked`() {
        val r = compiled("||mobileanalytics.*.amazonaws.com^")
        assertEquals(0, r.blocklist.blockedCount)
        assertEquals(1, r.unsupported[UnsupportedReason.WILDCARD])
    }

    @Test
    fun `modifier rule is unsupported`() {
        val r = compiled("||x.com^\$important")
        assertEquals(0, r.blocklist.blockedCount)
        assertEquals(1, r.unsupported[UnsupportedReason.MODIFIER])
    }

    @Test
    fun `cosmetic rule is unsupported`() {
        val r = compiled("example.com##.ad-banner")
        assertEquals(0, r.blocklist.blockedCount)
        assertEquals(1, r.unsupported[UnsupportedReason.COSMETIC])
    }

    @Test
    fun `regexp rule is unsupported`() {
        val r = compiled("/ads?\\.example\\./")
        assertEquals(0, r.blocklist.blockedCount)
        assertEquals(1, r.unsupported[UnsupportedReason.REGEXP])
    }

    @Test
    fun `ip literal target is unsupported`() {
        val r = compiled("||192.168.1.1^")
        assertEquals(0, r.blocklist.blockedCount)
        assertEquals(1, r.unsupported[UnsupportedReason.IP_LITERAL])
    }

    // ---- invalid input -------------------------------------------------------

    @Test
    fun `label starting with a hyphen is invalid`() {
        val r = compiled("-bad-.com")
        assertEquals(0, r.blocklist.blockedCount)
        assertEquals(1, r.invalid[InvalidReason.BAD_DOMAIN])
    }

    @Test
    fun `single label is invalid`() {
        val r = compiled("localhostish")
        assertEquals(0, r.blocklist.blockedCount)
        assertEquals(1, r.invalidCount)
    }

    @Test
    fun `over-long name is invalid`() {
        val long = (1..20).joinToString(".") { "a".repeat(20) } // > 253 chars
        assertTrue(long.length > 253)
        val r = compiled(long)
        assertEquals(0, r.blocklist.blockedCount)
        assertEquals(1, r.invalidCount)
    }

    @Test
    fun `over-long label is invalid`() {
        val r = compiled("${"a".repeat(64)}.com")
        assertEquals(0, r.blocklist.blockedCount)
        assertEquals(1, r.invalidCount)
    }

    // ---- dedup and usability -------------------------------------------------

    @Test
    fun `same domain from two sources compiles to one entry`() {
        val r = compiled("ads.example.com", "||ads.example.com^", "0.0.0.0 ads.example.com")
        assertEquals(1, r.blocklist.blockedCount)
        assertEquals(2, r.duplicates)
    }

    @Test
    fun `a source that parses to nothing is not usable`() {
        // Master plan §16's "minimum valid-rule threshold": this must be a
        // failed update, so the caller keeps the previous blockset.
        val r = compiled("! only a comment", "", "||*.wildcard.com^")
        assertFalse(r.isUsable)
        assertEquals(0, r.blocklist.blockedCount)
    }

    @Test
    fun `a source with one real rule is usable`() {
        assertTrue(compiled("ads.example.com").isUsable)
    }

    // ---- empty list ----------------------------------------------------------

    @Test
    fun `empty blocklist matches nothing`() {
        assertEquals(Decision.NO_MATCH, Blocklist.EMPTY.decide("anything.example.com"))
    }

    @Test
    fun `unparseable query is not blocked`() {
        assertEquals(Decision.NO_MATCH, decide("example.com", ""))
    }
}
