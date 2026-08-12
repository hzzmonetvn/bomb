package com.hzzmonet.zkbomb.core

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.os.ParcelFileDescriptor
import com.hzzmonet.zkbomb.api.AutomationRuleParcel
import com.hzzmonet.zkbomb.api.AutomationRulesSnapshot
import com.hzzmonet.zkbomb.api.BatteryLabProfileParcel
import com.hzzmonet.zkbomb.api.BatteryLabSnapshot
import com.hzzmonet.zkbomb.api.BombLiveEventParcel
import com.hzzmonet.zkbomb.api.BombCapabilities
import com.hzzmonet.zkbomb.api.BombCapability
import com.hzzmonet.zkbomb.api.BombResult
import com.hzzmonet.zkbomb.api.BombRuntimeMode
import com.hzzmonet.zkbomb.api.BridgeStatusSnapshot
import com.hzzmonet.zkbomb.api.FirewallRuleParcel
import com.hzzmonet.zkbomb.api.FreezeStatus
import com.hzzmonet.zkbomb.api.FrequencyLimitRequestParcel
import com.hzzmonet.zkbomb.api.FrequencyScalingSnapshot
import com.hzzmonet.zkbomb.api.IBombService
import com.hzzmonet.zkbomb.api.LogStatus
import com.hzzmonet.zkbomb.api.MemoryConfig
import com.hzzmonet.zkbomb.api.MemoryStatus
import com.hzzmonet.zkbomb.api.PackageSnapshot
import com.hzzmonet.zkbomb.api.PerformanceProfilesSnapshot
import com.hzzmonet.zkbomb.api.ProcessSnapshot
import com.hzzmonet.zkbomb.api.RecordingBackendStatus
import com.hzzmonet.zkbomb.api.RecordingRequestParcel
import com.hzzmonet.zkbomb.api.SelectedProcessMemory
import com.hzzmonet.zkbomb.api.SelectedProcessMemoryStatus
import com.hzzmonet.zkbomb.api.SettingsAssignmentParcel
import com.hzzmonet.zkbomb.api.SettingsOverrideParcel
import com.hzzmonet.zkbomb.api.SystemTelemetrySnapshot
import com.hzzmonet.zkbomb.api.ThermalGuardianConfigParcel
import com.hzzmonet.zkbomb.api.VisibilityCallerPolicyParcel
import com.hzzmonet.zkbomb.domain.freeze.FreezeMode
import com.hzzmonet.zkbomb.domain.freeze.PackageNameValidator
import com.hzzmonet.zkbomb.domain.automation.AutomationRuleEngine
import com.hzzmonet.zkbomb.domain.automation.PerformanceProfile
import com.hzzmonet.zkbomb.domain.automation.ResolvedAutomationAction
import com.hzzmonet.zkbomb.domain.log.LogLevel
import com.hzzmonet.zkbomb.domain.log.LogProfile
import com.hzzmonet.zkbomb.domain.log.LogTransitionPlanner
import com.hzzmonet.zkbomb.domain.log.LogTransition
import com.hzzmonet.zkbomb.domain.memory.ZramConfig
import com.hzzmonet.zkbomb.domain.memory.ZramConfigValidator
import com.hzzmonet.zkbomb.domain.model.CapabilityKey
import com.hzzmonet.zkbomb.domain.recorder.PlatformRecordingPolicy
import com.hzzmonet.zkbomb.domain.recorder.RecordingKind
import com.hzzmonet.zkbomb.domain.settings.SettingsNamespace
import com.hzzmonet.zkbomb.domain.validation.ApiV6InputValidator
import java.util.concurrent.Executors

internal const val CURRENT_BOMB_API_VERSION = 13

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
 * Property writes cross the fixed bombd socket protocol. Device writes are
 * isolated in typed, allowlisted backends and remain disabled unless the exact
 * node is readable and writable under the installed policy. No Binder method
 * accepts a property name, filesystem path or shell command.
 */
class BombCoreService : Service() {

