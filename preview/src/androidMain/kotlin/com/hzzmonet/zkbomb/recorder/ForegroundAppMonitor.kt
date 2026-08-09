package com.hzzmonet.zkbomb.recorder

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process

/**
 * Reports which app is in the foreground, so a call can be attributed to the
 * watched package that is actually on screen.
 *
 * This needs the `PACKAGE_USAGE_STATS` special access, which is granted per app
 * in Settings, never by a runtime dialog. [hasAccess] says whether it has been
 * granted; when it has not, [currentForegroundPackage] returns null and the
 * policy stays in standby rather than guessing — attributing a call to the wrong
 * app, or recording an app the user never watched, is worse than not arming.
 *
 * The alternative signals were rejected on purpose: there is no public API for
 * "which app put the audio system into communication mode", and polling the
 * process list does not distinguish foreground from a background service.
 */
internal class ForegroundAppMonitor(private val context: Context) {

    private val usageStats =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager

    /** Whether the PACKAGE_USAGE_STATS grant is in place for this app. */
    @Suppress("DEPRECATION") // unsafeCheckOpNoThrow is still the only op-check that does not throw.
    fun hasAccess(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return false
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * The package most recently moved to the foreground within the lookback
     * window, or null if usage access is missing or nothing was reported.
     *
     * A window rather than a point read because `queryEvents` is event-based:
     * the last MOVE_TO_FOREGROUND inside it is the current foreground app.
     */
    fun currentForegroundPackage(nowEpochMillis: Long = System.currentTimeMillis()): String? {
        val stats = usageStats ?: return null
        if (!hasAccess()) return null
        val events = runCatching {
            stats.queryEvents(nowEpochMillis - LOOKBACK_MILLIS, nowEpochMillis)
        }.getOrNull() ?: return null

        var foreground: String? = null
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                foreground = event.packageName
            }
        }
        return foreground
    }

    private companion object {
        const val LOOKBACK_MILLIS = 10_000L
    }
}
