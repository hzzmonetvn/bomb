package com.hzzmonet.zkbomb.domain.log

/**
 * The three log tiers set by the project owner.
 *
 * Ordered weakest to strongest logging suppression. The ordering is meaningful:
 * [OFF] is a strict superset of [REDUCED]'s suppression.
 */
enum class LogLevel {
    /** Bomb sets nothing. The platform's own defaults apply. */
    DEFAULT,

    /**
     * Volume reduced, but `logd` stays alive.
     *
     * Deliberately preserves crash logs, tombstones, bugreports and — the one
     * that matters most to this project — **SELinux denials**. That is what makes
     * this tier compatible with the "SELinux remains enforcing" invariant, since
     * the invariant is verified by reading denials.
     */
    REDUCED,

    /**
     * `logd` stopped outright.
     *
     * On the integrated target ROM this is handled by an init property trigger,
     * not by a root session. `docs/research/LOG_CONTROL.md` §2 established that
     * the image has no repeating `logd` restarter and that `logd` is not marked
     * critical. A normal APK still cannot do this; support is reported only when
     * a ROM or root backend was actually detected.
     */
    OFF,
}

/**
 * The exact properties Bomb may write for log control.
 *
 * An enumerated set, not a free-form name. `CLAUDE.md` forbids exposing an
 * arbitrary property-write API; the privileged surface is
 * `setLogProfile(level)` taking an enum, and this enum bounds everything that
 * call can reach. A property not listed here cannot be written by this
 * subsystem at all.
 */
enum class LogProperty(val key: String) {
    /** Kernel log collection into logd. */
    LOGD_KERNEL("persist.logd.kernel"),

    /** logd's own statistics tracking. */
    LOGD_STATISTICS("persist.logd.statistics"),

    /**
     * Per-buffer size.
     *
     * Takes effect **only** after `logd --reinit`. Setting it alone is a silent
     * no-op, which is why [LogTransitionPlanner] always pairs it with
     * [LogService.LOGD_REINIT].
     */
    LOGD_SIZE("persist.logd.size"),

    /** logd's rate limiter. `Off` disables the throttle entirely. */
    LOGD_LIMIT("persist.logd.limit"),

    /** Persistent log collection to `/data/misc/logd`. */
    LOGPERSISTD_ENABLE("logd.logpersistd.enable"),

    /** Perfetto tracing daemon. */
    TRACED_ENABLE("persist.traced.enable"),

    /** Perfetto performance probes. */
    TRACED_PERF_ENABLE("persist.traced_perf.enable"),

    /**
     * SELinux denial generation rate, in messages per second.
     *
     * `logd.rc:38-43` runs `auditctl -r ${persist.logd.audit.rate:-5}`, and the
     * service is re-run by `on property:persist.logd.audit.rate=*`, so writing
     * this takes effect live. This is the middle ground between "all denials" and
     * "no denials" that an all-or-nothing tier design would have missed.
     */
    AUDIT_RATE("persist.logd.audit.rate"),

    /** Bomb's own tier marker, and the recovery path when the UI is unavailable. */
    BOMB_LOG_LEVEL("persist.sys.bomb.log.level"),
    ;

    /**
     * Whether [value] is in this property's allowed domain.
     *
     * Master plan §3.1 requires enum values and numeric ranges to be validated.
     * The planner is the only producer of these writes, so this is primarily a
     * regression guard — but it is the guard that would catch a future edit
     * emitting, say, a shell fragment as a buffer size.
     */
    fun accepts(value: String): Boolean = when (this) {
        LOGD_KERNEL, LOGD_STATISTICS, LOGPERSISTD_ENABLE ->
            value == "true" || value == "false" || value.isEmpty()

        TRACED_ENABLE, TRACED_PERF_ENABLE ->
            value == "0" || value == "1" || value.isEmpty()

        LOGD_SIZE -> value.isEmpty() || isBufferSize(value)

        LOGD_LIMIT -> value == "Off" || value.isEmpty()

        AUDIT_RATE -> value.isEmpty() || value.toIntOrNull()?.let { it in AUDIT_RATE_RANGE } == true

        BOMB_LOG_LEVEL -> value in setOf("default", "reduced", "off")
    }

    companion object {
        /**
         * `0` disables the audit rate limit entirely — the platform's own
         * semantic for `auditctl -r 0`, kept rather than reinterpreted. The upper
         * bound is arbitrary but bounded, which is the point.
         */
        val AUDIT_RATE_RANGE = 0..1000

        /** logd accepts a decimal count with an optional K/M/G suffix. */
        private fun isBufferSize(value: String): Boolean {
            val digits = value.takeWhile { it.isDigit() }
            if (digits.isEmpty()) return false
            val suffix = value.substring(digits.length)
            return suffix.isEmpty() || suffix in setOf("K", "M", "G")
        }
    }
}

