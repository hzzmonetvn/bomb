package com.hzzmonet.zkbomb.preview

/**
 * Device / ROM identity shown in the header banner.
 *
 * This is `expect`/`actual` on purpose: on Android the APK reports the **real**
 * ROM build, model and SoC from `android.os.Build`, so the banner is genuinely
 * useful on a device instead of showing sample text.
 */
data class PlatformInfo(
    val romName: String,
    val androidVersion: String,
    val buildNumber: String,
    val deviceName: String,
    val soc: String,
    val isRealDevice: Boolean,
)

expect fun platformInfo(): PlatformInfo

/**
 * Build channel only. **Not** the version.
 *
 * There used to be a `NAME`/`CODE` here that the Home banner displayed. They
 * were a hand-written copy of what `build.gradle.kts` declares, and they drifted
 * exactly as a copy does: the manifest said 0.2.0 while this said 0.1.0, and
 * neither had been bumped in six builds — so an installed APK could not be
 * identified at all.
 *
 * The version now comes from `PackageInfo` via [com.hzzmonet.zkbomb.data.BombInstallInfo],
 * which cannot disagree with the APK because it *is* the APK.
 */
object BombVersion {
    const val LABEL = "Preview"
}
