package com.hzzmonet.zkbomb.domain.recorder

/**
 * Decides what the recorder should do, and nothing else.
 *
 * Pure: [decide] is a function of the current [CallSignal], whether a recording
 * is already running, and the per-app answers the user has given before. It
 * touches no Android API and holds no state, so the entire decision table — the
 * part that is easy to get subtly wrong and expensive to debug on a device —
 * runs as plain JUnit.
 *
 * The ordering of the checks is the design:
 *
 *  1. **Capture support first.** If the device cannot actually record a call,
 *     no mode, no watched app and no prior answer matters. Refusing here means
 *     the UI can never present a control that would silently produce nothing.
 *  2. **Feature off.** [RecordingMode.OFF] stops anything in flight and arms
 *     nothing.
 *  3. **Is a watched app in a call?** Both conditions, together. A watched app
 *     merely in the foreground is [RecorderDecision.Armed] — observed, costing
 *     nothing, capturing nothing.
 *  4. **Consent.** Only once a watched app is genuinely in a call does mode and
 *     the remembered answer decide between recording, prompting and suppressing.
 */
object CallRecordingPolicy {

    fun decide(
        signal: CallSignal,
        wasRecording: Boolean,
        rememberedAnswers: Map<String, Boolean> = emptyMap(),
    ): RecorderDecision {
        // 1. Capture must be proven to work. Every non-usable state is a refusal
        //    with a distinct, actionable reason rather than a generic failure.
        if (!signal.capture.isUsable) {
            return RecorderDecision.Unsupported(reasonFor(signal.capture))
        }

        // 2. Feature off: stop anything running, arm nothing.
        if (signal.mode == RecordingMode.OFF) {
            return if (wasRecording) {
                RecorderDecision.StopRecording("Recording turned off")
            } else {
                RecorderDecision.Standby
            }
        }

        val pkg = signal.foregroundPackage
        val watchedForeground = pkg != null && pkg in signal.watched
        val callActive = signal.audioMode == AudioCallMode.IN_COMMUNICATION

        // 3. Not (a watched app AND an active call). Anything in flight stops;
        //    otherwise arm if a watched app is in the foreground, else stand by.
        if (!watchedForeground || !callActive) {
            return when {
                wasRecording -> RecorderDecision.StopRecording(
                    if (!callActive) "Call ended" else "Left the recorded app",
                )
                watchedForeground -> RecorderDecision.Armed(pkg)
                else -> RecorderDecision.Standby
            }
        }

        // 4. A watched app is in a live call. Keep going if already recording;
        //    otherwise the mode and the remembered answer decide.
        if (wasRecording) return RecorderDecision.ContinueRecording(pkg)

        return when (signal.mode) {
            RecordingMode.AUTOMATIC -> RecorderDecision.StartRecording(pkg)
            RecordingMode.ASK -> when (rememberedAnswers[pkg]) {
                true -> RecorderDecision.StartRecording(pkg)
                false -> RecorderDecision.Suppressed(pkg)
                null -> RecorderDecision.Prompt(pkg)
            }
            // Handled in step 2; here only to keep the `when` exhaustive.
            RecordingMode.OFF -> RecorderDecision.Standby
        }
    }

    private fun reasonFor(capture: CaptureSupport): String = when (capture) {
        CaptureSupport.SILENT ->
            "This device's call audio does not reach the capture path — recordings would be silent"
        CaptureSupport.DENIED ->
            "Bomb is missing the privileged permission needed to capture call audio"
        CaptureSupport.UNAVAILABLE ->
            "This build has no call-audio capture path"
        CaptureSupport.UNPROBED ->
            "Call capture has not been checked on this device yet"
        CaptureSupport.SUPPORTED ->
            // Unreachable: isUsable already screened this out.
            "Supported"
    }
}