/**
 * The init services the log tiers start and stop.
 *
 * Enumerated for the same reason as [LogProperty]: `ctl.start`/`ctl.stop` on an
 * arbitrary service name would be a generic privileged API.
 */
enum class LogService(val serviceName: String) {
    /** The log daemon itself. Stopped only by [LogLevel.OFF]. */
    LOGD("logd"),

    /**
     * `logd --reinit`.
     *
     * A oneshot that makes [LogProperty.LOGD_SIZE] actually take effect.
     */
    LOGD_REINIT("logd-reinit"),

    /** The auditd control service that applies [LogProperty.AUDIT_RATE]. */
    LOGD_AUDITCTL("logd-auditctl"),

    /** Background `logcat` capture to disk. */
    LOGCATD("logcatd"),

    /** Perfetto tracing daemon. */
    TRACED("traced"),

    /** Perfetto probe daemon. */
    TRACED_PROBES("traced_probes"),
}

/**
 * The values the managed properties held **before Bomb first changed them**.
 *
 * Captured once, on the first transition away from [LogLevel.DEFAULT], and used
 * to restore exactly those values later. This is the log subsystem's version of
 * the rule Performance Profiles follow: restore what Bomb set, to what it was —
 * never to a guessed platform default. Guessing is not a theoretical problem
 * here; `persist.traced.enable` has no safe guess, because `1` would enable
 * Perfetto on a device that shipped with it off and `""` would stop the init
 * trigger from ever starting `traced` again.
 */
data class LogSnapshot(val values: Map<LogProperty, String>) {

    /**
     * The captured value for [property], or the empty reset when nothing was
     * captured.
     *
     * The empty string is the honest fallback rather than a fabricated default:
     * it hands the decision back to the platform instead of asserting a value
     * Bomb never observed.
     */
    fun valueFor(property: LogProperty): String = values[property] ?: ""

    companion object {
        /** Nothing captured — every restore falls back to the empty reset. */
        val EMPTY = LogSnapshot(emptyMap())
    }
}

/** One privileged step in a tier transition. */
sealed interface LogAction {
    data class SetProperty(val property: LogProperty, val value: String) : LogAction
    data class StartService(val service: LogService) : LogAction
    data class StopService(val service: LogService) : LogAction
}

/**
 * What this device can actually do about logging.
 *
 * Master plan §3.3: never assume support.
 */
data class LogCapabilities(
    /** A ROM backend (init.rc property trigger) is present for [LogLevel.REDUCED]. */
    val reduceSupported: Boolean,

    /** A legacy/root-module backend is available. */
    val rootBackendAvailable: Boolean,

    /**
     * Whether the image was verified to have no `logd` restarter.
     *
     * For Bomb's integrated ROM this also means the typed init trigger is
     * installed, and therefore enables [LogLevel.OFF] without a root session.
     */
    val romDisableReachable: Boolean,

    /**
     * MIUI's `/dev/ylog_buffer` sink is present.
     *
     * No tier touches it (`docs/research/LOG_CONTROL.md` §2.3), so when this is
     * true the UI must not claim logging is off. Surfacing it is the difference
     * between an honest capability report and a false one.
     */
    val ylogPresent: Boolean,
) {
    fun supports(level: LogLevel): Boolean = when (level) {
        LogLevel.DEFAULT -> true
        LogLevel.REDUCED -> reduceSupported
        LogLevel.OFF -> romDisableReachable || rootBackendAvailable
    }
}

/**
 * Log subsystem state as read back from the device.
 *
 * Read rather than remembered, so that a crash midway through a transition, an
 * external `setprop`, or a vendor service restarting something is detected
 * instead of assumed away.
 */
data class ObservedLogState(
    val logdRunning: Boolean,
    val logcatdRunning: Boolean,
    val tracedRunning: Boolean,
    val tracedProbesRunning: Boolean,
    /** Every property [LogLevel.REDUCED] sets currently holds its reduced value. */
    val reducedPropertiesApplied: Boolean,
    /** The tier recorded in [LogProperty.BOMB_LOG_LEVEL]. */
    val declaredLevel: LogLevel,
) {
    /**
     * The tier the device is *actually* in.
     *
     * A half-applied [LogLevel.REDUCED] — properties set but `logcatd` still
     * running — deliberately resolves to [LogLevel.DEFAULT] rather than to
     * `REDUCED`. That is the conservative direction: it under-claims suppression,
     * and it makes [LogTransitionPlanner.reconcile] re-apply the tier instead of
     * concluding there is nothing to do.
     */
    fun effectiveLevel(): LogLevel = when {
        !logdRunning -> LogLevel.OFF
        reducedPropertiesApplied && !logcatdRunning && !tracedRunning && !tracedProbesRunning ->
            LogLevel.REDUCED
        else -> LogLevel.DEFAULT
    }

    /** True when the declared tier and the observed state agree. */
    fun isConsistent(): Boolean = declaredLevel == effectiveLevel()
}
