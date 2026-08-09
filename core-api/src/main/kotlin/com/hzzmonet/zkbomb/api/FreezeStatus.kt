package com.hzzmonet.zkbomb.api

import android.os.Parcelable
import com.hzzmonet.zkbomb.domain.freeze.FreezeMode
import kotlinx.parcelize.Parcelize

/**
 * One package's freeze state, as read back from the platform.
 *
 * Every field here is *observed*, never inferred from a privileged call having
 * returned — several of the relevant setters return `void` or a failure list, so
 * "the call completed" carries almost no information (`docs/BOMB_PLAN.md` §4.3).
 *
 * Enum values cross as their names for the same reason as in [BombCapabilities]:
 * an ordinal is a number whose meaning changes when someone inserts a constant,
 * and this contract has to survive that.
 */
@Parcelize
data class FreezeStatus(
    val packageName: String,
    val userId: Int,
    /** The [FreezeMode] name the observed state resolves to. */
    val mode: String,
    /**
     * The package still has running processes that are not frozen.
     *
     * The `:push still runs while the app reads as frozen` case. Surfaced rather
     * than hidden, because a UI that shows "frozen" while a process runs is
     * lying to the user about the thing they asked for.
     */
    val hasUnfrozenProcesses: Boolean,
    /** [FreezeMode] names this device can actually express for this package. */
    val availableModes: List<String>,
    /**
     * The [com.hzzmonet.zkbomb.domain.freeze.ExclusionReason] name when the
     * package is protected, or null when it may be frozen.
     */
    val exclusionReason: String?,
) : Parcelable {

    /** The parsed mode, or [FreezeMode.NORMAL] if the service sent something unknown. */
    fun resolvedMode(): FreezeMode =
        runCatching { FreezeMode.valueOf(mode) }.getOrDefault(FreezeMode.NORMAL)

    val isProtected: Boolean get() = exclusionReason != null
}
