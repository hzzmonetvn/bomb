package com.hzzmonet.zkbomb.domain.log

/**
 * The tier the user asked for, plus the one tunable [LogLevel.REDUCED] exposes.
 *
 * @param auditRatePerSecond SELinux denial rate cap, or null to leave the
 *   platform default (5/sec) in place. Exposing this is what turns `REDUCED`
 *   from an all-or-nothing choice into a spectrum — the user can keep denials
 *   visible while capping their volume, instead of choosing between every denial
 *   and none.
 */
data class LogProfile(
    val level: LogLevel,
    val auditRatePerSecond: Int? = null,
)

/** Why a tier change was refused despite being supported. */
enum class RefusalReason {
    /**
     * A Bomb SELinux bring-up is in progress.
     *
     * Turning off the denial channel during the work that depends on reading
     * denials is a foot-gun worth blocking outright.
     */
    SELINUX_BRINGUP_IN_PROGRESS,
}

/** Why a tier is not available on this device. */
enum class UnsupportedReason {
    /** Neither the integrated-ROM init trigger nor a legacy root backend exists. */
    NO_DISABLE_BACKEND,

    /** No ROM-side init trigger for [LogLevel.REDUCED]. */
    NO_ROM_BACKEND,
}

/** Why a profile was rejected before anything was planned. */
enum class ProfileViolation {
    /** [LogProfile.auditRatePerSecond] is outside [LogProperty.AUDIT_RATE_RANGE]. */
    AUDIT_RATE_OUT_OF_RANGE,

    /** An audit rate was supplied for a tier that does not use one. */
    AUDIT_RATE_NOT_APPLICABLE,
}

/** The outcome of planning a tier change. */
sealed interface LogTransition {

    /** Perform [actions] in order. */
    data class Apply(val actions: List<LogAction>) : LogTransition

    /**
     * Leaving [LogLevel.OFF] needs a reboot.
     *
     * Restarting a killed `logd` cleanly at runtime is unreliable — the upstream
     * disabler module's own uninstall path says a reboot is still recommended.
     * Bomb states this rather than attempting it and reporting a success it
     * cannot stand behind.
     */
    data class RebootRequired(val from: LogLevel, val to: LogLevel) : LogTransition

    /** Already at the requested tier, and the device agrees. */
    data class AlreadyAtLevel(val level: LogLevel) : LogTransition

    /** Not available on this device. */
    data class Unsupported(val level: LogLevel, val reason: UnsupportedReason) : LogTransition

    /** Available, but refused right now. */
    data class Refused(val reason: RefusalReason) : LogTransition

    /** The profile itself was malformed. */
    data class Invalid(val violations: List<ProfileViolation>) : LogTransition
}

/**
 * Plans log tier transitions, and converges a half-applied one at boot.
 *
 * Pure: no I/O, no property reads, no clock. Everything comes in as arguments so
 * that the whole transition table — including the states a crash mid-transition
 * can leave behind — is exercised as plain JUnit.
 */
class LogTransitionPlanner {

    /**
     * @param selinuxBringUpInProgress set while Bomb is validating new SELinux
     *   policy, during which [LogLevel.OFF] is refused.
     */
    fun plan(
        profile: LogProfile,
        observed: ObservedLogState,
        capabilities: LogCapabilities,
        savedDefaults: LogSnapshot = LogSnapshot.EMPTY,
        selinuxBringUpInProgress: Boolean = false,
    ): LogTransition {
        validate(profile).takeIf { it.isNotEmpty() }?.let { return LogTransition.Invalid(it) }

        if (profile.level == LogLevel.OFF && selinuxBringUpInProgress) {
            return LogTransition.Refused(RefusalReason.SELINUX_BRINGUP_IN_PROGRESS)
        }

        if (!capabilities.supports(profile.level)) {
            val reason = when (profile.level) {
                LogLevel.OFF -> UnsupportedReason.NO_DISABLE_BACKEND
                LogLevel.REDUCED -> UnsupportedReason.NO_ROM_BACKEND
                // DEFAULT is always supported; reaching here would be a
                // capability-model bug rather than a device limitation.
                LogLevel.DEFAULT -> UnsupportedReason.NO_ROM_BACKEND
            }
            return LogTransition.Unsupported(profile.level, reason)
        }

        val current = observed.effectiveLevel()

        // Checked before the already-at-level test: a device sitting at OFF whose
        // marker also says OFF still needs a reboot to reach anything else.
        if (current == LogLevel.OFF && profile.level != LogLevel.OFF) {
            return LogTransition.RebootRequired(current, profile.level)
        }

        if (current == profile.level && observed.isConsistent()) {
            return LogTransition.AlreadyAtLevel(profile.level)
        }

        return LogTransition.Apply(actionsFor(profile, savedDefaults))
    }

    /**
     * Converges the device onto whatever tier the Bomb property declares.
     *
     * Run at boot, and after any crash. The declared tier is authoritative
     * because it is the one piece of state that survives; everything else is
     * re-derived from the device. A device that was interrupted mid-transition
     * therefore finishes the transition rather than sitting half-applied.
     */
    fun reconcile(
        observed: ObservedLogState,
        capabilities: LogCapabilities,
        savedDefaults: LogSnapshot = LogSnapshot.EMPTY,
        auditRatePerSecond: Int? = null,
    ): LogTransition {
        val declared = observed.declaredLevel
        val profile = LogProfile(
            level = declared,
            auditRatePerSecond = auditRatePerSecond.takeIf { declared == LogLevel.REDUCED },
        )
        return plan(profile, observed, capabilities, savedDefaults, selinuxBringUpInProgress = false)
    }

