package com.hzzmonet.zkbomb.domain.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parser, validator and safety-gate cases from `docs/research/ZRAM.md`, where the
 * interface was read off a live kernel and the ROM state off the extracted
 * image.
 *
 * The case that matters most is the fresh-zram one: on a device that has not
 * swapped anything yet, both derived figures divide by zero, and the difference
 * between reporting *unavailable* and reporting `Infinity` is the difference
 * between an honest metric and a fabricated one.
 */
class ZramTest {

    /** The sample recorded in the research note. */
    private val sample = "5281574912 1554040446 1587322880 0 2267963392 25787 1868524 82574 142718"

    private val fullCapabilities = ZramCapabilities(
        present = true,
        disksize = true,
        compAlgorithm = true,
        memLimit = true,
        mmStat = true,
        writebackReadable = true,
        swappiness = true,
        pageCluster = true,
    )

    private val algorithms = ZramAlgorithms.parse("lzo-rle lzo lz4 lz4hc [zstd] deflate 842")!!

    private val eightGb = 8L * 1024 * 1024 * 1024

    // ---- mm_stat -------------------------------------------------------------

    @Test
    fun `mm_stat decodes all nine fields`() {
        val stat = ZramMmStat.parse(sample)!!
        assertEquals(5281574912L, stat.origDataSize)
        assertEquals(1554040446L, stat.comprDataSize)
        assertEquals(1587322880L, stat.memUsedTotal)
        assertEquals(0L, stat.memLimit)
        assertEquals(2267963392L, stat.memUsedMax)
        assertEquals(25787L, stat.samePages)
        assertEquals(1868524L, stat.pagesCompacted)
        assertEquals(82574L, stat.hugePages)
        assertEquals(142718L, stat.hugePagesSince)
    }

    @Test
    fun `ratio and efficiency differ because of allocator overhead`() {
        val stat = ZramMmStat.parse(sample)!!
        assertEquals(3.4, stat.compressionRatio!!, 0.05)
        assertEquals(3.3, stat.ramEfficiency!!, 0.05)
        // Efficiency is always the lower, honest figure — it is what the resize
        // gate reasons about.
        assertTrue(stat.ramEfficiency!! < stat.compressionRatio!!)
    }

    @Test
    fun `fresh zram reports unavailable rather than infinity`() {
        val stat = ZramMmStat.parse("0 0 0 0 0 0 0 0 0")!!
        assertNull(stat.compressionRatio)
        assertNull(stat.ramEfficiency)
    }

    @Test
    fun `older kernels without the huge page fields still parse`() {
        val stat = ZramMmStat.parse("100 50 60 0 70 1 2")!!
        assertNull(stat.hugePages)
        assertNull(stat.hugePagesSince)
        assertEquals(2.0, stat.compressionRatio!!, 0.001)
    }

    @Test
    fun `unreadable mm_stat is null rather than a substituted value`() {
        assertNull(ZramMmStat.parse(""))
        assertNull(ZramMmStat.parse("100 50 60"))          // too few fields
        assertNull(ZramMmStat.parse("a b c d e f g"))      // non-numeric
    }

    @Test
    fun `mm_stat tolerates surrounding whitespace`() {
        assertNotNull(ZramMmStat.parse("  100   50  60 0 70 1 2  \n"))
    }

    // ---- comp_algorithm ------------------------------------------------------

    @Test
    fun `comp_algorithm yields both the domain and the current value`() {
        assertEquals(listOf("lzo-rle", "lzo", "lz4", "lz4hc", "zstd", "deflate", "842"), algorithms.available)
        assertEquals("zstd", algorithms.current)
    }

    @Test
    fun `comp_algorithm without a marked current parses with a null current`() {
        val parsed = ZramAlgorithms.parse("lzo lz4")!!
        assertEquals(listOf("lzo", "lz4"), parsed.available)
        assertNull(parsed.current)
    }

    @Test
    fun `unreadable comp_algorithm is null`() {
        assertNull(ZramAlgorithms.parse(""))
        assertNull(ZramAlgorithms.parse("   "))
    }

    // ---- validation ----------------------------------------------------------

    @Test
    fun `a sane config validates`() {
        val violations = ZramConfigValidator.validate(
            ZramConfig(disksizeBytes = 4L * 1024 * 1024 * 1024, algorithm = "zstd", swappiness = 100, pageCluster = 0),
            fullCapabilities,
            algorithms,
            eightGb,
        )
        assertTrue(violations.toString(), violations.isEmpty())
    }

    @Test
    fun `an algorithm the kernel did not report is rejected`() {
        val violations = ZramConfigValidator.validate(
            ZramConfig(null, "lz77-magic", null, null),
            fullCapabilities,
            algorithms,
            eightGb,
        )
        assertEquals(listOf(ZramViolation.ALGORITHM_NOT_AVAILABLE), violations)
    }

