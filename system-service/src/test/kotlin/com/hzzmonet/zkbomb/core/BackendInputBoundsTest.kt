package com.hzzmonet.zkbomb.core

import com.hzzmonet.zkbomb.api.BombResult
import com.hzzmonet.zkbomb.api.FirewallRuleParcel
import com.hzzmonet.zkbomb.api.SettingsOverrideParcel
import com.hzzmonet.zkbomb.api.SettingsAssignmentParcel
import org.junit.Assert.assertEquals
import org.junit.Test

class BackendInputBoundsTest {

    @Test
    fun `settings profile identifiers and names are bounded`() {
        val backend = SettingsVirtualizationBackend()

        assertEquals(
            BombResult.Status.INVALID_ARGUMENT,
            backend.createProfile("p".repeat(129), "Profile").status,
        )
        assertEquals(
            BombResult.Status.INVALID_ARGUMENT,
            backend.createProfile("profile", "n".repeat(257)).status,
        )
    }

    @Test
    fun `settings override values are bounded before parsing`() {
        val backend = SettingsVirtualizationBackend()
        val result = backend.addOverride(
            SettingsOverrideParcel(
                profileId = "profile",
                namespace = "SECURE",
                key = "example_key",
                valueType = "STRING",
                value = "v".repeat(4_097),
                enabled = true,
            ),
        )

        assertEquals(BombResult.Status.INVALID_ARGUMENT, result.status)
    }

    @Test
    fun `settings overrides require an existing profile and an allowlisted canonical value`() {
        val backend = SettingsVirtualizationBackend()
        val valid = SettingsOverrideParcel(
            profileId = "profile",
            namespace = "SYSTEM",
            key = "screen_brightness",
            valueType = "INTEGER",
            value = "42",
            enabled = true,
        )

        assertEquals(BombResult.Status.INVALID_ARGUMENT, backend.addOverride(valid).status)
        assertEquals(BombResult.Status.SUCCESS, backend.createProfile("profile", "Profile").status)
        assertEquals(BombResult.Status.SUCCESS, backend.addOverride(valid).status)
        assertEquals(
            BombResult.Status.INVALID_ARGUMENT,
            backend.addOverride(valid.copy(key = "font_scale", valueType = "FLOAT", value = "1.0")).status,
        )
        assertEquals(
            BombResult.Status.INVALID_ARGUMENT,
            backend.addOverride(valid.copy(value = "042")).status,
        )
    }

    @Test
    fun `settings assignment requires an existing profile`() {
        val backend = SettingsVirtualizationBackend()
        val assignment = SettingsAssignmentParcel(0, "com.example.app", "profile")

        assertEquals(BombResult.Status.INVALID_ARGUMENT, backend.assignProfile(assignment).status)
        assertEquals(BombResult.Status.SUCCESS, backend.createProfile("profile", "Profile").status)
        assertEquals(BombResult.Status.SUCCESS, backend.assignProfile(assignment).status)
    }

    @Test
    fun `firewall notes are bounded`() {
        val result = FirewallBackend().setRule(
            FirewallRuleParcel.allowAll(uid = 10_000, userId = 0).copy(
                note = "n".repeat(257),
            ),
        )

        assertEquals(BombResult.Status.INVALID_ARGUMENT, result.status)
    }
}
