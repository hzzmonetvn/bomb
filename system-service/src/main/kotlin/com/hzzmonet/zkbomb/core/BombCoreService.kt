package com.hzzmonet.zkbomb.core

import android.app.ActivityManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Process
import com.hzzmonet.zkbomb.api.BombCapabilities
import com.hzzmonet.zkbomb.api.BombResult
import com.hzzmonet.zkbomb.api.BombRuntimeMode
import com.hzzmonet.zkbomb.api.FreezeStatus
import com.hzzmonet.zkbomb.api.IBombService
import com.hzzmonet.zkbomb.api.LogStatus
import com.hzzmonet.zkbomb.api.MemoryConfig
import com.hzzmonet.zkbomb.api.MemoryStatus
import com.hzzmonet.zkbomb.api.ProcessSnapshot
import com.hzzmonet.zkbomb.api.SystemTelemetrySnapshot
import com.hzzmonet.zkbomb.domain.freeze.FreezeMode
import com.hzzmonet.zkbomb.domain.freeze.PackageNameValidator
import com.hzzmonet.zkbomb.domain.log.LogLevel
import com.hzzmonet.zkbomb.domain.log.LogProfile
import com.hzzmonet.zkbomb.domain.log.LogTransitionPlanner
import com.hzzmonet.zkbomb.domain.log.LogTransition
import com.hzzmonet.zkbomb.domain.memory.ZramConfig
import com.hzzmonet.zkbomb.domain.memory.ZramConfigValidator

/**
 * The bound service behind [IBombService].
 *
 * Shape of every call, without exception:
 *
 * ```
 * validate caller → validate arguments → check capability → execute → BombResult
 * ```
 *
 * Nothing shortcuts that order. Validating arguments before the caller would
 * leak, through error messages, what a rejected caller asked about; checking the
 * capability before the arguments would report "unsupported" for a request that
 * was malformed anyway.
 *
 * ROM-only writes cross the fixed bombd socket protocol. This app process never
 * receives property-service, sysfs or shell access; a sideloaded build simply
 * fails the daemon capability probe and reports the operation unsupported.
 */
class BombCoreService : Service() {

    private val properties by lazy { SystemPropertyReader() }
    private val controlWriter by lazy { RomControlPropertyWriter() }
    private val zramReader by lazy { ZramReader() }
    private val freezeBackend by lazy { FreezeBackend(this) }
    private val processTelemetry by lazy { ProcessTelemetryBackend(this) }
    private val logReader by lazy { LogStateReader(properties) }
    private val modeDetector by lazy { RuntimeModeDetector(this, properties) }
    private val capabilityProbe by lazy {
        CapabilityProbe(
            this,
            zramReader,
            properties,
            modeDetector,
            controlWriter,
            freezeBackend,
            processTelemetry,
        )
    }
    private val logPlanner = LogTransitionPlanner()

    private val callerPolicy by lazy {
        CallerPolicy(
            selfUid = Process.myUid(),
            allowedPackages = setOf(packageName),
        )
    }

    private val validator by lazy { CallerValidator(packageManager, callerPolicy) }

    override fun onBind(intent: Intent?): IBinder = binder

