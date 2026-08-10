package com.hzzmonet.zkbomb.api

import android.os.IBinder
import org.junit.Assert.assertEquals
import org.junit.Test

class ApiV8ContractOrderTest {

    @Test
    fun `v8 automation transactions are appended after v7`() {
        val v8Methods = listOf(
            "getAutomationRules",
            "upsertAutomationRule",
            "deleteAutomationRule",
            "setAutomationEnabled",
        )

        v8Methods.forEachIndexed { index, method ->
            assertEquals(
                IBinder.FIRST_CALL_TRANSACTION + V1_TO_V7_METHOD_COUNT + index,
                transactionId(method),
            )
        }
    }

    private fun transactionId(method: String): Int {
        val field = IBombService.Stub::class.java.getDeclaredField("TRANSACTION_$method")
        field.isAccessible = true
        return field.getInt(null)
    }

    private companion object {
        const val V1_TO_V7_METHOD_COUNT = 29
    }
}
