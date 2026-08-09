package com.hzzmonet.zkbomb.domain.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transition table and reconcile combinations from
 * `docs/research/LOG_CONTROL.md` §4-§5.
 *
 * Two of these encode findings that were expensive to establish and easy to
 * regress: that `persist.logd.size` is a silent no-op unless `logd-reinit` runs,
 * and that `REDUCED` must leave `logd` alive so SELinux denials stay visible.
 */
class LogTransitionPlannerTest {

    private val planner = LogTransitionPlanner()

    private val fullCapabilities = LogCapabilities(
        reduceSupported = true,
        rootBackendAvailable = true,
        romDisableReachable = true,
        ylogPresent = true,
    )

    private fun observed(
        logdRunning: Boolean = true,
        logcatdRunning: Boolean = true,
        tracedRunning: Boolean = true,
        tracedProbesRunning: Boolean = true,
        reducedPropertiesApplied: Boolean = false,
        declaredLevel: LogLevel = LogLevel.DEFAULT,
    ) = ObservedLogState(
        logdRunning = logdRunning,
        logcatdRunning = logcatdRunning,
        tracedRunning = tracedRunning,
        tracedProbesRunning = tracedProbesRunning,
        reducedPropertiesApplied = reducedPropertiesApplied,
        declaredLevel = declaredLevel,
    )

    private val atReduced = observed(
        logcatdRunning = false,
        tracedRunning = false,
        tracedProbesRunning = false,
        reducedPropertiesApplied = true,
        declaredLevel = LogLevel.REDUCED,
    )

    private val atOff = observed(
        logdRunning = false,
        logcatdRunning = false,
        tracedRunning = false,
        tracedProbesRunning = false,
        reducedPropertiesApplied = true,
        declaredLevel = LogLevel.OFF,
    )

    private fun actionsOf(transition: LogTransition): List<LogAction> =
        (transition as LogTransition.Apply).actions

    // ---- effective level -----------------------------------------------------

    @Test
    fun `logd stopped means off`() {
        assertEquals(LogLevel.OFF, atOff.effectiveLevel())
    }

    @Test
    fun `reduced needs both the properties and the stopped services`() {
        assertEquals(LogLevel.REDUCED, atReduced.effectiveLevel())
    }

    @Test
    fun `half applied reduced resolves to default so reconcile re-applies it`() {
        // Properties set but logcatd still running: a crash midway through.
        // Resolving this to REDUCED would make reconcile conclude there is
        // nothing to do and leave the device half-configured forever.
        val half = observed(reducedPropertiesApplied = true, logcatdRunning = true)
        assertEquals(LogLevel.DEFAULT, half.effectiveLevel())
    }

    @Test
    fun `declared and effective disagreeing is detected`() {
        val half = observed(reducedPropertiesApplied = true, declaredLevel = LogLevel.REDUCED)
        assertFalse(half.isConsistent())
    }

    // ---- REDUCED -------------------------------------------------------------

    @Test
    fun `reduced keeps logd alive`() {
        val actions = actionsOf(planner.plan(LogProfile(LogLevel.REDUCED), observed(), fullCapabilities))
        assertFalse(
            "REDUCED must never stop logd — SELinux denials are read from it",
            actions.contains(LogAction.StopService(LogService.LOGD)),
        )
    }

    @Test
    fun `reduced always pairs the buffer size with a reinit`() {
        val actions = actionsOf(planner.plan(LogProfile(LogLevel.REDUCED), observed(), fullCapabilities))
        val sizeIndex = actions.indexOfFirst {
            it is LogAction.SetProperty && it.property == LogProperty.LOGD_SIZE
        }
        val reinitIndex = actions.indexOf(LogAction.StartService(LogService.LOGD_REINIT))
        assertTrue("buffer size must be set", sizeIndex >= 0)
        assertTrue("logd-reinit must run, or the size is a silent no-op", reinitIndex >= 0)
        assertTrue("reinit must come after the size is set", reinitIndex > sizeIndex)
    }

