package com.hzzmonet.zkbomb.core

import android.annotation.SuppressLint
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.hzzmonet.zkbomb.domain.automation.AutomationEvent
import com.hzzmonet.zkbomb.domain.automation.AutomationTrigger
import com.hzzmonet.zkbomb.domain.freeze.PackageNameValidator
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Event-driven app-importance and screen signal adapter. */
class AutomationSignalSource(
    private val context: Context,
    private val onEvent: (AutomationEvent) -> Unit,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val usageStats = context.getSystemService(UsageStatsManager::class.java)
    private var eventExecutor: ScheduledExecutorService? = null
    private var lastUsageQueryMillis = 0L
    private var foregroundPackage: String? = null
    private var running = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val trigger = when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> AutomationTrigger.SCREEN_ON
                Intent.ACTION_SCREEN_OFF -> AutomationTrigger.SCREEN_OFF
                else -> return
            }
            onEvent(AutomationEvent(trigger, nowMillis()))
        }
    }

    @Synchronized
    fun start(): Boolean {
        if (running) return true
        return runCatching {
            context.registerReceiver(
                screenReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_SCREEN_OFF)
                },
            )
            lastUsageQueryMillis = (nowMillis() - INITIAL_LOOKBACK_MILLIS).coerceAtLeast(0)
            eventExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "BombAutomationEvents").apply { isDaemon = true }
            }.also { executor ->
                executor.scheduleWithFixedDelay(
                    { runCatching(::pollUsageEvents) },
                    0,
                    EVENT_POLL_MILLIS,
                    TimeUnit.MILLISECONDS,
                )
            }
            running = true
            true
        }.getOrElse {
            runCatching { context.unregisterReceiver(screenReceiver) }
            false
        }
    }

    @Synchronized
    fun stop() {
        if (!running) return
        runCatching { context.unregisterReceiver(screenReceiver) }
        eventExecutor?.shutdownNow()
        eventExecutor = null
        foregroundPackage = null
        running = false
    }

    @Synchronized fun isRunning(): Boolean = running

    // Declared by the host app manifest and allowlisted for the integrated ROM.
    // runCatching still handles a normal/sideload install where AppOps denies it.
    @SuppressLint("MissingPermission")
    private fun pollUsageEvents() {
        val stats = usageStats ?: return
        val end = nowMillis()
        val start = lastUsageQueryMillis
        if (end < start) {
            lastUsageQueryMillis = end
            return
        }
        val events = runCatching { stats.queryEvents(start, end) }.getOrNull() ?: return
        lastUsageQueryMillis = end + 1
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val packageName = event.packageName?.takeIf(PackageNameValidator::isValid) ?: continue
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> onForeground(packageName, event.timeStamp)
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED,
                -> onBackground(packageName, event.timeStamp)
            }
        }
    }

    private fun onForeground(packageName: String, timestampMillis: Long) {
        val previous = foregroundPackage
        if (previous == packageName) return
        if (previous != null) {
            onEvent(
                AutomationEvent(
                    AutomationTrigger.APP_BACKGROUND,
                    timestampMillis.coerceAtLeast(0),
                    previous,
                ),
            )
        }
        foregroundPackage = packageName
        onEvent(
            AutomationEvent(
                AutomationTrigger.APP_FOREGROUND,
                timestampMillis.coerceAtLeast(0),
                packageName,
            ),
        )
    }

    private fun onBackground(packageName: String, timestampMillis: Long) {
        if (foregroundPackage != packageName) return
        foregroundPackage = null
        onEvent(
            AutomationEvent(
                AutomationTrigger.APP_BACKGROUND,
                timestampMillis.coerceAtLeast(0),
                packageName,
            ),
        )
    }

    private companion object {
        const val INITIAL_LOOKBACK_MILLIS = 2_000L
        const val EVENT_POLL_MILLIS = 1_000L
    }
}
