package com.hzzmonet.zkbomb.core

/** Why a Binder caller was refused. */
enum class DenialReason {
    /** The calling UID maps to no package — an isolated process, or a bare UID. */
    UNKNOWN_UID,

    /** A package in the calling UID is not on Bomb's allowlist. */
    PACKAGE_NOT_ALLOWED,

    /** The caller does not carry Bomb's signing certificate. */
    SIGNATURE_MISMATCH,

    /**
     * The caller is `root` or `system`.
     *
     * Refused deliberately rather than waved through. A privileged shell can do
     * anything Bomb can do without going through Bomb, so allowing it here buys
     * nothing and creates an unaudited path into every typed API on the
     * interface — exactly the "no combination of legitimate calls yields an
     * unintended effect" rule this contract is supposed to hold.
     */
    PRIVILEGED_SHELL,
}

sealed interface CallerVerdict {
    data object Allowed : CallerVerdict
    data class Denied(val reason: DenialReason) : CallerVerdict
}

/**
 * Decides whether a Binder caller may use the Bomb service.
 *
 * Deliberately pure: the Android-specific lookups (UID → packages, signature
 * comparison) happen in [CallerValidator] and their *results* are passed in
 * here. That is what makes shared-UID handling and the privileged-shell rule
 * testable without a device, and those are the two cases most likely to be
 * wrong.
 */
class CallerPolicy(
    private val selfUid: Int,
    private val allowedPackages: Set<String>,
) {

    /**
     * @param callingUid `Binder.getCallingUid()`
     * @param packagesForUid every package sharing that UID, as
     *   `PackageManager.getPackagesForUid` returns them — plural, because a
     *   shared UID really can hold several.
     * @param signatureMatches whether the caller carries Bomb's signing certificate
     */
    fun evaluate(
        callingUid: Int,
        packagesForUid: List<String>?,
        signatureMatches: Boolean,
    ): CallerVerdict {
        // Bomb's own process. Checked first and cheaply, because it is every
        // call in the common case.
        if (callingUid == selfUid) return CallerVerdict.Allowed

        if (callingUid == ROOT_UID || callingUid == SYSTEM_UID) {
            return CallerVerdict.Denied(DenialReason.PRIVILEGED_SHELL)
        }

        if (packagesForUid.isNullOrEmpty()) {
            return CallerVerdict.Denied(DenialReason.UNKNOWN_UID)
        }

        // Every package in the UID must be allowed, not merely one of them.
        // A shared UID means any package in it can originate the call, so an
        // "any match" rule would let an unrelated package ride in on a
        // permitted one's UID.
        if (!allowedPackages.containsAll(packagesForUid)) {
            return CallerVerdict.Denied(DenialReason.PACKAGE_NOT_ALLOWED)
        }

        // Checked last: a package name is a claim, a signature is proof. An
        // attacker can install a package with any name they like.
        if (!signatureMatches) {
            return CallerVerdict.Denied(DenialReason.SIGNATURE_MISMATCH)
        }

        return CallerVerdict.Allowed
    }

    private companion object {
        const val ROOT_UID = 0
        const val SYSTEM_UID = 1000
    }
}
