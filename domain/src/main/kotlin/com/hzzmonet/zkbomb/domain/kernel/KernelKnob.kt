package com.hzzmonet.zkbomb.domain.kernel

/**
 * Which backend can actually write a kernel knob on this device.
 *
 * This is the field the UI turns into "needs root". It is **not** a guess: each
 * value in [KernelKnobRegistry] was derived from the target ROM's own SELinux
 * policy — whether `init` holds a write rule on the node's type — because that
 * is what decides whether Bomb's init-trigger path can reach it at all.
 */
enum class KnobBackend {
    /**
     * `init` can write it. Reachable in ROM mode with **no root**, through the
     * same property-trigger path the Log Governor and swappiness already use.
     */
    INIT,

    /**
     * No `init` rule exists, but `bombd` could be granted one.
     *
     * Reachable in ROM mode **only after adding an SELinux rule**, which is a
     * deliberate policy decision per node, not something to do in bulk. Until
     * that rule exists this behaves like [ROOT].
     */
    BOMBD_WITH_NEW_RULE,

    /**
     * Only the root module reaches it.
     *
     * The node's type is owned by vendor domains Bomb cannot join, so no amount
     * of ROM-side integration helps. This is the honest "needs root".
     */
    ROOT,

    /**
     * Not established on this device.
     *
     * Reported as unusable. A knob nobody has checked must not be offered as
     * though it worked — that is the failure the whole capability model exists
     * to prevent.
     */
    UNVERIFIED,
}

/**
 * Whether something else writes the same node.
 *
 * The hard-won lesson from the thermal and charging investigation: permission
 * is not the only question. A node a vendor daemon rewrites on every sample
 * cannot be controlled by writing it once — the write lands and is then undone,
 * which looks exactly like the write having failed.
 */
enum class KnobContention {
    /** Nothing else writes it. A write sticks. */
    NONE,

    /**
     * A vendor daemon owns it and rewrites it continuously.
     *
     * Writing is unreliable by construction, not by bug. The supported way to
     * change these is to reconfigure the daemon at build time, not to fight it
     * at runtime.
     */
    VENDOR_DAEMON,

    /** Written by something else occasionally — the value may not survive. */
    OCCASIONAL,

    /** Not established. */
    UNKNOWN,
}

/** What values a knob accepts. */
sealed interface KnobValueDomain {

    /** A bounded integer. */
    data class Bounded(val min: Int, val max: Int, val unit: String? = null) : KnobValueDomain

    /**
     * One of a fixed set the **device** reports, not a hardcoded list.
     *
     * CPU governors, I/O schedulers and GPU power levels all differ by kernel,
     * and every one of them publishes its own set. [available] is filled in by
     * reading the node; an empty list means it has not been read yet, and the
     * knob is not offerable until it has.
     */
    data class Probed(val available: List<String> = emptyList()) : KnobValueDomain

    /** `0` or `1`. */
    data object Flag : KnobValueDomain
}

/** Display grouping. */
enum class KnobGroup { CPU, GPU, MEMORY, IO, SCHEDULER, NETWORK, THERMAL, POWER }

/**
 * One tunable, and everything needed to decide whether to offer it.
 *
 * The three fields that matter most are [backend], [contention] and
 * [evidence] — respectively "can we write it", "will the write survive", and
 * "how do we know". A knob missing any of them is not ready to ship.
 */
data class KernelKnob(
    val id: String,
    /** The node, as an absolute path. Never taken from a caller. */
    val path: String,
    val group: KnobGroup,
    val valueDomain: KnobValueDomain,
    val backend: KnobBackend,
    val contention: KnobContention,
    /** The SELinux type the node carries, where established. */
    val selinuxType: String? = null,
    /** How [backend] and [contention] were established. Never left empty. */
    val evidence: String,
    /** What the user is actually changing, in their terms. */
    val summary: String,
) {
    /**
     * True when Bomb can write this today without a root backend.
     *
     * Deliberately requires **both** a reachable backend and a write that
     * survives. A knob a daemon overwrites is not "supported" merely because the
     * write is permitted.
     */
    val usableInRomMode: Boolean
        get() = backend == KnobBackend.INIT && contention == KnobContention.NONE

    /** True when only the root module can reach it. */
    val requiresRoot: Boolean
        get() = backend == KnobBackend.ROOT

    /**
     * A one-line reason for the UI when the knob is not offerable.
     *
     * Distinguishing these matters: "needs root" and "a daemon will undo this"
     * are different problems with different answers, and collapsing them into
     * "unavailable" tells the user nothing they can act on.
     */
    fun unavailableReason(): String? = when {
        backend == KnobBackend.UNVERIFIED ->
            "Not verified on this device yet"
        contention == KnobContention.VENDOR_DAEMON ->
            "A vendor daemon rewrites this continuously — a write will not hold"
        backend == KnobBackend.ROOT ->
            "Needs root: the node is owned by a domain Bomb cannot join"
        backend == KnobBackend.BOMBD_WITH_NEW_RULE ->
            "Needs an SELinux rule that has not been added"
        valueDomain is KnobValueDomain.Probed && valueDomain.available.isEmpty() ->
            "The device has not reported which values it accepts"
        else -> null
    }
}
