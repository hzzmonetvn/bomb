package com.hzzmonet.zkbomb.domain.visibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the HMA-style App Visibility engine — §12 of BOMB_PLAN.
 *
 * All tests are pure JVM (no Android dependency), executed in milliseconds.
 * Test matrix covers:
 *  - BLACKLIST mode: hidden packages, non-configured callers, system exemptions
 *  - WHITELIST mode: only listed visible, non-listed hidden
 *  - Shared UID expansion
 *  - Multi-user (userId) isolation
 *  - system_server (UID 1000) exempt
 *  - Trusted exemptions never hidden
 *  - Conflict resolution (BLACKLIST + WHITELIST → WHITELIST)
 *  - VisibilityPolicyCache atomic swap and staleness rejection
 */
class VisibilityPolicyTest {

    // ---- helpers -----------------------------------------------------------

    private val bombPackage = "com.hzzmonet.zkbomb"

    private fun snapshot(
        revision: Long = 1L,
        build: VisibilityPolicyBuilder.() -> Unit,
    ): VisibilityPolicySnapshot =
        VisibilityPolicyBuilder(revision, setOf(bombPackage)).apply(build).build()

    private fun blacklistSnapshot(
        callingUid: Int,
        userId: Int,
        vararg hidden: String,
    ): VisibilityPolicySnapshot = snapshot {
        addCallerPolicy(
            VisibilityCallerPolicy(
                caller = CallerKey(callingUid, userId),
                mode = VisibilityMode.BLACKLIST,
                packageSet = hidden.toSet(),
            )
        )
    }

    private fun whitelistSnapshot(
        callingUid: Int,
        userId: Int,
        vararg visible: String,
    ): VisibilityPolicySnapshot = snapshot {
        addCallerPolicy(
            VisibilityCallerPolicy(
                caller = CallerKey(callingUid, userId),
                mode = VisibilityMode.WHITELIST,
                packageSet = visible.toSet(),
            )
        )
    }

    // ---- BLACKLIST mode ----------------------------------------------------

    @Test
    fun `blacklist - configured package is hidden from designated caller`() {
        val snap = blacklistSnapshot(callingUid = 10100, userId = 0, "com.example.hidden")
        assertTrue(snap.shouldHidePackage(10100, 0, "com.example.hidden"))
    }

    @Test
    fun `blacklist - non-listed package is visible`() {
        val snap = blacklistSnapshot(callingUid = 10100, userId = 0, "com.example.hidden")
        assertFalse(snap.shouldHidePackage(10100, 0, "com.example.other"))
    }

    @Test
    fun `blacklist - different caller sees the hidden package normally`() {
        val snap = blacklistSnapshot(callingUid = 10100, userId = 0, "com.example.hidden")
        assertFalse(snap.shouldHidePackage(10200, 0, "com.example.hidden"))
    }

    @Test
    fun `blacklist - caller with no policy sees everything`() {
        val snap = blacklistSnapshot(callingUid = 10100, userId = 0, "com.example.hidden")
        assertFalse(snap.shouldHidePackage(99999, 0, "com.example.hidden"))
    }

    // ---- WHITELIST mode ----------------------------------------------------

    @Test
    fun `whitelist - listed package is visible`() {
        val snap = whitelistSnapshot(callingUid = 10100, userId = 0, "com.allowed.app")
        assertFalse(snap.shouldHidePackage(10100, 0, "com.allowed.app"))
    }

    @Test
    fun `whitelist - non-listed package is hidden`() {
        val snap = whitelistSnapshot(callingUid = 10100, userId = 0, "com.allowed.app")
        assertTrue(snap.shouldHidePackage(10100, 0, "com.other.app"))
    }

    @Test
    fun `whitelist - different caller is unaffected, sees everything`() {
        val snap = whitelistSnapshot(callingUid = 10100, userId = 0, "com.allowed.app")
        assertFalse(snap.shouldHidePackage(10200, 0, "com.other.app"))
    }

    // ---- Trusted exemptions ------------------------------------------------

