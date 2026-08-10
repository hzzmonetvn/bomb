package com.hzzmonet.zkbomb.api

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/** Read-only manifest component information returned by Package Inspector. */
@Parcelize
data class PackageComponentInfo(
    /** A ComponentKind name: ACTIVITY, SERVICE, RECEIVER or PROVIDER. */
    val kind: String,
    val className: String,
    val processName: String?,
    val manifestEnabled: Boolean,
    val effectiveEnabled: Boolean,
    val exported: Boolean,
    val permission: String?,
    val readPermission: String?,
    val writePermission: String?,
    val directBootAware: Boolean,
    /** Provider authorities only; null for other component kinds. */
    val authorities: String?,
    /** A ComponentOverrideState name. */
    val overrideState: String,
) : Parcelable

/**
 * Bounded Package Inspector snapshot.
 *
 * Component lists are capped before crossing Binder. [totalComponentCount]
 * preserves the real total and [componentsTruncated] tells the UI that filters
 * operate on a partial view rather than silently pretending it is complete.
 */
@Parcelize
data class PackageSnapshot(
    val packageName: String,
    val userId: Int,
    val uid: Int,
    val label: String,
    val versionName: String?,
    val longVersionCode: Long,
    val targetSdkVersion: Int,
    val minSdkVersion: Int,
    val compileSdkVersion: Int,
    val firstInstallTimeMillis: Long,
    val lastUpdateTimeMillis: Long,
    val enabled: Boolean,
    val stopped: Boolean,
    val suspended: Boolean,
    val systemApp: Boolean,
    val updatedSystemApp: Boolean,
    val debuggable: Boolean,
    val testOnly: Boolean,
    val installerPackageName: String?,
    val requestedPermissions: List<String>,
    /** SHA-256 certificate digests, not raw certificates. */
    val signingCertificateSha256: List<String>,
    val processNames: List<String>,
    val components: List<PackageComponentInfo>,
    val totalComponentCount: Int,
    val componentsTruncated: Boolean,
    /** Existing critical-package exclusion, or null when mutation is allowed. */
    val protectionReason: String?,
) : Parcelable
