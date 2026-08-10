package com.hzzmonet.zkbomb.core

import com.hzzmonet.zkbomb.api.BombResult
import com.hzzmonet.zkbomb.api.FirewallRuleParcel
import com.hzzmonet.zkbomb.domain.firewall.FirewallPolicyBuilder
import com.hzzmonet.zkbomb.domain.firewall.FirewallPolicySnapshot
import com.hzzmonet.zkbomb.domain.firewall.FirewallRule
import com.hzzmonet.zkbomb.domain.firewall.FirewallRuleKey
import com.hzzmonet.zkbomb.domain.validation.ApiV6InputValidator
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Service-side backend for §18 Firewall per-app/UID policy.
 *
 * Manages the per-UID rule store and recompiles to an immutable
 * [FirewallPolicySnapshot] on every write.
 *
 * On a real ROM, [applyToSystem] would call into [android.net.NetworkPolicyManager]
 * or equivalent privileged API to enforce the rule. The structure is kept
 * separate from the enforcement call so both are testable independently.
 *
 * §3.3 — Firewall is a capability. [setRule] must only be called after confirming
 * [FIREWALL] is [CapabilityState.SUPPORTED].
 */
class FirewallBackend {

    private val revision = AtomicLong(1L)
    private val _snapshot = AtomicReference(FirewallPolicySnapshot.EMPTY)
    private val rules = mutableMapOf<FirewallRuleKey, FirewallRule>()

    // ---- Read --------------------------------------------------------------

    /**
     * The current active policy snapshot.
     * Lock-free read — backed by [AtomicReference].
     */
    val snapshot: FirewallPolicySnapshot get() = _snapshot.get()

    // ---- Write operations --------------------------------------------------

    /**
     * Set or replace the firewall rule for one app UID.
     *
     * Validates:
     * - All [NetworkAccess] names are valid.
     * - UID is in the app range and its encoded user matches userId.
     * - userId is within the bounded API range.
     *
     * After validation, applies the rule to the system (stub — ROM integration
     * point) and updates the in-memory snapshot.
     */
    @Synchronized
    fun setRule(parcel: FirewallRuleParcel): BombResult {
        ApiV6InputValidator.uidUserViolation(parcel.uid, parcel.userId)?.let {
            return BombResult.invalidArgument(it)
        }
        ApiV6InputValidator.firewallNoteViolation(parcel.note)?.let {
            return BombResult.invalidArgument(it)
        }
        val domain = parcel.toDomain()
            ?: return BombResult.invalidArgument(
                "Invalid NetworkAccess names: wifi='${parcel.wifiAccess}', " +
                    "mobile='${parcel.mobileAccess}', background='${parcel.backgroundAccess}'"
            )

        // Domain constructor already validates uid >= 1000 and userId >= 0.
        // toDomain() wraps with runCatching so IllegalArgumentException becomes null.

        val ruleKey = FirewallRuleKey(domain.uid, domain.userId)
        if (ruleKey !in rules && rules.size >= MAX_RULES) {
            return BombResult.invalidArgument("firewall rule limit reached")
        }
        rules[ruleKey] = domain
        rebuildSnapshot()

        // ROM integration point: apply to NetworkPolicyManager / iptables / netd.
        // In non-ROM mode this records the policy for display and future enforcement.
        applyToSystem(domain)

        return BombResult.success()
    }

    /**
     * Remove the firewall rule for (uid, userId), restoring platform defaults
     * (all ALLOW).
     */
    @Synchronized
    fun clearRule(uid: Int, userId: Int): BombResult {
        ApiV6InputValidator.uidUserViolation(uid, userId)?.let {
            return BombResult.invalidArgument(it)
        }
        rules.remove(FirewallRuleKey(uid, userId))
        rebuildSnapshot()
        return BombResult.success()
    }

    // ---- Rebuild -----------------------------------------------------------

    private fun rebuildSnapshot() {
        val builder = FirewallPolicyBuilder(revision.incrementAndGet())
        for (rule in rules.values) builder.addRule(rule)
        _snapshot.set(builder.build())
    }

    /**
     * ROM integration point — apply a rule to the network stack.
     *
     * In a real ROM build this would call:
     *   NetworkPolicyManager.setUidPolicy(uid, policy) or
     *   equivalent netd/iptables commands via the privileged daemon.
     *
     * The stub here is intentional: the service layer must compile and the
     * domain model tests must pass without needing a live ROM.
     */
    private fun applyToSystem(rule: FirewallRule) {
        // ROM integration: NetworkPolicyManager / netd calls go here.
        // No-op in non-ROM mode — policy is recorded in-memory for display.
    }

    private companion object {
        const val MAX_RULES = 4_096
    }
}
