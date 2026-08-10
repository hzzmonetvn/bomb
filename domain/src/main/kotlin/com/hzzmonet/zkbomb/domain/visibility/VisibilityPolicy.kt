package com.hzzmonet.zkbomb.domain.visibility

/**
 * Master plan §12 — App Visibility (HMA-style caller-aware package hiding).
 *
 * This is NOT Android 11 package visibility permission management. It does not
 * revoke QUERY_ALL_PACKAGES. Instead it makes specific configured packages
 * "not found" for a given calling UID while every other caller continues to
 * see the real package.
 */

// ---------------------------------------------------------------------------
// Mode
// ---------------------------------------------------------------------------

/**
 * Visibility policy mode for a single caller/package association.
 *
 * BLACKLIST — listed packages are hidden from the caller.
 * WHITELIST — only listed packages are visible to the caller; all others are
 *             hidden, subject to mandatory system/framework exemptions.
 */
enum class VisibilityMode { BLACKLIST, WHITELIST }

// ---------------------------------------------------------------------------
// Domain models
// ---------------------------------------------------------------------------

/**
 * Identifies a calling entity by both UID and userId.
 *
 * Policy identity must include userId per §12 "Multi-user". A UID can be the
 * same numeric value in two different user-spaces (each user gets their own
 * 100000-range), so keying only by UID produces wrong answers on multi-user.
 */
data class CallerKey(
    val uid: Int,
    val userId: Int,
)

/**
 * A single caller-scoped visibility policy.
 *
 * When [mode] is BLACKLIST, [packageSet] lists the packages hidden from [caller].
 * When [mode] is WHITELIST, [packageSet] lists the only packages visible to [caller].
 *
 * [sharedUidPackages] maps each UID that appears in [packageSet] to ALL packages
 * sharing that UID, so the engine can handle shared-UID groups consistently.
 */
data class VisibilityCallerPolicy(
    val caller: CallerKey,
    val mode: VisibilityMode,
    val packageSet: Set<String>,
    /** UID -> all packages sharing that UID, for group-consistent decisions. */
    val sharedUidPackages: Map<Int, Set<String>> = emptyMap(),
)

// ---------------------------------------------------------------------------
// Snapshot
// ---------------------------------------------------------------------------

/**
 * Immutable, compiled in-memory visibility policy snapshot.
 *
 * Master plan §12 — "Package Manager is a hot path. Never perform Room/disk/
 * network/remote Binder I/O for each visibility check. Compile configuration
 * into an immutable in-memory snapshot."
 *
 * This snapshot is computed once from configuration and replaced atomically via
 * [VisibilityPolicyCache] when config changes. Every call to [shouldHidePackage]
 * is O(1) hash-map lookups on pre-computed data structures.
 *
 * @param revision Monotonically incremented each time the snapshot is rebuilt.
 *   Lets callers detect stale references after a reload.
 * @param callerPolicies Per-(uid, userId) policy; indexed for fast lookup.
 * @param trustedExemptions Packages that are NEVER hidden from any caller.
 *   Bomb itself and system_server always land here. Centralized per §12:
 *   "Centralize exemptions rather than sprinkling UID checks throughout."
 */
class VisibilityPolicySnapshot(
    val revision: Long,
    private val callerPolicies: Map<CallerKey, VisibilityCallerPolicy>,
    val trustedExemptions: Set<String>,
) {

    /**
     * Core hiding predicate — §12's "Core policy concept".
     *
     * Returns true when the target package should be hidden from the given
     * (callingUid, userId) pair. Returns false when:
     *   - the target is a trusted exemption (Bomb, system_server, etc.)
     *   - no policy is configured for this caller
     *   - the caller is callerUid 1000 (system_server) — see §12 Exemptions
     *
     * Shared-UID handling: if two packages share a UID and one is in the hidden
     * set, the whole group is treated consistently rather than one package being
     * hidden and its UID-mate still visible. §12 "Do not assume one UID always
     * maps to exactly one package."
     */
    fun shouldHidePackage(callingUid: Int, userId: Int, targetPackage: String): Boolean {
        // Exemption 1: trusted system packages are never hidden.
        if (targetPackage in trustedExemptions) return false

        // Exemption 2: system_server (UID 1000) must never be filtered.
        // §12: "Do not accidentally apply user visibility filtering to internal
        // system_server package-management operations."
        if (callingUid == SYSTEM_SERVER_UID) return false

        val key = CallerKey(callingUid, userId)
        val policy = callerPolicies[key] ?: return false

        return when (policy.mode) {
            VisibilityMode.BLACKLIST -> isInBlacklist(policy, targetPackage)
            VisibilityMode.WHITELIST -> !isInWhitelist(policy, targetPackage)
        }
    }

    /**
     * True when [targetPackage] is in the blacklist, considering shared-UID
     * expansion: if the policy lists `com.foo.app` and `com.foo.app:push` shares
     * the same UID, both are hidden.
     */
    private fun isInBlacklist(
        policy: VisibilityCallerPolicy,
        targetPackage: String,
    ): Boolean {
        if (targetPackage in policy.packageSet) return true
        // Check if any package in the same UID group is blacklisted.
        val uidGroup = uidGroupFor(policy, targetPackage)
        return uidGroup != null && uidGroup.any { it in policy.packageSet }
    }

    /**
     * True when [targetPackage] is in the whitelist, considering shared-UID
     * expansion: if a package shares a UID with a whitelisted package, it too
     * must be visible or the app cannot function.
     */
    private fun isInWhitelist(
        policy: VisibilityCallerPolicy,
        targetPackage: String,
    ): Boolean {
        if (targetPackage in policy.packageSet) return true
        val uidGroup = uidGroupFor(policy, targetPackage)
        return uidGroup != null && uidGroup.any { it in policy.packageSet }
    }

    private fun uidGroupFor(
        policy: VisibilityCallerPolicy,
        targetPackage: String,
    ): Set<String>? {
        // Find which UID the target belongs to (if tracked in this policy).
        for ((_, packages) in policy.sharedUidPackages) {
            if (targetPackage in packages) return packages
        }
        return null
    }

    /** True when the snapshot contains no caller policies. */
    val isEmpty: Boolean get() = callerPolicies.isEmpty()

    companion object {
        /**
         * UID 1000 — the system_server process.
         * Never subject to visibility filtering per §12.
         */
        const val SYSTEM_SERVER_UID = 1000

        /**
         * A snapshot that hides nothing. Used before any config is loaded.
         */
        val EMPTY = VisibilityPolicySnapshot(
            revision = 0L,
            callerPolicies = emptyMap(),
            trustedExemptions = emptySet(),
        )
    }
}

