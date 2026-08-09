package com.hzzmonet.zkbomb.domain.freeze

/**
 * The set of packages Bomb refuses to freeze, and why.
 *
 * Master plan §7 requires *at least* Bomb, SystemUI, the launcher, the current
 * IME, essential framework packages and vendor/device-critical packages.
 *
 * Three of those are **not** constants and must not be hardcoded:
 *
 * - SystemUI is `com.android.systemui` on AOSP but the ROM may differ, so it is
 *   resolved rather than assumed;
 * - the launcher is whichever package currently resolves `CATEGORY_HOME` — the
 *   user can change it at any time, and freezing yesterday's launcher protects
 *   nothing;
 * - the IME that matters is the **current** one. Protecting every *enabled* IME
 *   would over-protect; protecting none would let the user freeze away their
 *   only way to type.
 *
 * The caller therefore builds this snapshot immediately before a decision. It is
 * a value object with no platform imports so that every branch below is testable
 * without a device.
 */
data class ProtectedPackages(
    /** Bomb's own package name. */
    val self: String,
    /** The resolved SystemUI package. */
    val systemUi: String,
    /** The package of the currently resolved home activity, or null if none resolves. */
    val launcher: String?,
    /** The currently selected IME's package, or null when none is selected. */
    val currentIme: String?,
    /** Essential framework packages, including `"android"`. */
    val framework: Set<String>,
    /** Vendor / device-critical packages, supplied by the device backend. */
    val vendorCritical: Set<String>,
    /** Packages with an active device administrator component. */
    val deviceAdmins: Set<String>,
    /** Packages the user added to the freeze exclusion list. */
    val userExcluded: Set<String>,
) {
    /**
     * The reason [packageName] is protected, or null when it may be frozen.
     *
     * Checked most-specific first so the reported reason is the most useful one:
     * a user who excluded SystemUI by hand is better told it is SystemUI.
     */
    fun exclusionFor(packageName: String): ExclusionReason? = when {
        packageName == self -> ExclusionReason.BOMB_ITSELF
        packageName == systemUi -> ExclusionReason.SYSTEM_UI
        packageName in framework -> ExclusionReason.FRAMEWORK
        packageName == launcher -> ExclusionReason.LAUNCHER
        packageName == currentIme -> ExclusionReason.CURRENT_IME
        packageName in vendorCritical -> ExclusionReason.VENDOR_CRITICAL
        packageName in deviceAdmins -> ExclusionReason.DEVICE_ADMIN
        packageName in userExcluded -> ExclusionReason.USER_EXCLUDED
        else -> null
    }

    companion object {
        /**
         * Framework packages that are never freezable regardless of device.
         *
         * `"android"` is refused by the platform itself for
         * [FreezeMechanism.HIDE] (`docs/research/HAIL.md` §2); the rest carry the
         * platform UID or host the components every other package depends on.
         * A device backend adds to this set, never replaces it.
         */
        val CORE_FRAMEWORK: Set<String> = setOf(
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.phone",
            "com.android.server.telecom",
            "com.android.providers.settings",
            "com.android.providers.media",
            "com.android.providers.downloads",
            "com.android.providers.contacts",
            "com.android.providers.telephony",
            "com.android.externalstorage",
            "com.android.shell",
            "com.android.keychain",
            "com.android.location.fused",
            "com.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.android.certinstaller",
            "com.android.inputdevices",
            "com.android.bluetooth",
            "com.android.nfc",
            "com.android.se",
            "com.android.wifi.resources",
            "com.android.networkstack",
            "com.android.dynsystem",
        )
    }
}

/**
 * Package-name validation for privileged freeze calls.
 *
 * Master plan §3.1 requires every privileged request to validate its arguments.
 * A package name reaching a `PackageManager` setter must at minimum be
 * syntactically a package name, so that a malformed value fails here with a
 * typed reason rather than deep inside a system call.
 *
 * This checks *shape*, not existence — existence is the caller's job and needs
 * the platform. The grammar is Android's: dot-separated segments, each starting
 * with a letter and continuing with letters, digits or underscore.
 *
 * A separating dot is deliberately **not** required, even though
 * `PackageParser.validateName(requireSeparator = true)` demands one for
 * installable apps. The platform package is literally `"android"`, and it is one
 * of the names Bomb most needs to reason about — it heads
 * [ProtectedPackages.CORE_FRAMEWORK] and the platform itself refuses to hide it.
 * Rejecting it here would report a real, protected package as *malformed*, which
 * is both untrue and less useful than naming the protection that applies.
 * Requiring a dot buys no safety either: the traversal and metacharacter cases
 * are already excluded by the character set.
 */
object PackageNameValidator {

    private const val MAX_LENGTH = 255

    fun isValid(packageName: String): Boolean {
        if (packageName.isEmpty() || packageName.length > MAX_LENGTH) return false

        var segmentStart = true
        for (c in packageName) {
            if (c == '.') {
                // A dot may neither start the name nor immediately follow another.
                if (segmentStart) return false
                segmentStart = true
                continue
            }
            if (segmentStart) {
                if (!c.isAsciiLetter()) return false
                segmentStart = false
            } else if (!c.isAsciiLetter() && !c.isAsciiDigit() && c != '_') {
                return false
            }
        }
        // A trailing dot leaves an empty final segment.
        return !segmentStart
    }

    private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

    private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'
}
