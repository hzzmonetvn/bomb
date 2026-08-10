package com.hzzmonet.zkbomb.domain.packagecontrol

/** Manifest component families supported by Package Inspector. */
enum class ComponentKind { ACTIVITY, SERVICE, RECEIVER, PROVIDER }

/** Explicit PackageManager override states; DEFAULT removes Bomb's override. */
enum class ComponentOverrideState { DEFAULT, ENABLED, DISABLED }

/**
 * Validates a fully qualified Java/Kotlin class name before it reaches
 * ComponentName. PackageManager supplies full names in snapshots, so relative
 * names are deliberately not accepted on the write path.
 */
object ComponentClassNameValidator {
    private const val MAX_LENGTH = 512
    private val segment = Regex("[A-Za-z_][A-Za-z0-9_$]*")

    fun isValid(className: String): Boolean {
        if (className.isBlank() || className.length > MAX_LENGTH) return false
        if (className.startsWith('.') || className.endsWith('.')) return false
        val parts = className.split('.')
        return parts.size >= 2 && parts.all(segment::matches)
    }
}
