package com.hzzmonet.zkbomb.core

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ComponentInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.content.pm.ServiceInfo
import android.os.Process
import com.hzzmonet.zkbomb.api.BombResult
import com.hzzmonet.zkbomb.api.PackageComponentInfo
import com.hzzmonet.zkbomb.api.PackageSnapshot
import com.hzzmonet.zkbomb.domain.freeze.PackageNameValidator
import com.hzzmonet.zkbomb.domain.packagecontrol.ComponentClassNameValidator
import com.hzzmonet.zkbomb.domain.packagecontrol.ComponentKind
import com.hzzmonet.zkbomb.domain.packagecontrol.ComponentOverrideState
import java.security.MessageDigest

/** Read-only package inspection plus two narrowly typed package operations. */
class PackageControlBackend(
    private val context: Context,
    private val freeze: FreezeBackend = FreezeBackend(context),
) {
    private val pm: PackageManager get() = context.packageManager

    fun snapshot(packageName: String, userId: Int): PackageSnapshot? {
        if (userId != currentUserId() || !PackageNameValidator.isValid(packageName)) return null
        val info = runCatching { pm.getPackageInfo(packageName, packageFlags()) }.getOrNull()
            ?: return null
        val app = info.applicationInfo ?: return null
        val componentRefs = collectComponentRefs(info)
            .sortedWith(compareBy({ it.kind }, { it.info.name.orEmpty() }))
        val components = boundedComponents(componentRefs, app)

        return PackageSnapshot(
            packageName = packageName,
            userId = userId,
            uid = app.uid,
            label = runCatching { app.loadLabel(pm).toString() }
                .getOrDefault(packageName)
                .take(MAX_LABEL_LENGTH),
            versionName = info.versionName?.take(MAX_VALUE_LENGTH),
            longVersionCode = info.longVersionCode,
            targetSdkVersion = app.targetSdkVersion,
            minSdkVersion = app.minSdkVersion,
            compileSdkVersion = compileSdkVersion(info, app),
            firstInstallTimeMillis = info.firstInstallTime,
            lastUpdateTimeMillis = info.lastUpdateTime,
            enabled = app.enabled,
            stopped = app.flags and ApplicationInfo.FLAG_STOPPED != 0,
            suspended = runCatching { pm.isPackageSuspended(packageName) }.getOrDefault(false),
            systemApp = app.flags and ApplicationInfo.FLAG_SYSTEM != 0,
            updatedSystemApp = app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0,
            debuggable = app.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
            testOnly = app.flags and ApplicationInfo.FLAG_TEST_ONLY != 0,
            installerPackageName = runCatching {
                pm.getInstallSourceInfo(packageName).installingPackageName
            }.getOrNull()?.take(MAX_VALUE_LENGTH),
            requestedPermissions = info.requestedPermissions.orEmpty()
                .asSequence()
                .filter { it.isNotBlank() }
                .map { it.take(MAX_VALUE_LENGTH) }
                .distinct()
                .sorted()
                .take(MAX_REQUESTED_PERMISSIONS)
                .toList(),
            signingCertificateSha256 = signingDigests(info),
            processNames = components.asSequence()
                .mapNotNull { it.processName }
                .plus(app.processName?.let(::sequenceOf) ?: emptySequence())
                .distinct()
                .sorted()
                .take(MAX_PROCESS_NAMES)
                .toList(),
            components = components,
            totalComponentCount = componentRefs.size,
            componentsTruncated = componentRefs.size > components.size,
            protectionReason = freeze.protectionReason(packageName),
        )
    }

    fun forceStop(packageName: String, userId: Int): BombResult {
        if (!PackageNameValidator.isValid(packageName)) {
            return BombResult.invalidArgument("packageName")
        }
        return freeze.forceStopPackage(packageName, userId)
    }

    fun setComponentState(
        packageName: String,
        userId: Int,
        className: String,
        requestedState: String,
    ): BombResult {
        if (!PackageNameValidator.isValid(packageName)) {
            return BombResult.invalidArgument("packageName")
        }
        if (userId != currentUserId()) {
            return BombResult.unsupported("Cross-user component control is not integrated")
        }
        if (!ComponentClassNameValidator.isValid(className)) {
            return BombResult.invalidArgument("className")
        }
        val state = ComponentOverrideState.entries.firstOrNull { it.name == requestedState }
            ?: return BombResult.invalidArgument("state")
        if (!canControlComponents()) {
            return BombResult.unsupported("CHANGE_COMPONENT_ENABLED_STATE is unavailable")
        }
        freeze.protectionReason(packageName)?.let { return BombResult.permissionDenied(it) }

        val packageInfo = runCatching { pm.getPackageInfo(packageName, packageFlags()) }.getOrNull()
            ?: return BombResult.invalidArgument("Package is not installed for this user")
        if (packageInfo.applicationInfo == null) {
            return BombResult.invalidArgument("Package has no application info")
        }
        if (collectComponentRefs(packageInfo).none { it.info.name == className }) {
            return BombResult.invalidArgument("Component does not belong to the package")
        }

        val component = ComponentName(packageName, className)
        val platformState = ComponentStateResolver.toPlatform(state)
        return runCatching {
            pm.setComponentEnabledSetting(component, platformState, PackageManager.DONT_KILL_APP)
            val observed = pm.getComponentEnabledSetting(component)
            if (observed != platformState) {
                BombResult.failed("PackageManager read-back is ${ComponentStateResolver.name(observed)}")
            } else {
                BombResult.success()
            }
        }.getOrElse { error ->
            BombResult.failed(error.message ?: error.javaClass.simpleName)
        }
    }

    fun canControlComponents(): Boolean =
        context.checkSelfPermission(Manifest.permission.CHANGE_COMPONENT_ENABLED_STATE) ==
            PackageManager.PERMISSION_GRANTED

    private fun collectComponentRefs(info: PackageInfo): List<ComponentRef> =
        buildList {
            info.activities.orEmpty().forEach { add(ComponentRef(ComponentKind.ACTIVITY, it)) }
            info.services.orEmpty().forEach { add(ComponentRef(ComponentKind.SERVICE, it)) }
            info.receivers.orEmpty().forEach { add(ComponentRef(ComponentKind.RECEIVER, it)) }
            info.providers.orEmpty().forEach { add(ComponentRef(ComponentKind.PROVIDER, it)) }
        }

    private fun component(
        kind: ComponentKind,
        info: ComponentInfo,
        app: ApplicationInfo,
    ): PackageComponentInfo {
        val className = info.name.orEmpty().take(MAX_CLASS_NAME_LENGTH)
        val componentName = ComponentName(app.packageName, className)
        val override = runCatching { pm.getComponentEnabledSetting(componentName) }
            .getOrDefault(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
        val provider = info as? ProviderInfo
        return PackageComponentInfo(
            kind = kind.name,
            className = className,
            processName = info.processName?.take(MAX_VALUE_LENGTH),
            manifestEnabled = info.enabled,
            effectiveEnabled = app.enabled && ComponentStateResolver.effective(info.enabled, override),
            exported = info.exported,
            permission = when (info) {
                is ActivityInfo -> info.permission
                is ServiceInfo -> info.permission
                else -> null
            }?.take(MAX_VALUE_LENGTH),
            readPermission = provider?.readPermission?.take(MAX_VALUE_LENGTH),
            writePermission = provider?.writePermission?.take(MAX_VALUE_LENGTH),
            directBootAware = info.directBootAware,
            authorities = provider?.authority?.take(MAX_VALUE_LENGTH),
            overrideState = ComponentStateResolver.name(override),
        )
    }

    /** Keeps Binder payloads comfortably below the per-process transaction cap. */
    private fun boundedComponents(
        refs: List<ComponentRef>,
        app: ApplicationInfo,
    ): List<PackageComponentInfo> = buildList {
        var textChars = 0
        for (ref in refs.take(MAX_COMPONENTS)) {
            val item = component(ref.kind, ref.info, app)
            val itemChars = item.textCharCount()
            if (textChars + itemChars > MAX_COMPONENT_TEXT_CHARS) break
            add(item)
            textChars += itemChars
        }
    }

    private fun PackageComponentInfo.textCharCount(): Int =
        kind.length +
            className.length +
            processName.orEmpty().length +
            permission.orEmpty().length +
            readPermission.orEmpty().length +
            writePermission.orEmpty().length +
            authorities.orEmpty().length +
            overrideState.length

    private fun signingDigests(info: PackageInfo): List<String> =
        info.signingInfo?.apkContentsSigners.orEmpty()
            .asSequence()
            .map { signature ->
                MessageDigest.getInstance("SHA-256")
                    .digest(signature.toByteArray())
                    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            }
            .distinct()
            .take(MAX_SIGNERS)
            .toList()

    private fun compileSdkVersion(info: PackageInfo, app: ApplicationInfo): Int =
        runCatching {
            PackageInfo::class.java.getField("compileSdkVersion").getInt(info)
        }.recoverCatching {
            ApplicationInfo::class.java.getField("compileSdkVersion").getInt(app)
        }.getOrDefault(0)

    private fun packageFlags(): PackageManager.PackageInfoFlags =
        PackageManager.PackageInfoFlags.of(
            (
                PackageManager.GET_ACTIVITIES or
                    PackageManager.GET_SERVICES or
                    PackageManager.GET_RECEIVERS or
                    PackageManager.GET_PROVIDERS or
                    PackageManager.GET_PERMISSIONS or
                    PackageManager.GET_META_DATA or
                    PackageManager.GET_SIGNING_CERTIFICATES or
                    PackageManager.MATCH_DISABLED_COMPONENTS or
                    PackageManager.MATCH_DIRECT_BOOT_AWARE or
                    PackageManager.MATCH_DIRECT_BOOT_UNAWARE
                ).toLong(),
        )

    private fun currentUserId(): Int = Process.myUid() / PER_USER_RANGE

    private data class ComponentRef(
        val kind: ComponentKind,
        val info: ComponentInfo,
    )

    private companion object {
        const val PER_USER_RANGE = 100_000
        const val MAX_COMPONENTS = 512
        const val MAX_COMPONENT_TEXT_CHARS = 120_000
        const val MAX_REQUESTED_PERMISSIONS = 128
        const val MAX_PROCESS_NAMES = 128
        const val MAX_SIGNERS = 8
        const val MAX_LABEL_LENGTH = 256
        const val MAX_CLASS_NAME_LENGTH = 512
        const val MAX_VALUE_LENGTH = 256
    }
}

/** Pure mapping shared by snapshot and mutation verification. */
internal object ComponentStateResolver {
    fun toPlatform(state: ComponentOverrideState): Int = when (state) {
        ComponentOverrideState.DEFAULT -> PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        ComponentOverrideState.ENABLED -> PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        ComponentOverrideState.DISABLED -> PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    }

    fun effective(manifestEnabled: Boolean, platformState: Int): Boolean = when (platformState) {
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED,
        -> false
        else -> manifestEnabled
    }

    fun name(platformState: Int): String = when (platformState) {
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> ComponentOverrideState.ENABLED.name
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED,
        -> ComponentOverrideState.DISABLED.name
        else -> ComponentOverrideState.DEFAULT.name
    }
}
