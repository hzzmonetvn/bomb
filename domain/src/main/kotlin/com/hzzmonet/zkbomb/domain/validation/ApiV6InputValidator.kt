package com.hzzmonet.zkbomb.domain.validation

import com.hzzmonet.zkbomb.domain.freeze.PackageNameValidator
import com.hzzmonet.zkbomb.domain.settings.SettingsKeyPolicy
import com.hzzmonet.zkbomb.domain.settings.SettingsNamespace
import com.hzzmonet.zkbomb.domain.settings.SettingsValuePolicy
import com.hzzmonet.zkbomb.domain.settings.SettingsValueType

/** Shared, Android-free bounds for the append-only API v6 write contract. */
object ApiV6InputValidator {
    const val MAX_USER_ID = 9_999
    const val MAX_ENUM_NAME_LENGTH = 32
    const val MAX_PROFILE_ID_LENGTH = 128
    const val MAX_PROFILE_NAME_LENGTH = 256
    const val MAX_SETTINGS_KEY_LENGTH = 256
    const val MAX_SETTINGS_VALUE_LENGTH = 4_096
    const val MAX_FIREWALL_NOTE_LENGTH = 256
    const val MAX_PACKAGES_PER_VISIBILITY_POLICY = 512
    const val MIN_APP_ID = 10_000
    const val PER_USER_RANGE = 100_000

    fun userIdViolation(userId: Int): String? = when {
        userId < 0 -> "userId must be non-negative"
        userId > MAX_USER_ID -> "userId exceeds $MAX_USER_ID"
        else -> null
    }

    fun currentUserViolation(userId: Int, currentUserId: Int): String? =
        userIdViolation(userId) ?: if (userId != currentUserId) {
            "Cross-user operations are not integrated"
        } else {
            null
        }

    fun uidViolation(uid: Int, minimum: Int = 0): String? =
        if (uid < minimum) "uid must be at least $minimum" else null

    fun appUidViolation(uid: Int): String? = when {
        uid < 0 -> "uid must be non-negative"
        uid % PER_USER_RANGE < MIN_APP_ID -> "uid does not identify an application"
        else -> null
    }

    fun uidUserViolation(uid: Int, userId: Int): String? =
        appUidViolation(uid) ?: userIdViolation(userId) ?: if (uid / PER_USER_RANGE != userId) {
            "uid does not belong to userId"
        } else {
            null
        }

    fun enumNameViolation(field: String, value: String): String? = when {
        value.isBlank() || value.length > MAX_ENUM_NAME_LENGTH ->
            "$field must contain 1..$MAX_ENUM_NAME_LENGTH characters"
        !ENUM_NAME.matches(value) -> "$field has an invalid enum name"
        else -> null
    }

    fun profileIdViolation(value: String): String? {
        boundedTextViolation("profileId", value, MAX_PROFILE_ID_LENGTH)?.let { return it }
        return if (PROFILE_ID.matches(value)) null else "profileId contains unsupported characters"
    }

    fun profileNameViolation(value: String): String? =
        boundedTextViolation("profileName", value, MAX_PROFILE_NAME_LENGTH)
            ?: if (value.any(Char::isISOControl)) "profileName contains control characters" else null

    fun settingsKeyViolation(value: String): String? {
        boundedTextViolation("key", value, MAX_SETTINGS_KEY_LENGTH)?.let { return it }
        return if (SETTINGS_KEY.matches(value)) null else "key contains unsupported characters"
    }

    fun virtualizableSettingsKeyViolation(namespace: SettingsNamespace, value: String): String? =
        settingsKeyViolation(value) ?: SettingsKeyPolicy.violation(namespace, value)

    fun settingsTypedValueViolation(type: SettingsValueType, value: String?): String? =
        settingsValueViolation(value) ?: SettingsValuePolicy.violation(type, value)

    fun settingsValueViolation(value: String?): String? = when {
        (value?.length ?: 0) > MAX_SETTINGS_VALUE_LENGTH ->
            "value exceeds $MAX_SETTINGS_VALUE_LENGTH characters"
        value?.contains('\u0000') == true -> "value contains a NUL character"
        else -> null
    }

    fun firewallNoteViolation(value: String?): String? = when {
        (value?.length ?: 0) > MAX_FIREWALL_NOTE_LENGTH ->
            "note exceeds $MAX_FIREWALL_NOTE_LENGTH characters"
        value?.any(Char::isISOControl) == true -> "note contains control characters"
        else -> null
    }

    fun visibilityPackagesViolation(packageNames: List<String>): String? {
        if (packageNames.size > MAX_PACKAGES_PER_VISIBILITY_POLICY) {
            return "packageNames exceeds $MAX_PACKAGES_PER_VISIBILITY_POLICY entries"
        }
        if (packageNames.any { !PackageNameValidator.isValid(it) }) {
            return "packageNames contains an invalid package name"
        }
        return null
    }

    private fun boundedTextViolation(field: String, value: String, maxLength: Int): String? =
        if (value.isBlank() || value.length > maxLength) {
            "$field must contain 1..$maxLength characters"
        } else {
            null
        }

    private val ENUM_NAME = Regex("[A-Z][A-Z0-9_]*")
    private val PROFILE_ID = Regex("[A-Za-z0-9._-]+")
    private val SETTINGS_KEY = Regex("[A-Za-z0-9._:-]+")
}
