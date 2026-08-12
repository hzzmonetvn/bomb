package com.hzzmonet.zkbomb.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import com.hzzmonet.zkbomb.api.BombResult
import com.hzzmonet.zkbomb.api.RecordingBackendStatus
import com.hzzmonet.zkbomb.domain.recorder.AudioCallMode
import com.hzzmonet.zkbomb.domain.recorder.CaptureSupport
import com.hzzmonet.zkbomb.domain.recorder.PlatformRecordingOutcome
import com.hzzmonet.zkbomb.domain.recorder.PlatformRecordingPolicy
import com.hzzmonet.zkbomb.domain.recorder.PlatformRecordingRequest
import com.hzzmonet.zkbomb.domain.recorder.PlatformRecordingRequestValidator
import com.hzzmonet.zkbomb.domain.recorder.PlatformRecordingState
import com.hzzmonet.zkbomb.domain.recorder.RecordingKind
import com.hzzmonet.zkbomb.domain.recorder.RecordingSignalClassifier
import java.io.FileDescriptor
import java.util.concurrent.atomic.AtomicBoolean

internal interface RecordingSink {
    val fileDescriptor: FileDescriptor
    fun isValid(): Boolean
    fun close()
}

internal class ParcelRecordingSink(
    private val descriptor: ParcelFileDescriptor,
) : RecordingSink {
    override val fileDescriptor: FileDescriptor get() = descriptor.fileDescriptor
    override fun isValid(): Boolean = descriptor.fileDescriptor.valid()
    override fun close() = descriptor.close()
}

internal data class RecordingCaptureResult(
    val completed: Boolean,
    val clientSilenced: Boolean,
    val peakAmplitude: Int,
)

internal interface PlatformRecordingHandle {
    fun clientSilenced(): Boolean?
    fun stop(): RecordingCaptureResult
}

internal interface PlatformRecordingPort {
    fun capturePermissionHeld(): Boolean
    fun currentAudioMode(): AudioCallMode
    fun start(
        sink: RecordingSink,
        onTerminated: (RecordingCaptureResult) -> Unit,
    ): PlatformRecordingHandle?
}

/** Android's privileged uplink+downlink source. Routing is verified after capture. */
internal class AndroidPlatformRecordingPort(private val context: Context) : PlatformRecordingPort {
    private val audioManager = context.getSystemService(AudioManager::class.java)

    override fun capturePermissionHeld(): Boolean =
        context.checkSelfPermission(CAPTURE_AUDIO_OUTPUT) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    override fun currentAudioMode(): AudioCallMode =
        AudioCallMode.fromAudioManagerMode(audioManager?.mode ?: AudioManager.MODE_NORMAL)

