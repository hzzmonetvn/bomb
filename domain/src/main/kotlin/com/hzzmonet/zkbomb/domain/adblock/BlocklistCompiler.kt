package com.hzzmonet.zkbomb.domain.adblock

/**
 * Turns raw source lines into a [Blocklist], reporting exactly what happened to
 * every line.
 *
 * The counts are not diagnostics — they are a product requirement. The real
 * AdGuardSDNSFilter corpus contains wildcard rules Bomb cannot honour, so *every*
 * real list is partially applied. Master plan §16 requires the user to see that
 * rather than assume full coverage, which is why [CompileResult] carries
 * `unsupported` and `invalid` alongside `applied`.
 *
 * [MIN_VALID_RULES] implements the master plan's "minimum valid-rule threshold":
 * a source that parses to (almost) nothing is a **failed update**, not an empty
 * blockset — so the caller keeps the previous compiled set instead of silently
 * disabling protection. AdGuard Home reaches the same outcome differently, by
 * only swapping its pending file on success
 * (`internal/filtering/filter.go:585` finalizeUpdate).
 */
object BlocklistCompiler {

    const val MIN_VALID_RULES = 1

    fun compile(lines: Sequence<String>): CompileResult {
        val blocked = LinkedHashSet<String>()
        val allowed = LinkedHashSet<String>()
        var ignored = 0
        var duplicates = 0
        val unsupported = mutableMapOf<UnsupportedReason, Int>()
        val invalid = mutableMapOf<InvalidReason, Int>()

        for (line in lines) {
            when (val outcome = BlocklistParser.parseLine(line)) {
                is ParseOutcome.Ignored -> ignored++

                is ParseOutcome.Accepted -> {
                    val target = if (outcome.kind == RuleKind.ALLOW) allowed else blocked
                    for (domain in outcome.domains) {
                        if (!target.add(domain)) duplicates++
                    }
                }

                is ParseOutcome.Unsupported ->
                    unsupported[outcome.reason] = (unsupported[outcome.reason] ?: 0) + 1

                is ParseOutcome.Invalid ->
                    invalid[outcome.reason] = (invalid[outcome.reason] ?: 0) + 1
            }
        }

        return CompileResult(
            blocklist = Blocklist(blocked, allowed),
            ignored = ignored,
            duplicates = duplicates,
            unsupported = unsupported.toMap(),
            invalid = invalid.toMap(),
        )
    }

    fun compile(text: String): CompileResult = compile(text.lineSequence())
}

data class CompileResult(
    val blocklist: Blocklist,
    val ignored: Int,
    val duplicates: Int,
    val unsupported: Map<UnsupportedReason, Int>,
    val invalid: Map<InvalidReason, Int>,
) {
    val appliedCount: Int get() = blocklist.blockedCount + blocklist.allowedCount
    val unsupportedCount: Int get() = unsupported.values.sum()
    val invalidCount: Int get() = invalid.values.sum()

    /**
     * False when the source produced too little to be worth swapping in. The
     * caller must then keep the previous blockset and mark the update failed.
     */
    val isUsable: Boolean get() = blocklist.blockedCount >= BlocklistCompiler.MIN_VALID_RULES
}
