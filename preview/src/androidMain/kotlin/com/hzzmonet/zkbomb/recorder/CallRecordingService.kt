package com.hzzmonet.zkbomb.recorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.hzzmonet.zkbomb.data.VoipCaptureSupport
import com.hzzmonet.zkbomb.domain.recorder.AudioCallMode
import com.hzzmonet.zkbomb.domain.recorder.CallRecordingPolicy
import com.hzzmonet.zkbomb.domain.recorder.CallSignal
import com.hzzmonet.zkbomb.domain.recorder.CaptureSupport
import com.hzzmonet.zkbomb.domain.recorder.RecorderDecision
import com.hzzmonet.zkbomb.domain.recorder.RecordingMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * The recorder's runtime: a foreground service that holds the capture grant,
 * watches for a watched app in a call, and drives capture through
 * [CallRecordingPolicy].
 *
 * It is deliberately the *only* moving part. The decision of what to do is the
 * domain policy's; the how of capturing is [CallAudioCapture]'s; storage is
 * [RecordingStore]'s. This class sequences them and owns the two things that must
 * be process-scoped and lifecycle-bound: the [MediaProjection] and the ongoing
 * notification that keeps the recording visible.
 *
 * Commands arrive as Intents (there is no bound interface) and state is reported
 * through [VoipRecorderHub]; see the companion for the typed entry points the UI
 * uses instead of building Intents by hand.
 *
 * Foreground-service type is `mediaProjection|microphone`, and on Android 14+ the
 * service must already be foreground *before* the projection is obtained — hence
 * the ordering in [handleEnable].
 */