// ---------------------------------------------------------------------------
// Builder
// ---------------------------------------------------------------------------

/**
 * Builds a [VisibilityPolicySnapshot] from raw configuration entries.
 *
 * This is the compile step: raw domain config -> normalized, de-duplicated,
 * conflict-checked, indexed snapshot ready for O(1) per-query evaluation.
 */
class VisibilityPolicyBuilder(
    private val revision: Long,
    private val bombPackages: Set<String>,
) {
    private val callerPolicies = mutableMapOf<CallerKey, VisibilityCallerPolicy>()
    private val additionalExemptions = mutableSetOf<String>()

    /**
     * Register a policy for a specific caller.
     *
     * §12 Conflict handling: if the caller already has a policy and the modes
     * differ, we normalize by merging the package sets under the more permissive
     * WHITELIST mode (whitelist overrides blacklist to avoid accidentally hiding
     * something the user explicitly allowed).
     */
    fun addCallerPolicy(policy: VisibilityCallerPolicy): VisibilityPolicyBuilder = apply {
        val existing = callerPolicies[policy.caller]
        callerPolicies[policy.caller] = if (existing == null) {
            policy
        } else {
            resolveConflict(existing, policy)
        }
    }

    fun addExemption(packageName: String): VisibilityPolicyBuilder = apply {
        additionalExemptions.add(packageName)
    }

    fun build(): VisibilityPolicySnapshot {
        val exemptions = bombPackages + additionalExemptions + MANDATORY_SYSTEM_EXEMPTIONS
        return VisibilityPolicySnapshot(
            revision = revision,
            callerPolicies = callerPolicies.toMap(),
            trustedExemptions = exemptions,
        )
    }

    /**
     * §12: "Detect conflicting policies among packages that share a UID and
     * either reject or normalize them consistently."
     * We normalize: WHITELIST + BLACKLIST → WHITELIST with merged set. This
     * ensures a package explicitly allowed is never accidentally hidden.
     */
    private fun resolveConflict(
        existing: VisibilityCallerPolicy,
        incoming: VisibilityCallerPolicy,
    ): VisibilityCallerPolicy {
        // Same mode: merge package sets.
        if (existing.mode == incoming.mode) {
            return existing.copy(
                packageSet = existing.packageSet + incoming.packageSet,
                sharedUidPackages = existing.sharedUidPackages + incoming.sharedUidPackages,
            )
        }
        // Conflicting modes: WHITELIST wins (more permissive, safer default).
        val whitelist = if (existing.mode == VisibilityMode.WHITELIST) existing else incoming
        val blacklist = if (existing.mode == VisibilityMode.BLACKLIST) existing else incoming
        // Packages in the whitelist take priority; blacklisted ones not in the
        // whitelist are dropped to avoid accidental denial.
        return whitelist.copy(
            packageSet = whitelist.packageSet,
            sharedUidPackages = whitelist.sharedUidPackages + blacklist.sharedUidPackages,
        )
    }

    companion object {
        /** Packages that are always exempt regardless of any policy. */
        private val MANDATORY_SYSTEM_EXEMPTIONS = setOf(
            "android",
            "com.android.settings",
            "com.android.systemui",
        )
    }
}
