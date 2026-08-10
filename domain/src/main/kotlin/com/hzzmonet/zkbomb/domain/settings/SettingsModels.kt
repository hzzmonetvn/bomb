package com.hzzmonet.zkbomb.domain.settings

/**
 * Master plan §13 — Per-App Settings Virtualization.
 *
 * Goal: selected applications may observe virtual Android Settings values
 * without changing real global settings.
 *
 * The virtualization layer intercepts Settings reads (System / Secure / Global),
 * checks the caller against assigned profiles, and returns a virtual value if
 * one is configured. Real settings are never mutated.
 *
 * IMPORTANT: This subsystem must NOT be used to spoof hardware-backed attestation,
 * Verified Boot, Play Integrity, or hardware security properties (§13).
 */

// ---------------------------------------------------------------------------
// Enums
// ---------------------------------------------------------------------------

/**
 * Android Settings namespace — exactly the three that the platform exposes.
 * Values cross the AIDL surface as their names, never as ordinals.
 */
enum class SettingsNamespace { SYSTEM, SECURE, GLOBAL }

/**
 * Type-tagged value discriminant.
 *
 * NULL means "return null for this key" — i.e., the key appears absent to
 * the caller. That is a meaningful override, distinct from "no override".
 */
enum class SettingsValueType { STRING, INTEGER, LONG, FLOAT, BOOLEAN, NULL }

// ---------------------------------------------------------------------------
// SettingsOverride — one key override within a profile
// ---------------------------------------------------------------------------

/**
 * A single settings key override within a [SettingsProfile].
 *
 * @param profileId  References the owning [SettingsProfile.id].
 * @param namespace  Which settings table.
 * @param key        An APP_READ key accepted by [SettingsKeyPolicy].
 * @param valueType  Discriminant for parsing [value].
 * @param value      Serialized value; null when [valueType] is NULL.
 * @param enabled    Allows temporarily disabling an override without deleting it.
 */
data class SettingsOverride(
    val profileId: String,
    val namespace: SettingsNamespace,
    val key: String,
    val valueType: SettingsValueType,
    val value: String?,
    val enabled: Boolean,
) {
    init {
        require(key.isNotBlank()) { "settings key must not be blank" }
        require(profileId.isNotBlank()) { "profileId must not be blank" }
        SettingsKeyPolicy.violation(namespace, key)?.let { error(it) }
        SettingsValuePolicy.violation(valueType, value)?.let { error(it) }
    }

    /**
     * Attempts to parse [value] to the declared type.
     *
     * Returns null when:
     * - [valueType] is NULL (key should appear absent), or
     * - [enabled] is false (override is inactive).
     *
     * Throws [IllegalStateException] when the stored value cannot be parsed
     * (indicates data corruption or a downgrade).
     */
    fun resolveTyped(): Any? {
        if (!enabled || valueType == SettingsValueType.NULL) return null
        val raw = requireNotNull(value) { "value is null for non-NULL type $valueType" }
        return when (valueType) {
            SettingsValueType.STRING -> raw
            SettingsValueType.INTEGER -> raw.toIntOrNull()
                ?: error("Cannot parse '$raw' as INTEGER for key $key")
            SettingsValueType.LONG -> raw.toLongOrNull()
                ?: error("Cannot parse '$raw' as LONG for key $key")
            SettingsValueType.FLOAT -> raw.toFloatOrNull()
                ?: error("Cannot parse '$raw' as FLOAT for key $key")
            SettingsValueType.BOOLEAN -> when (raw.lowercase()) {
                "1", "true" -> true
                "0", "false" -> false
                else -> error("Cannot parse '$raw' as BOOLEAN for key $key")
            }
            SettingsValueType.NULL -> null
        }
    }
}

// ---------------------------------------------------------------------------
// SettingsProfile — named set of overrides
// ---------------------------------------------------------------------------

/**
 * A named, reusable collection of [SettingsOverride] entries.
 *
 * §14: "App Visibility and Settings Virtualization should support reusable
 * profiles … Allow one profile to be assigned to multiple apps/users."
 *
 * @param id        Stable unique identifier (UUID or similar).
 * @param name      Human-readable name shown in Bomb UI.
 * @param overrides Key-level overrides indexed by (namespace, key) for O(1) lookup.
 */
data class SettingsProfile(
    val id: String,
    val name: String,
    val overrides: Map<OverrideKey, SettingsOverride> = emptyMap(),
) {
    init {
        require(id.isNotBlank()) { "profile id must not be blank" }
        require(name.isNotBlank()) { "profile name must not be blank" }
    }

    /**
     * Look up the active override for a given namespace/key pair.
     *
     * Returns null (= no override) when:
     * - no override is configured for this key, or
     * - the matching override has [SettingsOverride.enabled] = false.
     */
    fun overrideFor(namespace: SettingsNamespace, key: String): SettingsOverride? {
        val override = overrides[OverrideKey(namespace, key)] ?: return null
        return if (override.enabled) override else null
    }

    companion object {
        /**
         * Build a profile's override map from a flat list, de-duplicating by
         * (namespace, key). Last entry wins on collision.
         */
        fun buildOverrideMap(overrides: List<SettingsOverride>): Map<OverrideKey, SettingsOverride> =
            overrides.associateBy { OverrideKey(it.namespace, it.key) }
    }
}

/**
 * Composite key for a single override slot in a [SettingsProfile].
 */
data class OverrideKey(
    val namespace: SettingsNamespace,
    val key: String,
)

