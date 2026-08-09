package com.hzzmonet.zkbomb.domain.taskmanager

import com.hzzmonet.zkbomb.domain.model.ProcessCategory
import com.hzzmonet.zkbomb.domain.model.ProcessItem

enum class ProcessSortBy {
    CPU,
    RAM,
    PID,
    NAME,
}

enum class SortOrder {
    ASCENDING,
    DESCENDING,
}

/**
 * Task Manager engine handling sorting, filtering, and query processing for process lists.
 */
object TaskManagerEngine {

    fun filterAndSort(
        processes: List<ProcessItem>,
        query: String? = null,
        categoryFilter: ProcessCategory? = null,
        sortBy: ProcessSortBy = ProcessSortBy.RAM,
        sortOrder: SortOrder = SortOrder.DESCENDING,
    ): List<ProcessItem> {
        val trimmedQuery = query?.trim()?.lowercase()
        val filtered = processes.filter { process ->
            if (categoryFilter != null && process.category != categoryFilter) {
                return@filter false
            }
            if (!trimmedQuery.isNullOrEmpty()) {
                val matchName = process.processName.lowercase().contains(trimmedQuery)
                val matchPkg = process.packageNames.any { it.lowercase().contains(trimmedQuery) }
                if (!matchName && !matchPkg) {
                    return@filter false
                }
            }
            true
        }

        val comparator = when (sortBy) {
            ProcessSortBy.CPU -> compareBy<ProcessItem> { it.cpuTimeTicks ?: 0L }
            ProcessSortBy.RAM -> compareBy<ProcessItem> { it.pssBytes ?: it.rssBytes ?: 0L }
            ProcessSortBy.PID -> compareBy<ProcessItem> { it.pid }
            ProcessSortBy.NAME -> compareBy<ProcessItem> { it.processName.lowercase() }
        }

        return if (sortOrder == SortOrder.DESCENDING) {
            filtered.sortedWith(comparator.reversed())
        } else {
            filtered.sortedWith(comparator)
        }
    }
}
