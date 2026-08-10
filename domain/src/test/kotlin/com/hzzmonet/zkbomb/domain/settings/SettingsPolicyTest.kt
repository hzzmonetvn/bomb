package com.hzzmonet.zkbomb.domain.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for §13 Per-App Settings Virtualization domain models.
 *
 * Tests cover:
 * - SettingsOverride typed resolution (STRING, INTEGER, LONG, FLOAT, BOOLEAN, NULL)
 * - SettingsOverride disabled flag suppresses override
 * - SettingsProfile.overrideFor lookup and enabled guard
 * - SettingsPolicySnapshot.resolveOverride by callerPackages
 * - Multi-user isolation (userId in AssignmentKey)
 * - PassThrough when no assignment/profile/override is configured
 * - builder round-trip: addProfile + addAssignment -> resolveOverride works
 */
class SettingsPolicyTest {

    // ---- SettingsOverride typed resolution ----------------------------------

    @Test
    fun `override resolves STRING type`() {
        val override = override(SettingsValueType.STRING, "hello")
        assertEquals("hello", override.resolveTyped())
    }

    @Test
    fun `override resolves INTEGER type`() {
        val override = override(SettingsValueType.INTEGER, "42")
        assertEquals(42, override.resolveTyped())
    }

    @Test
    fun `override resolves LONG type`() {
        val override = override(SettingsValueType.LONG, "1234567890123")
        assertEquals(1234567890123L, override.resolveTyped())
    }

    @Test
    fun `override resolves FLOAT type`() {
        val override = override(SettingsValueType.FLOAT, "1.25")
        assertEquals(1.25f, override.resolveTyped())
    }

    @Test
    fun `override resolves BOOLEAN true via 1`() {
        assertEquals(true, override(SettingsValueType.BOOLEAN, "1").resolveTyped())
    }

    @Test
    fun `override resolves BOOLEAN false via 0`() {
        assertEquals(false, override(SettingsValueType.BOOLEAN, "0").resolveTyped())
    }

    @Test
    fun `override rejects non-canonical BOOLEAN true`() {
        assertTrue(runCatching { override(SettingsValueType.BOOLEAN, "true") }.isFailure)
    }

    @Test
    fun `override rejects non-canonical BOOLEAN false`() {
        assertTrue(runCatching { override(SettingsValueType.BOOLEAN, "false") }.isFailure)
    }

    @Test
    fun `override resolves NULL type to null`() {
        val override = SettingsOverride(
            profileId = "p1",
            namespace = SettingsNamespace.SYSTEM,
            key = "screen_brightness",
            valueType = SettingsValueType.NULL,
            value = null,
            enabled = true,
        )
        assertNull(override.resolveTyped())
    }

    @Test
    fun `disabled override resolveTyped returns null`() {
        val override = override(SettingsValueType.STRING, "hidden_value", enabled = false)
        assertNull(override.resolveTyped())
    }

    // ---- SettingsProfile.overrideFor ---------------------------------------

    @Test
    fun `profile returns override for matching namespace and key`() {
        val profile = profile(
            override(SettingsValueType.STRING, "1.0", key = "screen_brightness"),
        )
        val result = profile.overrideFor(SettingsNamespace.SYSTEM, "screen_brightness")
        assertEquals("1.0", result?.value)
    }

    @Test
    fun `profile returns null for missing key`() {
        val profile = profile(override(SettingsValueType.STRING, "1.0", key = "screen_brightness"))
        assertNull(profile.overrideFor(SettingsNamespace.SYSTEM, "unknown_key"))
    }

    @Test
    fun `profile returns null for disabled override`() {
        val profile = profile(
            override(SettingsValueType.STRING, "1.0", key = "screen_brightness", enabled = false)
        )
        assertNull(profile.overrideFor(SettingsNamespace.SYSTEM, "screen_brightness"))
    }

    @Test
    fun `profile namespace is part of the key - SECURE does not match SYSTEM`() {
        val profile = profile(
            SettingsOverride(
                profileId = "p1",
                namespace = SettingsNamespace.SECURE,
                key = "accessibility_display_inversion_enabled",
                valueType = SettingsValueType.STRING,
                value = "value",
                enabled = true,
            )
        )
        assertNull(profile.overrideFor(SettingsNamespace.SYSTEM, "accessibility_display_inversion_enabled"))
        assertEquals(
            "value",
            profile.overrideFor(SettingsNamespace.SECURE, "accessibility_display_inversion_enabled")?.value,
        )
    }

