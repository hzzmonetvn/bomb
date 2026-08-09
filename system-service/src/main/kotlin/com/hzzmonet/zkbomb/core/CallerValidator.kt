package com.hzzmonet.zkbomb.core

import android.content.pm.PackageManager
import com.hzzmonet.zkbomb.api.BombResult

/**
 * The Android half of caller validation: resolves a UID to packages and checks
 * the signature, then hands both to [CallerPolicy] to decide.
 *
 * The decision itself deliberately lives in [CallerPolicy], where it is testable
 * without a device — shared UIDs and the privileged-shell rule are the parts
 * most likely to be got wrong, and they are also the parts hardest to exercise
 * on real hardware.
 */
class CallerValidator(
    private val packageManager: PackageManager,
    private val policy: CallerPolicy,
) {

    fun evaluate(callingUid: Int): CallerVerdict {
        val packages = packageManager.getPackagesForUid(callingUid)?.toList()
        return policy.evaluate(
            callingUid = callingUid,
            packagesForUid = packages,
            signatureMatches = signatureMatches(callingUid),
        )
    }

    fun isAllowed(callingUid: Int): Boolean = evaluate(callingUid) is CallerVerdict.Allowed

    /**
     * A [BombResult] when the caller is refused, or null when it is allowed.
     *
     * Shaped for `?.let { return it }` at the top of every mutating call, so the
     * check cannot be written in a way that forgets to return.
     */
    fun verdictFor(callingUid: Int): BombResult? = when (val verdict = evaluate(callingUid)) {
        is CallerVerdict.Allowed -> null
        // The reason is named to Bomb's own logs and to the caller, which is
        // safe: a refused caller learns only that it was refused and why, never
        // anything about the device or the request.
        is CallerVerdict.Denied -> BombResult.permissionDenied(verdict.reason.name)
    }

    @Suppress("DEPRECATION")
    private fun signatureMatches(callingUid: Int): Boolean =
        packageManager.checkSignatures(callingUid, android.os.Process.myUid()) ==
            PackageManager.SIGNATURE_MATCH
}
