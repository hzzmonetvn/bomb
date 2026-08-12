package com.hzzmonet.zkbomb.api

import android.os.IBinder
import org.junit.Assert.assertEquals
import org.junit.Test

class ApiV10ContractOrderTest {
    @Test fun `v10 bridge and performance transactions append after v9`() {
        val methods = listOf(
            "getBridgeStatus", "publishLiveEvent", "dismissLiveEvent",
            "getPerformanceProfiles", "setPerformanceProfile", "clearPerformanceProfile",
            "setThermalGuardianConfig", "clearThermalGuardianConfig",
        )
        methods.forEachIndexed { index, method ->
            assertEquals(
                IBinder.FIRST_CALL_TRANSACTION + V1_TO_V9_METHOD_COUNT + index,
                transactionId(method),
            )
        }
    }

    private fun transactionId(method: String): Int {
        val field = IBombService.Stub::class.java.getDeclaredField("TRANSACTION_$method")
        field.isAccessible = true
        return field.getInt(null)
    }

    private companion object { const val V1_TO_V9_METHOD_COUNT = 36 }
}
