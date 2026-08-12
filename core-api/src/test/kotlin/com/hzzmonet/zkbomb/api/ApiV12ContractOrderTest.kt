package com.hzzmonet.zkbomb.api

import android.os.IBinder
import org.junit.Assert.assertEquals
import org.junit.Test

class ApiV12ContractOrderTest {
    @Test fun `v12 frequency transactions append after v11`() {
        listOf(
            "getFrequencyScalingSnapshot",
            "setFrequencyLimits",
        ).forEachIndexed { index, method ->
            assertEquals(
                IBinder.FIRST_CALL_TRANSACTION + V1_TO_V11_METHOD_COUNT + index,
                transactionId(method),
            )
        }
    }

    private fun transactionId(method: String): Int {
        val field = IBombService.Stub::class.java.getDeclaredField("TRANSACTION_$method")
        field.isAccessible = true
        return field.getInt(null)
    }

    private companion object { const val V1_TO_V11_METHOD_COUNT = 47 }
}

