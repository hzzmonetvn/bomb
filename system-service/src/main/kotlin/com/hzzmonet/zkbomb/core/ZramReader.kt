package com.hzzmonet.zkbomb.core

import com.hzzmonet.zkbomb.domain.memory.SwappinessTarget
import com.hzzmonet.zkbomb.domain.memory.ZramAlgorithms
import com.hzzmonet.zkbomb.domain.memory.ZramCapabilities
import com.hzzmonet.zkbomb.domain.memory.ZramMmStat
import java.io.File

/**
 * Reads zram and swap state off sysfs.
 *
 * Every read can fail — the node may not exist, SELinux may deny it, or the
 * kernel may not expose that attribute. Each failure yields **null**, which the
 * caller turns into an explicit unavailable state. Nothing here substitutes a
 * plausible number for one it could not read.
 *
 * The parsing itself lives in `:domain` and is unit-tested there; this class
 * only does I/O. That split is why the awkward cases (fresh zram dividing by
 * zero, kernels missing the huge-page fields) are covered by fast JVM tests
 * rather than needing a device.
 */
class ZramReader(private val root: File = File("/")) {

    private fun node(path: String) = File(root, path)

    /** Trimmed contents of a sysfs node, or null if it cannot be read. */
    private fun read(path: String): String? =
        runCatching {
            val file = node(path)
            if (!file.canRead()) null else file.readText().trim().ifEmpty { null }
        }.getOrNull()

    fun probe(): ZramCapabilities {
        val present = node(ZRAM_DIR).let { it.exists() && it.isDirectory }
        if (!present) return ZramCapabilities.NONE
        return ZramCapabilities(
            present = true,
            disksize = read("$ZRAM_DIR/disksize") != null,
            compAlgorithm = read("$ZRAM_DIR/comp_algorithm") != null,
            memLimit = read("$ZRAM_DIR/mem_limit") != null,
            mmStat = read("$ZRAM_DIR/mm_stat") != null,
            // Read-only for Bomb: the ROM chowns these to `system` with the
            // comment "System server manages zram writeback", so they are
            // telemetry and never a control.
            writebackReadable = read("$ZRAM_DIR/bd_stat") != null,
            swappiness = read(GLOBAL_SWAPPINESS) != null,
            pageCluster = read(PAGE_CLUSTER) != null,
        )
    }

    fun mmStat(): ZramMmStat? = read("$ZRAM_DIR/mm_stat")?.let(ZramMmStat::parse)

    fun algorithms(): ZramAlgorithms? = read("$ZRAM_DIR/comp_algorithm")?.let(ZramAlgorithms::parse)

    fun disksizeBytes(): Long? = read("$ZRAM_DIR/disksize")?.toLongOrNull()

    fun globalSwappiness(): Int? = read(GLOBAL_SWAPPINESS)?.toIntOrNull()

    fun pageCluster(): Int? = read(PAGE_CLUSTER)?.toIntOrNull()

    /**
     * Swappiness for every writable memcg target that can be read.
     *
     * Reported per target rather than as one number: this ROM writes five of
     * them at different values (100 globally, 60 for `freeze-app`), so a single
     * "swappiness" figure would be wrong for whichever cgroup the user cared
     * about. Targets that cannot be read are omitted rather than defaulted.
     */
    fun swappinessByTarget(): Map<String, Int> =
        SwappinessTarget.WRITABLE
            .mapNotNull { target ->
                read(target.path.removePrefix("/"))?.toIntOrNull()?.let { target.name to it }
            }
            .toMap()

    private companion object {
        const val ZRAM_DIR = "sys/block/zram0"
        const val GLOBAL_SWAPPINESS = "proc/sys/vm/swappiness"
        const val PAGE_CLUSTER = "proc/sys/vm/page-cluster"
    }
}