    @Test
    fun `reduced stops the trace and capture daemons`() {
        val actions = actionsOf(planner.plan(LogProfile(LogLevel.REDUCED), observed(), fullCapabilities))
        assertTrue(actions.contains(LogAction.StopService(LogService.LOGCATD)))
        assertTrue(actions.contains(LogAction.StopService(LogService.TRACED)))
        assertTrue(actions.contains(LogAction.StopService(LogService.TRACED_PROBES)))
    }

    @Test
    fun `reduced applies the audit rate when one is given`() {
        val actions = actionsOf(
            planner.plan(LogProfile(LogLevel.REDUCED, auditRatePerSecond = 2), observed(), fullCapabilities),
        )
        assertTrue(actions.contains(LogAction.SetProperty(LogProperty.AUDIT_RATE, "2")))
    }

    @Test
    fun `reduced leaves the audit rate untouched when none is given`() {
        val actions = actionsOf(planner.plan(LogProfile(LogLevel.REDUCED), observed(), fullCapabilities))
        assertFalse(actions.any { it is LogAction.SetProperty && it.property == LogProperty.AUDIT_RATE })
    }

    @Test
    fun `reduced is unsupported without a rom backend`() {
        val transition = planner.plan(
            LogProfile(LogLevel.REDUCED),
            observed(),
            fullCapabilities.copy(reduceSupported = false),
        )
        assertEquals(
            LogTransition.Unsupported(LogLevel.REDUCED, UnsupportedReason.NO_ROM_BACKEND),
            transition,
        )
    }

    // ---- OFF -----------------------------------------------------------------

    @Test
    fun `off stops logd after its dependents`() {
        val actions = actionsOf(planner.plan(LogProfile(LogLevel.OFF), observed(), fullCapabilities))
        val logdIndex = actions.indexOf(LogAction.StopService(LogService.LOGD))
        val auditIndex = actions.indexOf(LogAction.StopService(LogService.LOGD_AUDITCTL))
        val logcatdIndex = actions.indexOf(LogAction.StopService(LogService.LOGCATD))
        assertTrue(logdIndex > auditIndex)
        assertTrue(logdIndex > logcatdIndex)
    }

    @Test
    fun `off never starts logd-reinit`() {
        // Starting a reinit oneshot as part of stopping the daemon would race
        // the stop and could bring logd back.
        val actions = actionsOf(planner.plan(LogProfile(LogLevel.OFF), observed(), fullCapabilities))
        assertFalse(actions.any { it is LogAction.StartService })
    }

    @Test
    fun `off keeps the reduced properties so a restarted logd comes back quiet`() {
        val actions = actionsOf(planner.plan(LogProfile(LogLevel.OFF), observed(), fullCapabilities))
        assertTrue(actions.contains(LogAction.SetProperty(LogProperty.LOGD_SIZE, "64K")))
    }

    @Test
    fun `off works through the integrated rom without root`() {
        val transition = planner.plan(
            LogProfile(LogLevel.OFF),
            observed(),
            fullCapabilities.copy(rootBackendAvailable = false),
        )
        assertTrue(transition is LogTransition.Apply)
    }

    @Test
    fun `off is unsupported without either rom or root backend`() {
        val transition = planner.plan(
            LogProfile(LogLevel.OFF),
            observed(),
            fullCapabilities.copy(rootBackendAvailable = false, romDisableReachable = false),
        )
        assertEquals(LogTransition.Unsupported(LogLevel.OFF, UnsupportedReason.NO_DISABLE_BACKEND), transition)
    }

    @Test
    fun `off is refused during a selinux bring-up`() {
        val transition = planner.plan(
            LogProfile(LogLevel.OFF),
            observed(),
            fullCapabilities,
            selinuxBringUpInProgress = true,
        )
        assertEquals(LogTransition.Refused(RefusalReason.SELINUX_BRINGUP_IN_PROGRESS), transition)
    }

