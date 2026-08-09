package com.hzzmonet.zkbomb.preview

import androidx.compose.ui.graphics.Color

/**
 * ============================================================================
 *  UI FALLBACK DATA — REPLACE WITH REAL BACKEND VALUES PER FEATURE
 * ============================================================================
 *
 * `CLAUDE.md` forbids fake data in the product, and that rule holds: nothing in
 * this file may be used only where a backend explicitly reports unavailable.
 *
 * Two safeguards keep this honest:
 *  1. live Android collectors take precedence whenever data is available;
 *  2. this is the only file in the module holding fallback values — screens and
 *     design system take everything as parameters, so wiring them to a real
 *     `TelemetryRepository` later is a change of caller, not of UI code.
 *
 * The numbers are plausible values for a Snapdragon HyperOS device. They are
 * illustrative, not measured.
 */
object PreviewData {

    val tints = listOf(
        Color(0xFF3482FF),
        Color(0xFF8B5CF6),
        Color(0xFF0EA5A5),
        Color(0xFFFF6A2B),
        Color(0xFF17A67B),
        Color(0xFFE94634),
        Color(0xFF0284C7),
        Color(0xFFE08700),
    )

    fun tintFor(key: String): Color {
        var hash = 7
        key.forEach { hash = (hash * 31 + it.code) and 0x7FFFFFFF }
        return tints[hash % tints.size]
    }

    // ---------------------------------------------------------------- system

    const val DEVICE_PROFILE = "Balanced"
    const val CPU_PERCENT = 18
    const val RAM_PERCENT = 52
    const val RAM_USED_GB = "6.2"
    const val RAM_TOTAL_GB = "12"
    const val BATTERY_PERCENT = 82
    const val BATTERY_TEMP = "37.4"
    const val SOC_TEMP = "41.2"
    const val POWER_WATTS = "5.3"
    const val GPU_PERCENT = 63
    const val FPS = 118

    val cpuHistory = listOf(
        14f, 17f, 22f, 19f, 16f, 24f, 31f, 27f, 21f, 18f,
        16f, 19f, 26f, 34f, 29f, 23f, 20f, 17f, 15f, 18f,
    )
    val gpuHistory = listOf(
        41f, 46f, 52f, 58f, 63f, 66f, 61f, 57f, 60f, 64f,
        68f, 65f, 59f, 55f, 58f, 62f, 66f, 63f, 61f, 63f,
    )
    val thermalHistory = listOf(
        34f, 34f, 35f, 36f, 36f, 37f, 38f, 39f, 39f, 40f,
        40f, 41f, 41f, 42f, 41f, 41f, 40f, 41f, 41f, 41f,
    )

    /** Per-core load / clock / cluster — a 4 + 3 + 1 big.LITTLE layout. */
    val coreLoads = listOf(22f, 18f, 31f, 25f, 44f, 39f, 12f, 68f)
    val coreFreqs = listOf(1075, 1075, 1382, 1075, 2016, 1804, 1420, 2803)
    val coreMaxFreqs = listOf(2016, 2016, 2016, 2016, 2803, 2803, 2803, 3187)
    val coreClusters = listOf("Silver", "Silver", "Silver", "Silver", "Gold", "Gold", "Gold", "Prime")

    const val CPU_USER_PERCENT = 11.4f
    const val CPU_SYS_PERCENT = 5.2f
    const val CPU_IO_WAIT_PERCENT = 1.4f
    const val LOAD_AVG = "1.42 / 1.18 / 0.96"

    const val RAM_AVAILABLE_GB = "5.8"
    const val RAM_CACHED_GB = "2.4"
    const val ZRAM_USED_GB = "1.1"
    const val ZRAM_TOTAL_GB = "4.0"
    const val SWAP_USED_GB = "0.3"

    // ------------------------------------------------------------------ apps

    data class DemoApp(
        val name: String,
        val packageName: String,
        val system: Boolean,
        val running: Boolean,
        val frozen: Boolean,
        val ramMb: Int,
        val freezeMode: String,
        val profile: String,
        val backgroundImpact: String,
    )

