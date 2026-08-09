package com.hzzmonet.zkbomb.core

import android.Manifest
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.PersistableBundle
import android.os.Process
import android.provider.Settings
import com.hzzmonet.zkbomb.api.BombResult
import com.hzzmonet.zkbomb.api.FreezeStatus
import com.hzzmonet.zkbomb.domain.freeze.FreezeAction
import com.hzzmonet.zkbomb.domain.freeze.FreezeCapabilities
import com.hzzmonet.zkbomb.domain.freeze.FreezeDecision
import com.hzzmonet.zkbomb.domain.freeze.FreezeMechanism
import com.hzzmonet.zkbomb.domain.freeze.FreezeMode
import com.hzzmonet.zkbomb.domain.freeze.FreezePolicyEngine
import com.hzzmonet.zkbomb.domain.freeze.FreezeRequest
import com.hzzmonet.zkbomb.domain.freeze.ObservedFreezeState
import com.hzzmonet.zkbomb.domain.freeze.ProtectedPackages

/**
 * Privileged FreezeBackend executing freeze mechanisms backed by Android platform APIs.
 */
class FreezeBackend(private val context: Context) {

    private val pm: PackageManager get() = context.packageManager
    private val activityManager: ActivityManager?
        get() = context.getSystemService(ActivityManager::class.java)

    fun status(packageName: String, userId: Int): FreezeStatus? {
        if (userId != currentUserId()) return null
        val observed = observe(packageName) ?: return null
        val caps = capabilities()
        val modes = buildList {
            add(FreezeMode.NORMAL.name)
            if (caps.platformFreezer && observed.hasRunningProcesses) add(FreezeMode.SOFT_FREEZE.name)
            if (caps.suspend || caps.hide) add(FreezeMode.DEEP_FREEZE.name)
            if (caps.disable) add(FreezeMode.DISABLED.name)
        }
        return FreezeStatus(
            packageName = packageName,
            userId = userId,
            mode = observed.resolveMode().name,
            hasUnfrozenProcesses = observed.hasUnfrozenProcesses(),
            availableModes = modes,
            exclusionReason = protectedPackages().exclusionFor(packageName)?.name,
        )
    }

    fun setMode(packageName: String, userId: Int, target: FreezeMode): BombResult {
        if (userId != currentUserId()) {
            return BombResult.unsupported("Cross-user freeze is not integrated")
        }
        val observed = observe(packageName)
            ?: return BombResult.invalidArgument("Package is not installed for this user")
        val engine = FreezePolicyEngine(capabilities(), protectedPackages())
        return when (
            val decision = engine.plan(FreezeRequest(packageName, userId, target, observed))
        ) {
            is FreezeDecision.Invalid -> BombResult.invalidArgument(decision.reason.name)
            is FreezeDecision.Excluded -> BombResult.permissionDenied(decision.reason.name)
            is FreezeDecision.Unsupported ->
                BombResult.unsupported(decision.attempted.joinToString { it.name })
            is FreezeDecision.NothingToFreeze -> BombResult.success()
            is FreezeDecision.AlreadyInMode -> BombResult.success()
            is FreezeDecision.Execute -> execute(packageName, target, decision.actions)
        }
    }

    private fun execute(
        packageName: String,
        target: FreezeMode,
        actions: List<FreezeAction>,
    ): BombResult = runCatching {
        for (action in actions) {
            when (action) {
                FreezeAction.ForceStop -> forceStop(packageName)
                is FreezeAction.SetMechanism -> setMechanism(packageName, action)
            }
        }
        val after = observe(packageName)
            ?: return BombResult.failed("Package state disappeared after apply")
        if (after.resolveMode() != target) {
            return BombResult.failed(
                "Platform state is ${after.resolveMode().name}, requested ${target.name}",
            )
        }
        BombResult.success()
    }.getOrElse { error ->
        BombResult.failed(error.message ?: error.javaClass.simpleName)
    }