    override fun start(
        sink: RecordingSink,
        onTerminated: (RecordingCaptureResult) -> Unit,
    ): PlatformRecordingHandle? {
        if (!capturePermissionHeld()) return null
        var recorder: MediaRecorder? = null
        return try {
            val created = MediaRecorder(context)
            recorder = created
            val handle = AndroidPlatformRecordingHandle(created, sink, onTerminated)
            created.apply {
                setAudioSource(MediaRecorder.AudioSource.VOICE_CALL)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(sink.fileDescriptor)
                setMaxDuration(MAX_DURATION_MILLIS)
                setMaxFileSize(MAX_FILE_SIZE_BYTES)
                setOnErrorListener { _, _, _ -> handle.terminate(completedHint = false) }
                setOnInfoListener { _, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED ||
                        what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED
                    ) {
                        handle.terminate(completedHint = true)
                    }
                }
                prepare()
                start()
            }
            handle
        } catch (_: Throwable) {
            runCatching { recorder?.reset() }
            runCatching { recorder?.release() }
            null
        }
    }

    private class AndroidPlatformRecordingHandle(
        private val recorder: MediaRecorder,
        private val sink: RecordingSink,
        private val onTerminated: (RecordingCaptureResult) -> Unit,
    ) : PlatformRecordingHandle {
        private val finished = AtomicBoolean(false)

        override fun clientSilenced(): Boolean? = runCatching {
            recorder.activeRecordingConfiguration?.isClientSilenced
        }.getOrNull()

        override fun stop(): RecordingCaptureResult =
            finish(completedHint = true, requireStopSuccess = true)
                ?: RecordingCaptureResult(false, clientSilenced = false, peakAmplitude = 0)

        fun terminate(completedHint: Boolean) {
            val result = finish(completedHint, requireStopSuccess = false) ?: return
            onTerminated(result)
        }

        private fun finish(
            completedHint: Boolean,
            requireStopSuccess: Boolean,
        ): RecordingCaptureResult? {
            if (!finished.compareAndSet(false, true)) return null
            val silenced = clientSilenced() == true
            val peak = runCatching { recorder.maxAmplitude }.getOrDefault(0)
            val stopped = runCatching { recorder.stop() }.isSuccess
            runCatching { recorder.reset() }
            runCatching { recorder.release() }
            runCatching { sink.close() }
            return RecordingCaptureResult(
                completed = completedHint && (!requireStopSuccess || stopped),
                clientSilenced = silenced,
                peakAmplitude = peak,
            )
        }
    }

    private companion object {
        const val CAPTURE_AUDIO_OUTPUT = "android.permission.CAPTURE_AUDIO_OUTPUT"
        const val MAX_DURATION_MILLIS = 4 * 60 * 60 * 1_000
        const val MAX_FILE_SIZE_BYTES = 1_073_741_824L
    }
}

