package com.hzzmonet.zkbomb.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The selected-process memory resolver — the app-side decision logic for the v7
 * call.
 *
 * The cases that carry the weight are the guards: an older service must fall back
 * (never call), and a reused PID must never surface another process's memory even
 * when the backend reported it as available. Those are asserted first and hardest.
 */
class SelectedMemoryResolverTest {

    private fun memory(
        status: BombSelectedMemoryStatus,
        pssBytes: Long? = null,
    ) = BombSelectedProcessMemory(
        pid = 4242,
        sampledAtElapsedRealtimeMillis = 0L,
        status = status,
        pssBytes = pssBytes,
        privateDirtyBytes = null,
        rssBytes = null,
    )

    private fun resolve(
        apiVersion: Int = 7,
        result: BombSelectedProcessMemory? = memory(BombSelectedMemoryStatus.AVAILABLE),
        expected: Long? = 1_000L,
        current: Long? = 1_000L,
    ) = SelectedMemoryResolver.resolve(apiVersion, result, expected, current)

    // ---- contract / call guards ---------------------------------------------

    @Test
    fun `older than v7 is unsupported, whatever the result`() {
        assertEquals(
            SelectedProcessMemoryState.Unsupported,
            resolve(apiVersion = 6, result = memory(BombSelectedMemoryStatus.AVAILABLE)),
        )
    }

    @Test
    fun `a failed v7 call is an error, not a zero`() {
        assertIs<SelectedProcessMemoryState.Error>(resolve(result = null))
    }

    // ---- PID-reuse identity guard -------------------------------------------

    @Test
    fun `available with a reused pid is disappeared, not the other process's memory`() {
        val state = resolve(
            result = memory(BombSelectedMemoryStatus.AVAILABLE, pssBytes = 999L),
            expected = 1_000L,
            current = 2_000L,
        )
        assertEquals(SelectedProcessMemoryState.Disappeared, state)
    }

    @Test
    fun `available with a matching generation is ready and carries the sample`() {
        val state = resolve(
            result = memory(BombSelectedMemoryStatus.AVAILABLE, pssBytes = 12_345L),
            expected = 1_000L,
            current = 1_000L,
        )
        val ready = assertIs<SelectedProcessMemoryState.Ready>(state)
        assertEquals(12_345L, ready.memory.pssBytes)
    }

    @Test
    fun `unknown generation cannot prove reuse and is allowed through`() {
        // No captured identity (deep-link route) — reuse cannot be checked.
        assertIs<SelectedProcessMemoryState.Ready>(resolve(expected = null, current = 1_000L))
        // Current start tick withheld — reuse cannot be proven either.
        assertIs<SelectedProcessMemoryState.Ready>(resolve(expected = 1_000L, current = null))
    }

    // ---- backend status mapping ---------------------------------------------

    @Test
    fun `invalid pid and disappeared map to disappeared`() {
        assertEquals(SelectedProcessMemoryState.Disappeared, resolve(result = memory(BombSelectedMemoryStatus.INVALID_PID)))
        assertEquals(SelectedProcessMemoryState.Disappeared, resolve(result = memory(BombSelectedMemoryStatus.DISAPPEARED)))
    }

    @Test
    fun `not-visible, unavailable and permission-denied map to unavailable with a reason`() {
        for (status in listOf(
            BombSelectedMemoryStatus.NOT_VISIBLE,
            BombSelectedMemoryStatus.UNAVAILABLE,
            BombSelectedMemoryStatus.PERMISSION_DENIED,
        )) {
            val state = resolve(result = memory(status))
            val unavailable = assertIs<SelectedProcessMemoryState.Unavailable>(state)
            assertTrue(unavailable.reason.isNotBlank(), "$status should carry a reason")
        }
    }

    // ---- status name mapping is total ---------------------------------------

    @Test
    fun `an unknown backend status name degrades to unavailable`() {
        assertEquals(BombSelectedMemoryStatus.UNAVAILABLE, BombSelectedMemoryStatus.fromName("SOMETHING_NEW"))
    }
}
