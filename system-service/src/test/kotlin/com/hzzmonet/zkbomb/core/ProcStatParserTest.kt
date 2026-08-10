package com.hzzmonet.zkbomb.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProcStatParserTest {

    @Test
    fun `process stat accepts spaces and closing parens in comm`() {
        val parsed = ProcStatParser.parseProcess(
            "42 (worker ) name) S 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20",
        )

        assertEquals(23L, parsed?.cpuTimeTicks)
        assertEquals(17, parsed?.threadCount)
        assertEquals(19L, parsed?.startTimeTicks)
    }

    @Test
    fun `aggregate cpu includes iowait in idle and all counters in total`() {
        val parsed = ProcStatParser.parseCpuTotals("cpu  10 2 3 40 5 6 7 8 9 10")

        assertEquals(100L, parsed?.totalTicks)
        assertEquals(45L, parsed?.idleTicks)
    }

    @Test
    fun `malformed and truncated proc lines are unavailable`() {
        assertNull(ProcStatParser.parseProcess("not a stat line"))
        assertNull(ProcStatParser.parseCpuTotals("cpu 1 nope 3 4"))
    }

    @Test
    fun `process status parses VmRSS in bytes and refuses missing or zero values`() {
        assertEquals(
            12_345L * 1024L,
            ProcStatParser.parseRssBytes("Name:\tworker\nVmRSS:\t   12345 kB\nThreads:\t4\n"),
        )
        assertNull(ProcStatParser.parseRssBytes("Name:\tworker\nThreads:\t4\n"))
        assertNull(ProcStatParser.parseRssBytes("VmRSS:\t0 kB\n"))
        assertNull(ProcStatParser.parseRssBytes("VmRSS:\t12 MB\n"))
    }
}
