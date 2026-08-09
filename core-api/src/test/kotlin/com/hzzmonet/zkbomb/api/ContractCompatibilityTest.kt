package com.hzzmonet.zkbomb.api

import com.hzzmonet.zkbomb.domain.freeze.FreezeMode
import com.hzzmonet.zkbomb.domain.log.LogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the two ways a Binder contract silently breaks.
 *
 * A Bomb app and a Bomb service are updated separately — in ROM mode the service
 * ships with the ROM and the app does not — so an old client will call a new
 * service and vice versa. Neither side crashes when that goes wrong; they just
 * quietly disagree about what a value means, which is far worse.
 *
 * These are plain JVM tests. `Parcel` round-trips need a device or Robolectric
 * and are **not** covered here — see the note at the end of this file.
 */
class ContractCompatibilityTest {

    // ---- capabilities: names, not ordinals -----------------------------------

    @Test
    fun `an unreported capability reads as not probed`() {
        // The critical default. If an unanswered capability read as SUPPORTED,
        // a capability accidentally left out of the probe would enable a control
        // the device cannot honour.
        assertEquals(CapabilityState.NOT_PROBED, BombCapabilities.NONE[BombCapability.GPU_TELEMETRY])
        assertFalse(BombCapabilities.NONE.isSupported(BombCapability.GPU_TELEMETRY))
    }

    @Test
    fun `every capability defaults to not probed`() {
        for (capability in BombCapability.entries) {
            assertEquals(
                "$capability must not default to supported",
                CapabilityState.NOT_PROBED,
                BombCapabilities.NONE[capability],
            )
        }
    }

    @Test
    fun `a capability name this client does not know is ignored, not misread`() {
        // Simulates a service newer than the app: it reports a capability that
        // did not exist when this client was built. The client must ignore it
        // rather than shift the meaning of anything else.
        val fromNewerService = BombCapabilities(
            mapOf(
                BombCapability.SOFT_FREEZE.name to CapabilityState.SUPPORTED.name,
                "QUANTUM_TELEPORT" to "SUPPORTED",
            ),
        )
        assertTrue(fromNewerService.isSupported(BombCapability.SOFT_FREEZE))
        assertEquals(1, fromNewerService.probed().size)
    }

    @Test
    fun `an unknown state name degrades to not probed rather than to supported`() {
        val garbled = BombCapabilities(mapOf(BombCapability.AD_BLOCK.name to "MAYBE"))
        assertEquals(CapabilityState.NOT_PROBED, garbled[BombCapability.AD_BLOCK])
        assertFalse(garbled.isSupported(BombCapability.AD_BLOCK))
    }

    @Test
    fun `builder records both tri-state and boolean forms`() {
        val capabilities = BombCapabilities.Builder()
            .set(BombCapability.SOFT_FREEZE, true)
            .set(BombCapability.DEEP_FREEZE, false)
            .set(BombCapability.LOG_DISABLE, CapabilityState.REQUIRES_ROOT)
            .set(BombCapability.AIRDROP_INTEROP, CapabilityState.DECLARED_NOT_IMPLEMENTED)
            .build()

        assertEquals(CapabilityState.SUPPORTED, capabilities[BombCapability.SOFT_FREEZE])
        assertEquals(CapabilityState.UNSUPPORTED, capabilities[BombCapability.DEEP_FREEZE])
        assertEquals(CapabilityState.REQUIRES_ROOT, capabilities[BombCapability.LOG_DISABLE])
        assertEquals(
            CapabilityState.DECLARED_NOT_IMPLEMENTED,
            capabilities[BombCapability.AIRDROP_INTEROP],
        )
        assertEquals(4, capabilities.probed().size)
    }

    @Test
    fun `requires-root is not supported`() {
        // REQUIRES_ROOT means "would work with a backend you do not have", so a
        // UI must not treat it as usable.
        val capabilities = BombCapabilities.Builder()
            .set(BombCapability.LOG_DISABLE, CapabilityState.REQUIRES_ROOT)
            .build()
        assertFalse(capabilities.isSupported(BombCapability.LOG_DISABLE))
    }

    @Test
    fun `declared-not-implemented is distinguishable from unsupported`() {
        // The honest answer for AirDrop interop on the current target: the ROM
        // declares the HAL but ships no implementation. Collapsing this into
        // UNSUPPORTED would lose the fact that supplying the files would work.
        val declared = BombCapabilities.Builder()
            .set(BombCapability.AIRDROP_INTEROP, CapabilityState.DECLARED_NOT_IMPLEMENTED)
            .build()
        val absent = BombCapabilities.Builder()
            .set(BombCapability.AIRDROP_INTEROP, CapabilityState.UNSUPPORTED)
            .build()
        assertFalse(declared.isSupported(BombCapability.AIRDROP_INTEROP))
        assertFalse(absent.isSupported(BombCapability.AIRDROP_INTEROP))
        assertTrue(declared[BombCapability.AIRDROP_INTEROP] != absent[BombCapability.AIRDROP_INTEROP])
    }

