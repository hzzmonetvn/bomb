package com.hzzmonet.zkbomb.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts only automation that the user explicitly left enabled. */
class AutomationBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!SharedPreferencesAutomationRuleRepository(context).isEnabled()) return
        context.startService(Intent(context, BombCoreService::class.java))
    }
}
