package com.hzzmonet.zkbomb.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Binder trust boundary.
 *
 * Two of these encode decisions that are easy to get subtly wrong and impossible
 * to notice afterwards: how a **shared UID** is treated, and whether a
 * privileged shell gets a free pass.
 */
class CallerPolicyTest {

    private val selfUid = 10123
    private val policy = CallerPolicy(
        selfUid = selfUid,
        allowedPackages = setOf("com.hzzmonet.zkbomb", "com.hzzmonet.zkbomb.companion"),
    )

    private fun evaluate(
        uid: Int,
        packages: List<String>? = listOf("com.hzzmonet.zkbomb"),
        signatureMatches: Boolean = true,
    ) = policy.evaluate(uid, packages, signatureMatches)

    @Test
    fun `bomb's own process is allowed`() {
        assertEquals(CallerVerdict.Allowed, evaluate(selfUid, packages = null, signatureMatches = false))
    }

    @Test
    fun `an allowed package with a matching signature is allowed`() {
        assertEquals(CallerVerdict.Allowed, evaluate(10200))
    }

    @Test
    fun `a uid with no packages is refused`() {
        assertEquals(
            CallerVerdict.Denied(DenialReason.UNKNOWN_UID),
            evaluate(10200, packages = null),
        )
        assertEquals(
            CallerVerdict.Denied(DenialReason.UNKNOWN_UID),
            evaluate(10200, packages = emptyList()),
        )
    }

    @Test
    fun `an unlisted package is refused`() {
        assertEquals(
            CallerVerdict.Denied(DenialReason.PACKAGE_NOT_ALLOWED),
            evaluate(10200, packages = listOf("com.attacker.app")),
        )
    }

    @Test
    fun `a shared uid must be entirely allowed, not merely partly`() {
        // The case that matters. An "any package matches" rule would let
        // com.attacker.app act through the UID it shares with a permitted
        // package, and every later check would see a legitimate caller.
        assertEquals(
            CallerVerdict.Denied(DenialReason.PACKAGE_NOT_ALLOWED),
            evaluate(10200, packages = listOf("com.hzzmonet.zkbomb", "com.attacker.app")),
        )
    }

    @Test
    fun `a fully allowed shared uid is allowed`() {
        assertEquals(
            CallerVerdict.Allowed,
            evaluate(
                10200,
                packages = listOf("com.hzzmonet.zkbomb", "com.hzzmonet.zkbomb.companion"),
            ),
        )
    }

    @Test
    fun `the right package name without the signature is refused`() {
        // A package name is a claim; anyone can install a package called
        // anything. The signature is the proof, and it is checked last so this
        // path cannot be reached by name alone.
        assertEquals(
            CallerVerdict.Denied(DenialReason.SIGNATURE_MISMATCH),
            evaluate(10200, signatureMatches = false),
        )
    }

    @Test
    fun `root is refused`() {
        assertEquals(
            CallerVerdict.Denied(DenialReason.PRIVILEGED_SHELL),
            evaluate(0, packages = listOf("com.hzzmonet.zkbomb")),
        )
    }

    @Test
    fun `system is refused`() {
        // Not an oversight. A privileged shell can already do anything Bomb can
        // do without going through Bomb, so allowing it here adds an unaudited
        // path into every typed API and buys nothing.
        assertEquals(
            CallerVerdict.Denied(DenialReason.PRIVILEGED_SHELL),
            evaluate(1000, packages = listOf("com.hzzmonet.zkbomb")),
        )
    }

    @Test
    fun `a privileged uid is refused even with a valid package and signature`() {
        assertEquals(
            CallerVerdict.Denied(DenialReason.PRIVILEGED_SHELL),
            evaluate(0, packages = listOf("com.hzzmonet.zkbomb"), signatureMatches = true),
        )
    }

    @Test
    fun `self check precedes the privileged-uid rule`() {
        // If Bomb itself ever runs as the system UID — which is exactly what
        // ROM mode aims at — it must not lock itself out.
        val asSystem = CallerPolicy(selfUid = 1000, allowedPackages = setOf("com.hzzmonet.zkbomb"))
        assertEquals(CallerVerdict.Allowed, asSystem.evaluate(1000, null, false))
    }
}
