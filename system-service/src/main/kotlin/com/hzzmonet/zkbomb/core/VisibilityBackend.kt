package com.hzzmonet.zkbomb.core

import android.content.pm.PackageManager
import com.hzzmonet.zkbomb.api.BombResult
import com.hzzmonet.zkbomb.api.VisibilityCallerPolicyParcel
import com.hzzmonet.zkbomb.domain.visibility.CallerKey
import com.hzzmonet.zkbomb.domain.visibility.VisibilityCallerPolicy
import com.hzzmonet.zkbomb.domain.visibility.VisibilityMode
import com.hzzmonet.zkbomb.domain.visibility.VisibilityPolicyBuilder
import com.hzzmonet.zkbomb.domain.visibility.VisibilityPolicyCache
import com.hzzmonet.zkbomb.domain.visibility.VisibilityPolicySnapshot
import com.hzzmonet.zkbomb.domain.validation.ApiV6InputValidator
import java.util.concurrent.atomic.AtomicLong

/**
 * Service-side backend for §12 App Visibility.
 *
 * Responsible for:
 *  - Validating incoming [VisibilityCallerPolicyParcel] entries.
 *  - Maintaining the mutable configuration store.
 *  - Recompiling the [VisibilityPolicySnapshot] and swapping it into
 *    [VisibilityPolicyCache] on every config change (atomically).
 *
 * Never performs any disk/network I/O on the visibility check path.
 * [shouldHidePackage] is the hot path — delegates to [VisibilityPolicyCache].
 *
 * @param packageManager Used to resolve package names for shared-UID expansion.
 * @param bombPackageName The service's own package name, always exempted.
 */
class VisibilityBackend(
    private val packageManager: PackageManager,
    private val bombPackageName: String,
) {

    private val revision = AtomicLong(1L)

    /** Live configuration — mutable on the service thread, rebuilt on every change. */
    private val configuredPolicies =
        mutableMapOf<CallerKey, VisibilityCallerPolicy>()

    /** The in-memory cache queried on every shouldHidePackage call. */
    val cache = VisibilityPolicyCache()

    // ---- Write operations --------------------------------------------------

    /**
     * Set or replace the visibility policy for the caller described by [parcel].
     *
     * Validates:
     * - [VisibilityCallerPolicyParcel.mode] is a known [VisibilityMode] name.
     * - Every package name in the set has a valid shape.
     * - [callingUid] is an app UID whose encoded user matches [userId].
     *
     * @return [BombResult.success] on success, or the appropriate error result.
     */
    @Synchronized
    fun setPolicy(parcel: VisibilityCallerPolicyParcel): BombResult {
        ApiV6InputValidator.uidUserViolation(parcel.callingUid, parcel.userId)?.let {
            return BombResult.invalidArgument(it)
        }
        ApiV6InputValidator.enumNameViolation("mode", parcel.mode)?.let {
            return BombResult.invalidArgument(it)
        }
        ApiV6InputValidator.visibilityPackagesViolation(parcel.packageNames)?.let {
            return BombResult.invalidArgument(it)
        }
        val domain = parcel.toDomain()
            ?: return BombResult.invalidArgument("mode is not a known VisibilityMode name: ${parcel.mode}")

        // Expand shared UIDs for all packages in the set.
        val sharedUidMap = buildSharedUidMap(parcel.packageNames)

        val enriched = domain.copy(sharedUidPackages = sharedUidMap)
        if (enriched.caller !in configuredPolicies && configuredPolicies.size >= MAX_POLICIES) {
            return BombResult.invalidArgument("visibility policy limit reached")
        }
        configuredPolicies[enriched.caller] = enriched
        rebuildAndSwap()
        return BombResult.success()
    }

    /**
     * Remove the policy for the given (callingUid, userId) pair.
     */
    @Synchronized
    fun clearPolicy(callingUid: Int, userId: Int): BombResult {
        ApiV6InputValidator.uidUserViolation(callingUid, userId)?.let {
            return BombResult.invalidArgument(it)
        }
        configuredPolicies.remove(CallerKey(callingUid, userId))
        rebuildAndSwap()
        return BombResult.success()
    }

    // ---- Hot-path read (called for every PM query) -------------------------

    /**
     * Returns true when [targetPackage] should appear as not-installed to the
     * given [callingUid]/[userId].
     *
     * O(1) — no locks, no allocations beyond the lookup.
     */
    fun shouldHidePackage(callingUid: Int, userId: Int, targetPackage: String): Boolean =
        cache.shouldHidePackage(callingUid, userId, targetPackage)

    // ---- Internal ----------------------------------------------------------

    /**
     * Recompile the entire configuration into an immutable snapshot and swap it
     * into the cache atomically.
     *
     * Called only from @Synchronized write operations, so there is no concurrent
     * mutation risk here, but the resulting snapshot is handed to a lock-free
     * AtomicReference, which is fine — it is immutable.
     */
    private fun rebuildAndSwap() {
        val newRevision = revision.incrementAndGet()
        val builder = VisibilityPolicyBuilder(
            revision = newRevision,
            bombPackages = setOf(bombPackageName),
        )
        for (policy in configuredPolicies.values) {
            builder.addCallerPolicy(policy)
        }
        cache.swap(builder.build())
    }

    /**
     * For each package name, find all other packages sharing its UID and build
     * the sharedUidPackages map needed by [VisibilityCallerPolicy].
     *
     * Uses [PackageManager.getPackagesForUid] — only called on config writes,
     * never on the hot visibility-check path.
     */
    private fun buildSharedUidMap(packageNames: List<String>): Map<Int, Set<String>> {
        val result = mutableMapOf<Int, MutableSet<String>>()
        for (pkgName in packageNames) {
            val uid = try {
                packageManager.getApplicationInfo(pkgName, 0).uid
            } catch (_: PackageManager.NameNotFoundException) {
                continue // Package not installed — skip, don't fabricate.
            }
            result.getOrPut(uid) { mutableSetOf() }.addAll(
                packageManager.getPackagesForUid(uid)?.toSet() ?: emptySet()
            )
        }
        return result.mapValues { it.value.toSet() }
    }

    private companion object {
        const val MAX_POLICIES = 2_048
    }
}
