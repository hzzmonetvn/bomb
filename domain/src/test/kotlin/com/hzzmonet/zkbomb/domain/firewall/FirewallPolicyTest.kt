package com.hzzmonet.zkbomb.domain.firewall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for §18 Firewall per-app/UID policy domain models.
 *
 * Tests cover:
 * - FirewallRule defaults (all ALLOW)
 * - FirewallRule isRestricted flag
 * - FirewallRule uid validation (must be >= 1000)
 * - FirewallPolicySnapshot.ruleFor returns default when no rule configured
 * - FirewallPolicySnapshot.restrictedUids enumeration
 * - Builder addRule / removeRule / build
 * - Empty snapshot is unrestricted for all UIDs
 */
class FirewallPolicyTest {

    @Test
    fun `default rule allows all interfaces`() {
        val rule = FirewallRule(uid = 10100, userId = 0)
        assertEquals(NetworkAccess.ALLOW, rule.wifiAccess)
        assertEquals(NetworkAccess.ALLOW, rule.mobileAccess)
        assertEquals(NetworkAccess.ALLOW, rule.backgroundAccess)
        assertFalse(rule.isRestricted)
    }

    @Test
    fun `rule with wifi denied is restricted`() {
        val rule = FirewallRule(uid = 10100, userId = 0, wifiAccess = NetworkAccess.DENY)
        assertTrue(rule.isRestricted)
    }

    @Test
    fun `rule with mobile denied is restricted`() {
        val rule = FirewallRule(uid = 10100, userId = 0, mobileAccess = NetworkAccess.DENY)
        assertTrue(rule.isRestricted)
    }

    @Test
    fun `rule with background denied is restricted`() {
        val rule = FirewallRule(uid = 10100, userId = 0, backgroundAccess = NetworkAccess.DENY)
        assertTrue(rule.isRestricted)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rule with uid below 1000 is rejected`() {
        FirewallRule(uid = 999, userId = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rule with negative userId is rejected`() {
        FirewallRule(uid = 10100, userId = -1)
    }

    @Test
    fun `snapshot returns default allow-all rule for unconfigured uid`() {
        val snap = FirewallPolicySnapshot.EMPTY
        val rule = snap.ruleFor(uid = 10200, userId = 0)
        assertFalse(rule.isRestricted)
        assertEquals(NetworkAccess.ALLOW, rule.wifiAccess)
        assertEquals(NetworkAccess.ALLOW, rule.mobileAccess)
        assertEquals(NetworkAccess.ALLOW, rule.backgroundAccess)
    }

    @Test
    fun `snapshot returns configured rule for known uid`() {
        val snap = FirewallPolicyBuilder(revision = 1L)
            .addRule(FirewallRule(uid = 10100, userId = 0, wifiAccess = NetworkAccess.DENY))
            .build()

        val rule = snap.ruleFor(10100, 0)
        assertEquals(NetworkAccess.DENY, rule.wifiAccess)
        assertEquals(NetworkAccess.ALLOW, rule.mobileAccess)
    }

    @Test
    fun `snapshot restrictedUids lists only uids with restrictions`() {
        val snap = FirewallPolicyBuilder(revision = 1L)
            .addRule(FirewallRule(10100, 0, wifiAccess = NetworkAccess.DENY))
            .addRule(FirewallRule(10200, 0)) // all ALLOW - not restricted
            .addRule(FirewallRule(10300, 0, mobileAccess = NetworkAccess.DENY))
            .build()

        val restricted = snap.restrictedUids()
        assertEquals(2, restricted.size)
        assertTrue(restricted.contains(FirewallRuleKey(10100, 0)))
        assertTrue(restricted.contains(FirewallRuleKey(10300, 0)))
        assertFalse(restricted.contains(FirewallRuleKey(10200, 0)))
    }

    @Test
    fun `builder removeRule clears a previously added rule`() {
        val snap = FirewallPolicyBuilder(revision = 1L)
            .addRule(FirewallRule(10100, 0, wifiAccess = NetworkAccess.DENY))
            .removeRule(uid = 10100, userId = 0)
            .build()

        val rule = snap.ruleFor(10100, 0)
        assertFalse(rule.isRestricted)
    }

    @Test
    fun `multi-user - same uid different userId has independent rules`() {
        val snap = FirewallPolicyBuilder(revision = 1L)
            .addRule(FirewallRule(10100, userId = 0, wifiAccess = NetworkAccess.DENY))
            .addRule(FirewallRule(10100, userId = 10, mobileAccess = NetworkAccess.DENY))
            .build()

        val ruleUser0 = snap.ruleFor(10100, 0)
        val ruleUser10 = snap.ruleFor(10100, 10)

        assertEquals(NetworkAccess.DENY, ruleUser0.wifiAccess)
        assertEquals(NetworkAccess.ALLOW, ruleUser0.mobileAccess)

        assertEquals(NetworkAccess.ALLOW, ruleUser10.wifiAccess)
        assertEquals(NetworkAccess.DENY, ruleUser10.mobileAccess)
    }

    @Test
    fun `builder last write wins for same uid and userId`() {
        val snap = FirewallPolicyBuilder(revision = 1L)
            .addRule(FirewallRule(10100, 0, wifiAccess = NetworkAccess.DENY))
            .addRule(FirewallRule(10100, 0, mobileAccess = NetworkAccess.DENY)) // replaces
            .build()

        val rule = snap.ruleFor(10100, 0)
        // The second addRule completely replaced the first.
        assertEquals(NetworkAccess.ALLOW, rule.wifiAccess)
        assertEquals(NetworkAccess.DENY, rule.mobileAccess)
    }

    @Test
    fun `allowAll factory produces unrestricted rule`() {
        val rule = FirewallRule.allowAll(uid = 10500, userId = 0)
        assertFalse(rule.isRestricted)
    }

    @Test
    fun `empty snapshot has no restricted uids`() {
        assertTrue(FirewallPolicySnapshot.EMPTY.isEmpty)
        assertTrue(FirewallPolicySnapshot.EMPTY.restrictedUids().isEmpty())
    }
}
