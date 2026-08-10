package com.hzzmonet.zkbomb.core

import com.hzzmonet.zkbomb.api.BombResult
import com.hzzmonet.zkbomb.domain.adblock.Blocklist
import com.hzzmonet.zkbomb.domain.adblock.BlocklistCompiler
import java.util.concurrent.atomic.AtomicReference

/**
 * Service-side AdBlock engine backend for §16.
 *
 * Manages:
 * - The active [Blocklist] in an [AtomicReference] for lock-free queries.
 * - The atomic compile-and-swap pipeline:
 *   raw input → parse → normalize → deduplicate → apply allowlist → compile → swap.
 *
 * The raw source text is stored in-memory after each [loadSource] call.
 * Persistence (writing/reading from Bomb private storage) is handled by a higher
 * layer that calls [loadSource] on service startup with the last-known good source.
 *
 * §16: "If update fails, keep the previous working blockset."
 * This contract is enforced by [reloadAndSwap] — it only swaps when
 * [CompileResult.isUsable] is true.
 */
class AdBlockEngine {

    /** The currently active compiled blocklist. Lock-free read. */
    private val _active = AtomicReference<Blocklist>(Blocklist.EMPTY)

    /** The last successfully loaded source text. Null until first load. */
    @Volatile
    private var rawSource: String? = null

    /**
     * The active [Blocklist] for DNS query decisions.
     *
     * O(1) read — backed by AtomicReference.
     */
    val active: Blocklist get() = _active.get()

    // ---- Source management ------------------------------------------------

    /**
     * Replace the raw source text and immediately trigger a compile-and-swap.
     *
     * This is the entry point for:
     *  - Initial load from private storage on service start.
     *  - Download completion from a remote source.
     *  - Manual user import.
     *
     * @param source Raw text in any supported format (hosts, plain domain, AdBlock DNS).
     * @return [BombResult.success] if the source compiled to at least
     *   [BlocklistCompiler.MIN_VALID_RULES] rules and was swapped in.
     *   [BombResult.failed] if the source produced no usable rules; the existing
     *   active blocklist is retained.
     */
    fun loadSource(source: String): BombResult {
        rawSource = source
        return compileAndSwap(source)
    }

    /**
     * Re-compile the last loaded source and atomically swap the active blocklist.
     *
     * Called from [IBombService.reloadAdBlockRules] on the Binder thread.
     * Does NOT block — the compile is fast (in-memory string processing).
     *
     * §16 pipeline:
     *   1. parse (BlocklistParser.parseLine per line)
     *   2. normalize (IDN → punycode, lowercase — done inside parser)
     *   3. deduplicate (LinkedHashSet in BlocklistCompiler)
     *   4. apply allowlist (Blocklist.decide checks allow before block)
     *   5. compile → Blocklist
     *   6. atomic swap (only if isUsable)
     *
     * @return [BombResult.success] on successful swap,
     *   [BombResult.failed] when the result is not usable (too few rules),
     *   [BombResult.backendUnavailable] when no source has been loaded yet.
     */
    fun reloadAndSwap(): BombResult {
        val source = rawSource
            ?: return BombResult.backendUnavailable("No blocklist source has been loaded yet")
        return compileAndSwap(source)
    }

    // ---- Query (hot path) -------------------------------------------------

    /**
     * Decide whether [host] should be blocked by the active blocklist.
     *
     * Called on every intercepted DNS query — must be non-blocking and allocation-minimal.
     */
    fun decide(host: String) = _active.get().decide(host)

    // ---- Internal ----------------------------------------------------------

    private fun compileAndSwap(source: String): BombResult {
        val result = BlocklistCompiler.compile(source)

        if (!result.isUsable) {
            // §16: keep the previous blockset — do not swap.
            return BombResult.failed(
                "Compiled blocklist has ${result.blocklist.blockedCount} block rules — " +
                    "minimum is ${BlocklistCompiler.MIN_VALID_RULES}. " +
                    "Previous blockset retained. " +
                    "Unsupported=${result.unsupportedCount}, Invalid=${result.invalidCount}"
            )
        }

        _active.set(result.blocklist)
        return BombResult(
            status = BombResult.Status.SUCCESS,
            detail =
                "Loaded ${result.blocklist.blockedCount} block rules, " +
                    "${result.blocklist.allowedCount} allow rules. " +
                    "Unsupported=${result.unsupportedCount}, Invalid=${result.invalidCount}, " +
                    "Ignored=${result.ignored}, Duplicates=${result.duplicates}",
        )
    }
}
