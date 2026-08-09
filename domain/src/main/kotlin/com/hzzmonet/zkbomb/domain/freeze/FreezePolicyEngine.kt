package com.hzzmonet.zkbomb.domain.freeze

/**
 * A single privileged step the caller must perform.
 *
 * The engine emits steps rather than performing them, which is what keeps the
 * whole policy testable as plain JVM code and keeps `:domain` free of Android
 * imports. The privileged layer executes these in order and re-reads state
 * afterwards.
 */
sealed interface FreezeAction {

    /**
     * Engage or release one mechanism.
     *
     * `engaged = false` means "undo this" — unsuspend, unhide, re-enable,
     * unfreeze — and is how every recovery path is expressed.
     */
    data class SetMechanism(val mechanism: FreezeMechanism, val engaged: Boolean) : FreezeAction

    /**
     * Force stop the package before the following step.
     *
     * Emitted only ahead of [FreezeMechanism.DISABLE], because disabling a
     * package does not stop a process that is already running
     * (`docs/BOMB_PLAN.md` §4.3). Force stop is otherwise a Task Manager action
     * and never a freeze state in its own right.
     */
    data object ForceStop : FreezeAction
}

/** Why a request was rejected before any capability or state was consulted. */
enum class InvalidReason {
    /** The package name is not syntactically a package name. */
    MALFORMED_PACKAGE_NAME,

    /** The user id is negative. */
    INVALID_USER_ID,
}

/**
 * The outcome of planning one freeze request.
 *
 * Every branch is explicit. Master plan §3.5 forbids silently swallowing
 * failures, and a refusal is a failure the user is entitled to see the reason
 * for.
 */
sealed interface FreezeDecision {

    /** Perform [actions] in order, then re-read platform state. */
    data class Execute(val actions: List<FreezeAction>) : FreezeDecision

    /** Already in the requested mode; nothing to do. */
    data class AlreadyInMode(val mode: FreezeMode) : FreezeDecision

    /** The package is protected. */
    data class Excluded(val reason: ExclusionReason) : FreezeDecision

    /**
     * No supported mechanism can express this mode on this device.
     *
     * [attempted] lists what was considered, in preference order, so the UI can
     * explain the gap instead of showing a bare "unsupported".
     */
    data class Unsupported(
        val mode: FreezeMode,
        val attempted: List<FreezeMechanism>,
    ) : FreezeDecision

    /**
     * [FreezeMode.SOFT_FREEZE] was requested for a package with no running
     * processes.
     *
     * The platform freezer acts on processes, so there is genuinely nothing to
     * freeze. Reporting success here would claim a state that does not exist;
     * reporting failure would be wrong too, since the app is already not running.
     */
    data object NothingToFreeze : FreezeDecision

    /** The request was malformed. */
    data class Invalid(val reason: InvalidReason) : FreezeDecision
}

/**
 * Turns a freeze *intent* into an ordered list of privileged steps.
 *
 * The engine is pure: same inputs, same decision, no clock, no I/O, no platform.
 * Everything it needs — capabilities, protected packages, observed state — is
 * passed in, because all three are things the caller must read fresh anyway.
 *
 * Mechanism selection per mode (`docs/BOMB_PLAN.md` §4.3):
 *
 * | Mode | Preference order |
 * | --- | --- |
 * | [FreezeMode.SOFT_FREEZE] | [FreezeMechanism.PLATFORM_FREEZER] |
 * | [FreezeMode.DEEP_FREEZE] | [FreezeMechanism.SUSPEND], then [FreezeMechanism.HIDE] |
 * | [FreezeMode.DISABLED] | [FreezeMechanism.DISABLE] |
 *
 * Suspend leads deep freeze by decision D8: a hidden package behaves as
 * uninstalled and breaks other apps' queries, while a suspended one stays
 * queryable and attributable.
 */