class PlatformRecordingBackend internal constructor(
    private val port: PlatformRecordingPort,
    private val elapsedRealtime: () -> Long,
    private val onSessionEnded: () -> Unit = {},
) {
    private val support = RecordingKind.entries.associateWith { CaptureSupport.UNPROBED }.toMutableMap()
    private var state = PlatformRecordingState.IDLE
    private var active: ActiveSession? = null
    private var lastOutcome: PlatformRecordingOutcome? = null
    private var lastPeakAmplitude: Int? = null

    @Synchronized
    internal fun start(request: PlatformRecordingRequest, sink: RecordingSink): BombResult {
        PlatformRecordingRequestValidator.violations(request).firstOrNull()?.let {
            runCatching { sink.close() }
            return BombResult.invalidArgument(it)
        }
        if (active != null) {
            runCatching { sink.close() }
            return BombResult.failed("A recording session is already active")
        }
        if (!runCatching { sink.isValid() }.getOrDefault(false)) {
            runCatching { sink.close() }
            return BombResult.invalidArgument("output descriptor is invalid")
        }
        val permission = port.capturePermissionHeld()
        if (!permission) {
            runCatching { sink.close() }
            return BombResult.permissionDenied("Privileged call-audio capture is not granted")
        }
        val mode = port.currentAudioMode()
        if (!PlatformRecordingPolicy.mayStart(request.kind, mode, permission, support.getValue(request.kind))) {
            runCatching { sink.close() }
            return BombResult.backendUnavailable(
                "${PlatformRecordingPolicy.expectedMode(request.kind).name} call audio is not active or usable",
            )
        }
        val handle = port.start(sink) { result ->
            completeFromBackend(request.sessionId, result)
        }
        if (handle == null) {
            support[request.kind] = CaptureSupport.UNAVAILABLE
            runCatching { sink.close() }
            return BombResult.backendUnavailable("VOICE_CALL capture path could not be opened")
        }
        active = ActiveSession(request, elapsedRealtime(), handle, sink)
        state = PlatformRecordingState.RECORDING
        return BombResult.success()
    }

    @Synchronized
    internal fun stop(sessionId: String): BombResult {
        if (!PlatformRecordingRequestValidator.isValidSessionId(sessionId)) {
            return BombResult.invalidArgument("sessionId")
        }
        val session = active ?: return BombResult.invalidArgument("No recording session is active")
        if (session.request.sessionId != sessionId) {
            return BombResult.permissionDenied("sessionId does not own the active recording")
        }
        state = PlatformRecordingState.STOPPING
        val result = runCatching { session.handle.stop() }.getOrElse {
            RecordingCaptureResult(false, false, 0)
        }
        return complete(session, result)
    }

    @Synchronized
    private fun completeFromBackend(sessionId: String, result: RecordingCaptureResult) {
        val session = active?.takeIf { it.request.sessionId == sessionId } ?: return
        state = PlatformRecordingState.STOPPING
        complete(session, result)
    }

    private fun complete(session: ActiveSession, result: RecordingCaptureResult): BombResult {
        runCatching { session.sink.close() }
        val boundedPeakAmplitude = result.peakAmplitude.coerceIn(0, MAX_AMPLITUDE)
        val classified = RecordingSignalClassifier.classify(
            result.completed,
            result.clientSilenced,
            boundedPeakAmplitude,
        )
        support[session.request.kind] = classified
        lastOutcome = when (classified) {
            CaptureSupport.SUPPORTED -> PlatformRecordingOutcome.COMPLETED
            CaptureSupport.SILENT -> PlatformRecordingOutcome.SILENT
            else -> PlatformRecordingOutcome.FAILED
        }
        lastPeakAmplitude = boundedPeakAmplitude
        active = null
        state = PlatformRecordingState.IDLE
        val operationResult = when (classified) {
            CaptureSupport.SUPPORTED -> BombResult.success()
            CaptureSupport.SILENT -> BombResult.failed(
                "Capture completed without usable call audio; this path is now disabled",
            )
            else -> BombResult.failed("Recording could not be finalized")
        }
        runCatching { onSessionEnded() }
        return operationResult
    }

    @Synchronized
    internal fun stopForShutdown() {
        val session = active ?: return
        runCatching { session.handle.stop() }
        runCatching { session.sink.close() }
        active = null
        state = PlatformRecordingState.IDLE
    }

    @Synchronized
    internal fun status(): RecordingBackendStatus {
        val session = active
        val permission = port.capturePermissionHeld()
        fun current(kind: RecordingKind): CaptureSupport =
            if (permission) support.getValue(kind) else CaptureSupport.DENIED
        return RecordingBackendStatus(
            state = state.name,
            activeSessionId = session?.request?.sessionId,
            activeKind = session?.request?.kind?.name,
            startedAtElapsedRealtimeMillis = session?.startedAtElapsedRealtimeMillis,
            cellularSupport = current(RecordingKind.CELLULAR).name,
            voipSupport = current(RecordingKind.VOIP).name,
            capturePermissionHeld = permission,
            activeClientSilenced = session?.handle?.clientSilenced(),
            lastOutcome = lastOutcome?.name,
            lastPeakAmplitude = lastPeakAmplitude,
        )
    }

    internal fun capabilitySupport(kind: RecordingKind): CaptureSupport {
        if (!port.capturePermissionHeld()) return CaptureSupport.DENIED
        return synchronized(this) { support.getValue(kind) }
    }

    companion object {
        private const val MAX_AMPLITUDE = 32_767

        internal fun create(
            context: Context,
            onSessionEnded: () -> Unit = {},
        ): PlatformRecordingBackend = PlatformRecordingBackend(
            port = AndroidPlatformRecordingPort(context),
            elapsedRealtime = { android.os.SystemClock.elapsedRealtime() },
            onSessionEnded = onSessionEnded,
        )
    }

    private data class ActiveSession(
        val request: PlatformRecordingRequest,
        val startedAtElapsedRealtimeMillis: Long,
        val handle: PlatformRecordingHandle,
        val sink: RecordingSink,
    )
}