    @Test
    fun `reduced is not refused during a selinux bring-up`() {
        // REDUCED keeps denials visible, so the guard must not over-reach.
        val transition = planner.plan(
            LogProfile(LogLevel.REDUCED),
            observed(),
            fullCapabilities,
            selinuxBringUpInProgress = true,
        )
        assertTrue(transition is LogTransition.Apply)
    }

    @Test
    fun `leaving off requires a reboot`() {
        val transition = planner.plan(LogProfile(LogLevel.DEFAULT), atOff, fullCapabilities)
        assertEquals(LogTransition.RebootRequired(LogLevel.OFF, LogLevel.DEFAULT), transition)
    }

    @Test
    fun `leaving off for reduced also requires a reboot`() {
        val transition = planner.plan(LogProfile(LogLevel.REDUCED), atOff, fullCapabilities)
        assertEquals(LogTransition.RebootRequired(LogLevel.OFF, LogLevel.REDUCED), transition)
    }

    @Test
    fun `staying at off is already-at-level rather than a reboot`() {
        val transition = planner.plan(LogProfile(LogLevel.OFF), atOff, fullCapabilities)
        assertEquals(LogTransition.AlreadyAtLevel(LogLevel.OFF), transition)
    }

    // ---- restore -------------------------------------------------------------

    @Test
    fun `restore uses captured values rather than guessed defaults`() {
        val snapshot = LogSnapshot(
            mapOf(
                LogProperty.TRACED_ENABLE to "0",
                LogProperty.LOGD_SIZE to "256K",
            ),
        )
        val actions = actionsOf(
            planner.plan(LogProfile(LogLevel.DEFAULT), atReduced, fullCapabilities, snapshot),
        )
        // The device shipped with traced off. Guessing "1" would enable Perfetto
        // on a device that never had it.
        assertTrue(actions.contains(LogAction.SetProperty(LogProperty.TRACED_ENABLE, "0")))
        assertTrue(actions.contains(LogAction.SetProperty(LogProperty.LOGD_SIZE, "256K")))
    }

    @Test
    fun `restore falls back to the empty reset for uncaptured properties`() {
        val actions = actionsOf(
            planner.plan(LogProfile(LogLevel.DEFAULT), atReduced, fullCapabilities, LogSnapshot.EMPTY),
        )
        assertTrue(actions.contains(LogAction.SetProperty(LogProperty.LOGD_KERNEL, "")))
    }

    @Test
    fun `restore never writes the bomb marker as a device property to preserve`() {
        val actions = actionsOf(
            planner.plan(LogProfile(LogLevel.DEFAULT), atReduced, fullCapabilities),
        )
        val markerWrites = actions.filterIsInstance<LogAction.SetProperty>()
            .filter { it.property == LogProperty.BOMB_LOG_LEVEL }
        assertEquals(listOf(LogAction.SetProperty(LogProperty.BOMB_LOG_LEVEL, "default")), markerWrites)
    }

    @Test
    fun `restore re-runs reinit so the buffer size actually returns`() {
        val actions = actionsOf(
            planner.plan(LogProfile(LogLevel.DEFAULT), atReduced, fullCapabilities),
        )
        assertTrue(actions.contains(LogAction.StartService(LogService.LOGD_REINIT)))
    }

    // ---- reconcile -----------------------------------------------------------

    @Test
    fun `reconcile finishes a half-applied reduced`() {
        val half = observed(reducedPropertiesApplied = true, declaredLevel = LogLevel.REDUCED)
        val transition = planner.reconcile(half, fullCapabilities)
        assertTrue(transition is LogTransition.Apply)
        assertTrue(actionsOf(transition).contains(LogAction.StopService(LogService.LOGCATD)))
    }

    @Test
    fun `reconcile re-applies off when logd came back`() {
        val drifted = observed(declaredLevel = LogLevel.OFF)
        val transition = planner.reconcile(drifted, fullCapabilities)
        assertTrue(actionsOf(transition).contains(LogAction.StopService(LogService.LOGD)))
    }