internal class CallRecordingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()

    private lateinit var store: RecordingStore
    private lateinit var probe: VoipCaptureProbe
    private lateinit var foreground: ForegroundAppMonitor
    private lateinit var audioManager: AudioManager

    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var detectionJob: Job? = null

    private var watched: Set<String> = emptySet()
    private var automatic: Boolean = false
    private var captureSupport: CaptureSupport = CaptureSupport.UNPROBED

    /** Per-app remembered ASK answers for this session. */
    private val rememberedAnswers = HashMap<String, Boolean>()

    /** One-call approvals/denials, cleared when the current call ends. */
    private val oneShotApproved = HashSet<String>()
    private val oneShotDenied = HashSet<String>()

    private var session: Session? = null
    @Volatile
    private var stopping = false

    override fun onCreate() {
        super.onCreate()
        store = RecordingStore(applicationContext)
        probe = VoipCaptureProbe(applicationContext)
        foreground = ForegroundAppMonitor(applicationContext)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ENABLE -> handleEnable(intent)
            ACTION_SET_CONFIG -> handleSetConfig(intent)
            ACTION_ANSWER_PROMPT -> handleAnswerPrompt(intent)
            ACTION_STOP_CURRENT -> stopCapture("Stopped by user")
            ACTION_DISABLE -> handleDisable()
            else -> Unit
        }
        // Do not auto-restart: a restarted process has no MediaProjection, and
        // silently coming back armed-but-unable-to-record would be a lie.
        return START_NOT_STICKY
    }

    // ---- command handlers ----------------------------------------------------

    private fun handleEnable(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        watched = intent.getStringArrayListExtra(EXTRA_WATCHED)?.toSet() ?: emptySet()
        automatic = intent.getBooleanExtra(EXTRA_AUTOMATIC, false)

        // Foreground first, with the declared types, so getMediaProjection is
        // allowed to succeed on Android 14+.
        startForeground(NOTIFICATION_ID, buildNotification("Preparing…"), FOREGROUND_TYPES)

        val probeResult = probe.probe()
        captureSupport = probeResult.support
        VoipRecorderHub.update {
            it.copy(support = probeResult.support.toCommon(), supportDetail = probeResult.detail)
        }
        if (probeResult.support != CaptureSupport.SUPPORTED) {
            VoipRecorderHub.update { it.copy(enabled = false, error = probeResult.detail) }
            stopSelfCleanly()
            return
        }

        if (resultData == null) {
            VoipRecorderHub.update {
                it.copy(enabled = false, error = "No capture consent was provided")
            }
            stopSelfCleanly()
            return
        }

        val manager = getSystemService(MediaProjectionManager::class.java)
        val newProjection = runCatching { manager.getMediaProjection(resultCode, resultData) }.getOrNull()
        if (newProjection == null) {
            VoipRecorderHub.update {
                it.copy(enabled = false, error = "Could not acquire the capture grant")
            }
            stopSelfCleanly()
            return
        }
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                // The system or user revoked the projection: tear down honestly.
                handleDisable()
            }
        }
        newProjection.registerCallback(callback, mainHandler)
        projection = newProjection
        projectionCallback = callback

        val usageWarning = if (!foreground.hasAccess()) {
            "Grant usage access so Bomb can tell which app is calling"
        } else {
            null
        }
        VoipRecorderHub.update {
            it.copy(enabled = true, isRecording = false, activePackage = null, error = usageWarning)
        }
        updateNotification("Watching for calls")
        startDetection()
    }

    private fun handleSetConfig(intent: Intent) {
        watched = intent.getStringArrayListExtra(EXTRA_WATCHED)?.toSet() ?: watched
        automatic = intent.getBooleanExtra(EXTRA_AUTOMATIC, automatic)
    }

    private fun handleAnswerPrompt(intent: Intent) {
        val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: return
        val record = intent.getBooleanExtra(EXTRA_RECORD, false)
        val remember = intent.getBooleanExtra(EXTRA_REMEMBER, false)
        if (remember) rememberedAnswers[pkg] = record
        if (record) oneShotApproved.add(pkg) else oneShotDenied.add(pkg)
        VoipRecorderHub.update { it.copy(pendingPromptPackage = null) }
    }

    private fun handleDisable() {
        stopCapture("Recorder turned off")
        detectionJob?.cancel()
        detectionJob = null
        releaseProjection()
        VoipRecorderHub.update {
            it.copy(enabled = false, isRecording = false, activePackage = null, pendingPromptPackage = null)
        }
        stopSelfCleanly()
    }

    // ---- detection loop ------------------------------------------------------

    private fun startDetection() {
        detectionJob?.cancel()
        detectionJob = scope.launch {
            while (isActive) {
                tick()
                delay(POLL_INTERVAL_MILLIS)
            }
        }
    }

    private fun tick() {
        val audioMode = AudioCallMode.fromAudioManagerMode(audioManager.mode)
        if (audioMode != AudioCallMode.IN_COMMUNICATION) {
            // No VoIP call: any per-call consent is stale.
            oneShotApproved.clear()
            oneShotDenied.clear()
            if (VoipRecorderHub.state.value.pendingPromptPackage != null) {
                VoipRecorderHub.update { it.copy(pendingPromptPackage = null) }
            }
        }

        val signal = CallSignal(
            foregroundPackage = foreground.currentForegroundPackage(),
            audioMode = audioMode,
            watched = watched,
            mode = if (automatic) RecordingMode.AUTOMATIC else RecordingMode.ASK,
            capture = captureSupport,
        )
        val decision = CallRecordingPolicy.decide(
            signal = signal,
            wasRecording = session != null && !stopping,
            rememberedAnswers = effectiveAnswers(),
        )
        act(decision)
    }

    private fun effectiveAnswers(): Map<String, Boolean> {
        val answers = HashMap(rememberedAnswers)
        oneShotApproved.forEach { answers[it] = true }
        oneShotDenied.forEach { answers[it] = false }
        return answers
    }

    private fun act(decision: RecorderDecision) {
        when (decision) {
            is RecorderDecision.StartRecording -> startCapture(decision.packageName)
            is RecorderDecision.ContinueRecording -> Unit
            is RecorderDecision.StopRecording -> stopCapture(decision.reason)
            is RecorderDecision.Prompt -> {
                if (VoipRecorderHub.state.value.pendingPromptPackage != decision.packageName) {
                    VoipRecorderHub.update { it.copy(pendingPromptPackage = decision.packageName) }
                    updateNotification("Call from ${decision.packageName} — asking")
                }
            }
            is RecorderDecision.Suppressed -> {
                if (session != null) stopCapture("Not recording this app")
            }
            is RecorderDecision.Armed -> {
                if (session != null) stopCapture("Call ended")
                updateNotification("Watching ${decision.packageName}")
            }
            RecorderDecision.Standby -> {
                if (session != null) stopCapture("Call ended")
                updateNotification("Watching for calls")
            }
            is RecorderDecision.Unsupported -> {
                captureSupport = CaptureSupport.UNPROBED
                if (session != null) stopCapture("Capture unavailable")
                VoipRecorderHub.update { it.copy(error = decision.reason) }
            }
        }
    }

    // ---- capture lifecycle ---------------------------------------------------

    private fun startCapture(packageName: String) {
        synchronized(lock) {
            if (session != null) return
            val activeProjection = projection ?: return
            val allocation = store.allocate()
            val capture = CallAudioCapture(activeProjection, allocation.file)
            val newSession = Session(
                id = allocation.id,
                packageName = packageName,
                file = allocation.file,
                startedAt = System.currentTimeMillis(),
                capture = capture,
            )
            session = newSession
            stopping = false
            capture.start { result -> onCaptureComplete(newSession, result) }
        }
        VoipRecorderHub.update {
            it.copy(isRecording = true, activePackage = packageName, pendingPromptPackage = null, error = null)
        }
        updateNotification("Recording $packageName")
    }

    private fun stopCapture(reason: String) {
        val active = synchronized(lock) {
            val current = session ?: return
            if (stopping) return
            stopping = true
            current
        }
        // The capture thread finalises the file and calls onCaptureComplete; the
        // reason is carried only for the notification/UI here.
        updateNotification(reason)
        active.capture.stop()
    }

    private fun onCaptureComplete(finished: Session, result: CallAudioCapture.Result) {
        val duration = System.currentTimeMillis() - finished.startedAt
        val file: File = finished.file
        if (result.completed && file.exists() && file.length() > 0L) {
            store.commit(
                id = finished.id,
                packageName = finished.packageName,
                startedAtEpochMillis = finished.startedAt,
                durationMillis = duration,
                silent = result.silent,
            )
            VoipRecorderHub.bumpRecordings()
        } else {
            // Nothing usable was written — do not leave an empty file behind.
            runCatching { if (file.exists()) file.delete() }
        }

        synchronized(lock) {
            session = null
            stopping = false
        }

        val note = when {
            !result.completed -> result.error ?: "Recording failed"
            result.silent -> "Last recording was silent — call audio may not route to capture on this device"
            !result.downlinkCaptured -> "Recorded your side only — the remote capture path did not open"
            else -> null
        }
        VoipRecorderHub.update {
            it.copy(isRecording = false, activePackage = null, error = note ?: it.error)
        }
        updateNotification(if (VoipRecorderHub.state.value.enabled) "Watching for calls" else "Stopped")
    }

    // ---- projection / lifecycle ----------------------------------------------

    private fun releaseProjection() {
        val callback = projectionCallback
        val active = projection
        projectionCallback = null
        projection = null
        if (active != null && callback != null) runCatching { active.unregisterCallback(callback) }
        runCatching { active?.stop() }
    }

    private fun stopSelfCleanly() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        detectionJob?.cancel()
        scope.cancel()
        // Best effort: never leave a capture thread or projection running past us.
        synchronized(lock) { session?.capture?.stop() }
        releaseProjection()
        super.onDestroy()
    }

    // ---- notification --------------------------------------------------------

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Call recording",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Shows while Bomb is watching for or recording a call"
                    setShowBadge(false)
                },
            )
        }
    }

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Bomb call recorder")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private class Session(
        val id: String,
        val packageName: String,
        val file: File,
        val startedAt: Long,
        val capture: CallAudioCapture,
    )

    companion object {
        private const val CHANNEL_ID = "voip_recorder"
        private const val NOTIFICATION_ID = 0x50EC
        private const val POLL_INTERVAL_MILLIS = 1_000L
        private val FOREGROUND_TYPES =
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE

        private const val ACTION_ENABLE = "com.hzzmonet.zkbomb.recorder.ENABLE"
        private const val ACTION_DISABLE = "com.hzzmonet.zkbomb.recorder.DISABLE"
        private const val ACTION_SET_CONFIG = "com.hzzmonet.zkbomb.recorder.SET_CONFIG"
        private const val ACTION_STOP_CURRENT = "com.hzzmonet.zkbomb.recorder.STOP_CURRENT"
        private const val ACTION_ANSWER_PROMPT = "com.hzzmonet.zkbomb.recorder.ANSWER_PROMPT"

        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val EXTRA_WATCHED = "watched"
        private const val EXTRA_AUTOMATIC = "automatic"
        private const val EXTRA_PACKAGE = "package"
        private const val EXTRA_RECORD = "record"
        private const val EXTRA_REMEMBER = "remember"

        private fun base(context: Context, action: String): Intent =
            Intent(context, CallRecordingService::class.java).setAction(action)

        /** Start the service foreground with a fresh capture grant. */
        fun enable(
            context: Context,
            resultCode: Int,
            resultData: Intent,
            watched: List<String>,
            automatic: Boolean,
        ) {
            val intent = base(context, ACTION_ENABLE)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData)
                .putStringArrayListExtra(EXTRA_WATCHED, ArrayList(watched))
                .putExtra(EXTRA_AUTOMATIC, automatic)
            context.startForegroundService(intent)
        }

        fun disable(context: Context) {
            runCatching { context.startService(base(context, ACTION_DISABLE)) }
        }

        fun setConfig(context: Context, watched: List<String>, automatic: Boolean) {
            val intent = base(context, ACTION_SET_CONFIG)
                .putStringArrayListExtra(EXTRA_WATCHED, ArrayList(watched))
                .putExtra(EXTRA_AUTOMATIC, automatic)
            runCatching { context.startService(intent) }
        }

        fun stopCurrent(context: Context) {
            runCatching { context.startService(base(context, ACTION_STOP_CURRENT)) }
        }

        fun answerPrompt(context: Context, packageName: String, record: Boolean, remember: Boolean) {
            val intent = base(context, ACTION_ANSWER_PROMPT)
                .putExtra(EXTRA_PACKAGE, packageName)
                .putExtra(EXTRA_RECORD, record)
                .putExtra(EXTRA_REMEMBER, remember)
            runCatching { context.startService(intent) }
        }
    }
}

/** Maps the domain's capture verdict onto the UI-facing enum. */
internal fun CaptureSupport.toCommon(): VoipCaptureSupport = when (this) {
    CaptureSupport.SUPPORTED -> VoipCaptureSupport.SUPPORTED
    CaptureSupport.SILENT -> VoipCaptureSupport.SILENT
    CaptureSupport.DENIED -> VoipCaptureSupport.DENIED
    CaptureSupport.UNAVAILABLE -> VoipCaptureSupport.UNAVAILABLE
    CaptureSupport.UNPROBED -> VoipCaptureSupport.UNPROBED
}