    fun validate(profile: LogProfile): List<ProfileViolation> = buildList {
        val rate = profile.auditRatePerSecond
        if (rate != null) {
            if (rate !in LogProperty.AUDIT_RATE_RANGE) add(ProfileViolation.AUDIT_RATE_OUT_OF_RANGE)
            if (profile.level == LogLevel.DEFAULT) add(ProfileViolation.AUDIT_RATE_NOT_APPLICABLE)
        }
    }

    private fun actionsFor(profile: LogProfile, savedDefaults: LogSnapshot): List<LogAction> =
        when (profile.level) {
            LogLevel.DEFAULT -> defaultActions(savedDefaults)
            LogLevel.REDUCED -> reducedActions(profile.auditRatePerSecond)
            LogLevel.OFF -> offActions(profile.auditRatePerSecond)
        }

    /**
     * Restore every property Bomb touched to the value it had **before Bomb
     * touched it**, then bring the services back.
     *
     * Restoring captured values rather than guessed ones is the same rule
     * Performance Profiles follow (`docs/BOMB_PLAN.md` §4.9: restore only what
     * Bomb set). It matters here specifically for `persist.traced.enable`:
     * writing a guessed `1` would enable Perfetto on a device that shipped with
     * it off, and writing `""` would leave the init trigger unable to start
     * `traced` on the next boot. Only the captured value is correct, and when
     * nothing was captured the empty reset is the honest fallback.
     */
    private fun defaultActions(savedDefaults: LogSnapshot): List<LogAction> = buildList {
        for (property in MANAGED_PROPERTIES) {
            add(LogAction.SetProperty(property, savedDefaults.valueFor(property)))
        }
        // Without this the restored size is a silent no-op and the buffer stays
        // at whatever REDUCED shrank it to.
        add(LogAction.StartService(LogService.LOGD_REINIT))
        add(LogAction.StartService(LogService.TRACED))
        add(LogAction.StartService(LogService.TRACED_PROBES))
        add(LogAction.StartService(LogService.LOGCATD))
        add(LogAction.SetProperty(LogProperty.BOMB_LOG_LEVEL, "default"))
    }

    /**
     * Reduce volume, keep `logd` alive.
     *
     * Generalised from the ROM's existing, already-proven `minimal_logging`
     * block. Nothing here touches `debuggerd`, tombstones or ANR collection —
     * those do not pass through logd and must survive every tier.
     */
    private fun reducedActions(auditRate: Int?): List<LogAction> = buildList {
        addAll(reducedProperties(auditRate))
        add(LogAction.StopService(LogService.LOGCATD))
        add(LogAction.StopService(LogService.TRACED))
        add(LogAction.StopService(LogService.TRACED_PROBES))
        // Applies the new buffer size. Omitting it is the classic silent failure.
        add(LogAction.StartService(LogService.LOGD_REINIT))
        add(LogAction.SetProperty(LogProperty.BOMB_LOG_LEVEL, "reduced"))
    }

    /**
     * Everything [REDUCED] does, plus stopping the log daemon chain.
     *
     * The reduced property set is retained deliberately: if `logd` is ever
     * restarted — by a vendor path this analysis missed, or by a future ROM
     * update — it comes back small and quiet rather than at full volume.
     * Dependents are stopped before `logd` itself.
     */
    private fun offActions(auditRate: Int?): List<LogAction> = buildList {
        addAll(reducedProperties(auditRate))
        add(LogAction.StopService(LogService.LOGCATD))
        add(LogAction.StopService(LogService.TRACED))
        add(LogAction.StopService(LogService.TRACED_PROBES))
        // logd last, after everything that feeds or reads it.
        add(LogAction.StopService(LogService.LOGD_AUDITCTL))
        add(LogAction.StopService(LogService.LOGD))
        add(LogAction.SetProperty(LogProperty.BOMB_LOG_LEVEL, "off"))
    }

    /** The property writes shared by [LogLevel.REDUCED] and [LogLevel.OFF]. */
    private fun reducedProperties(auditRate: Int?): List<LogAction> = buildList {
        add(LogAction.SetProperty(LogProperty.LOGD_KERNEL, "false"))
        add(LogAction.SetProperty(LogProperty.LOGD_STATISTICS, "false"))
        add(LogAction.SetProperty(LogProperty.LOGD_SIZE, REDUCED_BUFFER_SIZE))
        add(LogAction.SetProperty(LogProperty.LOGD_LIMIT, "Off"))
        add(LogAction.SetProperty(LogProperty.LOGPERSISTD_ENABLE, "false"))
        add(LogAction.SetProperty(LogProperty.TRACED_ENABLE, "0"))
        add(LogAction.SetProperty(LogProperty.TRACED_PERF_ENABLE, "0"))
        if (auditRate != null) {
            add(LogAction.SetProperty(LogProperty.AUDIT_RATE, auditRate.toString()))
        }
    }

    private companion object {
        /** The value the ROM's own minimal-logging block already uses. */
        const val REDUCED_BUFFER_SIZE = "64K"

        /**
         * Every property this subsystem writes, and therefore every property it
         * must be able to restore. [LogProperty.BOMB_LOG_LEVEL] is excluded: it
         * is Bomb's own marker, not device state to be preserved.
         */
        val MANAGED_PROPERTIES = LogProperty.entries.filter { it != LogProperty.BOMB_LOG_LEVEL }
    }
}
