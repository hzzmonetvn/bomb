package com.hzzmonet.zkbomb.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClockControlTest {
    private val target = BombClockDomain(
        id = "policy4",
        kind = BombClockKind.CPU_POLICY,
        availableMHz = listOf(710, 1_420, 2_840),
        boostMHz = listOf(2_840),
        currentMinMHz = 710,
        currentMaxMHz = 2_840,
        writable = true,
    )

    @Test
    fun `accepts an ordered window composed from the probed MHz ladder`() {
        assertNull(BombClockBounds.violation(target, minMHz = 710, maxMHz = 2_840))
    }

    @Test
    fun `rejects values that were not dynamically advertised`() {
        assertEquals(
            "Minimum is not one of the probed steps",
            BombClockBounds.violation(target, minMHz = 1_000, maxMHz = 2_840),
        )
        assertEquals(
            "Maximum is not one of the probed steps",
            BombClockBounds.violation(target, minMHz = 710, maxMHz = 3_000),
        )
    }

    @Test
    fun `rejects an inverted window and an empty target`() {
        assertEquals(
            "Minimum must not exceed maximum",
            BombClockBounds.violation(target, minMHz = 2_840, maxMHz = 710),
        )
        assertEquals(
            "No frequency steps were probed for gpu0",
            BombClockBounds.violation(
                target.copy(id = "gpu0", kind = BombClockKind.GPU, availableMHz = emptyList()),
                minMHz = 0,
                maxMHz = 0,
            ),
        )
    }
}
