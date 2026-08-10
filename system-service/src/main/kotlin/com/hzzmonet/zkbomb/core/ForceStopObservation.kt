package com.hzzmonet.zkbomb.core

import com.hzzmonet.zkbomb.api.BombResult

/** Fail-closed observation of whether a package still owns a running process. */
internal enum class ProcessObservation {
    RUNNING,
    STOPPED,
    UNKNOWN,
}

/**
 * Converts the nullable/throwing ActivityManager process query into an explicit
 * state. A non-null inventory with no matching package proves STOPPED; an
 * unavailable inventory or an unidentified process proves nothing.
 */
internal class PackageProcessObserver(
    private val queryPackages: () -> List<List<String>?>?,
) {
    fun observe(packageName: String): ProcessObservation = runCatching {
        val processes = queryPackages() ?: return ProcessObservation.UNKNOWN
        when {
            processes.any { packages -> packages?.contains(packageName) == true } ->
                ProcessObservation.RUNNING
            processes.any { it == null } -> ProcessObservation.UNKNOWN
            else -> ProcessObservation.STOPPED
        }
    }.getOrDefault(ProcessObservation.UNKNOWN)
}

/** Bounded post-force-stop verification shared by production and unit tests. */
internal class ForceStopVerifier(
    private val attempts: Int,
    private val delayMillis: Long,
    private val sleep: (Long) -> Unit,
) {
    init {
        require(attempts > 0)
        require(delayMillis >= 0L)
    }

    fun verify(
        stoppedFlag: () -> Boolean,
        processObservation: () -> ProcessObservation,
    ): BombResult {
        var lastObservation = ProcessObservation.UNKNOWN
        repeat(attempts) { attempt ->
            val stopped = runCatching(stoppedFlag).getOrDefault(false)
            lastObservation = runCatching(processObservation)
                .getOrDefault(ProcessObservation.UNKNOWN)
            if (stopped && lastObservation == ProcessObservation.STOPPED) {
                return BombResult.success()
            }
            if (attempt + 1 < attempts) sleep(delayMillis)
        }

        return if (lastObservation == ProcessObservation.UNKNOWN) {
            BombResult.backendUnavailable(
                "Running-process observation remained unavailable after force-stop",
            )
        } else {
            BombResult.failed("PackageManager did not confirm stopped state after force-stop")
        }
    }
}
