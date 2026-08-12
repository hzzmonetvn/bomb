package com.hzzmonet.zkbomb.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/** Event-driven battery-temperature input; no polling loop and no raw sysfs write. */
internal class ThermalGuardianSignalSource(
    private val context: Context,
    private val onTemperature: (Int?) -> Unit,
) {
    private var registered = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
            val value = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, UNKNOWN)
                .takeUnless { it == UNKNOWN }
            onTemperature(value)
        }
    }

    fun start(): Boolean {
        if (registered) return true
        return runCatching {
            val sticky = context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            registered = true
            sticky?.let { receiver.onReceive(context, it) }
            true
        }.getOrDefault(false)
    }

    fun stop() {
        if (!registered) return
        runCatching { context.unregisterReceiver(receiver) }
        registered = false
    }

    private companion object {
        const val UNKNOWN = Int.MIN_VALUE
    }
}