    private val binder = object : IBombService.Stub() {

        override fun getApiVersion(): Int = API_VERSION

        override fun getCapabilities(): BombCapabilities {
            if (!validator.isAllowed(Binder.getCallingUid())) return BombCapabilities.NONE
            return capabilityProbe.probe()
        }

        override fun getRuntimeMode(): String {
            // Refused callers get NORMAL, the least-privileged reading, rather
            // than an error: a caller that may not ask has no business learning
            // how the device is set up either.
            if (!validator.isAllowed(Binder.getCallingUid())) return BombRuntimeMode.NORMAL.name
            return modeDetector.detect().name
        }

        // ---- Freeze --------------------------------------------------------

        override fun getFreezeStatus(packageName: String?, userId: Int): FreezeStatus? {
            if (!validator.isAllowed(Binder.getCallingUid())) return null
            if (packageName == null || !PackageNameValidator.isValid(packageName)) return null
            if (userId < 0) return null

            return freezeBackend.status(packageName, userId)
        }

        override fun setFreezeMode(packageName: String?, userId: Int, mode: String?): BombResult {
            validator.verdictFor(Binder.getCallingUid())?.let { return it }
            if (packageName == null || !PackageNameValidator.isValid(packageName)) {
                return BombResult.invalidArgument("packageName")
            }
            if (userId < 0) return BombResult.invalidArgument("userId")
            val parsed = mode?.let { name -> FreezeMode.entries.firstOrNull { it.name == name } }
                ?: return BombResult.invalidArgument("mode")

            if (!modeDetector.romDeclared()) {
                return BombResult.unsupported("Freeze needs the integrated ROM backend")
            }
            return freezeBackend.setMode(packageName, userId, parsed)
        }

        // ---- Log Governor --------------------------------------------------

        override fun getLogStatus(): LogStatus? {
            if (!validator.isAllowed(Binder.getCallingUid())) return null
            val observed = logReader.observe() ?: return null
            val effective = observed.effectiveLevel()
            return LogStatus(
                declaredLevel = observed.declaredLevel.name,
                effectiveLevel = effective.name,
                rebootRequiredToChange = effective == LogLevel.OFF,
                auditRatePerSecond = logReader.auditRatePerSecond(),
                vendorLogSinkActive = logReader.probe(
                    rootBackendAvailable = modeDetector.detect() == BombRuntimeMode.ROOT,
                    romBackendAvailable = modeDetector.romDeclared(),
                ).ylogPresent,
            )
        }

        override fun setLogLevel(level: String?, auditRatePerSecond: Int): BombResult {
            validator.verdictFor(Binder.getCallingUid())?.let { return it }
            val parsed = level?.let { name -> LogLevel.entries.firstOrNull { it.name == name } }
                ?: return BombResult.invalidArgument("level")

            val profile = LogProfile(
                level = parsed,
                // -1 is the wire form of "leave the platform default alone";
                // AIDL has no nullable int and a sentinel is clearer than an
                // extra boolean parameter that could disagree with the value.
                auditRatePerSecond = auditRatePerSecond.takeIf { it >= 0 },
            )

            // Validate through the same planner the privileged path will use, so
            // a bad request is rejected identically in both modes rather than
            // only once the backend exists.
            logPlanner.validate(profile).takeIf { it.isNotEmpty() }?.let {
                return BombResult.invalidArgument(it.joinToString())
            }

            val observed = logReader.observe()
                ?: return BombResult.backendUnavailable("System properties are not readable")

            val runtimeMode = modeDetector.detect()
            val capabilities = logReader.probe(
                rootBackendAvailable = runtimeMode == BombRuntimeMode.ROOT,
                romBackendAvailable = runtimeMode == BombRuntimeMode.ROM && controlWriter.available,
            )
            return when (
                val transition = logPlanner.plan(
                    profile,
                    observed,
                    capabilities,
                    selinuxBringUpInProgress =
                        properties.get("persist.sys.bomb.selinux_bringup") == "1",
                )
            ) {
                is LogTransition.Unsupported ->
                    BombResult.unsupported("${transition.level} — ${transition.reason}")
                is LogTransition.RebootRequired ->
                    BombResult.failed("Leaving ${transition.from} needs a reboot")
                is LogTransition.AlreadyAtLevel -> BombResult.success()
                is LogTransition.Invalid -> BombResult.invalidArgument(transition.violations.joinToString())
                is LogTransition.Refused -> BombResult.permissionDenied(transition.reason.name)
                is LogTransition.Apply -> when {
                    runtimeMode != BombRuntimeMode.ROM ->
                        BombResult.unsupported("This build has no integrated ROM log backend")
                    !controlWriter.requestLogLevel(parsed, profile.auditRatePerSecond) ->
                        BombResult.backendUnavailable("ROM control property was rejected")
                    else -> verifyLogLevel(parsed)
                }
            }
        }

        // ---- Memory --------------------------------------------------------

        override fun getMemoryStatus(): MemoryStatus? {
            if (!validator.isAllowed(Binder.getCallingUid())) return null
            val capabilities = zramReader.probe()
            val stat = zramReader.mmStat()
            val algorithms = zramReader.algorithms()
            return MemoryStatus(
                zramPresent = capabilities.present,
                disksizeBytes = zramReader.disksizeBytes(),
                origDataSize = stat?.origDataSize,
                comprDataSize = stat?.comprDataSize,
                memUsedTotal = stat?.memUsedTotal,
                compressionRatio = stat?.compressionRatio,
                ramEfficiency = stat?.ramEfficiency,
                currentAlgorithm = algorithms?.current,
                availableAlgorithms = algorithms?.available.orEmpty(),
                globalSwappiness = zramReader.globalSwappiness(),
                swappinessByTarget = zramReader.swappinessByTarget(),
                memoryExtensionSwitches = memoryExtensionSwitches(),
            )
        }

        override fun setMemoryConfig(config: MemoryConfig?): BombResult {
            validator.verdictFor(Binder.getCallingUid())?.let { return it }
            if (config == null) return BombResult.invalidArgument("config")

            val violations = ZramConfigValidator.validate(
                ZramConfig(
                    disksizeBytes = config.disksizeBytes,
                    algorithm = config.algorithm,
                    swappiness = config.swappiness,
                    pageCluster = config.pageCluster,
                ),
                zramReader.probe(),
                zramReader.algorithms(),
                totalRamBytes = totalRamBytes(),
            )
            if (violations.isNotEmpty()) {
                return BombResult.invalidArgument(violations.joinToString())
            }
            if (config.disksizeBytes != null || config.algorithm != null) {
                return BombResult.unsupported(
                    "ZRAM size and algorithm are boot-time-only and are not integrated yet",
                )
            }
            if (!modeDetector.romDeclared()) {
                return BombResult.unsupported("Memory control needs the integrated ROM backend")
            }
            if (config.swappiness == null && config.pageCluster == null) {
                return BombResult.success()
            }
            if (!controlWriter.requestMemory(config.swappiness, config.pageCluster)) {
                return BombResult.backendUnavailable("ROM memory control property was rejected")
            }
            repeat(MEMORY_VERIFY_ATTEMPTS) {
                val swappinessApplied = config.swappiness == null ||
                    zramReader.globalSwappiness() == config.swappiness
                val pageClusterApplied = config.pageCluster == null ||
                    zramReader.pageCluster() == config.pageCluster
                if (swappinessApplied && pageClusterApplied) return BombResult.success()
                android.os.SystemClock.sleep(MEMORY_VERIFY_DELAY_MS)
            }
            return BombResult.failed(
                "init accepted the request but read-back differs: " +
                    "swappiness=${zramReader.globalSwappiness()}, " +
                    "page-cluster=${zramReader.pageCluster()}",
            )
        }

        // ---- Task Manager / Stats Core (contract version 3 & 4) ------------

        override fun getProcessSnapshot(): ProcessSnapshot? {
            if (!validator.isAllowed(Binder.getCallingUid())) return null
            return processTelemetry.processSnapshot()
        }

        override fun getSystemTelemetrySnapshot(): SystemTelemetrySnapshot? {
            if (!validator.isAllowed(Binder.getCallingUid())) return null
            return processTelemetry.systemTelemetrySnapshot()
        }

        override fun getProcessList(): ProcessSnapshot? = getProcessSnapshot()

        override fun setFreezeState(packageName: String?, userId: Int, freezeState: String?): BombResult =
            setFreezeMode(packageName, userId, freezeState)

        override fun getTelemetrySnapshot(): SystemTelemetrySnapshot? = getSystemTelemetrySnapshot()
    }

