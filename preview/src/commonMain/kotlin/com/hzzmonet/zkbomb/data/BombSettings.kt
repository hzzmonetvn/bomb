package com.hzzmonet.zkbomb.data

import androidx.compose.runtime.Composable

/** Small, typed preference surface used by the UI state holder. */
interface BombSettings {
    fun getBoolean(key: String, defaultValue: Boolean): Boolean
    fun getInt(key: String, defaultValue: Int): Int
    fun getFloat(key: String, defaultValue: Float): Float
    fun getString(key: String, defaultValue: String): String
    fun putBoolean(key: String, value: Boolean)
    fun putInt(key: String, value: Int)
    fun putFloat(key: String, value: Float)
    fun putString(key: String, value: String)
}

@Composable
expect fun rememberBombSettings(): BombSettings
