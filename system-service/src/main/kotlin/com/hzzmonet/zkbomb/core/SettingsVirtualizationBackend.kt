package com.hzzmonet.zkbomb.core

import com.hzzmonet.zkbomb.api.BombResult
import com.hzzmonet.zkbomb.api.SettingsAssignmentParcel
import com.hzzmonet.zkbomb.api.SettingsOverrideParcel
import com.hzzmonet.zkbomb.domain.freeze.PackageNameValidator
import com.hzzmonet.zkbomb.domain.settings.AssignmentKey
import com.hzzmonet.zkbomb.domain.settings.OverrideKey
import com.hzzmonet.zkbomb.domain.settings.OverrideResult
import com.hzzmonet.zkbomb.domain.settings.SettingsAssignment
import com.hzzmonet.zkbomb.domain.settings.SettingsNamespace
import com.hzzmonet.zkbomb.domain.settings.SettingsPolicyBuilder
import com.hzzmonet.zkbomb.domain.settings.SettingsPolicySnapshot
import com.hzzmonet.zkbomb.domain.settings.SettingsProfile
import com.hzzmonet.zkbomb.domain.settings.SettingsValueType
import com.hzzmonet.zkbomb.domain.validation.ApiV6InputValidator
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Service-side backend for §13 Per-App Settings Virtualization.
 *
 * Manages the mutable configuration (profiles, overrides, assignments) and
 * recompiles to an immutable [SettingsPolicySnapshot] on every write.
 *
 * The active snapshot is held in an [AtomicReference] for lock-free reads on
 * the settings interception hot path. Writes are @Synchronized.
 *
 * No disk I/O is performed here — persistence (e.g. via Room) lives above this
 * layer and rehydrates by calling the write operations on service start.
 *
 * §13 constraint: this class must NEVER mutate any real Android Settings value.
 */
class SettingsVirtualizationBackend {

    private val revision = AtomicLong(1L)
    private val _snapshot = AtomicReference(SettingsPolicySnapshot.EMPTY)

    /** Per-profile mutable override store: profileId -> (OverrideKey -> override). */
    private val profileOverrides =
        mutableMapOf<String, MutableMap<OverrideKey, com.hzzmonet.zkbomb.domain.settings.SettingsOverride>>()

    /** Per-profile names. */
    private val profileNames = mutableMapOf<String, String>()

    /** (userId, packageName) -> profileId. */
    private val assignments = mutableMapOf<AssignmentKey, String>()

    // ---- Read (hot path) --------------------------------------------------

    /**
     * Resolve whether a virtual override exists for the given caller context.
     *
     * O(hash-map lookup) — no locks, no I/O.
     * Returns [OverrideResult.PassThrough] when no override is configured.
     */
    fun resolveOverride(
        namespace: SettingsNamespace,
        key: String,
        callingUid: Int,
        userId: Int,
        callerPackages: List<String>,
    ): OverrideResult = _snapshot.get().resolveOverride(
        namespace = namespace,
        key = key,
        callingUid = callingUid,
        userId = userId,
        callerPackages = callerPackages,
    )

    // ---- Profile lifecycle ------------------------------------------------

    /**
     * Create or replace a named settings profile.
     *
     * Does not wipe existing overrides if the profile already exists —
     * `addSettingsOverride` manages those.
     */
    @Synchronized
    fun createProfile(profileId: String, profileName: String): BombResult {
        ApiV6InputValidator.profileIdViolation(profileId)?.let {
            return BombResult.invalidArgument(it)
        }
        ApiV6InputValidator.profileNameViolation(profileName)?.let {
            return BombResult.invalidArgument(it)
        }
        if (profileId !in profileNames && profileNames.size >= MAX_PROFILES) {
            return BombResult.invalidArgument("settings profile limit reached")
        }
        profileNames[profileId] = profileName
        profileOverrides.getOrPut(profileId) { mutableMapOf() }
        rebuildSnapshot()
        return BombResult.success()
    }

    /**
     * Delete a profile and all its overrides.
     *
     * Assignments pointing to this profileId are NOT deleted — they become no-ops
     * because the snapshot won't find the profile. This is intentional: the UI
     * can display "profile deleted" rather than silently losing the assignment.
     */
    @Synchronized
    fun deleteProfile(profileId: String): BombResult {
        ApiV6InputValidator.profileIdViolation(profileId)?.let {
            return BombResult.invalidArgument(it)
        }
        profileNames.remove(profileId)
        profileOverrides.remove(profileId)
        rebuildSnapshot()
        return BombResult.success()
    }

    // ---- Override management ----------------------------------------------

