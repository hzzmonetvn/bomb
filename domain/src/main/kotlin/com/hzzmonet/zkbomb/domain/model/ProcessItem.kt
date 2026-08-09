package com.hzzmonet.zkbomb.domain.model

/**
 * Standard Process item representation in the pure JVM domain module.
 */
data class ProcessItem(
    val pid: Int,
    val uid: Int,
    val userId: Int,
    val processName: String,
    val packageNames: List<String> = emptyList(),
    val importance: Int = 100,
    val importanceReasonCode: Int = 0,
    val isForeground: Boolean = false,
    val pssBytes: Long? = null,
    val privateDirtyBytes: Long? = null,
    val rssBytes: Long? = null,
    val cpuTimeTicks: Long? = null,
    val threadCount: Int? = null,
    val startTimeTicks: Long? = null,
    val category: ProcessCategory = ProcessCategory.UNKNOWN,
) {
    fun isValid(): Boolean {
        if (pid <= 0) return false
        if (uid < 0) return false
        if (userId < 0) return false
        if (processName.isBlank()) return false
        return true
    }
}
