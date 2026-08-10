package com.hzzmonet.zkbomb.api

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ApiV6WireValidationTest {

    @Test
    fun `visibility parcel validates bounds before domain conversion`() {
        assertNotNull(
            VisibilityCallerPolicyParcel(
                callingUid = 10_001,
                userId = 0,
                mode = "BLACKLIST",
                packageNames = listOf("com.example.hidden"),
            ).toDomain(),
        )
        assertNull(
            VisibilityCallerPolicyParcel(
                callingUid = 10_001,
                userId = 0,
                mode = "BLACKLIST",
                packageNames = List(513) { "com.example.app$it" },
            ).toDomain(),
        )
        assertNull(
            VisibilityCallerPolicyParcel(
                callingUid = 1_010_001,
                userId = 0,
                mode = "BLACKLIST",
                packageNames = emptyList(),
            ).toDomain(),
        )
        assertNull(
            VisibilityCallerPolicyParcel(
                callingUid = 10_001,
                userId = 0,
                mode = "BLACKLIST",
                packageNames = listOf("not/a/package"),
            ).toDomain(),
        )
    }

    @Test
    fun `settings parcels reject oversized and invalid fields`() {
        assertNotNull(
            SettingsOverrideParcel(
                profileId = "profile",
                namespace = "SYSTEM",
                key = "screen_brightness",
                valueType = "INTEGER",
                value = "42",
                enabled = true,
            ).toDomain(),
        )
        assertNull(
            SettingsOverrideParcel(
                profileId = "p".repeat(129),
                namespace = "SECURE",
                key = "example_key",
                valueType = "STRING",
                value = "value",
                enabled = true,
            ).toDomain(),
        )
        assertNull(
            SettingsOverrideParcel(
                profileId = "profile",
                namespace = "SYSTEM",
                key = "font_scale",
                valueType = "FLOAT",
                value = "1.0",
                enabled = true,
            ).toDomain(),
        )
        assertNull(
            SettingsOverrideParcel(
                profileId = "profile",
                namespace = "SYSTEM",
                key = "screen_brightness",
                valueType = "INTEGER",
                value = "042",
                enabled = true,
            ).toDomain(),
        )
        assertNull(
            SettingsAssignmentParcel(
                userId = 10_000,
                targetPackage = "com.example.app",
                profileId = "profile",
            ).toDomain(),
        )
        assertNull(
            SettingsAssignmentParcel(
                userId = 0,
                targetPackage = "bad/package",
                profileId = "profile",
            ).toDomain(),
        )
    }

    @Test
    fun `firewall parcel rejects oversized note and unbounded user`() {
        assertNull(
            FirewallRuleParcel.allowAll(uid = 10_001, userId = 0)
                .copy(note = "n".repeat(257))
                .toDomain(),
        )
        assertNull(FirewallRuleParcel.allowAll(uid = 10_001, userId = 10_000).toDomain())
        assertNull(FirewallRuleParcel.allowAll(uid = 1_010_001, userId = 0).toDomain())
        assertNotNull(FirewallRuleParcel.allowAll(uid = 10_001, userId = 0).toDomain())
    }
}