    /**
     * Add or replace one settings override in the given profile.
     *
     * Validates enum names, key format, and value parseability.
     * The real setting is NEVER touched.
     */
    @Synchronized
    fun addOverride(parcel: SettingsOverrideParcel): BombResult {
        ApiV6InputValidator.profileIdViolation(parcel.profileId)?.let {
            return BombResult.invalidArgument(it)
        }
        ApiV6InputValidator.enumNameViolation("namespace", parcel.namespace)?.let {
            return BombResult.invalidArgument(it)
        }
        ApiV6InputValidator.settingsKeyViolation(parcel.key)?.let {
            return BombResult.invalidArgument(it)
        }
        ApiV6InputValidator.enumNameViolation("valueType", parcel.valueType)?.let {
            return BombResult.invalidArgument(it)
        }
        ApiV6InputValidator.settingsValueViolation(parcel.value)?.let {
            return BombResult.invalidArgument(it)
        }
        val domain = parcel.toDomain()
            ?: return BombResult.invalidArgument(
                "Invalid namespace '${parcel.namespace}' or valueType '${parcel.valueType}'"
            )

        // Validate the value can actually be parsed as the declared type.
        runCatching { domain.resolveTyped() }.onFailure {
            return BombResult.invalidArgument(
                "value cannot be parsed as ${parcel.valueType}: ${it.message}",
            )
        }

        if (!profileNames.containsKey(domain.profileId)) {
            return BombResult.invalidArgument("profileId does not exist")
        }
        val overrides = profileOverrides.getOrPut(domain.profileId) { mutableMapOf() }
        val overrideKey = OverrideKey(domain.namespace, domain.key)
        if (overrideKey !in overrides && overrides.size >= MAX_OVERRIDES_PER_PROFILE) {
            return BombResult.invalidArgument("override limit reached for profile")
        }
        overrides[overrideKey] = domain
        rebuildSnapshot()
        return BombResult.success()
    }

    /**
     * Remove one override from a profile.
     */
    @Synchronized
    fun removeOverride(profileId: String, namespace: String, key: String): BombResult {
        ApiV6InputValidator.profileIdViolation(profileId)?.let {
            return BombResult.invalidArgument(it)
        }
        ApiV6InputValidator.enumNameViolation("namespace", namespace)?.let {
            return BombResult.invalidArgument(it)
        }
        val ns = runCatching { SettingsNamespace.valueOf(namespace) }.getOrNull()
            ?: return BombResult.invalidArgument("Unknown namespace: $namespace")
        ApiV6InputValidator.settingsKeyViolation(key)?.let {
            return BombResult.invalidArgument(it)
        }
        profileOverrides[profileId]?.remove(OverrideKey(ns, key))
        rebuildSnapshot()
        return BombResult.success()
    }

    // ---- Assignment management --------------------------------------------

    /**
     * Assign a profile to a (userId, targetPackage) pair.
     */
    @Synchronized
    fun assignProfile(parcel: SettingsAssignmentParcel): BombResult {
        ApiV6InputValidator.profileIdViolation(parcel.profileId)?.let {
            return BombResult.invalidArgument(it)
        }
        ApiV6InputValidator.userIdViolation(parcel.userId)?.let {
            return BombResult.invalidArgument(it)
        }
        val domain = parcel.toDomain()
            ?: return BombResult.invalidArgument(
                "Invalid assignment: userId=${parcel.userId}, targetPackage='${parcel.targetPackage}', profileId='${parcel.profileId}'"
            )
        if (!PackageNameValidator.isValid(domain.targetPackage)) {
            return BombResult.invalidArgument("Invalid package name: ${domain.targetPackage}")
        }
        if (domain.profileId !in profileNames) {
            return BombResult.invalidArgument("profileId does not exist")
        }
        val assignmentKey = AssignmentKey(domain.userId, domain.targetPackage)
        if (assignmentKey !in assignments && assignments.size >= MAX_ASSIGNMENTS) {
            return BombResult.invalidArgument("settings assignment limit reached")
        }
        assignments[assignmentKey] = domain.profileId
        rebuildSnapshot()
        return BombResult.success()
    }

    /**
     * Remove the profile assignment for (userId, targetPackage).
     */
    @Synchronized
    fun clearAssignment(userId: Int, targetPackage: String): BombResult {
        ApiV6InputValidator.userIdViolation(userId)?.let {
            return BombResult.invalidArgument(it)
        }
        if (!PackageNameValidator.isValid(targetPackage)) {
            return BombResult.invalidArgument("targetPackage is invalid")
        }
        assignments.remove(AssignmentKey(userId, targetPackage))
        rebuildSnapshot()
        return BombResult.success()
    }

    // ---- Rebuild ----------------------------------------------------------

    /**
     * Recompile the current config into an immutable snapshot and update the
     * AtomicReference. Called only from @Synchronized methods.
     */
    private fun rebuildSnapshot() {
        val builder = SettingsPolicyBuilder(revision.incrementAndGet())
        for ((profileId, name) in profileNames) {
            val overrides = profileOverrides[profileId] ?: emptyMap()
            builder.addProfile(
                SettingsProfile(
                    id = profileId,
                    name = name,
                    overrides = overrides.toMap(),
                )
            )
        }
        for ((key, profileId) in assignments) {
            builder.addAssignment(
                SettingsAssignment(
                    userId = key.userId,
                    targetPackage = key.packageName,
                    profileId = profileId,
                )
            )
        }
        _snapshot.set(builder.build())
    }

    private companion object {
        const val MAX_PROFILES = 128
        const val MAX_OVERRIDES_PER_PROFILE = 512
        const val MAX_ASSIGNMENTS = 2_048
    }
}
