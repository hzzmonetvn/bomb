package com.hzzmonet.zkbomb.domain.telemetry

import com.hzzmonet.zkbomb.domain.model.TelemetrySnapshot

/**
 * Functional collector interface for telemetry snapshot polling.
 */
fun interface TelemetryCollector {
    suspend fun collect(): TelemetrySnapshot
}
