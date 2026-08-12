package com.hzzmonet.zkbomb.api

/**
 * How Bomb is deployed on this device.
 *
 * A deployment fact, not a user preference. The Mode screen used to let the user
 * pick between ROM and Root and then describe whichever they picked — which
 * meant the app reported "integrated · priv-app + bombd" on a plain sideloaded
 * install because someone had tapped a segmented button.
 */
enum class BombRuntimeMode {
    /**
     * An ordinary app install. Every privileged operation is unavailable, and
     * the reads that work do so through public APIs.
     */
    NORMAL,

    /**
     * The ROM declares it integrated Bomb, via `ro.bomb.integrated=1`, or
     * PackageManager reports Bomb as the preserved privileged system app after
     * its active APK is updated into `/data/app`.
     *
     * The property is a claim by the image builder — the only party who knows
     * The property and PackageManager identity are trusted for **what mode to
     * report**, and for nothing else. Permission grants, bombd reachability and
     * each operation remain capability-probed, so an incomplete privapp XML or
     * SELinux integration cannot turn a control green merely from placement.
     */
    ROM,

    /**
     * A root backend is present — a Magisk/KernelSU module carrying the policy
     * and the daemon.
     */
    ROOT,
    ;

    companion object {
        /** The property a ROM sets to declare Bomb integrated. */
        const val ROM_MARKER_PROPERTY = "ro.bomb.integrated"

        /** Marker used by early development images; read-only compatibility. */
        const val LEGACY_ROM_MARKER_PROPERTY = "persist.sys.zk.bomb"

        /**
         * The value that counts as "declared".
         *
         * Exactly `"1"`. Not "any non-empty value": a property left as `"0"`,
         * `"false"` or an empty string is a device that has *not* been
         * integrated, and treating those as true is how a flag ends up meaning
         * the opposite of what it says.
         */
        const val ROM_MARKER_VALUE = "1"

        /** Parses a name from the wire, defaulting to the least-privileged reading. */
        fun parse(name: String?): BombRuntimeMode =
            entries.firstOrNull { it.name == name } ?: NORMAL
    }
}
