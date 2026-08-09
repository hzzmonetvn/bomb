package com.hzzmonet.zkbomb.domain.freeze

/**
 * Per-app auto-freeze configuration (master plan §7 "Per-app options").
 *
 * Both delays are measured from the moment the app **left the foreground**, not
 * from the previous transition. That is what makes the ladder recoverable: after
 * a service restart the deadlines are recomputed from a single stored timestamp,
 * so a restart neither loses progress nor restarts the clock.
 */
data class FreezePolicy(
    /** Auto-freeze enabled for this app. */
    val autoFreeze: Boolean,
    /** Delay from leaving the foreground to [FreezeMode.SOFT_FREEZE]. */
    val softFreezeDelayMillis: Long,
    /** Delay from leaving the foreground to [FreezeMode.DEEP_FREEZE]. */
    val deepFreezeDelayMillis: Long,
    /** Escalate immediately once the screen turns off, skipping the remaining delay. */
    val freezeWhenScreenOff: Boolean,
    /**
     * The strongest mode auto-freeze may reach.
     *
     * [FreezeMode.DISABLED] is deliberately not reachable by escalation — it is a
     * deliberate user action, not something a timer should do to an app.
     */
    val maxMode: FreezeMode,
) {
    companion object {
        /** The ladder from master plan §7: +30 s soft, +5 min deep. */
        val DEFAULT = FreezePolicy(
            autoFreeze = false,
            softFreezeDelayMillis = 30_000L,
            deepFreezeDelayMillis = 300_000L,
            freezeWhenScreenOff = false,
            maxMode = FreezeMode.DEEP_FREEZE,
        )
    }
}

/** Why a [FreezePolicy] was rejected. */
enum class PolicyViolation {
    /** A delay was negative. */
    NEGATIVE_DELAY,

    /** The deep-freeze delay is earlier than the soft-freeze delay. */
    DEEP_BEFORE_SOFT,

    /** [FreezePolicy.maxMode] is a mode escalation must never reach. */
    UNREACHABLE_MAX_MODE,
}

/**
 * Validates a policy before it is stored.
 *
 * Master plan §3.1 requires numeric ranges to be validated on every privileged
 * request. A policy with `deepFreezeDelay < softFreezeDelay` would otherwise
 * produce a ladder that skips a rung in a way nobody asked for, silently.
 */
object FreezePolicyValidator {

    fun validate(policy: FreezePolicy): List<PolicyViolation> = buildList {
        if (policy.softFreezeDelayMillis < 0 || policy.deepFreezeDelayMillis < 0) {
            add(PolicyViolation.NEGATIVE_DELAY)
        }
        if (policy.deepFreezeDelayMillis < policy.softFreezeDelayMillis) {
            add(PolicyViolation.DEEP_BEFORE_SOFT)
        }
        if (policy.maxMode == FreezeMode.DISABLED || policy.maxMode == FreezeMode.NORMAL) {
            add(PolicyViolation.UNREACHABLE_MAX_MODE)
        }
    }
}

/** A manual freeze/unfreeze the user performed, which outranks the timer. */
data class ManualOverride(val mode: FreezeMode, val atMillis: Long)

/**
 * Everything the scheduler needs to know about one app right now.
 *
 * Deliberately a snapshot rather than a live object: the scheduler must be a
 * pure function so that the whole escalation ladder is testable against a fake
 * clock, including the restart case.
 */
data class AppRuntimeState(
    val packageName: String,
    /** True while the app is the foreground app. */
    val isForeground: Boolean,
    /**
     * When the app last left the foreground.
     *
     * Null means it has not been in the foreground since Bomb started observing,
     * in which case there is no reference point and no escalation is scheduled —
     * freezing an app on a timer that never started would be arbitrary.
     */
    val lastForegroundExitMillis: Long?,
    /** The mode the app is actually in, read back from the platform. */
    val currentMode: FreezeMode,
    /** A manual action, if one is in force. */
    val manualOverride: ManualOverride?,
)

/** What the scheduler wants done for one app. */
sealed interface ScheduleOutcome {

