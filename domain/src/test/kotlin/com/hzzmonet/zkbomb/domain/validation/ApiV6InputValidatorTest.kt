package com.hzzmonet.zkbomb.domain.validation

import com.hzzmonet.zkbomb.domain.settings.SettingsNamespace
import com.hzzmonet.zkbomb.domain.settings.SettingsValueType
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ApiV6InputValidatorTest {

    @Test
    fun `current-user validation rejects invalid and cross-user ids`() {
        assertNotNull(ApiV6InputValidator.currentUserViolation(-1, 0))
        assertNotNull(ApiV6InputValidator.currentUserViolation(10_000, 0))
        assertNotNull(ApiV6InputValidator.currentUserViolation(10, 0))
        assertNull(ApiV6InputValidator.currentUserViolation(0, 0))
        assertNull(ApiV6InputValidator.currentUserViolation(10, 10))
    }

    @Test
    fun `settings strings have explicit wire bounds`() {
        assertNull(ApiV6InputValidator.profileIdViolation("profile"))
        assertNotNull(ApiV6InputValidator.profileIdViolation("p".repeat(129)))
        assertNotNull(ApiV6InputValidator.profileIdViolation("../profile"))
        assertNotNull(ApiV6InputValidator.profileNameViolation("n".repeat(257)))
        assertNotNull(ApiV6InputValidator.profileNameViolation("bad\u0000name"))
        assertNotNull(ApiV6InputValidator.settingsKeyViolation("k".repeat(257)))
        assertNotNull(ApiV6InputValidator.settingsKeyViolation("bad/key"))
        assertNotNull(ApiV6InputValidator.settingsValueViolation("v".repeat(4_097)))
        assertNotNull(ApiV6InputValidator.settingsValueViolation("bad\u0000value"))
    }

    @Test
    fun `visibility lists are bounded and package-validated`() {
        assertNull(
            ApiV6InputValidator.visibilityPackagesViolation(
                listOf("com.example.one", "com.example.two"),
            ),
        )
        assertNotNull(
            ApiV6InputValidator.visibilityPackagesViolation(List(513) { "com.example.app$it" }),
        )
        assertNotNull(
            ApiV6InputValidator.visibilityPackagesViolation(listOf("../not-a-package")),
        )
    }

    @Test
    fun `enum names and firewall notes are bounded`() {
        assertNull(ApiV6InputValidator.enumNameViolation("mode", "BLACKLIST"))
        assertNotNull(ApiV6InputValidator.enumNameViolation("mode", "blacklist"))
        assertNotNull(ApiV6InputValidator.enumNameViolation("mode", "x".repeat(33)))
        assertNotNull(ApiV6InputValidator.firewallNoteViolation("n".repeat(257)))
        assertNotNull(ApiV6InputValidator.firewallNoteViolation("bad\nnote"))
    }

    @Test
    fun `settings keys and typed values fail closed`() {
        assertNull(
            ApiV6InputValidator.virtualizableSettingsKeyViolation(
                SettingsNamespace.SYSTEM,
                "screen_brightness",
            ),
        )
        assertNotNull(
            ApiV6InputValidator.virtualizableSettingsKeyViolation(
                SettingsNamespace.SYSTEM,
                "font_scale",
            ),
        )
        assertNotNull(
            ApiV6InputValidator.virtualizableSettingsKeyViolation(
                SettingsNamespace.SECURE,
                "android_id",
            ),
        )
        assertNotNull(
            ApiV6InputValidator.virtualizableSettingsKeyViolation(
                SettingsNamespace.SYSTEM,
                "unreviewed_key",
            ),
        )
        assertNull(ApiV6InputValidator.settingsTypedValueViolation(SettingsValueType.BOOLEAN, "1"))
        assertNotNull(ApiV6InputValidator.settingsTypedValueViolation(SettingsValueType.BOOLEAN, "true"))
        assertNull(ApiV6InputValidator.settingsTypedValueViolation(SettingsValueType.INTEGER, "42"))
        assertNotNull(ApiV6InputValidator.settingsTypedValueViolation(SettingsValueType.INTEGER, "042"))
        assertNull(ApiV6InputValidator.settingsTypedValueViolation(SettingsValueType.NULL, null))
        assertNotNull(ApiV6InputValidator.settingsTypedValueViolation(SettingsValueType.NULL, ""))
    }

    @Test
    fun `application uid validation uses the app id portion`() {
        assertNotNull(ApiV6InputValidator.appUidViolation(999))
        assertNotNull(ApiV6InputValidator.appUidViolation(1_001))
        assertNull(ApiV6InputValidator.appUidViolation(10_001))
        assertNull(ApiV6InputValidator.appUidViolation(1_010_001))
        assertNull(ApiV6InputValidator.uidUserViolation(1_010_001, 10))
        assertNotNull(ApiV6InputValidator.uidUserViolation(1_010_001, 0))
    }
}
