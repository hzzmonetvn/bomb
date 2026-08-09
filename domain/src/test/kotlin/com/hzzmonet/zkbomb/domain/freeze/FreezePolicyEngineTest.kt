package com.hzzmonet.zkbomb.domain.freeze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The test targets named in `docs/BOMB_PLAN.md` §4.3: exclusion rejection with
 * typed reasons, mechanism selection, and — the one that catches the classic bug
 * — proof that the four platform state bits are never conflated.
 */
class FreezePolicyEngineTest {

    private val allCapabilities = FreezeCapabilities(
        platformFreezer = true,
        suspend = true,
        hide = true,
        disable = true,
    )

    private val protected = ProtectedPackages(
        self = "com.hzzmonet.zkbomb",
        systemUi = "com.android.systemui",
        launcher = "com.miui.home",
        currentIme = "com.google.android.inputmethod.latin",
        framework = ProtectedPackages.CORE_FRAMEWORK,
        vendorCritical = setOf("com.miui.securitycenter"),
        deviceAdmins = setOf("com.corp.mdm"),
        userExcluded = setOf("com.example.keepalive"),
    )

    private fun engine(capabilities: FreezeCapabilities = allCapabilities) =
        FreezePolicyEngine(capabilities, protected)

    private fun state(
        enabled: Boolean = true,
        hidden: Boolean = false,
        suspended: Boolean = false,
        stopped: Boolean = false,
        allProcessesFrozen: Boolean = false,
        hasRunningProcesses: Boolean = true,
    ) = ObservedFreezeState(
        enabled = enabled,
        hidden = hidden,
        suspended = suspended,
        stopped = stopped,
        allProcessesFrozen = allProcessesFrozen,
        hasRunningProcesses = hasRunningProcesses,
    )

    private fun request(
        packageName: String = "com.example.app",
        userId: Int = 0,
        targetMode: FreezeMode,
        observed: ObservedFreezeState = state(),
    ) = FreezeRequest(packageName, userId, targetMode, observed)

    // ---- the four bits are four different things -----------------------------

    @Test
    fun `disabled outranks every other bit`() {
        val resolved = state(enabled = false, suspended = true, hidden = true).resolveMode()
        assertEquals(FreezeMode.DISABLED, resolved)
    }

    @Test
    fun `suspended resolves to deep freeze`() {
        assertEquals(FreezeMode.DEEP_FREEZE, state(suspended = true).resolveMode())
    }

    @Test
    fun `hidden resolves to deep freeze`() {
        assertEquals(FreezeMode.DEEP_FREEZE, state(hidden = true).resolveMode())
    }

    @Test
    fun `stopped alone is not a freeze state`() {
        // FLAG_STOPPED is force-stop residue. Treating it as a mode would report
        // every app not opened since boot as frozen.
        assertEquals(FreezeMode.NORMAL, state(stopped = true).resolveMode())
    }

    @Test
    fun `frozen processes resolve to soft freeze`() {
        val resolved = state(hasRunningProcesses = true, allProcessesFrozen = true).resolveMode()
        assertEquals(FreezeMode.SOFT_FREEZE, resolved)
    }

    @Test
    fun `not running is normal rather than soft frozen`() {
        val resolved = state(hasRunningProcesses = false, allProcessesFrozen = true).resolveMode()
        assertEquals(FreezeMode.NORMAL, resolved)
    }

    @Test
    fun `partially frozen package is reported as leaking`() {
        // The ":push still runs while the app reads as frozen" risk.
        assertTrue(state(hasRunningProcesses = true, allProcessesFrozen = false).hasUnfrozenProcesses())
    }

    @Test
    fun `package with no processes is not leaking`() {
        assertFalse(state(hasRunningProcesses = false, allProcessesFrozen = false).hasUnfrozenProcesses())
    }

    // ---- exclusions ----------------------------------------------------------

    @Test
    fun `bomb refuses to freeze itself`() {
        val decision = engine().plan(
            request(packageName = "com.hzzmonet.zkbomb", targetMode = FreezeMode.DEEP_FREEZE),
        )
        assertEquals(FreezeDecision.Excluded(ExclusionReason.BOMB_ITSELF), decision)
    }

    @Test
    fun `system ui is excluded`() {
        val decision = engine().plan(
            request(packageName = "com.android.systemui", targetMode = FreezeMode.SOFT_FREEZE),
        )
        assertEquals(FreezeDecision.Excluded(ExclusionReason.SYSTEM_UI), decision)
    }

    @Test
    fun `resolved launcher is excluded`() {
        val decision = engine().plan(
            request(packageName = "com.miui.home", targetMode = FreezeMode.DEEP_FREEZE),
        )
        assertEquals(FreezeDecision.Excluded(ExclusionReason.LAUNCHER), decision)
    }

