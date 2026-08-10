package com.hzzmonet.zkbomb.core

import android.content.pm.PackageManager
import com.hzzmonet.zkbomb.domain.packagecontrol.ComponentOverrideState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComponentStateResolverTest {

    @Test
    fun `default preserves the manifest state`() {
        assertTrue(ComponentStateResolver.effective(true, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT))
        assertFalse(ComponentStateResolver.effective(false, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT))
    }

    @Test
    fun `explicit overrides win over the manifest`() {
        assertTrue(ComponentStateResolver.effective(false, PackageManager.COMPONENT_ENABLED_STATE_ENABLED))
        assertFalse(ComponentStateResolver.effective(true, PackageManager.COMPONENT_ENABLED_STATE_DISABLED))
    }

    @Test
    fun `domain states map to stable platform constants`() {
        assertEquals(
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            ComponentStateResolver.toPlatform(ComponentOverrideState.DEFAULT),
        )
        assertEquals(
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            ComponentStateResolver.toPlatform(ComponentOverrideState.DISABLED),
        )
    }
}
