package com.hzzmonet.zkbomb.api

import android.os.Parcelable
import com.hzzmonet.zkbomb.domain.log.LogLevel
import kotlinx.parcelize.Parcelize

/**
 * The log subsystem's current state.
 *
 * Carries both the tier Bomb *declared* and the tier the device is *in*, because
 * a crash midway through a transition can leave them disagreeing and the UI has
 * to be able to say so rather than show a confident wrong answer.
 */
@Parcelize
data class LogStatus(
    /** The [LogLevel] name recorded in Bomb's own property. */
    val declaredLevel: String,
    /** The [LogLevel] name the observed device state actually resolves to. */
    val effectiveLevel: String,
    /**
     * Leaving the current tier needs a reboot.
     *
     * True at `OFF`: restarting a killed `logd` cleanly at runtime is
     * unreliable, so Bomb states the cost instead of attempting it and reporting
     * a success it cannot stand behind.
     */
    val rebootRequiredToChange: Boolean,
    /** SELinux denial rate cap in messages/second, or null for the platform default. */
    val auditRatePerSecond: Int?,
    /**
     * A vendor log sink is still writing regardless of tier.
     *
     * MIUI's `/dev/ylog_buffer` is not touched by any tier. When this is true the
     * UI must not claim logging is off — that claim would be false, and it is the
     * kind of false claim a user would only discover by finding their logs.
     */
    val vendorLogSinkActive: Boolean,
) : Parcelable {

    val isConsistent: Boolean get() = declaredLevel == effectiveLevel

    fun resolvedEffectiveLevel(): LogLevel =
        runCatching { LogLevel.valueOf(effectiveLevel) }.getOrDefault(LogLevel.DEFAULT)
}
