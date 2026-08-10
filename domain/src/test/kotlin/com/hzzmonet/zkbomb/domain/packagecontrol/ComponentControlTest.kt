package com.hzzmonet.zkbomb.domain.packagecontrol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComponentControlTest {

    @Test
    fun `normal and generated component names are valid`() {
        assertTrue(ComponentClassNameValidator.isValid("com.example.MainActivity"))
        assertTrue(ComponentClassNameValidator.isValid("com.example.SyncService_Impl\$Worker"))
    }

    @Test
    fun `relative malformed and oversized component names are rejected`() {
        assertFalse(ComponentClassNameValidator.isValid(".MainActivity"))
        assertFalse(ComponentClassNameValidator.isValid("com.example.bad-name"))
        assertFalse(ComponentClassNameValidator.isValid("a".repeat(513)))
    }
}
