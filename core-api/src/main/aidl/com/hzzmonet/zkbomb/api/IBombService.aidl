package com.hzzmonet.zkbomb.api;

import com.hzzmonet.zkbomb.api.BombCapabilities;
import com.hzzmonet.zkbomb.api.BombResult;
import com.hzzmonet.zkbomb.api.FreezeStatus;
import com.hzzmonet.zkbomb.api.LogStatus;
import com.hzzmonet.zkbomb.api.MemoryConfig;
import com.hzzmonet.zkbomb.api.MemoryStatus;
import com.hzzmonet.zkbomb.api.ProcessSnapshot;
import com.hzzmonet.zkbomb.api.SystemTelemetrySnapshot;

/**
 * The privileged surface. Everything the UI can ask Bomb to do passes through
 * here, and nothing else does.
 *
 * ## What is permanently absent
 *
 * There is no executeShell, no writeFile, no writeSysfs, no setSystemProperty,
 * and no method anywhere on this interface takes a filesystem path from the
 * caller. That is not an oversight to be filled in later — a generic privileged
 * API would make every other control on this interface decorative, because a
 * caller could just do the thing directly. Where a file is genuinely involved
 * the caller passes a file descriptor it already holds, never a path.
 *
 * ## Rules every added method must satisfy
 *
 * 1. named for what it does, not the mechanism it uses;
 * 2. every argument bounded — enum name, id, validated package, numeric range;
 * 3. files passed as an fd the caller already holds, never a path;
 * 4. no combination of legitimate calls yields an unintended effect;
 * 5. returns a BombResult distinguishing unsupported / denied / invalid /
 *    backend-unavailable / failed.
 *
 * ## Compatibility
 *
 * Append-only. Never reorder a method, never repurpose a transaction, never
 * change a signature — a Bomb app and a Bomb service can be updated separately
 * (in ROM mode the service ships with the ROM), so an old client will call a new
 * service and must not land on a different method than it meant to.
 *
 * Enum-valued arguments cross as their **names**, validated server-side against
 * the domain enum. An ordinal is a number whose meaning shifts the moment
 * someone inserts a constant; a name that no longer exists fails validation
 * loudly and returns INVALID_ARGUMENT.
 */
interface IBombService {

    /**
     * Contract version. Incremented when methods are appended, never otherwise.
     * A client uses it to know which calls exist before making them.
     */
    int getApiVersion();

    /**
     * What this device can actually do, freshly probed.
     *
     * Nothing on this interface may be assumed available; a UI binds, asks this,
     * and disables what is not supported.
     */
    BombCapabilities getCapabilities();

    // ---- Freeze Engine -----------------------------------------------------

    /**
     * @param packageName validated for package-name shape server-side
     * @param userId must be >= 0
     */
    FreezeStatus getFreezeStatus(String packageName, int userId);

    /**
     * @param mode a FreezeMode name — NORMAL, SOFT_FREEZE, DEEP_FREEZE, DISABLED
     *
     * The service resolves the mode to a mechanism itself; the caller states
     * intent and never names suspend, hide or disable directly. Protected
     * packages are refused with the reason. Unfreezing is never refused by
     * protection, or a protected package that somehow got frozen would be
     * unrecoverable from inside Bomb.
     */
    BombResult setFreezeMode(String packageName, int userId, String mode);

    // ---- Log Governor ------------------------------------------------------

    LogStatus getLogStatus();

    /**
     * @param level a LogLevel name — DEFAULT, REDUCED, OFF
     * @param auditRatePerSecond SELinux denial cap, or -1 for the platform
     *   default. Only meaningful for REDUCED.
     *
     * One typed call over an enumerated set of properties, not a property write.
     * OFF is handled by the integrated ROM's typed init trigger and requires no
     * root-mode session. It returns UNSUPPORTED when that ROM backend is absent,
     * and is refused outright while a Bomb SELinux bring-up is in progress —
     * turning off the denial channel during the work that depends on reading
     * denials is a foot-gun.
     */
    BombResult setLogLevel(String level, int auditRatePerSecond);

    // ---- Memory / ZRAM -----------------------------------------------------

    MemoryStatus getMemoryStatus();

    /**
     * Size and algorithm apply at the next boot; swappiness and page-cluster are
     * live. There is no live resize path, because a live resize faults every
     * swapped page back into RAM at once.
     */
    BombResult setMemoryConfig(in MemoryConfig config);

    // ---- Appended in contract version 2 ------------------------------------
    //
    // New methods go HERE, at the end, and nowhere else. AIDL numbers
    // transactions in declaration order, so inserting a method next to a related
    // one — which is where it reads best — silently renumbers every method below
    // it. An old client would then land on a different method than it called,
    // with arguments that happen to unmarshal. Readability is not worth that.

    /**
     * How Bomb is deployed here: a BombRuntimeMode name — NORMAL, ROM or ROOT.
     *
     * ROM mode is declared by the image itself through `ro.bomb.integrated=1`.
     * That is an assertion by whoever assembled the ROM, which is the only party
     * that knows whether the priv-app placement, the permission allowlist and
     * the SELinux policy were actually integrated — none of which an app can
     * establish from the inside.
     *
     * It is a **mode, not a capability answer**. Capabilities stay probed: the
     * flag says the integration was intended, a probe says whether a given
     * operation works. Letting the flag stand in for the measurement is exactly
     * what master plan §3.3 rules out, and it would make Bomb claim features on
     * any device where the property happens to be set.
     */
    String getRuntimeMode();

    // ---- Appended in contract version 3 ------------------------------------

    /**
     * A bounded snapshot of processes visible through ActivityManager. Fields
     * that the framework or SELinux withholds are null, never fabricated.
     */
    ProcessSnapshot getProcessSnapshot();

    /** Stable system-wide memory, power, thermal, traffic and CPU counters. */
    SystemTelemetrySnapshot getSystemTelemetrySnapshot();

    // ---- Appended in contract version 4 ------------------------------------

    /**
     * Alias/convenience method for getProcessSnapshot().
     */
    ProcessSnapshot getProcessList();

    /**
     * Alias method for setFreezeMode(), updating package freeze state.
     * @param packageName validated package name
     * @param userId user ID (>= 0)
     * @param freezeState target FreezeMode name (NORMAL, SOFT_FREEZE, DEEP_FREEZE, DISABLED)
     */
    BombResult setFreezeState(String packageName, int userId, String freezeState);

    /**
     * Alias/convenience method for getSystemTelemetrySnapshot().
     */
    SystemTelemetrySnapshot getTelemetrySnapshot();
}

