package com.hzzmonet.zkbomb.api

import android.os.IBinder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ApiContractOrderTest {

    @Test
    fun `v1 through v6 transaction ids stay fixed and v7 is appended`() {
        val v1ToV6 = listOf(
            "getApiVersion",
            "getCapabilities",
            "getFreezeStatus",
            "setFreezeMode",
            "getLogStatus",
            "setLogLevel",
            "getMemoryStatus",
            "setMemoryConfig",
            "getRuntimeMode",
            "getProcessSnapshot",
            "getSystemTelemetrySnapshot",
            "getProcessList",
            "setFreezeState",
            "getTelemetrySnapshot",
            "getPackageSnapshot",
            "forceStopPackage",
            "setComponentState",
            "setVisibilityPolicy",
            "clearVisibilityPolicy",
            "createSettingsProfile",
            "deleteSettingsProfile",
            "addSettingsOverride",
            "removeSettingsOverride",
            "assignSettingsProfile",
            "clearSettingsAssignment",
            "setFirewallRule",
            "clearFirewallRule",
            "reloadAdBlockRules",
        )

        v1ToV6.forEachIndexed { index, method ->
            assertEquals(IBinder.FIRST_CALL_TRANSACTION + index, transactionId(method))
        }
        assertEquals(
            IBinder.FIRST_CALL_TRANSACTION + v1ToV6.size,
            transactionId("getSelectedProcessMemory"),
        )
    }

    @Test
    fun `typed unavailable selected memory never fabricates zero metrics`() {
        val result = SelectedProcessMemory.unavailable(
            pid = 42,
            sampledAtElapsedRealtimeMillis = 123L,
            status = SelectedProcessMemoryStatus.UNAVAILABLE,
        )

        assertEquals(SelectedProcessMemoryStatus.UNAVAILABLE, result.parsedStatus)
        assertFalse(result.available)
        assertNull(result.pssBytes)
        assertNull(result.privateDirtyBytes)
        assertNull(result.rssBytes)
    }

    private fun transactionId(method: String): Int {
        val field = IBombService.Stub::class.java.getDeclaredField("TRANSACTION_$method")
        field.isAccessible = true
        return field.getInt(null)
    }
}