    // ---- results -------------------------------------------------------------

    @Test
    fun `only success is success`() {
        assertTrue(BombResult.success().isSuccess)
        for (status in BombResult.Status.entries.filter { it != BombResult.Status.SUCCESS }) {
            assertFalse("$status must not read as success", BombResult(status).isSuccess)
        }
    }

    @Test
    fun `unsupported and backend-unavailable stay distinct`() {
        // One means hide the control forever, the other means retry later.
        // A UI that conflates them either nags about a feature the device will
        // never have, or hides one that is only temporarily unreachable.
        assertTrue(
            BombResult.unsupported().status != BombResult.backendUnavailable().status,
        )
    }

    @Test
    fun `detail defaults to absent`() {
        assertNull(BombResult.success().detail)
    }

    // ---- status models -------------------------------------------------------

    @Test
    fun `freeze status parses the mode name it was given`() {
        val status = FreezeStatus(
            packageName = "com.example.app",
            userId = 0,
            mode = FreezeMode.DEEP_FREEZE.name,
            hasUnfrozenProcesses = false,
            availableModes = listOf(FreezeMode.NORMAL.name, FreezeMode.DEEP_FREEZE.name),
            exclusionReason = null,
        )
        assertEquals(FreezeMode.DEEP_FREEZE, status.resolvedMode())
        assertFalse(status.isProtected)
    }

    @Test
    fun `an unknown mode name degrades to normal rather than throwing`() {
        // A newer service could name a mode this client has never heard of.
        // Falling back to NORMAL under-claims (shows the app as not frozen)
        // rather than crashing the UI.
        val status = FreezeStatus("com.example.app", 0, "CRYOGENIC", false, emptyList(), null)
        assertEquals(FreezeMode.NORMAL, status.resolvedMode())
    }

    @Test
    fun `a protected package carries its reason`() {
        val status = FreezeStatus("android", 0, FreezeMode.NORMAL.name, false, emptyList(), "FRAMEWORK")
        assertTrue(status.isProtected)
        assertEquals("FRAMEWORK", status.exclusionReason)
    }

    @Test
    fun `log status detects a half-applied tier`() {
        val half = LogStatus(
            declaredLevel = LogLevel.REDUCED.name,
            effectiveLevel = LogLevel.DEFAULT.name,
            rebootRequiredToChange = false,
            auditRatePerSecond = null,
            vendorLogSinkActive = true,
        )
        assertFalse(half.isConsistent)
        assertEquals(LogLevel.DEFAULT, half.resolvedEffectiveLevel())
    }

    @Test
    fun `log status carries the vendor sink warning`() {
        // With ylog writing, "logging off" is not true, and the model has to be
        // able to say so.
        val off = LogStatus(LogLevel.OFF.name, LogLevel.OFF.name, true, null, vendorLogSinkActive = true)
        assertTrue(off.isConsistent)
        assertTrue(off.vendorLogSinkActive)
    }

    @Test
    fun `memory status reports unavailable ratios as null`() {
        val fresh = MemoryStatus(
            zramPresent = true,
            disksizeBytes = 8L * 1024 * 1024 * 1024,
            origDataSize = 0,
            comprDataSize = 0,
            memUsedTotal = 0,
            compressionRatio = null,
            ramEfficiency = null,
            currentAlgorithm = "zstd",
            availableAlgorithms = listOf("lzo", "lz4", "zstd"),
            globalSwappiness = 100,
            swappinessByTarget = mapOf("MEMCG_FREEZE_APP" to 60),
            memoryExtensionSwitches = mapOf(
                "perfinit.conf:extm_on" to "1",
                "persist.miui.extm.enable" to "0",
            ),
        )
        assertNull(fresh.compressionRatio)
        assertNull(fresh.ramEfficiency)
        // Both switches reported, neither trusted as authoritative.
        assertEquals(2, fresh.memoryExtensionSwitches.size)
    }
}

/*
 * Not covered here, and deliberately said out loud rather than left implied:
 *
 * 1. `Parcel` round-trips. `@Parcelize` generates the marshalling code and the
 *    generated `$Creator` classes were verified present in the AAR, but a real
 *    write-then-read needs `android.os.Parcel`, which needs Robolectric or a
 *    device. Adding Robolectric for this is a dependency decision, not something
 *    to slip in.
 *
 * 2. Transaction-ID stability. The generated stub currently assigns
 *    getApiVersion=0, getCapabilities=1, getFreezeStatus=2, setFreezeMode=3,
 *    getLogStatus=4, setLogLevel=5, getMemoryStatus=6, setMemoryConfig=7 — in
 *    declaration order, which is exactly why the AIDL file says append-only.
 *    Reordering methods would silently repoint every later transaction. That was
 *    verified by reading the generated stub, not by a test, because asserting on
 *    generated Java from a unit test is a fragile guard that fails for the wrong
 *    reasons.
 */
