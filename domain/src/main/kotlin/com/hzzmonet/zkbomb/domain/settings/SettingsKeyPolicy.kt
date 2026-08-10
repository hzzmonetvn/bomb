package com.hzzmonet.zkbomb.domain.settings

/** Capability classification required by BOMB_PLAN.md §4.6. */
enum class SettingsKeyClass {
    APP_READ,
    SYSTEM_READ,
    PER_APP_CONFIG,
    FORBIDDEN,
    UNKNOWN,
}

/**
 * Fail-closed allowlist for read-side settings virtualization.
 *
 * Only keys whose value is read through SettingsProvider in an application
 * process belong in [SettingsKeyClass.APP_READ]. Unknown keys are deliberately
 * rejected until their AOSP call path has been reviewed.
 */
object SettingsKeyPolicy {
    private val appReadKeys = mapOf(
        SettingsNamespace.SYSTEM to setOf(
            "alarm_alert",
            "notification_sound",
            "ringtone",
            "screen_brightness",
            "screen_brightness_mode",
            "time_12_24",
        ),
        SettingsNamespace.SECURE to setOf(
            "accessibility_display_daltonizer",
            "accessibility_display_daltonizer_enabled",
            "accessibility_display_inversion_enabled",
        ),
        SettingsNamespace.GLOBAL to emptySet(),
    )

    private val systemReadKeys = mapOf(
        SettingsNamespace.SYSTEM to setOf("font_scale"),
    )

    private val perAppConfigKeys = emptyMap<SettingsNamespace, Set<String>>()

    private val forbiddenKeys = mapOf(
        SettingsNamespace.SECURE to setOf("android_id"),
    )

    fun classify(namespace: SettingsNamespace, key: String): SettingsKeyClass = when (key) {
        in appReadKeys[namespace].orEmpty() -> SettingsKeyClass.APP_READ
        in systemReadKeys[namespace].orEmpty() -> SettingsKeyClass.SYSTEM_READ
        in perAppConfigKeys[namespace].orEmpty() -> SettingsKeyClass.PER_APP_CONFIG
        in forbiddenKeys[namespace].orEmpty() -> SettingsKeyClass.FORBIDDEN
        else -> SettingsKeyClass.UNKNOWN
    }

    fun violation(namespace: SettingsNamespace, key: String): String? {
        val keyClass = classify(namespace, key)
        return if (keyClass == SettingsKeyClass.APP_READ) {
            null
        } else {
            "settings key is not virtualizable: $namespace/$key ($keyClass)"
        }
    }
}

/** Canonical wire representation for the typed settings API. */
object SettingsValuePolicy {
    fun violation(type: SettingsValueType, value: String?): String? = when (type) {
        SettingsValueType.STRING -> if (value == null) "STRING value must not be null" else null
        SettingsValueType.INTEGER -> canonicalNumberViolation("INTEGER", value) { it.toIntOrNull()?.toString() }
        SettingsValueType.LONG -> canonicalNumberViolation("LONG", value) { it.toLongOrNull()?.toString() }
        SettingsValueType.FLOAT -> canonicalNumberViolation("FLOAT", value) {
            it.toFloatOrNull()?.takeIf(Float::isFinite)?.toString()
        }
        SettingsValueType.BOOLEAN -> if (value == "0" || value == "1") {
            null
        } else {
            "BOOLEAN value must be encoded as 0 or 1"
        }
        SettingsValueType.NULL -> if (value == null) null else "NULL value must be null"
    }

    private inline fun canonicalNumberViolation(
        type: String,
        value: String?,
        canonicalize: (String) -> String?,
    ): String? {
        if (value == null) return "$type value must not be null"
        val canonical = canonicalize(value) ?: return "$type value is invalid"
        return if (value == canonical) null else "$type value is not canonical"
    }
}
