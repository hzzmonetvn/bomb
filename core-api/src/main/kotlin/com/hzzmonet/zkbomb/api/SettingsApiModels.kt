package com.hzzmonet.zkbomb.api

import android.os.Parcelable
import com.hzzmonet.zkbomb.domain.settings.SettingsAssignment
import com.hzzmonet.zkbomb.domain.settings.SettingsNamespace
import com.hzzmonet.zkbomb.domain.settings.SettingsOverride
import com.hzzmonet.zkbomb.domain.settings.SettingsValueType
import com.hzzmonet.zkbomb.domain.validation.ApiV6InputValidator
import kotlinx.parcelize.Parcelize

/**
 * Wire representation of a [SettingsOverride] crossing Binder.
 *
 * All enum values cross as their **names** — consistent with the rest of the
 * IBombService contract. The service validates the names before using them.
 *
 * @param profileId   References the target [SettingsProfileParcel].
 * @param namespace   A [SettingsNamespace] name: SYSTEM, SECURE, or GLOBAL.
 * @param key         An allowlisted APP_READ settings key.
 * @param valueType   A [SettingsValueType] name: STRING, INTEGER, …, NULL.
 * @param value       Serialized value; null when [valueType] is NULL.
 * @param enabled     Whether this override is active.
 */
@Parcelize
data class SettingsOverrideParcel(
    val profileId: String,
    val namespace: String,
    val key: String,
    val valueType: String,
    val value: String?,
    val enabled: Boolean,
) : Parcelable {

    /**
     * Convert to domain type.
     *
     * Returns null when [namespace] or [valueType] is an unknown name so the
     * service can return INVALID_ARGUMENT cleanly.
     */
    fun toDomain(): SettingsOverride? {
        if (ApiV6InputValidator.profileIdViolation(profileId) != null) return null
        if (ApiV6InputValidator.enumNameViolation("namespace", namespace) != null) return null
        if (ApiV6InputValidator.settingsKeyViolation(key) != null) return null
        if (ApiV6InputValidator.enumNameViolation("valueType", valueType) != null) return null
        if (ApiV6InputValidator.settingsValueViolation(value) != null) return null
        val ns = runCatching { SettingsNamespace.valueOf(namespace) }.getOrNull() ?: return null
        val vt = runCatching { SettingsValueType.valueOf(valueType) }.getOrNull() ?: return null
        if (ApiV6InputValidator.virtualizableSettingsKeyViolation(ns, key) != null) return null
        if (ApiV6InputValidator.settingsTypedValueViolation(vt, value) != null) return null
        return runCatching {
            SettingsOverride(
                profileId = profileId,
                namespace = ns,
                key = key,
                valueType = vt,
                value = value,
                enabled = enabled,
            )
        }.getOrNull()
    }
}

/**
 * Wire representation of a settings profile assignment.
 *
 * @param userId        User context.
 * @param targetPackage Package whose settings reads are intercepted.
 * @param profileId     The profile to apply.
 */
@Parcelize
data class SettingsAssignmentParcel(
    val userId: Int,
    val targetPackage: String,
    val profileId: String,
) : Parcelable {

    fun toDomain(): SettingsAssignment? = runCatching {
        if (ApiV6InputValidator.userIdViolation(userId) != null) return null
        if (ApiV6InputValidator.visibilityPackagesViolation(listOf(targetPackage)) != null) return null
        if (ApiV6InputValidator.profileIdViolation(profileId) != null) return null
        SettingsAssignment(
            userId = userId,
            targetPackage = targetPackage,
            profileId = profileId,
        )
    }.getOrNull()
}
