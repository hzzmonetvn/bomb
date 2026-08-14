package com.hzzmonet.zkbomb.core

import android.annotation.SuppressLint
import android.net.LocalSocket
import android.net.LocalSocketAddress
import com.hzzmonet.zkbomb.domain.log.LogLevel
import java.nio.charset.StandardCharsets

/**
 * Reads Android system properties, or reports honestly that it cannot.
 *
 * `android.os.SystemProperties` is not public SDK. Reaching it means reflection,
 * and reflection onto non-SDK interfaces is restricted from API 28 onwards — so
 * on an ordinary install this may simply not work, and the Log Governor has to
 * degrade to "not probed" rather than pretend the tier is `DEFAULT`.
 *
 * The alternative — shelling out to `getprop` — is off the table by design:
 * `CLAUDE.md` forbids arbitrary shell execution, and carving out an exception
 * "just for reads" would put a general-purpose exec path into the privileged
 * module, which is precisely the thing the whole typed-API design exists to
 * avoid.
 *
 * In ROM mode Bomb is a platform-signed system app, where the restriction does
 * not apply. That is the difference this class exists to make visible rather
 * than paper over.
 */
class SystemPropertyReader {

    /**
     * Resolved once. A blocked reflective lookup will be blocked every time, and
     * repeating it per call costs a strict-mode warning per call for nothing.
     */
    private val getter: ((String) -> String?)? by lazy { resolveGetter() }

    /** True when properties can be read at all on this build. */
    val available: Boolean get() = getter != null

    /** The property value, or null when unset or unreadable. */
    fun get(key: String): String? = getter?.invoke(key)?.ifEmpty { null }

    @SuppressLint("PrivateApi") // Intentional, capability-probed access for platform-signed ROM mode.
    private fun resolveGetter(): ((String) -> String?)? = runCatching {
        val clazz = Class.forName("android.os.SystemProperties")
        val method = clazz.getMethod("get", String::class.java)
        val probe = method.invoke(null, "ro.build.version.sdk") as? String
        // A method that resolves but returns nothing for a property every device
        // has means the reflective call is being blocked silently. Treating that
        // as "reads work, everything is unset" would misreport every tier.
        if (probe.isNullOrEmpty()) return@runCatching null
        { key: String -> runCatching { method.invoke(null, key) as? String }.getOrNull() }
    }.getOrNull()
}

/**
 * The only ROM-control surface exposed to the Android service.
 *
 * This is intentionally not a generic `set(name, value)` API. The caller can
 * request one of three log profiles and an optional bounded audit rate; the
 * property names remain inside `bombd`. The APK never writes a property: it
 * sends a bounded command over the init-owned local socket, then `bombd` writes
 * the corresponding request property after checking the peer.
 */
class RomControlPropertyWriter(
    private val client: BombdClient = BombdClient(),
) {

    val available: Boolean get() = client.request("PING")

    fun requestLogLevel(level: LogLevel, auditRatePerSecond: Int?): Boolean {
        if (auditRatePerSecond != null && auditRatePerSecond !in 0..1000) return false
        val auditRate = auditRatePerSecond ?: -1
        return client.request("LOG ${level.name.lowercase()} $auditRate")
    }

    fun requestMemory(swappiness: Int?, pageCluster: Int?): Boolean {
        if (swappiness != null && swappiness !in 0..200) return false
        if (pageCluster != null && pageCluster !in 0..6) return false
        if (swappiness != null && !client.request("MEM swappiness $swappiness")) return false
        if (pageCluster != null && !client.request("MEM page_cluster $pageCluster")) return false
        return true
    }

    /**
     * Publish one charge-control write. The APK never writes the sysfs node; bombd
     * range-checks and sets the matching `persist.sys.bomb.charge.*` request
     * property, and init performs the write via its bomb.rc trigger. [field] is one
     * of the bounded control fields; bounds mirror the bombd handler:
     *  - `current_max`: 100000..20000000 µA
     *  - `end_threshold`: 0..100 percent
     *  - `disable` / `charging_enabled` / `input_suspend`: 0 or 1
     */
    fun requestChargeControl(field: String, value: Int): Boolean {
        return isValidChargeControl(field, value) && client.request("CHARGE $field $value")
    }

    /**
     * Publish one CPU/GPU frequency-limit write. The APK never writes the cpufreq
     * or GPU sysfs node; bombd range-checks and sets the matching
     * `persist.sys.bomb.freq.*` request property, and init performs the write via
     * its bomb.rc trigger. [field] is a bounded per-policy/GPU control field:
     *  - `cpu0_min`..`cpu7_max`: per-policy scaling min/max, kHz, 100000..50000000
     *  - `gpu_min` / `gpu_max`: GPU min/max, Hz, 10000000..3000000000
     * A [Long] is used because a GPU value in Hz can exceed [Int].
     */
    fun requestFrequencyControl(field: String, value: Long): Boolean {
        return isValidFrequencyControl(field, value) && client.request("FREQ $field $value")
    }
}

internal fun isValidChargeControl(field: String, value: Int): Boolean = when (field) {
    "current_max" -> value in 100_000..20_000_000
    "end_threshold" -> value in 0..100
    "disable", "charging_enabled", "input_suspend" -> value in 0..1
    else -> false
}

private val CPU_FREQ_FIELD = Regex("^cpu[0-7]_(min|max)$")

internal fun isValidFrequencyControl(field: String, value: Long): Boolean = when {
    CPU_FREQ_FIELD.matches(field) -> value in 100_000L..50_000_000L
    field == "gpu_min" || field == "gpu_max" -> value in 10_000_000L..3_000_000_000L
    else -> false
}

/** Client for the fixed, line-oriented bombd protocol. */
class BombdClient {

    fun request(command: String): Boolean {
        if (!VALID_COMMAND.matches(command)) return false
        return runCatching {
            LocalSocket().use { socket ->
                socket.soTimeout = TIMEOUT_MS
                socket.connect(
                    LocalSocketAddress(SOCKET_NAME, LocalSocketAddress.Namespace.RESERVED),
                )
                socket.outputStream.write("$command\n".toByteArray(StandardCharsets.US_ASCII))
                socket.outputStream.flush()
                socket.inputStream.bufferedReader(StandardCharsets.US_ASCII).readLine() == "OK"
            }
        }.getOrDefault(false)
    }

    private companion object {
        const val SOCKET_NAME = "bombd"
        const val TIMEOUT_MS = 1_500

        // Defense in depth. bombd repeats full parsing/range validation; the
        // client cannot be turned into a generic command transport either.
        val VALID_COMMAND = Regex(
            "^(PING|LOG (default|reduced|off) (-1|[0-9]{1,4})|" +
                "MEM (swappiness|page_cluster) [0-9]{1,3}|" +
                "CHARGE (current_max [0-9]{1,8}|end_threshold [0-9]{1,3}|" +
                "(disable|charging_enabled|input_suspend) [01])|" +
                "FREQ (cpu[0-7]_(min|max) [0-9]{1,8}|gpu_(min|max) [0-9]{1,10}))$",
        )
    }
}