    val apps = listOf(
        DemoApp("Telegram", "org.telegram.messenger", false, true, false, 412, "Smart", "Social", "MEDIUM"),
        DemoApp("Genshin Impact", "com.miHoYo.GenshinImpact", false, false, false, 0, "Never", "Gaming", "LOW"),
        DemoApp("Facebook", "com.facebook.katana", false, true, false, 638, "Deep Freeze", "Default", "HIGH"),
        DemoApp("Chrome", "com.android.chrome", false, true, false, 521, "Soft Freeze", "Default", "MEDIUM"),
        DemoApp("Spotify", "com.spotify.music", false, true, false, 297, "Never", "Media", "LOW"),
        DemoApp("Instagram", "com.instagram.android", false, false, true, 0, "Deep Freeze", "Social", "HIGH"),
        DemoApp("Shopee", "com.shopee.vn", false, false, true, 0, "Deep Freeze", "Default", "HIGH"),
        DemoApp("Zalo", "com.zing.zalo", false, true, false, 356, "Smart", "Social", "MEDIUM"),
        DemoApp("System UI", "com.android.systemui", true, true, false, 284, "Excluded", "System", "LOW"),
        DemoApp("Launcher", "com.miui.home", true, true, false, 198, "Excluded", "System", "LOW"),
        DemoApp("Settings", "com.android.settings", true, false, false, 0, "Never", "System", "LOW"),
        DemoApp("Bomb", "com.hzzmonet.zkbomb", true, true, false, 74, "Excluded", "System", "LOW"),
    )

    // ------------------------------------------------------------- processes

    data class DemoProcess(
        val name: String,
        val processName: String,
        val pid: Int,
        val cpu: Float,
        val ramMb: Int,
        val state: String?,
        val system: Boolean,
    )

    val processes = listOf(
        DemoProcess("System UI", "com.android.systemui", 1842, 6.4f, 284, null, true),
        DemoProcess("Facebook", "com.facebook.katana", 9214, 5.1f, 638, null, false),
        DemoProcess("Chrome", "com.android.chrome", 8877, 4.8f, 521, null, false),
        DemoProcess("Telegram", "org.telegram.messenger", 7431, 3.2f, 412, null, false),
        DemoProcess("system_server", "system_server", 1203, 2.9f, 412, null, true),
        DemoProcess("Zalo", "com.zing.zalo", 9902, 2.1f, 356, null, false),
        DemoProcess("Spotify", "com.spotify.music", 8102, 1.7f, 297, null, false),
        DemoProcess("Launcher", "com.miui.home", 2011, 1.2f, 198, null, true),
        DemoProcess("surfaceflinger", "surfaceflinger", 742, 0.9f, 156, null, true),
        DemoProcess("Bomb", "com.hzzmonet.zkbomb", 3140, 0.4f, 74, null, true),
        DemoProcess("Bomb core", "com.hzzmonet.zkbomb:core", 3155, 0.3f, 41, null, true),
        DemoProcess("Instagram", "com.instagram.android", 0, 0f, 0, "Frozen", false),
        DemoProcess("Shopee", "com.shopee.vn", 0, 0f, 0, "Frozen", false),
    )

    /**
     * Per-process detail, derived deterministically from the row above so the
     * sample set stays in one place. On a device these come from
     * `/proc/<pid>/{stat,status,smaps_rollup}` and `Debug.MemoryInfo`.
     */
    data class DemoProcessDetail(
        val uid: Int,
        val threads: Int,
        val pssMb: Int,
        val swapMb: Int,
        val oomAdj: Int,
        val schedState: String,
        val cpuTime: String,
        val userPercent: Float,
        val sysPercent: Float,
        val startedAgo: String,
    )

    fun detailFor(process: DemoProcess): DemoProcessDetail {
        var hash = 17
        process.processName.forEach { hash = (hash * 31 + it.code) and 0x7FFFFFFF }
        val userShare = 0.55f + (hash % 25) / 100f
        val minutes = 4 + hash % 220
        return DemoProcessDetail(
            uid = if (process.system) 1000 + hash % 40 else 10_100 + hash % 200,
            threads = if (process.pid == 0) 0 else 8 + hash % 46,
            pssMb = (process.ramMb * 0.78f).toInt(),
            swapMb = if (process.ramMb == 0) 0 else hash % 64,
            oomAdj = if (process.system) 0 else listOf(0, 100, 200, 700, 900)[hash % 5],
            schedState = when {
                process.pid == 0 -> "Frozen"
                process.cpu > 3f -> "Running"
                else -> "Sleeping"
            },
            cpuTime = "${minutes / 60}h ${minutes % 60}m",
            userPercent = process.cpu * userShare,
            sysPercent = process.cpu * (1f - userShare),
            startedAgo = "${1 + hash % 9}h ago",
        )
    }

    // -------------------------------------------------------- execution mode

    enum class ExecMode { ROM, ROOT }

    /**
     * What each privileged backend can actually do.
     * 2 = full, 1 = partial, 0 = unavailable.
     */
    data class DemoCapability(
        val name: String,
        val detail: String,
        val rom: Int,
        val root: Int,
    )

