package com.hzzmonet.zkbomb.data

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.hzzmonet.zkbomb.recorder.CallRecordingService
import com.hzzmonet.zkbomb.recorder.RecordingStore
import com.hzzmonet.zkbomb.recorder.VoipCaptureProbe
import com.hzzmonet.zkbomb.recorder.VoipRecorderHub
import com.hzzmonet.zkbomb.recorder.toCommon
import com.hzzmonet.zkbomb.domain.recorder.RetentionPolicy
import kotlin.concurrent.thread

/**
 * Wires the shared recorder UI to the real Android engine.
 *
 * Two things live here that cannot live in the service: the capture-consent
 * dialog (it must be launched from the composition through an activity-result
 * contract) and the on-disk recordings list (read straight from
 * [RecordingStore], kept off the per-update state so a long list never rides on
 * every status change). Everything else is delegated: commands go to
 * [CallRecordingService], live status comes back through [VoipRecorderHub].
 */
@Composable
actual fun rememberVoipRecorder(): VoipRecorderHandle {
    val appContext = LocalContext.current.applicationContext
    val runtime by VoipRecorderHub.state.collectAsState()

    val store = remember(appContext) { RecordingStore(appContext) }
    var recordings by remember { mutableStateOf(emptyList<VoipRecording>()) }

    // Reload the list whenever a recording is committed/deleted or capture
    // starts/stops — the two moments the on-disk set can change.
    LaunchedEffect(runtime.recordingsRevision, runtime.isRecording) {
        recordings = loadRecordings(store)
    }

    // The consent flow: request the microphone runtime permission, then the
    // capture-projection consent, then hand both results to the service. Held in
    // a small pending record so the async result knows what to enable.
    var pending by remember { mutableStateOf<PendingEnable?>(null) }

    val projectionManager = remember(appContext) {
        appContext.getSystemService(MediaProjectionManager::class.java)
    }

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val request = pending
        pending = null
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null && request != null) {
            CallRecordingService.enable(
                context = appContext,
                resultCode = result.resultCode,
                resultData = data,
                watched = request.watched,
                automatic = request.automatic,
            )
        } else {
            VoipRecorderHub.update { it.copy(error = "Capture consent was declined") }
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            launchProjectionConsent(projectionManager, projectionLauncher::launch)
        } else {
            pending = null
            VoipRecorderHub.update { it.copy(error = "Microphone permission is required to record calls") }
        }
    }

    val controller = remember(appContext) {
        object : VoipRecorderController {
            override fun enable(watched: List<String>, automatic: Boolean) {
                pending = PendingEnable(watched, automatic)
                if (hasMicPermission(appContext)) {
                    launchProjectionConsent(projectionManager, projectionLauncher::launch)
                } else {
                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }

            override fun disable() = CallRecordingService.disable(appContext)

            override fun updateConfig(watched: List<String>, automatic: Boolean) =
                CallRecordingService.setConfig(appContext, watched, automatic)

            override fun stopCurrent() = CallRecordingService.stopCurrent(appContext)

            override fun answerPrompt(packageName: String, record: Boolean, remember: Boolean) =
                CallRecordingService.answerPrompt(appContext, packageName, record, remember)

            override fun delete(id: String) {
                // File + metadata delete off the main thread; refresh via the hub.
                thread(name = "voip-delete") {
                    store.delete(id)
                    VoipRecorderHub.bumpRecordings()
                }
            }

            override fun applyRetention(days: Int?) {
                thread(name = "voip-retention") {
                    val policy = RetentionPolicy.entries.firstOrNull { it.days == days }
                        ?: RetentionPolicy.FOREVER
                    val removed = store.enforceRetention(policy, System.currentTimeMillis())
                    if (removed > 0) VoipRecorderHub.bumpRecordings()
                }
            }

            override fun probe() {
                thread(name = "voip-probe") {
                    val result = VoipCaptureProbe(appContext).probe()
                    VoipRecorderHub.update {
                        it.copy(support = result.support.toCommon(), supportDetail = result.detail)
                    }
                }
            }

            override fun clearError() = VoipRecorderHub.update { it.copy(error = null) }
        }
    }

    // One passive capability check per process, so the screen can show support
    // before anything is enabled. Guarded so it does not re-open the mic on every
    // recomposition or navigation.
    LaunchedEffect(Unit) {
        if (runtime.support == VoipCaptureSupport.UNPROBED) controller.probe()
    }

    val state = VoipRecorderState(
        support = runtime.support,
        supportDetail = runtime.supportDetail,
        enabled = runtime.enabled,
        isRecording = runtime.isRecording,
        activePackage = runtime.activePackage,
        recordings = recordings,
        storageBytes = recordings.sumOf { it.sizeBytes },
        pendingPromptPackage = runtime.pendingPromptPackage,
        error = runtime.error,
    )
    return VoipRecorderHandle(state, controller)
}

private data class PendingEnable(val watched: List<String>, val automatic: Boolean)

private fun hasMicPermission(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

private fun launchProjectionConsent(
    manager: MediaProjectionManager,
    launch: (android.content.Intent) -> Unit,
) {
    runCatching { launch(manager.createScreenCaptureIntent()) }
        .onFailure {
            VoipRecorderHub.update {
                it.copy(error = "This build has no capture-consent screen")
            }
        }
}

private fun loadRecordings(store: RecordingStore): List<VoipRecording> =
    store.list().map {
        VoipRecording(
            id = it.id,
            packageName = it.packageName,
            startedAtEpochMillis = it.startedAtEpochMillis,
            durationMillis = it.durationMillis,
            sizeBytes = it.sizeBytes,
            silent = it.silent,
        )
    }
