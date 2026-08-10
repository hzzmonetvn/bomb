package com.hzzmonet.zkbomb.api

import android.os.Parcelable
import com.hzzmonet.zkbomb.domain.firewall.FirewallRule
import com.hzzmonet.zkbomb.domain.firewall.NetworkAccess
import com.hzzmonet.zkbomb.domain.validation.ApiV6InputValidator
import kotlinx.parcelize.Parcelize

/**
 * Wire representation of a [FirewallRule] crossing Binder.
 *
 * Enum values ([NetworkAccess]) cross as their **names**: ALLOW or DENY.
 *
 * @param uid              Application UID (appId >= 10000) for [userId].
 * @param userId           User context.
 * @param wifiAccess       ALLOW or DENY for Wi-Fi.
 * @param mobileAccess     ALLOW or DENY for mobile data.
 * @param backgroundAccess ALLOW or DENY for background data.
 * @param note             Optional human-readable note.
 */
@Parcelize
data class FirewallRuleParcel(
    val uid: Int,
    val userId: Int,
    val wifiAccess: String,
    val mobileAccess: String,
    val backgroundAccess: String,
    val note: String?,
) : Parcelable {

    /**
     * Convert to domain type.
     *
     * Returns null when any [NetworkAccess] name is unrecognized — signals
     * INVALID_ARGUMENT to the service.
     */
    fun toDomain(): FirewallRule? {
        if (ApiV6InputValidator.uidUserViolation(uid, userId) != null) return null
        if (ApiV6InputValidator.enumNameViolation("wifiAccess", wifiAccess) != null) return null
        if (ApiV6InputValidator.enumNameViolation("mobileAccess", mobileAccess) != null) return null
        if (ApiV6InputValidator.enumNameViolation("backgroundAccess", backgroundAccess) != null) return null
        if (ApiV6InputValidator.firewallNoteViolation(note) != null) return null
        val wifi = runCatching { NetworkAccess.valueOf(wifiAccess) }.getOrNull() ?: return null
        val mobile = runCatching { NetworkAccess.valueOf(mobileAccess) }.getOrNull() ?: return null
        val bg = runCatching { NetworkAccess.valueOf(backgroundAccess) }.getOrNull() ?: return null
        return runCatching {
            FirewallRule(
                uid = uid,
                userId = userId,
                wifiAccess = wifi,
                mobileAccess = mobile,
                backgroundAccess = bg,
                note = note,
            )
        }.getOrNull()
    }

    companion object {
        /** Create a parcel with all interfaces allowed (wire form of the default). */
        fun allowAll(uid: Int, userId: Int) = FirewallRuleParcel(
            uid = uid,
            userId = userId,
            wifiAccess = NetworkAccess.ALLOW.name,
            mobileAccess = NetworkAccess.ALLOW.name,
            backgroundAccess = NetworkAccess.ALLOW.name,
            note = null,
        )
    }
}
