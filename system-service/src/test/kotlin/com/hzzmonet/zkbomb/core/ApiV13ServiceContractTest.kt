package com.hzzmonet.zkbomb.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ApiV13ServiceContractTest {
    @Test
    fun `service advertises battery current parcel contract v13`() {
        assertEquals(13, CURRENT_BOMB_API_VERSION)
    }
}