    @Test
    fun `current ime is excluded`() {
        val decision = engine().plan(
            request(
                packageName = "com.google.android.inputmethod.latin",
                targetMode = FreezeMode.DEEP_FREEZE,
            ),
        )
        assertEquals(FreezeDecision.Excluded(ExclusionReason.CURRENT_IME), decision)
    }

    @Test
    fun `framework package is excluded`() {
        val decision = engine().plan(request(packageName = "android", targetMode = FreezeMode.DISABLED))
        assertEquals(FreezeDecision.Excluded(ExclusionReason.FRAMEWORK), decision)
    }

    @Test
    fun `vendor critical package is excluded`() {
        val decision = engine().plan(
            request(packageName = "com.miui.securitycenter", targetMode = FreezeMode.DEEP_FREEZE),
        )
        assertEquals(FreezeDecision.Excluded(ExclusionReason.VENDOR_CRITICAL), decision)
    }

    @Test
    fun `device admin is refused before the platform refuses it`() {
        val decision = engine().plan(
            request(packageName = "com.corp.mdm", targetMode = FreezeMode.DEEP_FREEZE),
        )
        assertEquals(FreezeDecision.Excluded(ExclusionReason.DEVICE_ADMIN), decision)
    }

    @Test
    fun `user exclusion is honoured`() {
        val decision = engine().plan(
            request(packageName = "com.example.keepalive", targetMode = FreezeMode.SOFT_FREEZE),
        )
        assertEquals(FreezeDecision.Excluded(ExclusionReason.USER_EXCLUDED), decision)
    }

    @Test
    fun `an excluded package can always be unfrozen`() {
        // Recovery must never be blocked by protection, or a protected package
        // that somehow got frozen would be unrecoverable from inside Bomb.
        val decision = engine().plan(
            request(
                packageName = "com.miui.home",
                targetMode = FreezeMode.NORMAL,
                observed = state(suspended = true),
            ),
        )
        assertEquals(
            FreezeDecision.Execute(
                listOf(FreezeAction.SetMechanism(FreezeMechanism.SUSPEND, engaged = false)),
            ),
            decision,
        )
    }

    // ---- mechanism selection -------------------------------------------------

    @Test
    fun `deep freeze prefers suspend over hide`() {
        val decision = engine().plan(request(targetMode = FreezeMode.DEEP_FREEZE))
        assertEquals(
            FreezeDecision.Execute(
                listOf(FreezeAction.SetMechanism(FreezeMechanism.SUSPEND, engaged = true)),
            ),
            decision,
        )
    }

    @Test
    fun `deep freeze falls back to hide when suspend is unsupported`() {
        val decision = engine(allCapabilities.copy(suspend = false))
            .plan(request(targetMode = FreezeMode.DEEP_FREEZE))
        assertEquals(
            FreezeDecision.Execute(
                listOf(FreezeAction.SetMechanism(FreezeMechanism.HIDE, engaged = true)),
            ),
            decision,
        )
    }

    @Test
    fun `deep freeze is unsupported when neither mechanism exists`() {
        val decision = engine(allCapabilities.copy(suspend = false, hide = false))
            .plan(request(targetMode = FreezeMode.DEEP_FREEZE))
        assertEquals(
            FreezeDecision.Unsupported(
                FreezeMode.DEEP_FREEZE,
                listOf(FreezeMechanism.SUSPEND, FreezeMechanism.HIDE),
            ),
            decision,
        )
    }

    @Test
    fun `soft freeze uses the platform freezer`() {
        val decision = engine().plan(request(targetMode = FreezeMode.SOFT_FREEZE))
        assertEquals(
            FreezeDecision.Execute(
                listOf(FreezeAction.SetMechanism(FreezeMechanism.PLATFORM_FREEZER, engaged = true)),
            ),
            decision,
        )
    }

    @Test
    fun `soft freeze without a running process reports nothing to freeze`() {
        val decision = engine().plan(
            request(
                targetMode = FreezeMode.SOFT_FREEZE,
                observed = state(hasRunningProcesses = false),
            ),
        )
        assertEquals(FreezeDecision.NothingToFreeze, decision)
    }

    @Test
    fun `soft freeze is unsupported without the platform freezer`() {
        val decision = engine(allCapabilities.copy(platformFreezer = false))
            .plan(request(targetMode = FreezeMode.SOFT_FREEZE))
        assertEquals(
            FreezeDecision.Unsupported(
                FreezeMode.SOFT_FREEZE,
                listOf(FreezeMechanism.PLATFORM_FREEZER),
            ),
            decision,
        )
    }

    @Test
    fun `disable is preceded by a force stop when processes are running`() {
        val decision = engine().plan(
            request(targetMode = FreezeMode.DISABLED, observed = state(hasRunningProcesses = true)),
        )
        assertEquals(
            FreezeDecision.Execute(
                listOf(
                    FreezeAction.ForceStop,
                    FreezeAction.SetMechanism(FreezeMechanism.DISABLE, engaged = true),
                ),
            ),
            decision,
        )
    }

