package com.hzzmonet.zkbomb.api

import android.os.Parcelable
import com.hzzmonet.zkbomb.domain.visibility.CallerKey
import com.hzzmonet.zkbomb.domain.visibility.VisibilityCallerPolicy
import com.hzzmonet.zkbomb.domain.visibility.VisibilityMode
import com.hzzmonet.zkbomb.domain.validation.ApiV6InputValidator
import kotlinx.parcelize.Parcelize

/**
 * Wire representation of a single caller visibility policy — the data that
 * crosses Binder when the UI submits a new policy for a caller.
 *
 * Domain types cross as their **names** (strings), never as ordinals, matching
 * the same convention used for FreezeMode and LogLevel throughout IBombService.
 *
 * @param callingUid   UID of the calling app whose view is being controlled.
 * @param userId       User ID context.
 * @param mode         A [com.hzzmonet.zkbomb.domain.visibility.VisibilityMode] name —
 *                     BLACKLIST or WHITELIST.
 * @param packageNames Packages in the blacklist or whitelist, validated server-side.
 */
@Parcelize
data class VisibilityCallerPolicyParcel(
    val callingUid: Int,
    val userId: Int,
    val mode: String,
    val packageNames: List<String>,
) : Parcelable {

    /**
     * Convert to domain type for service-side consumption.
     *
     * Returns null if [mode] is not a valid [VisibilityMode] name — the service
     * must treat null as INVALID_ARGUMENT.
     */
    fun toDomain(): VisibilityCallerPolicy? {
        if (ApiV6InputValidator.uidUserViolation(callingUid, userId) != null) return null
        if (ApiV6InputValidator.enumNameViolation("mode", mode) != null) return null
        if (ApiV6InputValidator.visibilityPackagesViolation(packageNames) != null) return null
        val visibilityMode = runCatching { VisibilityMode.valueOf(mode) }.getOrNull()
            ?: return null
        return VisibilityCallerPolicy(
            caller = CallerKey(callingUid, userId),
            mode = visibilityMode,
            packageSet = packageNames.toSet(),
        )
    }
}
