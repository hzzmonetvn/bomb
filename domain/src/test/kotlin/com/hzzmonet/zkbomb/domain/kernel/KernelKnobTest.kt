package com.hzzmonet.zkbomb.domain.kernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The kernel-manager decision table.
 *
 * The case that carries the most weight is the daemon one: a knob a vendor
 * daemon rewrites must be refused even when the write would be permitted and
 * even with root, because the write lands and is then undone. Reporting success
 * for that is worse than refusing — the user watches the value revert with no
 * way to tell whether Bomb failed or the setting does nothing.
 */
class KernelKnobTest {

    // ---- the registry is honest about itself ---------------------------------

    @Test
    fun `every knob states how its backend was established`() {
        for (knob in KernelKnobRegistry.ALL) {
            assertTrue(
                "${knob.id} has no evidence",
                knob.evidence.isNotBlank(),
            )
        }
    }

    @Test
    fun `every knob has an absolute path and no caller-supplied component`() {
        for (knob in KernelKnobRegistry.ALL) {
            assertTrue("${knob.id}: ${knob.path}", knob.path.startsWith("/"))
            assertFalse("${knob.id} path traversal", knob.path.contains(".."))
        }
    }

    @Test
    fun `knob ids are unique`() {
        val ids = KernelKnobRegistry.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    // ---- what is actually usable ---------------------------------------------

    @Test
    fun `page-cluster and dirty ratios are the knobs ROM mode can really write`() {
        val usable = KernelKnobRegistry.romModeUsable().map { it.id }.toSet()
        assertTrue("vm.page-cluster" in usable)
        assertTrue("vm.dirty_ratio" in usable)
        assertTrue("vm.dirty_background_ratio" in usable)
    }

    @Test
    fun `swappiness is not counted as cleanly usable`() {
        // init reaches it, but perfinit.conf's swappiness_on_launcher also writes
        // it. Occasional contention is still contention, and claiming a clean
        // write would be wrong in exactly the cases the user would notice.
        val knob = KernelKnobRegistry.byId("vm.swappiness")!!
        assertEquals(KnobBackend.INIT, knob.backend)
        assertEquals(KnobContention.OCCASIONAL, knob.contention)
        assertFalse(knob.usableInRomMode)
    }

    @Test
    fun `CPU and GPU knobs are root-only and daemon-contested`() {
        for (id in listOf(
            "cpu.scaling_governor", "cpu.scaling_max_freq",
            "cpu.scaling_min_freq", "gpu.devfreq_governor", "gpu.max_pwrlevel",
        )) {
            val knob = KernelKnobRegistry.byId(id)!!
            assertTrue("$id should require root", knob.requiresRoot)
            assertEquals("$id contention", KnobContention.VENDOR_DAEMON, knob.contention)
            assertFalse("$id must not read as usable", knob.usableInRomMode)
        }
    }

    @Test
    fun `charge current is reported as a negotiation, not a setting`() {
        val knob = KernelKnobRegistry.byId("power.charge_current_max")!!
        assertTrue(knob.requiresRoot)
        assertEquals(KnobContention.VENDOR_DAEMON, knob.contention)
        assertTrue(knob.evidence.contains("hal_micharge_default"))
    }

    @Test
    fun `unverified knobs are not offered`() {
        for (knob in KernelKnobRegistry.unverified()) {
            assertFalse(knob.usableInRomMode)
            assertNotNull("${knob.id} must explain itself", knob.unavailableReason())
        }
    }

    // ---- reasons are distinguishable ------------------------------------------

    @Test
    fun `daemon contention outranks the root explanation`() {
        // "needs root" would send the user to install a root manager, which will
        // not help — the daemon still owns the node. The more actionable reason
        // has to win.
        val reason = KernelKnobRegistry.byId("cpu.scaling_governor")!!.unavailableReason()!!
        assertTrue(reason, reason.contains("daemon"))
    }

    @Test
    fun `a usable knob has no reason`() {
        assertNull(KernelKnobRegistry.byId("vm.page-cluster")!!.unavailableReason())
    }

    // ---- validation ------------------------------------------------------------

    @Test
    fun `an in-range value on a reachable knob passes`() {
        assertTrue(KernelKnobValidator.isAllowed("vm.page-cluster", "3", false))
    }

    @Test
    fun `an out-of-range value is refused`() {
        assertEquals(
            listOf(KnobViolation.OUT_OF_RANGE),
            KernelKnobValidator.validate("vm.page-cluster", "9", false),
        )
    }

    @Test
    fun `a non-numeric value is refused`() {
        assertEquals(
            listOf(KnobViolation.MALFORMED),
            KernelKnobValidator.validate("vm.dirty_ratio", "high", false),
        )
    }

    @Test
    fun `an unknown knob id is refused`() {
        assertEquals(
            listOf(KnobViolation.UNKNOWN_KNOB),
            KernelKnobValidator.validate("vm.make_it_fast", "1", true),
        )
    }

    @Test
    fun `a root-only knob is refused without the root backend`() {
        val violations = KernelKnobValidator.validate("vm.watermark_scale_factor", "200", false)
        assertTrue(KnobViolation.NO_BACKEND in violations)
    }

    @Test
    fun `the same knob passes once root is present`() {
        assertTrue(KernelKnobValidator.isAllowed("vm.watermark_scale_factor", "200", true))
    }

    @Test
    fun `root does not unlock a daemon-contested knob`() {
        // The point of the whole contention field. Permission was never the
        // problem here.
        val violations = KernelKnobValidator.validate("cpu.scaling_governor", "performance", true)
        assertTrue(KnobViolation.CONTESTED_BY_DAEMON in violations)
    }

    @Test
    fun `a probed domain with nothing probed refuses every value`() {
        // "The device has not told us what it accepts" is not a reason to accept
        // anything — it is a reason to accept nothing.
        val violations = KernelKnobValidator.validate("io.scheduler", "mq-deadline", true)
        assertTrue(KnobViolation.DOMAIN_NOT_PROBED in violations)
    }

    @Test
    fun `a probed domain accepts only what the device reported`() {
        val probed = KnobValueDomain.Probed(listOf("none", "mq-deadline", "kyber"))
        assertTrue("mq-deadline" in probed.available)
        assertFalse("bfq" in probed.available)
    }

    @Test
    fun `flag knobs take only 0 or 1`() {
        val flag = KnobValueDomain.Flag
        assertEquals(KnobValueDomain.Flag, flag)
    }
}
