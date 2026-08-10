package com.hzzmonet.zkbomb.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pure delta arithmetic behind Task Manager CPU% and Monitor CPU%.
 *
 * These are the calculations that would otherwise be impossible to reason about
 * on a device: a jiffie counter only means something as a difference between two
 * samples, and the failure modes (a missing counter, a counter that did not
 * advance, a reused pid) must all resolve to "unavailable", never a fake number.
 */
class TelemetryMathTest {

    // ---- system CPU% from /proc/stat total & idle deltas --------------------

    @Test
    fun systemCpuPercent_halfBusy() {
        // total advanced 1000, idle advanced 500 → 50% busy.
        assertEquals(50, TelemetryMath.systemCpuPercent(prevTotal = 0, prevIdle = 0, total = 1000, idle = 500))
    }

    @Test
    fun systemCpuPercent_fullyIdle() {
        assertEquals(0, TelemetryMath.systemCpuPercent(0, 0, 1000, 1000))
    }

    @Test
    fun systemCpuPercent_fullyBusy() {
        assertEquals(100, TelemetryMath.systemCpuPercent(0, 0, 1000, 0))
    }

    @Test
    fun systemCpuPercent_nullWhenCounterMissing() {
        assertNull(TelemetryMath.systemCpuPercent(prevTotal = null, prevIdle = 0, total = 1000, idle = 500))
        assertNull(TelemetryMath.systemCpuPercent(prevTotal = 0, prevIdle = 0, total = null, idle = 500))
    }

    @Test
    fun systemCpuPercent_nullWhenTotalDidNotAdvance() {
        // First sample against itself, or a stalled counter: not divide-by-zero, not 0%.
        assertNull(TelemetryMath.systemCpuPercent(1000, 500, 1000, 500))
    }

    @Test
    fun systemCpuPercent_clampsIdleOvershoot() {
        // Idle delta exceeding total delta (counter skew) must not go negative.
        assertEquals(0, TelemetryMath.systemCpuPercent(0, 0, 100, 200))
    }

    // ---- per-process CPU% as a share of total capacity ----------------------

    @Test
    fun processCpuPercent_quarterOfTotal() {
        // Process used 250 ticks while the whole machine used 1000 → 25%.
        assertEquals(25f, TelemetryMath.processCpuPercent(prevTicks = 0, ticks = 250, deltaTotalTicks = 1000))
    }

    @Test
    fun processCpuPercent_nullOnFirstSample() {
        // No previous ticks yet: the rate is unknown, not zero.
        assertNull(TelemetryMath.processCpuPercent(prevTicks = null, ticks = 250, deltaTotalTicks = 1000))
    }

    @Test
    fun processCpuPercent_nullWhenTicksWithheld() {
        assertNull(TelemetryMath.processCpuPercent(prevTicks = 0, ticks = null, deltaTotalTicks = 1000))
    }

    @Test
    fun processCpuPercent_nullWhenTotalDidNotAdvance() {
        assertNull(TelemetryMath.processCpuPercent(prevTicks = 0, ticks = 250, deltaTotalTicks = 0))
    }

    @Test
    fun processCpuPercent_pidReuseTreatedAsUnknown() {
        // A negative process delta means the pid was reused — not a spike.
        assertNull(TelemetryMath.processCpuPercent(prevTicks = 500, ticks = 100, deltaTotalTicks = 1000))
    }

    @Test
    fun processCpuPercent_clampsToHundred() {
        assertEquals(100f, TelemetryMath.processCpuPercent(prevTicks = 0, ticks = 5000, deltaTotalTicks = 1000))
    }

    // ---- uid classification --------------------------------------------------

    @Test
    fun isSystemUid_belowAppRange() {
        assertTrue(TelemetryMath.isSystemUid(1000)) // system
        assertTrue(TelemetryMath.isSystemUid(1001)) // radio
    }

    @Test
    fun isSystemUid_appRange() {
        assertTrue(!TelemetryMath.isSystemUid(10000)) // first app uid
        assertTrue(!TelemetryMath.isSystemUid(10234))
    }

    @Test
    fun isSystemUid_secondaryUserAppRange() {
        // User 10's app uid: 1010000+ → still an app, not system.
        assertTrue(!TelemetryMath.isSystemUid(1010042))
    }

    @Test
    fun isSystemUid_secondaryUserSystem() {
        assertTrue(TelemetryMath.isSystemUid(1001000)) // user 10, system uid
    }
}