    @Test
    fun `SYSTEM_READ and forbidden keys cannot become overrides`() {
        assertTrue(
            runCatching {
                override(SettingsValueType.FLOAT, "1.0", key = "font_scale")
            }.isFailure,
        )
        assertTrue(
            runCatching {
                override(
                    SettingsValueType.STRING,
                    "fake-id",
                    key = "android_id",
                    namespace = SettingsNamespace.SECURE,
                )
            }.isFailure,
        )
    }

    // ---- SettingsPolicySnapshot resolveOverride ----------------------------

    @Test
    fun `resolveOverride returns Overridden when caller has assignment with override`() {
        val snap = buildSnapshot { builder ->
            builder
                .addProfile(
                    profile(override(SettingsValueType.STRING, "1.0", key = "screen_brightness"))
                )
                .addAssignment(SettingsAssignment(userId = 0, targetPackage = "com.example.app", profileId = "p1"))
        }
        val result = snap.resolveOverride(
            namespace = SettingsNamespace.SYSTEM,
            key = "screen_brightness",
            callingUid = 10100,
            userId = 0,
            callerPackages = listOf("com.example.app"),
        )
        assertTrue(result is OverrideResult.Overridden)
        assertEquals("1.0", (result as OverrideResult.Overridden).override.value)
    }

    @Test
    fun `resolveOverride returns PassThrough when caller has no assignment`() {
        val snap = buildSnapshot { builder ->
            builder.addProfile(
                profile(override(SettingsValueType.STRING, "1.0", key = "screen_brightness"))
            )
            // No assignment added
        }
        val result = snap.resolveOverride(
            namespace = SettingsNamespace.SYSTEM,
            key = "screen_brightness",
            callingUid = 10100,
            userId = 0,
            callerPackages = listOf("com.example.app"),
        )
        assertTrue(result is OverrideResult.PassThrough)
    }

    @Test
    fun `resolveOverride returns PassThrough when key not in profile`() {
        val snap = buildSnapshot { builder ->
            builder
                .addProfile(
                    profile(override(SettingsValueType.STRING, "1.0", key = "screen_brightness"))
                )
                .addAssignment(
                    SettingsAssignment(userId = 0, targetPackage = "com.example.app", profileId = "p1")
                )
        }
        val result = snap.resolveOverride(
            namespace = SettingsNamespace.SYSTEM,
            key = "other_key",
            callingUid = 10100,
            userId = 0,
            callerPackages = listOf("com.example.app"),
        )
        assertTrue(result is OverrideResult.PassThrough)
    }

    @Test
    fun `resolveOverride multi-user - userId 0 assignment does not affect userId 10`() {
        val snap = buildSnapshot { builder ->
            builder
                .addProfile(
                    profile(override(SettingsValueType.STRING, "custom", key = "ringtone"))
                )
                .addAssignment(
                    SettingsAssignment(userId = 0, targetPackage = "com.example.app", profileId = "p1")
                )
        }
        // Query with userId=10 should not find the userId=0 assignment.
        val result = snap.resolveOverride(
            namespace = SettingsNamespace.SYSTEM,
            key = "ringtone",
            callingUid = 1_010_100,
            userId = 10,
            callerPackages = listOf("com.example.app"),
        )
        assertTrue(result is OverrideResult.PassThrough)
    }

    @Test
    fun `shared UID requires every package to have the same assignment`() {
        val snap = buildSnapshot { builder ->
            builder
                .addProfile(
                    profile(override(SettingsValueType.INTEGER, "72", key = "screen_brightness"))
                )
                .addAssignment(
                    SettingsAssignment(userId = 0, targetPackage = "com.foo.companion", profileId = "p1")
                )
        }
        val result = snap.resolveOverride(
            namespace = SettingsNamespace.SYSTEM,
            key = "screen_brightness",
            callingUid = 10100,
            userId = 0,
            callerPackages = listOf("com.foo.app", "com.foo.companion"), // companion has assignment
        )
        assertTrue(result is OverrideResult.PassThrough)
    }

