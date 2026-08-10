package com.hzzmonet.zkbomb.domain.firewall

/**
 * Master plan §18 — Firewall / Network Monitor per-app/UID policy.
 *
 * Per-app/UID policy controls:
 *   - Wi-Fi access
 *   - Mobile data access
 *   - Background data access
 *
 * This is a pure domain model. No Android framework references.
 * The system-service layer translates to NetworkPolicyManager/iptables/netd.
 */

// ---------------------------------------------------------------------------
// Per-app firewall rule
// ---------------------------------------------------------------------------

/**
 * The network access policy for a single application UID.
 *
 * §18: "Per-app/UID policy where supported: Wi-Fi, Mobile data, Roaming,
 * Background data, Screen-off data."
 *
 * All fields default to [NetworkAccess.ALLOW] — no change from platform
 * defaults until explicitly configured.
 *
 * @param uid         Application UID. Must be ≥ 1000 (Android app range).
 * @param userId      User the UID belongs to (multi-user aware).
 * @param wifiAccess  Whether the app can use Wi-Fi.
 * @param mobileAccess Whether the app can use mobile data.
 * @param backgroundAccess Whether background data is allowed (affects all
 *   interfaces when the app is not in the foreground).
 * @param note        Optional human-readable note stored with the rule.
 */
data class FirewallRule(
    val uid: Int,
    val userId: Int,
    val wifiAccess: NetworkAccess = NetworkAccess.ALLOW,
    val mobileAccess: NetworkAccess = NetworkAccess.ALLOW,
    val backgroundAccess: NetworkAccess = NetworkAccess.ALLOW,
    val note: String? = null,
) {
    init {
        require(uid >= MIN_APP_UID) {
            "uid must be >= $MIN_APP_UID (app range), was $uid"
        }
        require(userId >= 0) { "userId must be non-negative, was $userId" }
    }

    /** True if the rule deviates from the default allow-everything policy. */
    val isRestricted: Boolean
        get() = wifiAccess == NetworkAccess.DENY ||
            mobileAccess == NetworkAccess.DENY ||
            backgroundAccess == NetworkAccess.DENY

    companion object {
        /** Lowest UID assigned to regular Android apps. */
        const val MIN_APP_UID = 1000

        /** A rule that lifts all restrictions on the given UID. */
        fun allowAll(uid: Int, userId: Int) = FirewallRule(uid, userId)
    }
}

/**
 * Network access state for a single interface category.
 */
enum class NetworkAccess {
    /** Traffic is allowed (platform default). */
    ALLOW,

    /** Traffic is denied on this interface/state for this UID. */
    DENY,
}

// ---------------------------------------------------------------------------
// Firewall policy snapshot
// ---------------------------------------------------------------------------

/**
 * Compiled, immutable map of all active [FirewallRule] entries.
 *
 * Same design philosophy as [VisibilityPolicySnapshot] and [SettingsPolicySnapshot]:
 * compile once from config, query at O(1), swap atomically on change.
 */
class FirewallPolicySnapshot(
    val revision: Long,
    private val rules: Map<FirewallRuleKey, FirewallRule>,
) {

    /**
     * Return the configured rule for [uid]/[userId], or the default allow-all
     * rule if none is configured.
     */
    fun ruleFor(uid: Int, userId: Int): FirewallRule =
        rules[FirewallRuleKey(uid, userId)] ?: FirewallRule.allowAll(uid, userId)

    /** All UIDs that have a non-default rule, for enumeration. */
    fun restrictedUids(): Set<FirewallRuleKey> =
        rules.filterValues { it.isRestricted }.keys

    val isEmpty: Boolean get() = rules.isEmpty()

    companion object {
        val EMPTY = FirewallPolicySnapshot(revision = 0L, rules = emptyMap())
    }
}

/**
 * Composite key for a firewall rule: (UID, userId) pair.
 */
data class FirewallRuleKey(val uid: Int, val userId: Int)

// ---------------------------------------------------------------------------
// Builder
// ---------------------------------------------------------------------------

/**
 * Builds a [FirewallPolicySnapshot] from a list of [FirewallRule] entries.
 *
 * Rules are keyed by (uid, userId). Last write wins on duplicates.
 */
class FirewallPolicyBuilder(private val revision: Long) {

    private val rules = mutableMapOf<FirewallRuleKey, FirewallRule>()

    fun addRule(rule: FirewallRule): FirewallPolicyBuilder = apply {
        rules[FirewallRuleKey(rule.uid, rule.userId)] = rule
    }

    fun removeRule(uid: Int, userId: Int): FirewallPolicyBuilder = apply {
        rules.remove(FirewallRuleKey(uid, userId))
    }

    fun build(): FirewallPolicySnapshot = FirewallPolicySnapshot(
        revision = revision,
        rules = rules.toMap(),
    )
}