    @Test
    fun `disable skips the force stop when nothing is running`() {
        val decision = engine().plan(
            request(targetMode = FreezeMode.DISABLED, observed = state(hasRunningProcesses = false)),
        )
        assertEquals(
            FreezeDecision.Execute(
                listOf(FreezeAction.SetMechanism(FreezeMechanism.DISABLE, engaged = true)),
            ),
            decision,
        )
    }

    // ---- transitions ---------------------------------------------------------

    @Test
    fun `already in the requested mode does nothing`() {
        val decision = engine().plan(
            request(targetMode = FreezeMode.DEEP_FREEZE, observed = state(suspended = true)),
        )
        assertEquals(FreezeDecision.AlreadyInMode(FreezeMode.DEEP_FREEZE), decision)
    }

    @Test
    fun `unfreezing releases every engaged mechanism strongest first`() {
        val decision = engine().plan(
            request(
                targetMode = FreezeMode.NORMAL,
                observed = state(
                    enabled = false,
                    hidden = true,
                    suspended = true,
                    hasRunningProcesses = true,
                    allProcessesFrozen = true,
                ),
            ),
        )
        assertEquals(
            FreezeDecision.Execute(
                listOf(
                    FreezeAction.SetMechanism(FreezeMechanism.DISABLE, engaged = false),
                    FreezeAction.SetMechanism(FreezeMechanism.HIDE, engaged = false),
                    FreezeAction.SetMechanism(FreezeMechanism.SUSPEND, engaged = false),
                    FreezeAction.SetMechanism(FreezeMechanism.PLATFORM_FREEZER, engaged = false),
                ),
            ),
            decision,
        )
    }

    @Test
    fun `moving from disabled to deep freeze re-enables before suspending`() {
        val decision = engine().plan(
            request(
                targetMode = FreezeMode.DEEP_FREEZE,
                observed = state(enabled = false, hasRunningProcesses = false),
            ),
        )
        assertEquals(
            FreezeDecision.Execute(
                listOf(
                    FreezeAction.SetMechanism(FreezeMechanism.DISABLE, engaged = false),
                    FreezeAction.SetMechanism(FreezeMechanism.SUSPEND, engaged = true),
                ),
            ),
            decision,
        )
    }

    @Test
    fun `losing a capability makes the release path unsupported rather than silent`() {
        // A suspended package on a device where suspend is no longer permitted
        // cannot be recovered by Bomb; saying so is better than emitting a step
        // that will fail.
        val decision = engine(allCapabilities.copy(suspend = false)).plan(
            request(targetMode = FreezeMode.NORMAL, observed = state(suspended = true)),
        )
        assertEquals(
            FreezeDecision.Unsupported(FreezeMode.NORMAL, listOf(FreezeMechanism.SUSPEND)),
            decision,
        )
    }

    // ---- argument validation -------------------------------------------------

    @Test
    fun `malformed package name is rejected`() {
        val decision = engine().plan(request(packageName = "not a package", targetMode = FreezeMode.SOFT_FREEZE))
        assertEquals(FreezeDecision.Invalid(InvalidReason.MALFORMED_PACKAGE_NAME), decision)
    }

    @Test
    fun `negative user id is rejected`() {
        val decision = engine().plan(request(userId = -1, targetMode = FreezeMode.SOFT_FREEZE))
        assertEquals(FreezeDecision.Invalid(InvalidReason.INVALID_USER_ID), decision)
    }

    @Test
    fun `package name validation accepts and rejects the expected shapes`() {
        assertTrue(PackageNameValidator.isValid("com.example.app"))
        assertTrue(PackageNameValidator.isValid("a.b"))
        assertTrue(PackageNameValidator.isValid("com.example.app_2"))
        assertTrue(PackageNameValidator.isValid("com.example2.app"))
        // The platform package really is a single segment, and it is one Bomb
        // must be able to name in order to report it as protected.
        assertTrue(PackageNameValidator.isValid("android"))

        assertFalse(PackageNameValidator.isValid(""))
        assertFalse(PackageNameValidator.isValid(".com.example"))  // leading dot
        assertFalse(PackageNameValidator.isValid("com.example."))  // trailing dot
        assertFalse(PackageNameValidator.isValid("com..example"))  // empty segment
        assertFalse(PackageNameValidator.isValid("com.2example"))  // segment starts with a digit
        assertFalse(PackageNameValidator.isValid("com.exa mple"))  // space
        assertFalse(PackageNameValidator.isValid("com.example/../etc")) // path traversal
        assertFalse(PackageNameValidator.isValid("com.example;rm -rf")) // shell metacharacters
    }
}
