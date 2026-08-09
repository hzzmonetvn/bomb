package com.hzzmonet.zkbomb.core

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
                "MEM (swappiness|page_cluster) [0-9]{1,3})$",
        )
    }
}