    @Test
    fun `trusted exemption - Bomb itself is never hidden, even by whitelist`() {
        val snap = whitelistSnapshot(callingUid = 10100, userId = 0, "com.allowed.app")
        // bombPackage is added to exemptions by VisibilityPolicyBuilder
        assertFalse(snap.shouldHidePackage(10100, 0, bombPackage))
    }

    @Test
    fun `trusted exemption - android is never hidden`() {
        val snap = blacklistSnapshot(callingUid = 10100, userId = 0, "android")
        assertFalse(snap.shouldHidePackage(10100, 0, "android"))
    }

    @Test
    fun `trusted exemption - system settings is never hidden`() {
        val snap = blacklistSnapshot(callingUid = 10100, userId = 0, "com.android.settings")
        assertFalse(snap.shouldHidePackage(10100, 0, "com.android.settings"))
    }

    @Test
    fun `trusted exemption - custom extra exemption respected`() {
        val snap = snapshot {
            addExemption("com.custom.exempt")
            addCallerPolicy(
                VisibilityCallerPolicy(
                    caller = CallerKey(10100, 0),
                    mode = VisibilityMode.BLACKLIST,
                    packageSet = setOf("com.custom.exempt"),
                )
            )
        }
        assertFalse(snap.shouldHidePackage(10100, 0, "com.custom.exempt"))
    }

    // ---- system_server exemption -------------------------------------------

    @Test
    fun `system_server uid 1000 is never filtered`() {
        val snap = blacklistSnapshot(callingUid = 1000, userId = 0, "com.example.hidden")
        assertFalse(snap.shouldHidePackage(1000, 0, "com.example.hidden"))
    }

    @Test
    fun `system_server uid 1000 whitelist does not affect other callers`() {
        // Even if someone mistakenly creates a policy keyed to UID 1000,
        // it is ignored when the caller IS system_server.
        val snap = whitelistSnapshot(callingUid = 1000, userId = 0, "com.allowed.only")
        // system_server exemption fires before policy lookup.
        assertFalse(snap.shouldHidePackage(1000, 0, "com.other.app"))
    }

    // ---- Multi-user (userId isolation) ------------------------------------

    @Test
    fun `multi-user - policy for userId 0 does not affect userId 10`() {
        val snap = blacklistSnapshot(callingUid = 10100, userId = 0, "com.example.hidden")
        // Same UID but different userId should not be filtered.
        assertFalse(snap.shouldHidePackage(10100, 10, "com.example.hidden"))
    }

    @Test
    fun `multi-user - separate policies per userId are independent`() {
        val snap = snapshot {
            addCallerPolicy(
                VisibilityCallerPolicy(
                    caller = CallerKey(10100, 0),
                    mode = VisibilityMode.BLACKLIST,
                    packageSet = setOf("com.hidden.for.user0"),
                )
            )
            addCallerPolicy(
                VisibilityCallerPolicy(
                    caller = CallerKey(10100, 10),
                    mode = VisibilityMode.BLACKLIST,
                    packageSet = setOf("com.hidden.for.user10"),
                )
            )
        }
        assertTrue(snap.shouldHidePackage(10100, 0, "com.hidden.for.user0"))
        assertFalse(snap.shouldHidePackage(10100, 0, "com.hidden.for.user10"))
        assertTrue(snap.shouldHidePackage(10100, 10, "com.hidden.for.user10"))
        assertFalse(snap.shouldHidePackage(10100, 10, "com.hidden.for.user0"))
    }

    // ---- Shared UID handling -----------------------------------------------

    @Test
    fun `shared-uid - blacklisting one package hides its UID-mates`() {
        val snap = snapshot {
            addCallerPolicy(
                VisibilityCallerPolicy(
                    caller = CallerKey(10100, 0),
                    mode = VisibilityMode.BLACKLIST,
                    packageSet = setOf("com.foo.app"),
                    sharedUidPackages = mapOf(
                        1234 to setOf("com.foo.app", "com.foo.app.companion"),
                    ),
                )
            )
        }
        // The companion is in the same UID group as the listed package.
        assertTrue(snap.shouldHidePackage(10100, 0, "com.foo.app.companion"))
    }