    /** Move the app to [targetMode] now. */
    data class Escalate(val targetMode: FreezeMode) : ScheduleOutcome

    /** Nothing to do yet; re-evaluate at [atMillis]. */
    data class Wait(val atMillis: Long) : ScheduleOutcome

    /**
     * The user's manual choice is in force and the timer must not override it.
     *
     * Precedence is `manual > safety > per-app > global` (`docs/BOMB_PLAN.md`
     * §4.11).
     */
    data class HeldByManualOverride(val mode: FreezeMode) : ScheduleOutcome

    /** Nothing scheduled — auto-freeze off, app in the foreground, or already settled. */
    data object Idle : ScheduleOutcome
}

/**
 * Decides when an app should move up the freeze ladder.
 *
 * Stateless and clock-free: [evaluate] takes the current time, so a test drives
 * an entire escalation with three calls and no waiting, and the production
 * scheduler can be restarted at any point without losing its place.
 */
class FreezeScheduler {

    /**
     * @param now current wall time, same epoch as [AppRuntimeState.lastForegroundExitMillis].
     * @param screenOff whether the display is currently off.
     */
    fun evaluate(
        state: AppRuntimeState,
        policy: FreezePolicy,
        now: Long,
        screenOff: Boolean,
    ): ScheduleOutcome {
        // Manual first — before auto-freeze is even consulted — so that a manual
        // unfreeze is not immediately undone by the timer that froze the app.
        state.manualOverride?.let { return ScheduleOutcome.HeldByManualOverride(it.mode) }

        if (!policy.autoFreeze) return ScheduleOutcome.Idle

        // A foreground app is never escalated, and its exit timestamp is stale by
        // definition, so there is nothing to schedule until it leaves.
        if (state.isForeground) return ScheduleOutcome.Idle

        val exitedAt = state.lastForegroundExitMillis ?: return ScheduleOutcome.Idle

        val ladder = ladderUpTo(policy.maxMode)
        val currentRung = ladder.indexOf(state.currentMode)
        // A mode outside the escalation ladder (DISABLED, or a mode above
        // maxMode) means the app is already stronger than the timer would make
        // it. Leave it alone.
        if (currentRung < 0) return ScheduleOutcome.Idle
        if (currentRung == ladder.lastIndex) return ScheduleOutcome.Idle

        val next = ladder[currentRung + 1]

        // Screen off waives the remaining wait entirely, when the user asked for
        // that. Escalating one rung per evaluation rather than jumping straight
        // to maxMode keeps the observable sequence identical to the timed path.
        if (screenOff && policy.freezeWhenScreenOff) {
            return ScheduleOutcome.Escalate(next)
        }

        val deadline = exitedAt + delayFor(next, policy)
        return if (now >= deadline) {
            ScheduleOutcome.Escalate(next)
        } else {
            ScheduleOutcome.Wait(deadline)
        }
    }

    /**
     * The rungs from [FreezeMode.NORMAL] up to and including [maxMode].
     *
     * [FreezeMode.DISABLED] never appears: escalation must not disable an app.
     */
    private fun ladderUpTo(maxMode: FreezeMode): List<FreezeMode> = when (maxMode) {
        FreezeMode.SOFT_FREEZE -> listOf(FreezeMode.NORMAL, FreezeMode.SOFT_FREEZE)
        FreezeMode.DEEP_FREEZE -> listOf(FreezeMode.NORMAL, FreezeMode.SOFT_FREEZE, FreezeMode.DEEP_FREEZE)
        // Rejected by FreezePolicyValidator; treated as "no escalation" rather
        // than crashing, because a stored policy may predate a validator change.
        FreezeMode.NORMAL, FreezeMode.DISABLED -> listOf(FreezeMode.NORMAL)
    }

    private fun delayFor(mode: FreezeMode, policy: FreezePolicy): Long = when (mode) {
        FreezeMode.SOFT_FREEZE -> policy.softFreezeDelayMillis
        FreezeMode.DEEP_FREEZE -> policy.deepFreezeDelayMillis
        FreezeMode.NORMAL, FreezeMode.DISABLED -> Long.MAX_VALUE
    }
}