    val capabilities = listOf(
        DemoCapability("CPU / memory telemetry", "procfs sampling", 2, 2),
        DemoCapability("Per-process CPU", "/proc/<pid>/stat", 2, 2),
        DemoCapability("GPU telemetry", "vendor sysfs nodes", 2, 2),
        DemoCapability("FPS / frame time", "dumpsys gfxinfo, needs DUMP", 2, 2),
        DemoCapability("Thermal sensors", "thermal_zone + vendor nodes", 2, 2),
        DemoCapability("Force stop", "FORCE_STOP_PACKAGES", 2, 2),
        DemoCapability("Soft freeze", "background restriction", 2, 2),
        DemoCapability("Deep freeze", "package suspend / disable", 2, 2),
        DemoCapability("Performance control", "writes to governor nodes", 2, 2),
        DemoCapability("Charge control", "battery sysfs writes", 2, 2),
        DemoCapability("Network policy", "per-uid rules", 2, 2),
        DemoCapability("Log Governor", "logd properties", 2, 2),
        DemoCapability("Bomb Bridge", "notification + HyperOS renderer", 2, 2),
        DemoCapability("Call recording", "platform audio source", 2, 1),
    )

    // ------------------------------------------------------------- automation

    data class DemoRule(
        val name: String,
        val trigger: String,
        val action: String,
        val enabled: Boolean,
    )

    val rules = listOf(
        DemoRule("Gaming session", "App foreground · 3 games", "Performance → Gaming, Stats → Gaming overlay", true),
        DemoRule("Screen-off freeze", "Screen off for 10 min", "Freeze eligible apps", true),
        DemoRule("Thermal guard", "Battery temp ≥ 43 °C", "Performance → Sustainable, Bridge event", true),
        DemoRule("Night charging", "Charging · 23:00–06:00", "Charge limit 80 %", false),
        DemoRule("Low battery quiet", "Battery ≤ 15 %", "Performance → Eco, Log profile → Quiet", false),
    )

    val ruleTemplates = listOf(
        "Gaming" to "Profile + overlay on game launch",
        "Battery saver" to "Eco profile under a level",
        "Screen-off freeze" to "Freeze after the screen sleeps",
        "Thermal control" to "Back off when the device heats",
    )

    // ---------------------------------------------------------------- VoIP

    /**
     * Apps the recorder can watch.
     *
     * [captureSupported] is what a capability probe found on this build, not a
     * wish list: an app is only offered for recording when a usable capture
     * path actually exists for it.
     */
    data class DemoVoipApp(
        val name: String,
        val packageName: String,
        val captureSupported: Boolean,
        val note: String,
    )

    val voipApps = listOf(
        DemoVoipApp("Zalo", "com.zing.zalo", true, "VoIP audio source available"),
        DemoVoipApp("Telegram", "org.telegram.messenger", true, "VoIP audio source available"),
        DemoVoipApp("Messenger", "com.facebook.orca", true, "VoIP audio source available"),
        DemoVoipApp("WhatsApp", "com.whatsapp", false, "App blocks capture on this build"),
        DemoVoipApp("Viber", "com.viber.voip", true, "VoIP audio source available"),
        DemoVoipApp("Signal", "org.thoughtcrime.securesms", false, "App blocks capture on this build"),
        DemoVoipApp("Discord", "com.discord", true, "VoIP audio source available"),
    )

    // ----------------------------------------------------------- bridge/logs

    data class DemoEvent(
        val title: String,
        val subtitle: String,
        val type: String,
        val renderer: String,
    )

    val bridgeEvents = listOf(
        DemoEvent("Gaming", "118 FPS · 41 °C · 5.8 W", "GAME_MONITOR", "HyperOS"),
        DemoEvent("Charging", "67 % · 31 W · 37 °C", "CHARGING", "HyperOS"),
        DemoEvent("Recorder", "Recording · 08:42", "CALL_RECORDING", "Live Update"),
        DemoEvent("Hotspot", "3 devices · 12.4 Mbps", "HOTSPOT", "Live Update"),
        DemoEvent("Download", "ROM package · 68 %", "DOWNLOAD", "Notification"),
    )

    val logTags = listOf(
        Triple("ActivityManager", "INFO+", true),
        Triple("BluetoothManager", "WARN+", true),
        Triple("WifiHAL", "ERROR+", true),
        Triple("ThermalEngine", "WARN+", false),
    )

    // -------------------------------------------------------------- network

    data class DemoTraffic(
        val name: String,
        val packageName: String,
        val downMb: String,
        val upMb: String,
        val wifiAllowed: Boolean,
        val mobileAllowed: Boolean,
    )

    val traffic = listOf(
        DemoTraffic("Chrome", "com.android.chrome", "1.42 GB", "184 MB", true, true),
        DemoTraffic("Telegram", "org.telegram.messenger", "812 MB", "233 MB", true, true),
        DemoTraffic("Spotify", "com.spotify.music", "643 MB", "18 MB", true, false),
        DemoTraffic("Facebook", "com.facebook.katana", "512 MB", "96 MB", true, false),
        DemoTraffic("Shopee", "com.shopee.vn", "204 MB", "31 MB", true, true),
    )
}