    /**
     * Both switches that claim to control Memory Extension, reported side by
     * side.
     *
     * `perfinit.conf` declares `extm_on: 1` while `build.prop` carries
     * `persist.miui.extm.enable=0`. Two switches, one feature, and no way from
     * here to tell which wins — so both are shown rather than one being picked
     * and presented as the truth.
     */
    private fun memoryExtensionSwitches(): Map<String, String> = buildMap {
        properties.get("persist.miui.extm.enable")?.let { put("persist.miui.extm.enable", it) }
        properties.get("ro.miui.extm.enable")?.let { put("ro.miui.extm.enable", it) }
    }

    private fun totalRamBytes(): Long {
        val info = ActivityManager.MemoryInfo()
        getSystemService(ActivityManager::class.java)?.getMemoryInfo(info)
        return info.totalMem
    }

    private fun verifyLogLevel(expected: LogLevel): BombResult {
        repeat(CONTROL_VERIFY_ATTEMPTS) {
            val effective = logReader.observe()?.effectiveLevel()
            if (effective == expected) return BombResult.success()
            android.os.SystemClock.sleep(CONTROL_VERIFY_DELAY_MS)
        }
        return BombResult.failed(
            "init accepted the request but effective log level is " +
                (logReader.observe()?.effectiveLevel()?.name ?: "unreadable"),
        )
    }

    private companion object {
        /**
         * Bumped only when methods are appended to [IBombService], never for an
         * implementation change. A client reads it to know which calls exist.
         *
         * 2 — added `getRuntimeMode()`.
         * 3 — added process and system telemetry snapshots.
         * 4 — added getProcessList(), setFreezeState(), getTelemetrySnapshot().
         */
        const val API_VERSION = 4
        const val MEMORY_VERIFY_ATTEMPTS = 10
        const val MEMORY_VERIFY_DELAY_MS = 50L
        const val CONTROL_VERIFY_ATTEMPTS = 10
        const val CONTROL_VERIFY_DELAY_MS = 50L
    }
}
