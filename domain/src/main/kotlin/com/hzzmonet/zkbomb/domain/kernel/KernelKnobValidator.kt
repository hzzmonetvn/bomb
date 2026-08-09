package com.hzzmonet.zkbomb.domain.kernel

/** Why a requested knob value was refused. */
enum class KnobViolation {
    /** No knob with that id. */
    UNKNOWN_KNOB,

    /** Value outside the declared bounds. */
    OUT_OF_RANGE,

    /** Value is not one the device reported. */
    NOT_AN_ACCEPTED_VALUE,

    /** The device has not reported which values it accepts, so none can be validated. */
    DOMAIN_NOT_PROBED,

    /** Not a number where a number is required. */
    MALFORMED,

    /** No backend on this device can write it. */
    NO_BACKEND,

    /**
     * A vendor daemon rewrites this node.
     *
     * Refused rather than attempted: the write would be permitted, land, and
     * then be undone within a sample interval. Reporting success for that is
     * worse than refusing, because the user sees the value revert and has no way
     * to tell whether Bomb failed or the setting simply does nothing.
     */
    CONTESTED_BY_DAEMON,
}

/**
 * Validates a knob change before anything privileged is attempted.
 *
 * Pure. Every input — the requested value, the probed value domain, whether a
 * root backend is present — is passed in, so the whole decision table runs as
 * plain JUnit.
 */
object KernelKnobValidator {

    /**
     * @param rootBackendAvailable whether the root module is installed and
     *   responding. Only consulted for knobs whose backend is [KnobBackend.ROOT];
     *   it never upgrades a knob a daemon contests.
     */
    fun validate(
        knobId: String,
        rawValue: String,
        rootBackendAvailable: Boolean,
    ): List<KnobViolation> {
        val knob = KernelKnobRegistry.byId(knobId)
            ?: return listOf(KnobViolation.UNKNOWN_KNOB)

        return buildList {
            // Contention first. A knob a daemon owns is refused whatever the
            // value and whatever backend is available — the problem is not
            // permission, and root does not fix it.
            if (knob.contention == KnobContention.VENDOR_DAEMON) {
                add(KnobViolation.CONTESTED_BY_DAEMON)
            }

            val reachable = when (knob.backend) {
                KnobBackend.INIT -> true
                KnobBackend.ROOT -> rootBackendAvailable
                KnobBackend.BOMBD_WITH_NEW_RULE -> false
                KnobBackend.UNVERIFIED -> false
            }
            if (!reachable) add(KnobViolation.NO_BACKEND)

            when (val domain = knob.valueDomain) {
                is KnobValueDomain.Bounded -> {
                    val parsed = rawValue.toIntOrNull()
                    if (parsed == null) {
                        add(KnobViolation.MALFORMED)
                    } else if (parsed < domain.min || parsed > domain.max) {
                        add(KnobViolation.OUT_OF_RANGE)
                    }
                }

                is KnobValueDomain.Probed -> {
                    if (domain.available.isEmpty()) {
                        add(KnobViolation.DOMAIN_NOT_PROBED)
                    } else if (rawValue !in domain.available) {
                        add(KnobViolation.NOT_AN_ACCEPTED_VALUE)
                    }
                }

                KnobValueDomain.Flag -> {
                    if (rawValue != "0" && rawValue != "1") add(KnobViolation.MALFORMED)
                }
            }
        }
    }

    fun isAllowed(knobId: String, rawValue: String, rootBackendAvailable: Boolean): Boolean =
        validate(knobId, rawValue, rootBackendAvailable).isEmpty()
}
