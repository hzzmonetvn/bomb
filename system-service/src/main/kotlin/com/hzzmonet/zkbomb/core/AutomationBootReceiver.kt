package com.hzzmonet.zkbomb.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts only long-lived policies that the user explicitly left enabled. */
class AutomationBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val automationEnabled = SharedPreferencesAutomationRuleRepository(context).isEnabled()
        val batteryLabEnabled = SharedPreferencesBatteryLabStateStore(context).hasActiveProfile()
        if (!automationEnabled && !batteryLabEnabled) return
        context.startService(Intent(context, BombCoreService::class.java))
    }
}