    @Test
    fun `shared UID resolves when every package has the same assignment`() {
        val snap = buildSnapshot { builder ->
            builder
                .addProfile(
                    profile(override(SettingsValueType.INTEGER, "72", key = "screen_brightness"))
                )
                .addAssignment(SettingsAssignment(0, "com.foo.app", "p1"))
                .addAssignment(SettingsAssignment(0, "com.foo.companion", "p1"))
        }
        val result = snap.resolveOverride(
            SettingsNamespace.SYSTEM,
            "screen_brightness",
            10100,
            0,
            listOf("com.foo.app", "com.foo.companion"),
        )
        assertTrue(result is OverrideResult.Overridden)
    }

    @Test
    fun `system and mismatched-user callers always pass through`() {
        val snap = buildSnapshot { builder ->
            builder
                .addProfile(profile(override(SettingsValueType.INTEGER, "72")))
                .addAssignment(SettingsAssignment(0, "com.example.app", "p1"))
        }

        assertTrue(
            snap.resolveOverride(
                SettingsNamespace.SYSTEM,
                "screen_brightness",
                1000,
                0,
                listOf("com.example.app"),
            ) is OverrideResult.PassThrough,
        )
        assertTrue(
            snap.resolveOverride(
                SettingsNamespace.SYSTEM,
                "screen_brightness",
                1_010_100,
                0,
                listOf("com.example.app"),
            ) is OverrideResult.PassThrough,
        )
    }

    @Test
    fun `empty snapshot returns PassThrough for any query`() {
        val snap = SettingsPolicySnapshot.EMPTY
        assertTrue(snap.isEmpty)
        val result = snap.resolveOverride(
            SettingsNamespace.GLOBAL, "key", 10100, 0, listOf("com.any.app")
        )
        assertTrue(result is OverrideResult.PassThrough)
    }

    // ---- builder round-trip ------------------------------------------------

    @Test
    fun `builder - duplicate assignment for same package last write wins`() {
        val snap = buildSnapshot { builder ->
            val profile1 = SettingsProfile(
                id = "p1", name = "Profile 1",
                overrides = SettingsProfile.buildOverrideMap(
                    listOf(override(SettingsValueType.STRING, "value1", key = "screen_brightness"))
                )
            )
            val profile2 = SettingsProfile(
                id = "p2", name = "Profile 2",
                overrides = SettingsProfile.buildOverrideMap(
                    listOf(override(SettingsValueType.STRING, "value2", key = "screen_brightness", profileId = "p2"))
                )
            )
            builder
                .addProfile(profile1)
                .addProfile(profile2)
                .addAssignment(SettingsAssignment(0, "com.example.app", "p1"))
                .addAssignment(SettingsAssignment(0, "com.example.app", "p2")) // wins
        }
        val result = snap.resolveOverride(
            SettingsNamespace.SYSTEM, "screen_brightness", 10100, 0, listOf("com.example.app")
        )
        assertTrue(result is OverrideResult.Overridden)
        assertEquals("value2", (result as OverrideResult.Overridden).override.value)
    }

    // ---- helpers -----------------------------------------------------------

    private fun override(
        type: SettingsValueType,
        value: String?,
        key: String = "screen_brightness",
        namespace: SettingsNamespace = SettingsNamespace.SYSTEM,
        profileId: String = "p1",
        enabled: Boolean = true,
    ) = SettingsOverride(profileId, namespace, key, type, value, enabled)

    private fun profile(vararg overrides: SettingsOverride): SettingsProfile {
        val profileId = overrides.firstOrNull()?.profileId ?: "p1"
        return SettingsProfile(
            id = profileId,
            name = "Test Profile",
            overrides = SettingsProfile.buildOverrideMap(overrides.toList()),
        )
    }

    private fun buildSnapshot(
        revision: Long = 1L,
        block: (SettingsPolicyBuilder) -> Unit,
    ): SettingsPolicySnapshot {
        val builder = SettingsPolicyBuilder(revision)
        block(builder)
        return builder.build()
    }
}
