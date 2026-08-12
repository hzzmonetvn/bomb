package com.hzzmonet.zkbomb.core

import android.content.Context
import android.media.AudioManager
import com.hzzmonet.zkbomb.domain.recorder.AudioCallMode
import java.util.concurrent.Executor

/** Stops platform capture promptly when the matching call audio mode ends. */
internal class RecordingModeSignalSource(
    context: Context,
    private val executor: Executor,
    private val onModeChanged: (AudioCallMode) -> Unit,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val listener = AudioManager.OnModeChangedListener { mode ->
        onModeChanged(AudioCallMode.fromAudioManagerMode(mode))
    }
    private var registered = false

    @Synchronized
    fun start(): Boolean {
        if (registered) return true
        val manager = audioManager ?: return false
        return runCatching {
            manager.addOnModeChangedListener(executor, listener)
            registered = true
            executor.execute {
                onModeChanged(AudioCallMode.fromAudioManagerMode(manager.mode))
            }
            true
        }.getOrDefault(false)
    }

    @Synchronized
    fun stop() {
        if (!registered) return
        runCatching { audioManager?.removeOnModeChangedListener(listener) }
        registered = false
    }
}
