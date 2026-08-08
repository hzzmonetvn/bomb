package com.hzzmonet.zkbomb.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

private class AndroidBombSettings(
    private val preferences: SharedPreferences,
) : BombSettings {
    override fun getBoolean(key: String, defaultValue: Boolean) = preferences.getBoolean(key, defaultValue)
    override fun getInt(key: String, defaultValue: Int) = preferences.getInt(key, defaultValue)
    override fun getFloat(key: String, defaultValue: Float) = preferences.getFloat(key, defaultValue)
    override fun getString(key: String, defaultValue: String) =
        preferences.getString(key, defaultValue) ?: defaultValue

    override fun putBoolean(key: String, value: Boolean) {
        preferences.edit().putBoolean(key, value).apply()
    }

    override fun putInt(key: String, value: Int) {
        preferences.edit().putInt(key, value).apply()
    }

    override fun putFloat(key: String, value: Float) {
        preferences.edit().putFloat(key, value).apply()
    }

    override fun putString(key: String, value: String) {
        preferences.edit().putString(key, value).apply()
    }
}

@Composable
actual fun rememberBombSettings(): BombSettings {
    val appContext = LocalContext.current.applicationContext
    return remember(appContext) {
        AndroidBombSettings(appContext.getSharedPreferences("bomb_settings", Context.MODE_PRIVATE))
    }
}
