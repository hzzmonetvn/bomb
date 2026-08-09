package com.hzzmonet.zkbomb.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainModelTest {

    @Test
    fun `BombResult status helpers work correctly`() {
        val success = BombResult.success("Operation completed")
        assertTrue(success.isSuccess)
        assertEquals(BombResult.Status.SUCCESS, success.status)
        assertEquals("Operation completed", success.detail)

        val unsupported = BombResult.unsupported("Hardware feature missing")
        assertFalse(unsupported.isSuccess)
        assertEquals(BombResult.Status.UNSUPPORTED, unsupported.status)

        val denied = BombResult.permissionDenied("Caller missing signature")
        assertFalse(denied.isSuccess)

        val invalid = BombResult.invalidArgument("PID must be positive")
        assertFalse(invalid.isSuccess)

        val unavailable = BombResult.backendUnavailable("Daemon not running")
        assertFalse(unavailable.isSuccess)

        val failed = BombResult.failed("I/O error")
        assertFalse(failed.isSuccess)
    }

    @Test
    fun `BombCapabilities builder and getters report states correctly`() {
        val caps = BombCapabilities.Builder()
            .set(CapabilityKey.TASK_MANAGER, true)
            .set(CapabilityKey.DEEP_FREEZE, false)
            .set(CapabilityKey.LOG_DISABLE, CapabilityState.REQUIRES_ROOT)
            .build()

        assertTrue(caps.isSupported(CapabilityKey.TASK_MANAGER))
        assertFalse(caps.isSupported(CapabilityKey.DEEP_FREEZE))
        assertEquals(CapabilityState.REQUIRES_ROOT, caps.get(CapabilityKey.LOG_DISABLE))
        assertEquals(CapabilityState.NOT_PROBED, caps.get(CapabilityKey.AIRDROP_INTEROP))

        assertEquals(3, caps.probed().size)
    }

    @Test
    fun `ProcessItem validation identifies valid and invalid items`() {
        val validItem = ProcessItem(
            pid = 1234,
            uid = 10001,
            userId = 0,
            processName = "com.example.app",
            packageNames = listOf("com.example.app"),
            isForeground = true,
        )
        assertTrue(validItem.isValid())

        val invalidPid = validItem.copy(pid = -1)
        assertFalse(invalidPid.isValid())

        val invalidUid = validItem.copy(uid = -1)
        assertFalse(invalidUid.isValid())

        val invalidProcessName = validItem.copy(processName = "")
        assertFalse(invalidProcessName.isValid())
    }

    @Test
    fun `TelemetrySnapshot memory calculations and validity check work`() {
        val snapshot = TelemetrySnapshot(
            sampledAtElapsedRealtimeMillis = 1000L,
            uptimeMillis = 50000L,
            totalMemoryBytes = 8L * 1024 * 1024 * 1024,
            availableMemoryBytes = 2L * 1024 * 1024 * 1024,
        )

        assertTrue(snapshot.isValid())
        assertEquals(6L * 1024 * 1024 * 1024, snapshot.usedMemoryBytes)
        assertEquals(75.0, snapshot.memoryUsagePercentage, 0.01)

        val invalidSnapshot = snapshot.copy(sampledAtElapsedRealtimeMillis = 0L)
        assertFalse(invalidSnapshot.isValid())
    }
}
