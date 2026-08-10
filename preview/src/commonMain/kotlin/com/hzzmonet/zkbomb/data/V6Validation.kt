package com.hzzmonet.zkbomb.data

/**
 * Client-side mirror of the API v6 write-contract validation (the service's
 * `ApiV6InputValidator`, `SettingsKeyPolicy` and `SettingsValuePolicy`).
 *
 * The service remains the sole authority: every v6 write returns a
 * [BombOperationResult] the UI surfaces verbatim, including INVALID_ARGUMENT. This
 * mirror exists only so the UI can **disable** a control that would certainly be
 * rejected and **offer** only virtualizable keys — never to decide on the app's
 * behalf whether a privileged write is allowed. If this mirror and the server
 * ever disagree, the server wins and the UI shows its reason.
 */
object BombV6Validation {
    /** Android assigns each user a 100000-wide UID block; app UIDs start at appId 10000. */
    const val PER_USER_RANGE = 100_000
    const val MIN_APP_ID = 10_000
    const val MAX_PROFILE_ID_LENGTH = 128
    const val MAX_PROFILE_NAME_LENGTH = 256
    const val MAX_FIREWALL_NOTE_LENGTH = 256

    /**
     * True when [uid] identifies an installed application (appId >= 10000), the
     * only shape the firewall and visibility writes accept. Shared/system UIDs
     * (root, system, radio…) are excluded, matching `appUidViolation`.
     */
    fun isApplicationUid(uid: Int): Boolean =
        uid >= 0 && uid % PER_USER_RANGE >= MIN_APP_ID

    /** The user id a [uid] belongs to. */
    fun userIdOf(uid: Int): Int = uid / PER_USER_RANGE

    /** profileId is 1..128 characters from `[A-Za-z0-9._-]`. */
    fun isValidProfileId(id: String): Boolean =
        id.isNotEmpty() &&
            id.length <= MAX_PROFILE_ID_LENGTH &&
            id.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '_' || it == '-' }

    /** profileName is a 1..256 character label without control characters. */
    fun isValidProfileName(name: String): Boolean =
        name.isNotBlank() && name.length <= MAX_PROFILE_NAME_LENGTH && name.none { it.isISOControlChar() }

    /** Firewall note is optional, at most 256 characters, no control characters. */
    fun isValidFirewallNote(note: String?): Boolean =
        note == null || (note.length <= MAX_FIREWALL_NOTE_LENGTH && note.none { it.isISOControlChar() })

    /**
     * Reason a typed settings value is unacceptable for [valueType], or null when
     * it is canonical. Mirrors `SettingsValuePolicy`: numbers must round-trip
     * exactly (no leading zeros or `+`), BOOLEAN is `0`/`1`, NULL takes no value.
     */
    fun settingsValueViolation(valueType: String, value: String?): String? = when (valueType) {
        BombSettingsValueType.STRING ->
            if (value == null) "String value is required" else null
        BombSettingsValueType.INTEGER ->
            canonicalNumberViolation("Integer", value) { it.toIntOrNull()?.toString() }
        BombSettingsValueType.LONG ->
            canonicalNumberViolation("Long", value) { it.toLongOrNull()?.toString() }
        BombSettingsValueType.FLOAT ->
            canonicalNumberViolation("Float", value) {
                it.toFloatOrNull()?.takeIf { f -> f.isFinite() }?.toString()
            }
        BombSettingsValueType.BOOLEAN ->
            if (value == "0" || value == "1") null else "Boolean must be 0 or 1"
        BombSettingsValueType.NULL ->
            if (value == null) null else "Null type takes no value"
        else -> "Unknown value type"
    }

    private inline fun canonicalNumberViolation(
        type: String,
        value: String?,
        canonicalize: (String) -> String?,
    ): String? {
        if (value.isNullOrEmpty()) return "$type value is required"
        val canonical = canonicalize(value) ?: return "Not a valid $type"
        return if (value == canonical) null else "$type must be canonical (e.g. $canonical)"
    }

    // No Char.isISOControl in the common stdlib; classify the two control bands.
    private fun Char.isISOControlChar(): Boolean = this < ' ' || this in '\u007F'..'\u009F'
}

/** One virtualizable settings key: namespace, key, its natural type and a description. */
data class BombSettingsKey(
    val namespace: String,
    val key: String,
    val suggestedType: String,
    val description: String,
)

/**
 * The APP_READ settings keys the backend will actually virtualize.
 *
 * This is a deliberate mirror of the service's fail-closed allowlist
 * (`SettingsKeyPolicy`, BOMB_PLAN.md §4.6): only keys read through
 * SettingsProvider inside an app process qualify. Notably `font_scale` is *not*
 * here — it is SYSTEM_READ and the service rejects it — so the UI never offers a
 * key that is guaranteed to fail. [suggestedType] is only a sensible default; the
 * service validates the value's canonical form, not the key→type pairing.
 */
object BombSettingsCatalog {
    val APP_READ_KEYS: List<BombSettingsKey> = listOf(
        BombSettingsKey(BombSettingsNamespace.SYSTEM, "alarm_alert", BombSettingsValueType.STRING, "Default alarm sound URI"),
        BombSettingsKey(BombSettingsNamespace.SYSTEM, "notification_sound", BombSettingsValueType.STRING, "Default notification sound URI"),
        BombSettingsKey(BombSettingsNamespace.SYSTEM, "ringtone", BombSettingsValueType.STRING, "Default ringtone URI"),
        BombSettingsKey(BombSettingsNamespace.SYSTEM, "screen_brightness", BombSettingsValueType.INTEGER, "Screen brightness, 0–255"),
        BombSettingsKey(BombSettingsNamespace.SYSTEM, "screen_brightness_mode", BombSettingsValueType.INTEGER, "0 manual, 1 automatic"),
        BombSettingsKey(BombSettingsNamespace.SYSTEM, "time_12_24", BombSettingsValueType.STRING, "Clock format: \"12\" or \"24\""),
        BombSettingsKey(BombSettingsNamespace.SECURE, "accessibility_display_daltonizer", BombSettingsValueType.INTEGER, "Colour-correction mode"),
        BombSettingsKey(BombSettingsNamespace.SECURE, "accessibility_display_daltonizer_enabled", BombSettingsValueType.INTEGER, "0/1 colour correction"),
        BombSettingsKey(BombSettingsNamespace.SECURE, "accessibility_display_inversion_enabled", BombSettingsValueType.INTEGER, "0/1 colour inversion"),
    )
}
