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

object BombVersion {
    const val NAME = "0.1.0"
    const val CODE = 1
    const val LABEL = "Preview"
    val display: String get() = "v$NAME ($CODE) · $LABEL"
}
