package com.hzzmonet.zkbomb.preview

import android.os.Build

/**
 * Real device identity, from public `android.os.Build` fields only.
 *
 * The HyperOS/MIUI marketing name lives in `ro.mi.os.version.name`, which is only
 * reachable through hidden APIs — blocked for a normal app and not worth a
 * reflection hack in a preview build. `Build.DISPLAY` already carries the ROM
 * build id (`OS3.0.2.0.…`), which is the number that actually identifies a build.
 */
actual fun platformInfo(): PlatformInfo {
    val brand = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
    val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Build.SOC_MODEL.takeIf { it.isNotBlank() && it != Build.UNKNOWN } ?: Build.HARDWARE
    } else {
        Build.HARDWARE
    }
    return PlatformInfo(
        romName = brand,
        androidVersion = "Android ${Build.VERSION.RELEASE}",
        buildNumber = Build.DISPLAY.ifBlank { Build.ID },
        deviceName = Build.MODEL,
        soc = soc,
        isRealDevice = true,
    )
}