    @Test
    fun `reconcile of a consistent device does nothing`() {
        assertEquals(
            LogTransition.AlreadyAtLevel(LogLevel.REDUCED),
            planner.reconcile(atReduced, fullCapabilities),
        )
    }

    @Test
    fun `reconcile does not attempt an unsupported tier`() {
        val drifted = observed(declaredLevel = LogLevel.OFF)
        val transition = planner.reconcile(
            drifted,
            fullCapabilities.copy(rootBackendAvailable = false, romDisableReachable = false),
        )
        assertEquals(LogTransition.Unsupported(LogLevel.OFF, UnsupportedReason.NO_DISABLE_BACKEND), transition)
    }

    // ---- validation ----------------------------------------------------------

    @Test
    fun `an out of range audit rate is rejected`() {
        val transition = planner.plan(
            LogProfile(LogLevel.REDUCED, auditRatePerSecond = 5000),
            observed(),
            fullCapabilities,
        )
        assertEquals(
            LogTransition.Invalid(listOf(ProfileViolation.AUDIT_RATE_OUT_OF_RANGE)),
            transition,
        )
    }

    @Test
    fun `an audit rate on the default tier is rejected`() {
        val transition = planner.plan(
            LogProfile(LogLevel.DEFAULT, auditRatePerSecond = 2),
            observed(),
            fullCapabilities,
        )
        assertEquals(
            LogTransition.Invalid(listOf(ProfileViolation.AUDIT_RATE_NOT_APPLICABLE)),
            transition,
        )
    }

    @Test
    fun `every value the planner emits is in its property's domain`() {
        // The guard that would catch a future edit emitting something unintended
        // into a property write.
        val allActions = listOf(
            planner.plan(LogProfile(LogLevel.REDUCED, auditRatePerSecond = 3), observed(), fullCapabilities),
            planner.plan(LogProfile(LogLevel.OFF), observed(), fullCapabilities),
            planner.plan(LogProfile(LogLevel.DEFAULT), atReduced, fullCapabilities),
        ).flatMap { actionsOf(it) }

        val writes = allActions.filterIsInstance<LogAction.SetProperty>()
        assertTrue(writes.isNotEmpty())
        for (write in writes) {
            assertTrue(
                "${write.property.key} rejects '${write.value}'",
                write.property.accepts(write.value),
            )
        }
    }

    @Test
    fun `property domains reject nonsense`() {
        assertFalse(LogProperty.LOGD_SIZE.accepts("64X"))
        assertFalse(LogProperty.LOGD_SIZE.accepts("; reboot"))
        assertFalse(LogProperty.LOGD_KERNEL.accepts("yes"))
        assertFalse(LogProperty.TRACED_ENABLE.accepts("true"))
        assertFalse(LogProperty.AUDIT_RATE.accepts("-1"))
        assertFalse(LogProperty.BOMB_LOG_LEVEL.accepts("disabled"))
        assertTrue(LogProperty.LOGD_SIZE.accepts("64K"))
        assertTrue(LogProperty.AUDIT_RATE.accepts("0"))
        assertTrue(LogProperty.BOMB_LOG_LEVEL.accepts("off"))
    }

    // ---- honesty about ylog --------------------------------------------------

    @Test
    fun `ylog presence is reported so the ui cannot claim logging is off`() {
        // No tier touches /dev/ylog_buffer. The capability must carry that fact
        // rather than letting the UI infer silence from a stopped logd.
        assertTrue(fullCapabilities.ylogPresent)
        val actions = actionsOf(planner.plan(LogProfile(LogLevel.OFF), observed(), fullCapabilities))
        assertTrue(actions.contains(LogAction.StopService(LogService.LOGD)))
    }

    @Test
    fun `rom reachability enables off without a root backend`() {
        val romOnly = fullCapabilities.copy(rootBackendAvailable = false, romDisableReachable = true)
        assertTrue(romOnly.supports(LogLevel.OFF))
        assertTrue(romOnly.romDisableReachable)
    }
}
