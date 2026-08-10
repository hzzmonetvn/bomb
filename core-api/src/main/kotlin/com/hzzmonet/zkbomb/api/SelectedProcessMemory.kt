package com.hzzmonet.zkbomb.api

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/** Stable result states for the API v7 selected-process memory call. */
enum class SelectedProcessMemoryStatus {
    AVAILABLE,
    INVALID_PID,
    NOT_VISIBLE,
    DISAPPEARED,
    UNAVAILABLE,
    PERMISSION_DENIED,
}

/**
 * One explicitly selected process's expensive memory sample.
 *
 * Metric fields are nullable independently. A platform returning zero or
 * withholding one metric is represented as null, never as a fabricated zero.
 * [status] crosses Binder as an enum name so future states remain append-safe.
 */
@Parcelize
data class SelectedProcessMemory(
    val pid: Int,
    val sampledAtElapsedRealtimeMillis: Long,
    val status: String,
    val pssBytes: Long?,
    val privateDirtyBytes: Long?,
    val rssBytes: Long?,
) : Parcelable {
    val parsedStatus: SelectedProcessMemoryStatus
        get() = runCatching { SelectedProcessMemoryStatus.valueOf(status) }
            .getOrDefault(SelectedProcessMemoryStatus.UNAVAILABLE)

    val available: Boolean get() = parsedStatus == SelectedProcessMemoryStatus.AVAILABLE

    companion object {
        fun unavailable(
            pid: Int,
            sampledAtElapsedRealtimeMillis: Long,
            status: SelectedProcessMemoryStatus,
        ): SelectedProcessMemory {
            require(status != SelectedProcessMemoryStatus.AVAILABLE)
            return SelectedProcessMemory(
                pid = pid,
                sampledAtElapsedRealtimeMillis = sampledAtElapsedRealtimeMillis,
                status = status.name,
                pssBytes = null,
                privateDirtyBytes = null,
                rssBytes = null,
            )
        }
    }
}
