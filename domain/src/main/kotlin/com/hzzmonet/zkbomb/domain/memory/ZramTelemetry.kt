package com.hzzmonet.zkbomb.domain.memory

/**
 * A decoded `/sys/block/zram0/mm_stat` line.
 *
 * Nine whitespace-separated fields in kernel order
 * (`docs/research/ZRAM.md` §1):
 *
 * ```
 * orig_data_size compr_data_size mem_used_total mem_limit mem_used_max
 * same_pages pages_compacted huge_pages huge_pages_since
 * ```
 *
 * The last two are recent additions and are absent on older kernels, so they are
 * nullable rather than assumed. Everything a user actually wants to see —
 * compression ratio, RAM efficiency — is **derived** here rather than read,
 * because the kernel does not publish either.
 */
data class ZramMmStat(
    /** Uncompressed size of the data currently stored. */
    val origDataSize: Long,
    /** Compressed size of that data. */
    val comprDataSize: Long,
    /** Total memory zram has allocated, including allocator overhead. */
    val memUsedTotal: Long,
    /** Configured memory limit, 0 when unlimited. */
    val memLimit: Long,
    /** High-water mark of [memUsedTotal]. */
    val memUsedMax: Long,
    /** Pages deduplicated because they were identical (usually zero pages). */
    val samePages: Long,
    /** Pages freed by background compaction. */
    val pagesCompacted: Long,
    /** Huge (incompressible) pages currently stored, or null on kernels without the field. */
    val hugePages: Long?,
    /** Cumulative huge pages, or null on kernels without the field. */
    val hugePagesSince: Long?,
) {

    /**
     * `orig_data_size / compr_data_size` — how well the data compressed.
     *
     * Null rather than infinity on a fresh zram where nothing has been written
     * yet. Returning `Infinity` or `0` would both be displayable, and both would
     * be a fabricated metric.
     */
    val compressionRatio: Double?
        get() = if (comprDataSize > 0) origDataSize.toDouble() / comprDataSize else null

    /**
     * `orig_data_size / mem_used_total` — how much RAM the compression actually
     * saved.
     *
     * Always lower than [compressionRatio] because of allocator overhead (3.3×
     * against 3.4× in the verified sample). This is the honest figure, and the
     * one the resize safety gate uses. The UI must show both rather than
     * flattening them into one number: [samePages] are deduplicated, not
     * compressed, and counting them as compression inflates the ratio.
     */
    val ramEfficiency: Double?
        get() = if (memUsedTotal > 0) origDataSize.toDouble() / memUsedTotal else null

    companion object {

        /** Every kernel that publishes `mm_stat` publishes at least these seven. */
        private const val REQUIRED_FIELDS = 7

        /**
         * Parses one `mm_stat` line, or returns null when it is not one.
         *
         * Null covers every unreadable case — a missing node, a short line, a
         * non-numeric field — because the caller's only correct response to all
         * of them is the same: report the metric unavailable rather than
         * substitute a value.
         */
        fun parse(line: String): ZramMmStat? {
            val fields = line.trim().split(WHITESPACE)
            if (fields.size < REQUIRED_FIELDS) return null
            val values = fields.map { it.toLongOrNull() ?: return null }
            return ZramMmStat(
                origDataSize = values[0],
                comprDataSize = values[1],
                memUsedTotal = values[2],
                memLimit = values[3],
                memUsedMax = values[4],
                samePages = values[5],
                pagesCompacted = values[6],
                hugePages = values.getOrNull(7),
                hugePagesSince = values.getOrNull(8),
            )
        }

        private val WHITESPACE = Regex("\\s+")
    }
}

/**
 * The parsed `/sys/block/zram0/comp_algorithm` line.
 *
 * ```
 * lzo-rle lzo lz4 lz4hc [zstd] deflate 842
 * ```
 *
 * One read yields both the enum domain for validation and the present value,
 * which is why [ZramConfigValidator] never needs a hardcoded algorithm list —
 * the device tells it what it supports.
 */
data class ZramAlgorithms(
    val available: List<String>,
    /** The bracketed entry, or null if the kernel marked none. */
    val current: String?,
) {
    companion object {
        fun parse(line: String): ZramAlgorithms? {
            val tokens = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return null

            var current: String? = null
            val available = tokens.map { token ->
                if (token.length > 2 && token.startsWith('[') && token.endsWith(']')) {
                    token.substring(1, token.length - 1).also { current = it }
                } else {
                    token
                }
            }
            // A line of only brackets, or containing an empty name, is not a
            // valid algorithm list.
            if (available.any { it.isEmpty() }) return null
            return ZramAlgorithms(available, current)
        }
    }
}

/**
 * Per-attribute zram capability.
 *
 * `docs/research/ZRAM.md` §1: `recomp_algorithm`, `recompress`,
 * `algorithm_params` and `compressed_writeback` are recent additions and any
 * given Android kernel has a subset, so this is a set of probes rather than one
 * boolean.
 *
 * [writeback] is deliberately **read-only**: the target ROM chowns
 * `/sys/block/zram0/{idle,writeback,...}` to `system` with the comment *"System
 * server manages zram writeback"*, so for Bomb it is telemetry, never a control.
 */
data class ZramCapabilities(
    /** A zram device exists and is initialised. */
    val present: Boolean,
    val disksize: Boolean,
    val compAlgorithm: Boolean,
    val memLimit: Boolean,
    val mmStat: Boolean,
    /** Readable only. See the class note. */
    val writebackReadable: Boolean,
    val swappiness: Boolean,
    val pageCluster: Boolean,
) {
    companion object {
        /** Nothing probed yet — the honest starting point. */
        val NONE = ZramCapabilities(
            present = false,
            disksize = false,
            compAlgorithm = false,
            memLimit = false,
            mmStat = false,
            writebackReadable = false,
            swappiness = false,
            pageCluster = false,
        )
    }
}
