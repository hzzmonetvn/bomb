package com.hzzmonet.zkbomb.domain.validation

import com.hzzmonet.zkbomb.domain.freeze.FreezeMode
import com.hzzmonet.zkbomb.domain.freeze.PackageNameValidator
import com.hzzmonet.zkbomb.domain.log.LogLevel
import com.hzzmonet.zkbomb.domain.model.BombResult

/**
 * Validates inputs for AIDL privileged calls.
 *
 * Implements AIDL Rule 2: "every argument bounded — enum name, id, validated package, numeric range".
 */
object AidlValidator {

    fun validatePackageName(packageName: String?): BombResult {
        if (packageName.isNullOrEmpty()) {
            return BombResult.invalidArgument("Package name must not be null or empty")
        }
        if (!PackageNameValidator.isValid(packageName)) {
            return BombResult.invalidArgument("Invalid package name: $packageName")
        }
        return BombResult.success()
    }

    fun validateUserId(userId: Int): BombResult {
        if (userId < 0) {
            return BombResult.invalidArgument("userId must be non-negative, was: $userId")
        }
        if (userId > 9999) {
            return BombResult.invalidArgument("userId exceeds maximum allowed limit (9999), was: $userId")
        }
        return BombResult.success()
    }

    fun validatePid(pid: Int): BombResult {
        if (pid <= 0) {
            return BombResult.invalidArgument("pid must be positive, was: $pid")
        }
        return BombResult.success()
    }

    fun validateUid(uid: Int): BombResult {
        if (uid < 0) {
            return BombResult.invalidArgument("uid must be non-negative, was: $uid")
        }
        return BombResult.success()
    }

    fun validateFreezeState(mode: String?): BombResult {
        if (mode.isNullOrBlank()) {
            return BombResult.invalidArgument("Freeze mode string must not be null or blank")
        }
        val isValid = FreezeMode.entries.any { it.name.equals(mode, ignoreCase = true) }
        if (!isValid) {
            return BombResult.invalidArgument("Unknown freeze mode: $mode")
        }
        return BombResult.success()
    }

    fun validateLogLevel(level: String?): BombResult {
        if (level.isNullOrBlank()) {
            return BombResult.invalidArgument("Log level string must not be null or blank")
        }
        val isValid = LogLevel.entries.any { it.name.equals(level, ignoreCase = true) }
        if (!isValid) {
            return BombResult.invalidArgument("Unknown log level: $level")
        }
        return BombResult.success()
    }

    fun validateAuditRate(rate: Int): BombResult {
        if (rate != -1 && rate < 0) {
            return BombResult.invalidArgument("auditRatePerSecond must be -1 or non-negative, was: $rate")
        }
        return BombResult.success()
    }
}