    private val properties by lazy { SystemPropertyReader() }
    private val controlWriter by lazy { RomControlPropertyWriter() }
    private val zramReader by lazy { ZramReader() }
    private val freezeBackend by lazy { FreezeBackend(this) }
    private val packageControl by lazy { PackageControlBackend(this, freezeBackend) }
    private val processTelemetry by lazy { ProcessTelemetryBackend(this) }
    private val powerSupplyBackend by lazy { PowerSupplyBackend() }
    private val logReader by lazy { LogStateReader(properties) }
    private val modeDetector by lazy { RuntimeModeDetector(this, properties) }
    private val liveUpdateBridge by lazy { LiveUpdateBridgeBackend.create(this, properties) }
    private val platformRecordingBackend by lazy {
        PlatformRecordingBackend.create(this, ::onRecordingSessionEnded)
    }
    private val frequencyScalingBackend by lazy { FrequencyScalingBackend() }
    private val capabilityProbe by lazy {
        CapabilityProbe(
            this,
            zramReader,
            properties,
            modeDetector,
            controlWriter,
            freezeBackend,
            processTelemetry,
            packageControl,
            powerSupplyBackend,
            performanceProfiles = performanceProfileBackend,
            liveUpdateBridge = liveUpdateBridge,
            recording = platformRecordingBackend,
            frequencyScaling = frequencyScalingBackend,
        )
    }
    private val logPlanner = LogTransitionPlanner()

    // ---- Phase 3 backends --------------------------------------------------

    private val visibilityBackend by lazy {
        VisibilityBackend(
            packageManager = packageManager,
            bombPackageName = packageName,
        )
    }

    private val settingsBackend by lazy { SettingsVirtualizationBackend() }

    private val firewallBackend by lazy { FirewallBackend() }

    private val adBlockEngine by lazy { AdBlockEngine() }

    // ---- Bomb Rules / Automation (contract version 8) ---------------------

    private val automationRepository by lazy { SharedPreferencesAutomationRuleRepository(this) }

    private val performanceProfileBackend by lazy {
        PerformanceProfileBackend(
            object : MemoryTuningPort {
                override fun available(): Boolean =
                    modeDetector.romDeclared() && controlWriter.available

                override fun currentSwappiness(): Int? = zramReader.globalSwappiness()

                override fun currentPageCluster(): Int? = zramReader.pageCluster()

                override fun request(swappiness: Int, pageCluster: Int): Boolean =
                    controlWriter.requestMemory(swappiness, pageCluster)

                override fun waitBeforeVerification() {
                    android.os.SystemClock.sleep(MEMORY_VERIFY_DELAY_MS)
                }
            },
        )
    }

    // ---- Live Updates / Performance Profiles (contract version 10) -------