class FreezePolicyEngine(
    private val capabilities: FreezeCapabilities,
    private val protectedPackages: ProtectedPackages,
) {

    fun plan(request: FreezeRequest): FreezeDecision {
        if (!PackageNameValidator.isValid(request.packageName)) {
            return FreezeDecision.Invalid(InvalidReason.MALFORMED_PACKAGE_NAME)
        }
        if (request.userId < 0) {
            return FreezeDecision.Invalid(InvalidReason.INVALID_USER_ID)
        }

        // Protection guards *freezing*, never *unfreezing*. If a protected
        // package somehow ends up frozen — a rule fired before the launcher
        // changed, a restore from a stale profile — the user must still be able
        // to recover it, and refusing here would be the one refusal that cannot
        // be worked around from inside the app.
        if (request.targetMode != FreezeMode.NORMAL) {
            protectedPackages.exclusionFor(request.packageName)?.let {
                return FreezeDecision.Excluded(it)
            }
        }

        // NORMAL is the absence of a mechanism, not an unsupported one — an
        // empty target set means "release everything currently engaged".
        val wanted: Set<FreezeMechanism> = if (request.targetMode == FreezeMode.NORMAL) {
            emptySet()
        } else {
            val mechanism = mechanismFor(request.targetMode)
                ?: return FreezeDecision.Unsupported(
                    mode = request.targetMode,
                    attempted = preferenceOrder(request.targetMode),
                )
            setOf(mechanism)
        }

        if (request.targetMode == FreezeMode.SOFT_FREEZE && !request.observed.hasRunningProcesses) {
            return FreezeDecision.NothingToFreeze
        }

        val engaged = engagedMechanisms(request.observed)

        val toRelease = engaged - wanted
        val toEngage = wanted - engaged

        // Releasing needs the same capability as engaging: unsuspending is
        // `setPackagesSuspended(false)`. If the capability has been lost since
        // the state was set, say so rather than emitting a step that cannot run.
        (toRelease + toEngage).firstOrNull { !capabilities.supports(it) }?.let {
            return FreezeDecision.Unsupported(request.targetMode, listOf(it))
        }

        if (toRelease.isEmpty() && toEngage.isEmpty()) {
            return FreezeDecision.AlreadyInMode(request.targetMode)
        }

        val actions = buildList {
            // Release first: a package must be enabled before it can meaningfully
            // be suspended, and leaving two mechanisms engaged at once would make
            // the observed state ambiguous.
            for (mechanism in RELEASE_ORDER) {
                if (mechanism in toRelease) add(FreezeAction.SetMechanism(mechanism, engaged = false))
            }
            for (mechanism in ENGAGE_ORDER) {
                if (mechanism !in toEngage) continue
                if (mechanism == FreezeMechanism.DISABLE && request.observed.hasRunningProcesses) {
                    add(FreezeAction.ForceStop)
                }
                add(FreezeAction.SetMechanism(mechanism, engaged = true))
            }
        }

        return FreezeDecision.Execute(actions)
    }

    /** The best supported mechanism for [mode], or null when none is supported. */
    private fun mechanismFor(mode: FreezeMode): FreezeMechanism? {
        if (mode == FreezeMode.NORMAL) return null
        return preferenceOrder(mode).firstOrNull { capabilities.supports(it) }
    }

    private fun preferenceOrder(mode: FreezeMode): List<FreezeMechanism> = when (mode) {
        FreezeMode.NORMAL -> emptyList()
        FreezeMode.SOFT_FREEZE -> listOf(FreezeMechanism.PLATFORM_FREEZER)
        // D8: suspend before hide.
        FreezeMode.DEEP_FREEZE -> listOf(FreezeMechanism.SUSPEND, FreezeMechanism.HIDE)
        FreezeMode.DISABLED -> listOf(FreezeMechanism.DISABLE)
    }

    private fun engagedMechanisms(state: ObservedFreezeState): Set<FreezeMechanism> = buildSet {
        if (!state.enabled) add(FreezeMechanism.DISABLE)
        if (state.suspended) add(FreezeMechanism.SUSPEND)
        if (state.hidden) add(FreezeMechanism.HIDE)
        if (state.hasRunningProcesses && state.allProcessesFrozen) add(FreezeMechanism.PLATFORM_FREEZER)
    }

    private companion object {
        /**
         * Strongest first: re-enable before unhiding before unsuspending, so the
         * package is reachable by the time the weaker calls are made.
         */
        val RELEASE_ORDER = listOf(
            FreezeMechanism.DISABLE,
            FreezeMechanism.HIDE,
            FreezeMechanism.SUSPEND,
            FreezeMechanism.PLATFORM_FREEZER,
        )

        /** Weakest first, so the package stays reachable for as long as possible. */
        val ENGAGE_ORDER = listOf(
            FreezeMechanism.PLATFORM_FREEZER,
            FreezeMechanism.SUSPEND,
            FreezeMechanism.HIDE,
            FreezeMechanism.DISABLE,
        )
    }
}

/**
 * One freeze intent, with the platform state it was decided against.
 *
 * [observed] is part of the request rather than fetched by the engine because
 * the caller has already paid for that read, and because passing it in is what
 * makes every transition reproducible in a test.
 */
data class FreezeRequest(
    val packageName: String,
    val userId: Int,
    val targetMode: FreezeMode,
    val observed: ObservedFreezeState,
)
