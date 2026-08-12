package com.hzzmonet.zkbomb.core

import android.content.pm.ApplicationInfo
import com.hzzmonet.zkbomb.api.BombRuntimeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeModeDetectorTest {
    @Test
    fun `clean priv-app placement is privileged`() {
        assertTrue(
            isPrivilegedInstall(
                sourceDir = "/system_ext/priv-app/Bomb/Bomb.apk",
                publicSourceDir = null,
                applicationFlags = 0,
                privateFlags = null,
            ),
        )
    }

    @Test
    fun `data update preserves privileged system identity`() {
        val privileged = isPrivilegedInstall(
            sourceDir = "/data/app/com.hzzmonet.zkbomb/base.apk",
            publicSourceDir = "/data/app/com.hzzmonet.zkbomb/base.apk",
            applicationFlags = ApplicationInfo.FLAG_UPDATED_SYSTEM_APP,
            privateFlags = PRIVATE_FLAG_PRIVILEGED,
        )

        assertTrue(privileged)
        assertEquals(
            BombRuntimeMode.ROM,
            classifyRuntimeMode(
                romDeclared = false,
                privilegedInstall = privileged,
                rootBackendPresent = false,
            ),
        )
    }

    @Test
    fun `ordinary sideload cannot claim privileged mode from one flag alone`() {
        assertFalse(
            isPrivilegedInstall(
                sourceDir = "/data/app/com.hzzmonet.zkbomb/base.apk",
                publicSourceDir = null,
                applicationFlags = ApplicationInfo.FLAG_UPDATED_SYSTEM_APP,
                privateFlags = 0,
            ),
        )
        assertFalse(
            isPrivilegedInstall(
                sourceDir = "/data/app/com.hzzmonet.zkbomb/base.apk",
                publicSourceDir = null,
                applicationFlags = 0,
                privateFlags = PRIVATE_FLAG_PRIVILEGED,
            ),
        )
    }

    @Test
    fun `preserved privapp XML grant survives when hidden private flags are unreadable`() {
        assertTrue(
            isPrivilegedInstall(
                sourceDir = "/data/app/com.hzzmonet.zkbomb/base.apk",
                publicSourceDir = null,
                applicationFlags = ApplicationInfo.FLAG_UPDATED_SYSTEM_APP,
                privateFlags = null,
                allowlistedPermissionGranted = true,
            ),
        )
    }
}