// ---------------------------------------------------------------------------
// SettingsAssignment — binding a profile to an app/user pair
// ---------------------------------------------------------------------------

/**
 * Binds a [SettingsProfile] to a specific (userId, targetPackage) pair.
 *
 * §13 model:
 * ```
 * SettingsAssignment
 * - userId
 * - targetPackage
 * - profileId
 * ```
 *
 * One profile can be assigned to many apps (via multiple assignments), and one
 * app can have only one active profile at a time (last write wins in the
 * [SettingsPolicySnapshot]).
 */
data class SettingsAssignment(
    val userId: Int,
    val targetPackage: String,
    val profileId: String,
) {
    init {
        require(userId >= 0) { "userId must be non-negative, was $userId" }
        require(targetPackage.isNotBlank()) { "targetPackage must not be blank" }
        require(profileId.isNotBlank()) { "profileId must not be blank" }
    }
}

// ---------------------------------------------------------------------------
// Compiled policy snapshot
// ---------------------------------------------------------------------------

/**
 * Immutable in-memory snapshot of all settings virtualization policy.
 *
 * Compiled from [SettingsProfile] + [SettingsAssignment] records once and then
 * queried per-call at O(1) cost — same design principle as [VisibilityPolicySnapshot].
 *
 * The critical path:
 * ```
 * Settings read
 *    |
 * callerUid  →  targetPackage  →  userId
 *    |
 * SettingsPolicySnapshot.resolveOverride(namespace, key, callingUid, userId)
 *    |
 * override?
 *  /      \
 * yes      no
 *  |        |
 * virtual   real
 * ```
 */
class SettingsPolicySnapshot(
    val revision: Long,
    /** profileId → profile, for lookup by assignment. */
    private val profiles: Map<String, SettingsProfile>,
    /** (userId, packageName) → profileId */
    private val assignments: Map<AssignmentKey, String>,
) {

    /**
     * Resolve whether a virtual override exists for the given caller context.
     *
     * @param namespace  Which settings table is being read.
     * @param key        The settings key.
     * @param callingUid The UID of the reading process.
     * @param userId     The user context.
     * @param callerPackages All packages belonging to [callingUid] — needed because
     *   assignments are by package, not UID.
     *
     * @return An [OverrideResult.Overridden] with the virtual value, or
     *   [OverrideResult.PassThrough] to read the real value.
     */
    fun resolveOverride(
        namespace: SettingsNamespace,
        key: String,
        callingUid: Int,
        userId: Int,
        callerPackages: List<String>,
    ): OverrideResult {
        if (callingUid < 0 || callingUid % PER_USER_RANGE < MIN_APP_ID) {
            return OverrideResult.PassThrough
        }
        if (userId < 0 || callingUid / PER_USER_RANGE != userId || callerPackages.isEmpty()) {
            return OverrideResult.PassThrough
        }

        // A shared UID is one security principal. Every package in the UID must
        // be assigned to the same profile or the lookup fails closed.
        var selectedProfileId: String? = null
        for (pkg in callerPackages) {
            val profileId = assignments[AssignmentKey(userId, pkg)] ?: return OverrideResult.PassThrough
            if (selectedProfileId != null && selectedProfileId != profileId) {
                return OverrideResult.PassThrough
            }
            selectedProfileId = profileId
        }
        val resolvedProfileId = selectedProfileId ?: return OverrideResult.PassThrough
        val profile = profiles[resolvedProfileId] ?: return OverrideResult.PassThrough
        val override = profile.overrideFor(namespace, key) ?: return OverrideResult.PassThrough
        return OverrideResult.Overridden(override)
    }

    /** True when no assignments or profiles are configured. */
    val isEmpty: Boolean get() = assignments.isEmpty() || profiles.isEmpty()

    companion object {
        private const val MIN_APP_ID = 10_000
        private const val PER_USER_RANGE = 100_000

        val EMPTY = SettingsPolicySnapshot(
            revision = 0L,
            profiles = emptyMap(),
            assignments = emptyMap(),
        )
    }
}

/**
 * Composite key for an assignment lookup.
 */
data class AssignmentKey(val userId: Int, val packageName: String)

/**
 * Result of [SettingsPolicySnapshot.resolveOverride].
 */
sealed interface OverrideResult {
    /** Use the virtual value from the stored override. */
    data class Overridden(val override: SettingsOverride) : OverrideResult

    /** No override configured — read the real setting. */
    data object PassThrough : OverrideResult
}

// ---------------------------------------------------------------------------
// Snapshot builder
// ---------------------------------------------------------------------------

/**
 * Compiles a set of [SettingsProfile] and [SettingsAssignment] into a
 * [SettingsPolicySnapshot].
 */
class SettingsPolicyBuilder(private val revision: Long) {

    private val profiles = mutableMapOf<String, SettingsProfile>()
    private val assignments = mutableMapOf<AssignmentKey, String>()

    fun addProfile(profile: SettingsProfile): SettingsPolicyBuilder = apply {
        profiles[profile.id] = profile
    }

    fun addAssignment(assignment: SettingsAssignment): SettingsPolicyBuilder = apply {
        // Last write wins — consistent with AIDL "setAssignment" semantics.
        assignments[AssignmentKey(assignment.userId, assignment.targetPackage)] = assignment.profileId
    }

    fun build(): SettingsPolicySnapshot = SettingsPolicySnapshot(
        revision = revision,
        profiles = profiles.toMap(),
        assignments = assignments.toMap(),
    )
}
