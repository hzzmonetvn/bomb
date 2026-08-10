package com.hzzmonet.zkbomb.domain.telemetry

/** The only refresh intervals accepted by the telemetry subscription contract. */
enum class TelemetrySamplingInterval(val millis: Int) {
    MS_500(500),
    MS_1000(1_000),
    MS_2000(2_000),
    MS_5000(5_000),
    ;

    companion object {
        /** Returns null unless [millis] exactly matches a supported interval. */
        fun fromMillis(millis: Int): TelemetrySamplingInterval? =
            entries.firstOrNull { it.millis == millis }
    }
}

/** Relative sampling cost used to decide which metrics may be read at an interval. */
enum class TelemetryCostClass {
    CHEAP,
    MODERATE,
    EXPENSIVE,
    PLATFORM_LIMITED,
}

/**
 * Pure M4 cost gate. Callers sample only allowed costs for the current frame;
 * a disallowed metric remains unavailable and must never be carried forward.
 */
object TelemetrySamplingPolicy {
    private val allowedCosts = mapOf(
        TelemetrySamplingInterval.MS_500 to setOf(
            TelemetryCostClass.CHEAP,
        ),
        TelemetrySamplingInterval.MS_1000 to setOf(
            TelemetryCostClass.CHEAP,
            TelemetryCostClass.MODERATE,
        ),
        TelemetrySamplingInterval.MS_2000 to setOf(
            TelemetryCostClass.CHEAP,
            TelemetryCostClass.MODERATE,
            TelemetryCostClass.EXPENSIVE,
        ),
        TelemetrySamplingInterval.MS_5000 to TelemetryCostClass.entries.toSet(),
    )

    fun allowedCostsFor(interval: TelemetrySamplingInterval): Set<TelemetryCostClass> =
        allowedCosts.getValue(interval)

    fun allows(
        interval: TelemetrySamplingInterval,
        cost: TelemetryCostClass,
    ): Boolean = cost in allowedCostsFor(interval)
}