    @Test
    fun `an absurd disksize is rejected`() {
        val violations = ZramConfigValidator.validate(
            ZramConfig(disksizeBytes = eightGb * 10, algorithm = null, swappiness = null, pageCluster = null),
            fullCapabilities,
            algorithms,
            eightGb,
        )
        assertEquals(listOf(ZramViolation.DISKSIZE_ABSURD), violations)
    }

    @Test
    fun `a zero disksize is rejected`() {
        val violations = ZramConfigValidator.validate(
            ZramConfig(disksizeBytes = 0, algorithm = null, swappiness = null, pageCluster = null),
            fullCapabilities,
            algorithms,
            eightGb,
        )
        assertEquals(listOf(ZramViolation.DISKSIZE_NOT_POSITIVE), violations)
    }

    @Test
    fun `swappiness 200 is accepted because this rom itself sets it`() {
        // swappiness_on_launcher pushes 200; a 0..100 bound would reject a value
        // the device sets for itself.
        val violations = ZramConfigValidator.validate(
            ZramConfig(null, null, swappiness = 200, pageCluster = null),
            fullCapabilities,
            algorithms,
            eightGb,
        )
        assertTrue(violations.isEmpty())
    }

    @Test
    fun `swappiness above the kernel range is rejected`() {
        val violations = ZramConfigValidator.validate(
            ZramConfig(null, null, swappiness = 201, pageCluster = null),
            fullCapabilities,
            algorithms,
            eightGb,
        )
        assertEquals(listOf(ZramViolation.SWAPPINESS_OUT_OF_RANGE), violations)
    }

    @Test
    fun `page cluster outside the bomb range is rejected`() {
        val violations = ZramConfigValidator.validate(
            ZramConfig(null, null, null, pageCluster = 12),
            fullCapabilities,
            algorithms,
            eightGb,
        )
        assertEquals(listOf(ZramViolation.PAGE_CLUSTER_OUT_OF_RANGE), violations)
    }

    @Test
    fun `no zram means everything else is moot`() {
        val violations = ZramConfigValidator.validate(
            ZramConfig(disksizeBytes = -5, algorithm = "nope", swappiness = 9999, pageCluster = 99),
            ZramCapabilities.NONE,
            null,
            eightGb,
        )
        // One clear reason, not four consequential ones.
        assertEquals(listOf(ZramViolation.ZRAM_UNAVAILABLE), violations)
    }

    @Test
    fun `an attribute this kernel lacks is reported unsupported`() {
        val violations = ZramConfigValidator.validate(
            ZramConfig(null, null, null, pageCluster = 3),
            fullCapabilities.copy(pageCluster = false),
            algorithms,
            eightGb,
        )
        assertEquals(listOf(ZramViolation.ATTRIBUTE_UNSUPPORTED), violations)
    }

    // ---- swappiness targets --------------------------------------------------

    @Test
    fun `sys_critical is never writable`() {
        // It pins ueventd, vold, netd, surfaceflinger and servicemanager out of
        // swap. Writing it would let the processes that keep the device usable be
        // swapped out under pressure.
        assertFalse(SwappinessTarget.MEMCG_SYS_CRITICAL.writable)
        assertFalse(SwappinessTarget.MEMCG_SYS_CRITICAL in SwappinessTarget.WRITABLE)
    }

    @Test
    fun `the frozen-app memcg is among the writable targets`() {
        // A swappiness control touching only the global knob would be silently
        // wrong for exactly the apps the Freeze Engine froze.
        assertTrue(SwappinessTarget.MEMCG_FREEZE_APP in SwappinessTarget.WRITABLE)
    }

    @Test
    fun `every writable target is a fixed path`() {
        for (target in SwappinessTarget.WRITABLE) {
            assertTrue(target.path.startsWith("/proc/sys/vm/") || target.path.startsWith("/dev/memcg/"))
            assertFalse(target.path.contains(".."))
        }
    }

    // ---- resize safety -------------------------------------------------------

    @Test
    fun `a live resize with 5 GB swapped and little free memory is refused`() {
        val stat = ZramMmStat.parse(sample)!!
        val verdict = ZramResizeSafety.evaluate(stat, memAvailableBytes = 1L * 1024 * 1024 * 1024)
        assertEquals(
            ResizeVerdict.UnsafeLive(
                wouldFaultInBytes = 5281574912L,
                availableBytes = 1073741824L,
            ),
            verdict,
        )
    }

    @Test
    fun `an empty zram passes the gate`() {
        val stat = ZramMmStat.parse("0 0 0 0 0 0 0 0 0")!!
        assertEquals(ResizeVerdict.DeferToBoot, ZramResizeSafety.evaluate(stat, memAvailableBytes = 0))
    }

    @Test
    fun `the gate demands headroom beyond the raw size`() {
        val stat = ZramMmStat.parse("1000 500 600 0 700 1 2")!!
        // Exactly the uncompressed size is not enough: the working set keeps
        // running while pages fault back in.
        assertTrue(ZramResizeSafety.evaluate(stat, 1000) is ResizeVerdict.UnsafeLive)
        assertEquals(ResizeVerdict.DeferToBoot, ZramResizeSafety.evaluate(stat, 1200))
    }
}