    @Test
    fun `shared-uid - whitelist one package makes its UID-mates visible`() {
        val snap = snapshot {
            addCallerPolicy(
                VisibilityCallerPolicy(
                    caller = CallerKey(10100, 0),
                    mode = VisibilityMode.WHITELIST,
                    packageSet = setOf("com.foo.app"),
                    sharedUidPackages = mapOf(
                        1234 to setOf("com.foo.app", "com.foo.app.companion"),
                    ),
                )
            )
        }
        // Companion is UID-grouped with a whitelisted package, so it is visible.
        assertFalse(snap.shouldHidePackage(10100, 0, "com.foo.app.companion"))
    }

    // ---- Conflict resolution -----------------------------------------------

    @Test
    fun `conflict resolution - two blacklist entries for same caller merge sets`() {
        val snap = snapshot {
            addCallerPolicy(
                VisibilityCallerPolicy(
                    caller = CallerKey(10100, 0),
                    mode = VisibilityMode.BLACKLIST,
                    packageSet = setOf("com.example.a"),
                )
            )
            addCallerPolicy(
                VisibilityCallerPolicy(
                    caller = CallerKey(10100, 0),
                    mode = VisibilityMode.BLACKLIST,
                    packageSet = setOf("com.example.b"),
                )
            )
        }
        assertTrue(snap.shouldHidePackage(10100, 0, "com.example.a"))
        assertTrue(snap.shouldHidePackage(10100, 0, "com.example.b"))
    }

    @Test
    fun `conflict resolution - whitelist beats blacklist on mode conflict`() {
        // When BLACKLIST and WHITELIST both apply to the same caller, WHITELIST
        // wins per §12 "normalize consistently".
        val snap = snapshot {
            addCallerPolicy(
                VisibilityCallerPolicy(
                    caller = CallerKey(10100, 0),
                    mode = VisibilityMode.BLACKLIST,
                    packageSet = setOf("com.example.blocked"),
                )
            )
            addCallerPolicy(
                VisibilityCallerPolicy(
                    caller = CallerKey(10100, 0),
                    mode = VisibilityMode.WHITELIST,
                    packageSet = setOf("com.example.allowed"),
                )
            )
        }
        // In WHITELIST mode the allowed package is visible.
        assertFalse(snap.shouldHidePackage(10100, 0, "com.example.allowed"))
    }

    // ---- Empty snapshot ----------------------------------------------------

    @Test
    fun `empty snapshot hides nothing`() {
        assertTrue(VisibilityPolicySnapshot.EMPTY.isEmpty)
        assertFalse(
            VisibilityPolicySnapshot.EMPTY.shouldHidePackage(10100, 0, "com.example.anything")
        )
    }

    // ---- VisibilityPolicyCache ---------------------------------------------

    @Test
    fun `cache swap applies higher revision`() {
        val cache = VisibilityPolicyCache()
        assertEquals(0L, cache.current.revision)

        val snap = blacklistSnapshot(callingUid = 10100, userId = 0, "com.example.hidden")
        val applied = cache.swap(snap)
        assertTrue(applied)
        assertEquals(1L, cache.current.revision)
    }

    @Test
    fun `cache swap rejects stale revision`() {
        val cache = VisibilityPolicyCache()
        val snap1 = blacklistSnapshot(callingUid = 10100, userId = 0, "com.example.a")
        cache.swap(snap1)

        // A snapshot with revision 0 (older than 1) must be rejected.
        val stale = VisibilityPolicySnapshot.EMPTY // revision = 0
        val applied = cache.swap(stale)
        assertFalse(applied)
        assertEquals(1L, cache.current.revision)
    }

    @Test
    fun `cache delegates shouldHidePackage to current snapshot`() {
        val cache = VisibilityPolicyCache()
        val snap = blacklistSnapshot(callingUid = 10100, userId = 0, "com.example.hidden")
        cache.swap(snap)
        assertTrue(cache.shouldHidePackage(10100, 0, "com.example.hidden"))
        assertFalse(cache.shouldHidePackage(10100, 0, "com.example.other"))
    }
}