    private val performanceProfileCoordinator by lazy {
        PerformanceProfileCoordinator(performanceProfileBackend)
    }
    private val phase7Executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "BombThermalGuardian").apply { isDaemon = true }
    }
    private val thermalGuardianSignals by lazy {
        ThermalGuardianSignalSource(this) { temperature ->
            runCatching {
                phase7Executor.execute {
                    performanceProfileCoordinator.evaluateTemperature(
                        temperature,
                        android.os.SystemClock.elapsedRealtime(),
                    )
                }
            }
        }
    }
    private val recordingExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "BombCallRecorder").apply { isDaemon = true }
    }
    private val recordingModeSignals: RecordingModeSignalSource by lazy {
        RecordingModeSignalSource(
            context = this,
            executor = recordingExecutor,
            onModeChanged = modeChanged@ { mode ->
                val status = platformRecordingBackend.status()
                val sessionId = status.activeSessionId ?: return@modeChanged
                val kind = status.activeKind?.let { name ->
                    RecordingKind.entries.firstOrNull { it.name == name }
                } ?: return@modeChanged
                if (mode != PlatformRecordingPolicy.expectedMode(kind)) {
                    platformRecordingBackend.stop(sessionId)
                    recordingModeSignals.stop()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                }
            },
        )
    }

    private val automationExecutor by lazy {
        BombAutomationActionExecutor(
            freeze = object : FreezeActionPort {
                override fun currentMode(packageName: String, userId: Int): FreezeMode? =
                    freezeBackend.status(packageName, userId)?.mode?.let { name ->
                        FreezeMode.entries.firstOrNull { it.name == name }
                    }

                override fun setMode(
                    packageName: String,
                    userId: Int,
                    mode: FreezeMode,
                ): BombResult = freezeBackend.setMode(packageName, userId, mode)
            },
            performance = performanceProfileBackend,
        )
    }

    private val automationCoordinator by lazy {
        val interactive = getSystemService(PowerManager::class.java)?.isInteractive != false
        AutomationCoordinator(
            repository = automationRepository,
            engine = AutomationRuleEngine(),
            executor = automationExecutor,
            screenInitiallyOn = interactive,
        )
    }

    private val automationSignals by lazy {
        AutomationSignalSource(this, automationCoordinator::onEvent)
    }

    // ---- Thermal & Battery Lab (contract version 9) -----------------------

    private val batteryLabStore by lazy { SharedPreferencesBatteryLabStateStore(this) }
    private val batteryLabCoordinator by lazy {
        BatteryLabCoordinator(powerSupplyBackend, batteryLabStore)
    }
    private val batteryLabExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "BombBatteryLab").apply { isDaemon = true }
    }
    private val batteryLabSignals by lazy {
        BatteryLabSignalSource(this) {
            runCatching {
                batteryLabExecutor.execute { batteryLabCoordinator.evaluate() }
            }
        }
    }

    // -----------------------------------------------------------------------

    private val callerPolicy by lazy {
        CallerPolicy(
            selfUid = Process.myUid(),
            allowedPackages = setOf(packageName),
        )
    }

    private val validator by lazy { CallerValidator(packageManager, callerPolicy) }

    override fun onCreate() {
        super.onCreate()
        if (automationRepository.isEnabled()) startAutomationSignals()
        if (batteryLabCoordinator.hasActiveProfile()) {
            startBatteryLabSignals()
            batteryLabExecutor.execute { batteryLabCoordinator.resumeAtStartup() }
        }
        if (performanceProfileCoordinator.hasThermalGuardian()) startThermalGuardianSignals()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (automationRepository.isEnabled()) startAutomationSignals()
        if (batteryLabCoordinator.hasActiveProfile()) startBatteryLabSignals()
        if (performanceProfileCoordinator.hasThermalGuardian()) startThermalGuardianSignals()
        return if (automationRepository.isEnabled() || batteryLabCoordinator.hasActiveProfile() ||
            performanceProfileCoordinator.hasThermalGuardian()
        ) {
            START_STICKY
        } else {
            START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        automationSignals.stop()
        automationCoordinator.setObserverRunning(false)
        batteryLabSignals.stop()
        batteryLabExecutor.execute { batteryLabCoordinator.pause() }
        batteryLabExecutor.shutdown()
        thermalGuardianSignals.stop()
        phase7Executor.execute { performanceProfileCoordinator.clearThermalGuardian() }
        phase7Executor.shutdown()
        platformRecordingBackend.stopForShutdown()
        recordingModeSignals.stop()
        recordingExecutor.shutdown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

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
            return freezeBackend.setMode(packageName, userId, parsed).also { result ->
                if (result.isSuccess) {
                    automationCoordinator.recordManualAction(
                        ResolvedAutomationAction.SetFreezeMode(packageName, userId, parsed),
                        System.currentTimeMillis(),
                    )
                }
            }
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

        // ---- Package Inspector / Component Control (contract version 5) ----

        override fun getPackageSnapshot(packageName: String?, userId: Int): PackageSnapshot? {
            if (!validator.isAllowed(Binder.getCallingUid())) return null
            if (packageName == null || !PackageNameValidator.isValid(packageName) || userId < 0) {
                return null
            }
            return packageControl.snapshot(packageName, userId)
        }

        override fun forceStopPackage(packageName: String?, userId: Int): BombResult {
            validator.verdictFor(Binder.getCallingUid())?.let { return it }
            if (packageName == null || !PackageNameValidator.isValid(packageName)) {
                return BombResult.invalidArgument("packageName")
            }
            if (userId < 0) return BombResult.invalidArgument("userId")
            return packageControl.forceStop(packageName, userId)
        }

        override fun setComponentState(
            packageName: String?,
            userId: Int,
            className: String?,
            state: String?,
        ): BombResult {
            validator.verdictFor(Binder.getCallingUid())?.let { return it }
            if (packageName == null || !PackageNameValidator.isValid(packageName)) {
                return BombResult.invalidArgument("packageName")
            }
            if (userId < 0) return BombResult.invalidArgument("userId")
            if (className == null) return BombResult.invalidArgument("className")
            if (state == null) return BombResult.invalidArgument("state")
            return packageControl.setComponentState(packageName, userId, className, state)
        }

        // ---- Phase 3: App Visibility (§12) — contract version 6 ------------

        override fun setVisibilityPolicy(policy: VisibilityCallerPolicyParcel?): BombResult {
            validator.verdictFor(Binder.getCallingUid())?.let { return it }
            if (policy == null) return BombResult.invalidArgument("policy must not be null")
            if (policy.toDomain() == null) return BombResult.invalidArgument("policy")
            validateCurrentUser(policy.userId)?.let { return it }

            return executeFrameworkWrite(
                BombCapability.PACKAGE_VISIBILITY_VIRTUALIZATION,
                "PACKAGE_VISIBILITY_VIRTUALIZATION requires an acknowledged framework bridge",
            ) {
                visibilityBackend.setPolicy(policy)
            }
        }

        override fun clearVisibilityPolicy(callingUid: Int, userId: Int): BombResult {
            validator.verdictFor(Binder.getCallingUid())?.let { return it }
            ApiV6InputValidator.uidUserViolation(callingUid, userId)?.let {
                return BombResult.invalidArgument(it)
            }
            validateCurrentUser(userId)?.let { return it }
            return executeFrameworkWrite(
                BombCapability.PACKAGE_VISIBILITY_VIRTUALIZATION,
                "PACKAGE_VISIBILITY_VIRTUALIZATION requires an acknowledged framework bridge",
            ) {
                visibilityBackend.clearPolicy(callingUid, userId)
            }
        }

        // ---- Phase 3: Settings Virtualization (§13) -------------------------

        override fun createSettingsProfile(profileId: String?, profileName: String?): BombResult {
            validator.verdictFor(Binder.getCallingUid())?.let { return it }
            if (profileId == null) return BombResult.invalidArgument("profileId")
            if (profileName == null) return BombResult.invalidArgument("profileName")
            ApiV6InputValidator.profileIdViolation(profileId)?.let {
                return BombResult.invalidArgument(it)
            }
            ApiV6InputValidator.profileNameViolation(profileName)?.let {
                return BombResult.invalidArgument(it)
            }
            return executeFrameworkWrite(
                BombCapability.SETTINGS_VIRTUALIZATION,
                "SETTINGS_VIRTUALIZATION requires an acknowledged framework bridge",
            ) {
                settingsBackend.createProfile(profileId, profileName)
            }
        }

        override fun deleteSettingsProfile(profileId: String?): BombResult {
            validator.verdictFor(Binder.getCallingUid())?.let { return it }
            if (profileId == null) return BombResult.invalidArgument("profileId")
            ApiV6InputValidator.profileIdViolation(profileId)?.let {
                return BombResult.invalidArgument(it)
            }
            return executeFrameworkWrite(
                BombCapability.SETTINGS_VIRTUALIZATION,
                "SETTINGS_VIRTUALIZATION requires an acknowledged framework bridge",
            ) {
                settingsBackend.deleteProfile(profileId)
            }
        }

        override fun addSettingsOverride(override: SettingsOverrideParcel?): BombResult {
            validator.verdictFor(Binder.getCallingUid())?.let { return it }
            if (override == null) return BombResult.invalidArgument("override must not be null")
            if (override.toDomain() == null) return BombResult.invalidArgument("override")
            return executeFrameworkWrite(
                BombCapability.SETTINGS_VIRTUALIZATION,
                "SETTINGS_VIRTUALIZATION requires an acknowledged framework bridge",
            ) {
                settingsBackend.addOverride(override)
            }
        }

        override fun removeSettingsOverride(
            profileId: String?,
            namespace: String?,
            key: String?,
        ): BombResult {
            validator.verdictFor(Binder.getCallingUid())?.let { return it }
            if (profileId == null) return BombResult.invalidArgument("profileId")
            if (namespace == null) return BombResult.invalidArgument("namespace")
            if (key == null) return BombResult.invalidArgument("key")
            ApiV6InputValidator.profileIdViolation(profileId)?.let {
                return BombResult.invalidArgument(it)
            }
            ApiV6InputValidator.enumNameViolation("namespace", namespace)?.let {
                return BombResult.invalidArgument(it)
            }
            if (SettingsNamespace.entries.none { it.name == namespace }) {
                return BombResult.invalidArgument("namespace")
            }
            ApiV6InputValidator.settingsKeyViolation(key)?.let {
                return BombResult.invalidArgument(it)
            }
            return executeFrameworkWrite(
                BombCapability.SETTINGS_VIRTUALIZATION,
                "SETTINGS_VIRTUALIZATION requires an acknowledged framework bridge",
            ) {
                settingsBackend.removeOverride(profileId, namespace, key)
            }
        }

        override fun assignSettingsProfile(assignment: SettingsAssignmentParcel?): BombResult {
            validator.verdictFor(Binder.getCallingUid())?.let { return it }
            if (assignment == null) return BombResult.invalidArgument("assignment must not be null")
            if (assignment.toDomain() == null) return BombResult.invalidArgument("assignment")
            validateCurrentUser(assignment.userId)?.let { return it }
            return executeFrameworkWrite(
                BombCapability.SETTINGS_VIRTUALIZATION,
                "SETTINGS_VIRTUALIZATION requires an acknowledged framework bridge",
            ) {
                settingsBackend.assignProfile(assignment)
            }
        }

        override fun clearSettingsAssignment(userId: Int, targetPackage: String?): BombResult {
            validator.verdictFor(Binder.getCallingUid())?.let { return it }
            if (targetPackage == null || !PackageNameValidator.isValid(targetPackage)) {
                return BombResult.invalidArgument("targetPackage")
            }
            validateCurrentUser(userId)?.let { return it }
            return executeFrameworkWrite(
                BombCapability.SETTINGS_VIRTUALIZATION,
                "SETTINGS_VIRTUALIZATION requires an acknowledged framework bridge",
            ) {
                settingsBackend.clearAssignment(userId, targetPackage)
            }
        }

        // ---- Phase 3: Firewall (§18) ----------------------------------------

        override fun setFirewallRule(rule: FirewallRuleParcel?): BombResult {
            validator.verdictFor(Binder.getCallingUid())?.let { return it }
            if (rule == null) return BombResult.invalidArgument("rule must not be null")
            if (rule.toDomain() == null) return BombResult.invalidArgument("rule")
            validateCurrentUser(rule.userId)?.let { return it }
            val caps = capabilityProbe.probe()
            if (!caps.isSupported(com.hzzmonet.zkbomb.api.BombCapability.FIREWALL)) {
                return BombResult.unsupported("FIREWALL capability is not available on this device")
            }
            return firewallBackend.setRule(rule)
        }

        override fun clearFirewallRule(uid: Int, userId: Int): BombResult {
            validator.verdictFor(Binder.getCallingUid())?.let { return it }
            ApiV6InputValidator.uidUserViolation(uid, userId)?.let {
                return BombResult.invalidArgument(it)
            }
            validateCurrentUser(userId)?.let { return it }
            val caps = capabilityProbe.probe()
            if (!caps.isSupported(com.hzzmonet.zkbomb.api.BombCapability.FIREWALL)) {
                return BombResult.unsupported("FIREWALL capability is not available on this device")
            }
            return firewallBackend.clearRule(uid, userId)
        }

        // ---- Phase 3: AdBlock (§16) -----------------------------------------

        override fun reloadAdBlockRules(): BombResult {
            validator.verdictFor(Binder.getCallingUid())?.let { return it }
            val caps = capabilityProbe.probe()
            if (!caps.isSupported(com.hzzmonet.zkbomb.api.BombCapability.AD_BLOCK)) {
                return BombResult.unsupported("AD_BLOCK capability is not available on this device")
            }
            return adBlockEngine.reloadAndSwap()
        }

        // ---- Selected-process memory (contract version 7) ------------------

        override fun getSelectedProcessMemory(pid: Int): SelectedProcessMemory {
            if (!validator.isAllowed(Binder.getCallingUid())) {
                return SelectedProcessMemory.unavailable(
                    pid = pid,
                    sampledAtElapsedRealtimeMillis = android.os.SystemClock.elapsedRealtime(),
                    status = SelectedProcessMemoryStatus.PERMISSION_DENIED,
                )
            }
            if (pid <= 0) {
                return SelectedProcessMemory.unavailable(
                    pid = pid,
                    sampledAtElapsedRealtimeMillis = android.os.SystemClock.elapsedRealtime(),
                    status = SelectedProcessMemoryStatus.INVALID_PID,
                )
            }
            return processTelemetry.selectedProcessMemory(pid)
        }

        // ---- Bomb Rules / Automation (contract version 8) -----------------

        override fun getAutomationRules(): AutomationRulesSnapshot {
            if (!validator.isAllowed(Binder.getCallingUid())) {
                return AutomationRulesSnapshot(
                    enabled = false,
                    observerRunning = false,
                    rules = emptyList(),
                    lastTrigger = null,
                    lastTriggeredAtMillis = null,
                )
            }
            return automationCoordinator.snapshot()
        }

        override fun upsertAutomationRule(rule: AutomationRuleParcel?): BombResult {
            validator.verdictFor(Binder.getCallingUid())?.let { return it }
            if (rule == null) return BombResult.invalidArgument("rule must not be null")
            return automationCoordinator.upsert(rule)
        }

        override fun deleteAutomationRule(ruleId: String?): BombResult {
            validator.verdictFor(Binder.getCallingUid())?.let { return it }
            if (ruleId == null) return BombResult.invalidArgument("ruleId")
            return automationCoordinator.delete(ruleId)
        }

        override fun setAutomationEnabled(enabled: Boolean): BombResult {
            validator.verdictFor(Binder.getCallingUid())?.let { return it }
            if (!enabled) {
                automationCoordinator.setEnabled(false)
                automationSignals.stop()
                automationCoordinator.setObserverRunning(false)
                return BombResult.success()
            }

            automationCoordinator.setEnabled(true)
            if (!startAutomationSignals()) {
                automationCoordinator.setEnabled(false)
                return BombResult.backendUnavailable("App/screen automation signal source is unavailable")
            }
            startService(Intent(this@BombCoreService, BombCoreService::class.java))
            return BombResult.success()
        }

        // ---- Thermal & Battery Lab (contract version 9) -------------------

        override fun getBatteryLabSnapshot(): BatteryLabSnapshot {
            if (!validator.isAllowed(Binder.getCallingUid())) {
                return BatteryLabSnapshot.unavailable(android.os.SystemClock.elapsedRealtime())
            }
            return batteryLabCoordinator.snapshot()
        }

        override fun setBatteryLabProfile(profile: BatteryLabProfileParcel?): BombResult {
            validator.verdictFor(Binder.getCallingUid())?.let { return it }
            val parsed = profile?.toDomain()
                ?: return BombResult.invalidArgument("profile")
            val result = batteryLabCoordinator.setProfile(parsed)
            if (!result.isSuccess) return result
            if (!startBatteryLabSignals()) {
                val rollback = batteryLabCoordinator.clear()
                if (!rollback.isSuccess) {
                    return BombResult.failed(
                        "Battery signal setup failed and device state could not be restored",
                    )
                }
                return BombResult.backendUnavailable("Battery change signal source is unavailable")
            }
            startService(Intent(this@BombCoreService, BombCoreService::class.java))
            return BombResult.success()
        }

        override fun clearBatteryLabProfile(): BombResult {
            validator.verdictFor(Binder.getCallingUid())?.let { return it }
            val result = batteryLabCoordinator.clear()
            if (result.isSuccess) batteryLabSignals.stop()
            return result
        }

        // ---- Live Updates / Performance Profiles (contract version 10) ---

        override fun getBridgeStatus(): BridgeStatusSnapshot {
            if (!validator.isAllowed(Binder.getCallingUid())) {
                return BridgeStatusSnapshot(false, false, false, 0, false, false, emptyList())
            }
            return liveUpdateBridge.snapshot()
        }

        override fun publishLiveEvent(event: BombLiveEventParcel?): BombResult {
            validator.verdictFor(Binder.getCallingUid())?.let { return it }
            val parsed = event?.toDomain() ?: return BombResult.invalidArgument("event")
            if (parsed.sourcePackage != packageName) {
                return BombResult.permissionDenied("sourcePackage does not match the allowed caller")
            }
            return liveUpdateBridge.publish(parsed)
        }

        override fun dismissLiveEvent(eventId: String?): BombResult {
            validator.verdictFor(Binder.getCallingUid())?.let { return it }
            return liveUpdateBridge.dismiss(eventId ?: return BombResult.invalidArgument("eventId"))
        }

        override fun getPerformanceProfiles(): PerformanceProfilesSnapshot {
            if (!validator.isAllowed(Binder.getCallingUid())) {
                return PerformanceProfilesSnapshot(emptyList(), null, false, emptyList(), null, null)
            }
            return performanceProfileCoordinator.snapshot()
        }

        override fun setPerformanceProfile(profile: String?): BombResult {
            validator.verdictFor(Binder.getCallingUid())?.let { return it }
            val parsed = profile?.let { value ->
                PerformanceProfile.entries.firstOrNull { it.name == value }
            } ?: return BombResult.invalidArgument("profile")
            return performanceProfileCoordinator.setProfile(parsed)
        }

        override fun clearPerformanceProfile(): BombResult {
            validator.verdictFor(Binder.getCallingUid())?.let { return it }
            return performanceProfileCoordinator.clearProfile()
        }

        override fun setThermalGuardianConfig(config: ThermalGuardianConfigParcel?): BombResult {
            validator.verdictFor(Binder.getCallingUid())?.let { return it }
            val parsed = config?.toDomain() ?: return BombResult.invalidArgument("config")
            val result = performanceProfileCoordinator.setThermalGuardian(parsed)
            if (!result.isSuccess) return result
            if (!startThermalGuardianSignals()) {
                performanceProfileCoordinator.clearThermalGuardian()
                return BombResult.backendUnavailable("Battery temperature signal source is unavailable")
            }
            startService(Intent(this@BombCoreService, BombCoreService::class.java))
            return BombResult.success()
        }

        override fun clearThermalGuardianConfig(): BombResult {
            validator.verdictFor(Binder.getCallingUid())?.let { return it }
            val result = performanceProfileCoordinator.clearThermalGuardian()
            if (result.isSuccess) thermalGuardianSignals.stop()
            return result
        }

        // ---- Call / VoIP Recording (contract version 11) -----------------

        override fun getRecordingBackendStatus(): RecordingBackendStatus {
            if (!validator.isAllowed(Binder.getCallingUid())) return unavailableRecordingStatus()
            return platformRecordingBackend.status()
        }

        override fun startCallRecording(
            request: RecordingRequestParcel?,
            output: ParcelFileDescriptor?,
        ): BombResult {
            validator.verdictFor(Binder.getCallingUid())?.let {
                runCatching { output?.close() }
                return it
            }
            val parsed = request?.toDomain()
            if (parsed == null || output == null) {
                runCatching { output?.close() }
                return BombResult.invalidArgument("request and output are required")
            }
            if (!startRecordingForeground(parsed.kind.name)) {
                runCatching { output.close() }
                return BombResult.backendUnavailable("Recording foreground notification is unavailable")
            }
            val result = platformRecordingBackend.start(parsed, ParcelRecordingSink(output))
            if (!result.isSuccess) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                return result
            }
            if (!recordingModeSignals.start()) {
                platformRecordingBackend.stop(parsed.sessionId)
                stopForeground(STOP_FOREGROUND_REMOVE)
                return BombResult.backendUnavailable("Audio mode listener is unavailable")
            }
            return result
        }

        override fun stopCallRecording(sessionId: String?): BombResult {
            validator.verdictFor(Binder.getCallingUid())?.let { return it }
            if (sessionId == null) return BombResult.invalidArgument("sessionId")
            val result = platformRecordingBackend.stop(sessionId)
            if (platformRecordingBackend.status().activeSessionId == null) {
                recordingModeSignals.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
            return result
        }

        // ---- CPU / GPU dynamic frequency limits (contract version 12) ----

        override fun getFrequencyScalingSnapshot(): FrequencyScalingSnapshot {
            if (!validator.isAllowed(Binder.getCallingUid())) {
                return FrequencyScalingSnapshot(emptyList())
            }
            return FrequencyScalingSnapshot.fromDomain(frequencyScalingBackend.snapshot())
        }

        override fun setFrequencyLimits(request: FrequencyLimitRequestParcel?): BombResult {
            validator.verdictFor(Binder.getCallingUid())?.let { return it }
            val parsed = request?.toDomain() ?: return BombResult.invalidArgument("request")
            return frequencyScalingBackend.set(parsed)
        }
    }

    private fun startAutomationSignals(): Boolean {
        val started = automationSignals.start()
        automationCoordinator.setObserverRunning(started)
        return started
    }

    private fun startBatteryLabSignals(): Boolean = batteryLabSignals.start()

    private fun startThermalGuardianSignals(): Boolean = thermalGuardianSignals.start()

    private fun startRecordingForeground(kind: String): Boolean = runCatching {
        val manager = getSystemService(NotificationManager::class.java)
            ?: return@runCatching false
        manager.createNotificationChannel(
            NotificationChannel(
                RECORDING_CHANNEL_ID,
                "Bomb call recording",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val notification = Notification.Builder(this, RECORDING_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Bomb call recorder")
            .setContentText("Recording ${kind.lowercase()} call audio")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        startForeground(
            RECORDING_NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        true
    }.getOrDefault(false)

    private fun onRecordingSessionEnded() {
        recordingModeSignals.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun unavailableRecordingStatus() = RecordingBackendStatus(
        state = "IDLE",
        activeSessionId = null,
        activeKind = null,
        startedAtElapsedRealtimeMillis = null,
        cellularSupport = "DENIED",
        voipSupport = "DENIED",
        capturePermissionHeld = false,
        activeClientSilenced = null,
        lastOutcome = null,
        lastPeakAmplitude = null,
    )

    private fun executeFrameworkWrite(
        capability: BombCapability,
        unavailableDetail: String,
        mutation: () -> BombResult,
    ): BombResult = FrameworkBridgeGate.executeWrite(
        capabilityState = capabilityProbe.probe()[capability],
        unavailableDetail = unavailableDetail,
        mutation = mutation,
    )

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

    private fun validateCurrentUser(userId: Int): BombResult? {
        ApiV6InputValidator.userIdViolation(userId)?.let {
            return BombResult.invalidArgument(it)
        }
        val currentUserId = Process.myUid() / ApiV6InputValidator.PER_USER_RANGE
        return if (userId == currentUserId) {
            null
        } else {
            BombResult.unsupported("Cross-user operations are not integrated")
        }
    }

    private companion object {
        /**
         * Bumped when methods are appended or a tail-compatible parcel schema is
         * extended. A client reads it to know which calls/fields exist.
         *
         * 2 — added `getRuntimeMode()`.
         * 3 — added process and system telemetry snapshots.
         * 4 — added getProcessList(), setFreezeState(), getTelemetrySnapshot().
         * 5 — added Package Inspector, force-stop and component override calls.
         * 6 — Phase 3: Visibility (§12), Settings Virtualization (§13),
         *     Firewall (§18), AdBlock reload (§16).
         * 7 — selected-process-only PSS/private-dirty memory sampling.
         * 8 — Bomb Rules CRUD/status plus app/screen automation orchestration.
         * 9 — capability-probed Thermal Guardian and Battery Lab profiles.
         * 10 — Live Update/HyperIsland bridge and performance/thermal profiles.
         * 11 — capability-verified platform Call/VoIP recording backend.
         * 12 — dynamic CPU policy and GPU devfreq min/max limits.
         * 13 — Battery Lab maximum charge-current fields in existing v9 parcels.
         */
        const val API_VERSION = CURRENT_BOMB_API_VERSION
        const val MEMORY_VERIFY_ATTEMPTS = 10
        const val MEMORY_VERIFY_DELAY_MS = 50L
        const val CONTROL_VERIFY_ATTEMPTS = 10
        const val CONTROL_VERIFY_DELAY_MS = 50L
        const val RECORDING_CHANNEL_ID = "bomb_platform_recording"
        const val RECORDING_NOTIFICATION_ID = 0xB011
    }
}
