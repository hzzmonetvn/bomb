package com.hzzmonet.zkbomb.core

import com.hzzmonet.zkbomb.domain.log.LogCapabilities
import com.hzzmonet.zkbomb.domain.log.LogLevel
import com.hzzmonet.zkbomb.domain.log.LogProperty
import com.hzzmonet.zkbomb.domain.log.ObservedLogState
import java.io.File

/**
 * Reads the log subsystem's actual state.
 *
 * Service state comes from `init.svc.<name>`, which is how init publishes it —
 * not from a process scan, which would confuse a stopped service with one that
 * merely has no process right now.
 */
class LogStateReader(
    private val properties: SystemPropertyReader,
    private val root: File = File("/"),
) {

    /** Null when properties cannot be read at all, so the caller can say so. */
    fun observe(): ObservedLogState? {
        if (!properties.available) return null
        return ObservedLogState(
            logdRunning = isRunning("logd"),
            logcatdRunning = isRunning("logcatd"),
            tracedRunning = isRunning("traced"),
            tracedProbesRunning = isRunning("traced_probes"),
            reducedPropertiesApplied = reducedApplied(),
            declaredLevel = declaredLevel(),
        )
    }

    fun probe(rootBackendAvailable: Boolean, romBackendAvailable: Boolean = false): LogCapabilities = LogCapabilities(
        // REDUCED needs a ROM-side init trigger keyed on Bomb's property. Its
        // presence cannot be read directly, so this reports what is actually
        // knowable: whether Bomb can write the property at all.
        reduceSupported = properties.available && (romBackendAvailable || rootBackendAvailable),
        rootBackendAvailable = rootBackendAvailable,
        // Established by reading the extracted image, not by probing the running
        // device: init.target.rc:78 starts logd inside a one-shot `on init`, the
        // only repeating property triggers start logd-auditctl rather than logd,
        // and logd is not marked critical.
        romDisableReachable = romBackendAvailable,
        ylogPresent = File(root, "dev/ylog_buffer").exists(),
    )

    private fun isRunning(service: String): Boolean =
        properties.get("init.svc.$service") == "running"

    private fun declaredLevel(): LogLevel =
        when (properties.get(LogProperty.BOMB_LOG_LEVEL.key)) {
            "reduced" -> LogLevel.REDUCED
            "off" -> LogLevel.OFF
            // Unset, or a value this build does not know: DEFAULT is the safe
            // reading, because it is the tier that suppresses nothing.
            else -> LogLevel.DEFAULT
        }

    /**
     * True only when **every** property `REDUCED` sets holds its reduced value.
     *
     * All of them, not any: a partially applied tier is exactly what boot-time
     * reconcile exists to finish, and reporting it as applied would make
     * reconcile conclude there is nothing to do.
     */
    private fun reducedApplied(): Boolean =
        properties.get(LogProperty.LOGD_KERNEL.key) == "false" &&
            properties.get(LogProperty.LOGD_STATISTICS.key) == "false" &&
            properties.get(LogProperty.TRACED_ENABLE.key) == "0" &&
            properties.get(LogProperty.LOGD_SIZE.key) != null

    /** Current SELinux denial cap, or null when the platform default applies. */
    fun auditRatePerSecond(): Int? = properties.get(LogProperty.AUDIT_RATE.key)?.toIntOrNull()
}