    private fun setMechanism(packageName: String, action: FreezeAction.SetMechanism) {
        when (action.mechanism) {
            FreezeMechanism.SUSPEND -> {
                val method = PackageManager::class.java.getMethod(
                    "setPackagesSuspended",
                    Array<String>::class.java,
                    Boolean::class.javaPrimitiveType,
                    PersistableBundle::class.java,
                    PersistableBundle::class.java,
                    String::class.java,
                )
                @Suppress("UNCHECKED_CAST")
                val failures = method.invoke(
                    pm,
                    arrayOf(packageName),
                    action.engaged,
                    null,
                    null,
                    "Managed by Bomb",
                ) as Array<String>
                check(failures.isEmpty()) { "PackageManager refused: ${failures.joinToString()}" }
            }
            FreezeMechanism.HIDE -> {
                val userHandleClass = Class.forName("android.os.UserHandle")
                val ofMethod = userHandleClass.getMethod("of", Int::class.javaPrimitiveType)
                val userHandle = ofMethod.invoke(null, currentUserId())
                val method = PackageManager::class.java.getMethod(
                    "setApplicationHiddenSettingAsUser",
                    String::class.java,
                    Boolean::class.javaPrimitiveType,
                    userHandleClass,
                )
                val success = method.invoke(pm, packageName, action.engaged, userHandle) as Boolean
                check(success) { "PackageManager refused to set hidden state for $packageName" }
            }
            FreezeMechanism.DISABLE -> pm.setApplicationEnabledSetting(
                packageName,
                if (action.engaged) {
                    forceStop(packageName)
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                },
                PackageManager.DONT_KILL_APP,
            )
            FreezeMechanism.PLATFORM_FREEZER -> {
                val manager = activityManager ?: error("ActivityManager unavailable")
                val runningProcs = manager.runningAppProcesses.orEmpty()
                    .filter { it.pkgList?.contains(packageName) == true }
                check(runningProcs.isNotEmpty()) { "No running processes found to freeze for $packageName" }
                val freezerMethod = ActivityManager::class.java.getMethod(
                    "setProcessFrozen",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                )
                for (procInfo in runningProcs) {
                    freezerMethod.invoke(manager, procInfo.pid, procInfo.uid, action.engaged)
                }
            }
        }
    }

    private fun forceStop(packageName: String) {
        val manager = activityManager ?: error("ActivityManager unavailable")
        val method = ActivityManager::class.java.getMethod("forceStopPackage", String::class.java)
        method.invoke(manager, packageName)
    }

    private fun observe(packageName: String): ObservedFreezeState? {
        val info = runCatching { pm.getApplicationInfo(packageName, 0) }.getOrNull() ?: return null
        val hidden = runCatching {
            val privateFlags = ApplicationInfo::class.java.getField("privateFlags").getInt(info)
            (privateFlags and 1) != 0
        }.getOrDefault(false)

        val procs = runCatching { activityManager?.runningAppProcesses.orEmpty() }
            .getOrDefault(emptyList())
            .filter { it.pkgList?.contains(packageName) == true }

        val hasRunning = procs.isNotEmpty()
        val allFrozen = hasRunning && procs.all { procInfo ->
            procInfo.importance >= ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED
        }

        return ObservedFreezeState(
            enabled = info.enabled,
            hidden = hidden,
            suspended = runCatching { pm.isPackageSuspended(packageName) }.getOrDefault(false),
            stopped = info.flags and ApplicationInfo.FLAG_STOPPED != 0,
            allProcessesFrozen = allFrozen,
            hasRunningProcesses = hasRunning,
        )
    }

    fun capabilities(): FreezeCapabilities = FreezeCapabilities(
        platformFreezer = freezerMethodAvailable(),
        suspend = granted("android.permission.SUSPEND_APPS") &&
            granted("android.permission.FORCE_STOP_PACKAGES") &&
            suspendMethodAvailable() && forceStopAvailable(),
        hide = hideMethodAvailable() &&
            (granted("android.permission.MANAGE_USERS") || granted(Manifest.permission.CHANGE_COMPONENT_ENABLED_STATE)),
        disable = granted(Manifest.permission.CHANGE_COMPONENT_ENABLED_STATE) && forceStopAvailable(),
    )

    private fun suspendMethodAvailable(): Boolean = runCatching {
        PackageManager::class.java.getMethod(
            "setPackagesSuspended",
            Array<String>::class.java,
            Boolean::class.javaPrimitiveType,
            PersistableBundle::class.java,
            PersistableBundle::class.java,
            String::class.java,
        )
    }.isSuccess

    private fun hideMethodAvailable(): Boolean = runCatching {
        val userHandleClass = Class.forName("android.os.UserHandle")
        PackageManager::class.java.getMethod(
            "setApplicationHiddenSettingAsUser",
            String::class.java,
            Boolean::class.javaPrimitiveType,
            userHandleClass,
        )
    }.isSuccess

    private fun freezerMethodAvailable(): Boolean = runCatching {
        ActivityManager::class.java.getMethod(
            "setProcessFrozen",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        )
    }.isSuccess

    private fun forceStopAvailable(): Boolean = runCatching {
        ActivityManager::class.java.getMethod("forceStopPackage", String::class.java)
    }.isSuccess

    private fun protectedPackages(): ProtectedPackages {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val launcher = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        val ime = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')
            ?.takeIf { it.isNotBlank() }
        val admins = context.getSystemService(DevicePolicyManager::class.java)
            ?.activeAdmins
            .orEmpty()
            .mapTo(mutableSetOf()) { it.packageName }
        return ProtectedPackages(
            self = context.packageName,
            systemUi = "com.android.systemui",
            launcher = launcher,
            currentIme = ime,
            framework = ProtectedPackages.CORE_FRAMEWORK,
            vendorCritical = setOf("com.miui.securitycenter", "com.xiaomi.xmsf"),
            deviceAdmins = admins,
            userExcluded = emptySet(),
        )
    }

    private fun granted(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun currentUserId(): Int = Process.myUid() / PER_USER_RANGE

    private companion object {
        const val PER_USER_RANGE = 100_000
    }
}
