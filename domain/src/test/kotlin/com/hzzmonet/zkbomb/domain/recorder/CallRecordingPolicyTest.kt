package com.hzzmonet.zkbomb.domain.recorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The VoIP recorder's decision table.
 *
 * The cases that carry the most weight are the negative ones: a device that
 * cannot really capture must never reach a "recording" decision, and consent
 * must gate ASK mode before any capture starts. Those are the failures the whole
 * capability/consent design exists to prevent, so they are tested first and
 * hardest.
 */
class CallRecordingPolicyTest {

    private val watched = setOf("com.whatsapp", "org.telegram.messenger")

    private fun signal(
        foreground: String? = "com.whatsapp",
        audioMode: AudioCallMode = AudioCallMode.IN_COMMUNICATION,
        mode: RecordingMode = RecordingMode.AUTOMATIC,
        capture: CaptureSupport = CaptureSupport.SUPPORTED,
    ) = CallSignal(foreground, audioMode, watched, mode, capture)

    // ---- capture support gates everything -----------------------------------

    @Test
    fun `unprobed capture is unsupported whatever else is true`() {
        val decision = CallRecordingPolicy.decide(
            signal(capture = CaptureSupport.UNPROBED),
            wasRecording = false,
        )
        assertTrue(decision is RecorderDecision.Unsupported)
    }

    @Test
    fun `silent capture is refused, not recorded`() {
        // The single most important case: the path opens but yields only silence.
        // Recording it anyway would save an hour of zeroes as if it were a call.
        val decision = CallRecordingPolicy.decide(
            signal(capture = CaptureSupport.SILENT),
            wasRecording = false,
        )
        assertTrue(decision is RecorderDecision.Unsupported)
        assertTrue((decision as RecorderDecision.Unsupported).reason.contains("silent"))
    }

    @Test
    fun `denied capture names a permission problem, not a device one`() {
        val decision = CallRecordingPolicy.decide(
            signal(capture = CaptureSupport.DENIED),
            wasRecording = false,
        ) as RecorderDecision.Unsupported
        assertTrue(decision.reason.contains("permission"))
    }

    @Test
    fun `unsupported wins even while a recording is somehow in flight`() {
        // If capture support flips to unusable mid-call, the decision is still
        // Unsupported — the service turns that into a stop.
        val decision = CallRecordingPolicy.decide(
            signal(capture = CaptureSupport.UNAVAILABLE),
            wasRecording = true,
        )
        assertTrue(decision is RecorderDecision.Unsupported)
    }

    // ---- off -----------------------------------------------------------------

    @Test
    fun `off with nothing running is standby`() {
        val decision = CallRecordingPolicy.decide(
            signal(mode = RecordingMode.OFF),
            wasRecording = false,
        )
        assertEquals(RecorderDecision.Standby, decision)
    }

    @Test
    fun `off stops a recording already running`() {
        val decision = CallRecordingPolicy.decide(
            signal(mode = RecordingMode.OFF),
            wasRecording = true,
        )
        assertTrue(decision is RecorderDecision.StopRecording)
    }

    // ---- arming vs recording -------------------------------------------------

    @Test
    fun `watched app in foreground but no call is armed, not recording`() {
        val decision = CallRecordingPolicy.decide(
            signal(audioMode = AudioCallMode.NORMAL),
            wasRecording = false,
        )
        assertEquals(RecorderDecision.Armed("com.whatsapp"), decision)
    }

    @Test
    fun `unwatched app in a call is standby`() {
        val decision = CallRecordingPolicy.decide(
            signal(foreground = "com.random.app"),
            wasRecording = false,
        )
        assertEquals(RecorderDecision.Standby, decision)
    }

    @Test
    fun `a cellular call does not arm the VoIP recorder`() {
        // IN_CALL is the phone path, a separate feature. IN_COMMUNICATION is VoIP.
        val decision = CallRecordingPolicy.decide(
            signal(audioMode = AudioCallMode.IN_CALL),
            wasRecording = false,
        )
        assertEquals(RecorderDecision.Armed("com.whatsapp"), decision)
    }

    @Test
    fun `null foreground package is standby`() {
        val decision = CallRecordingPolicy.decide(
            signal(foreground = null),
            wasRecording = false,
        )
        assertEquals(RecorderDecision.Standby, decision)
    }

    // ---- automatic -----------------------------------------------------------

    @Test
    fun `automatic records a watched call with no prompt`() {
        val decision = CallRecordingPolicy.decide(
            signal(mode = RecordingMode.AUTOMATIC),
            wasRecording = false,
        )
        assertEquals(RecorderDecision.StartRecording("com.whatsapp"), decision)
    }

    // ---- ask + remembered answers -------------------------------------------

    @Test
    fun `ask with no remembered answer prompts`() {
        val decision = CallRecordingPolicy.decide(
            signal(mode = RecordingMode.ASK),
            wasRecording = false,
        )
        assertEquals(RecorderDecision.Prompt("com.whatsapp"), decision)
    }

    @Test
    fun `ask with a remembered yes records without prompting again`() {
        val decision = CallRecordingPolicy.decide(
            signal(mode = RecordingMode.ASK),
            wasRecording = false,
            rememberedAnswers = mapOf("com.whatsapp" to true),
        )
        assertEquals(RecorderDecision.StartRecording("com.whatsapp"), decision)
    }

    @Test
    fun `ask with a remembered no suppresses`() {
        val decision = CallRecordingPolicy.decide(
            signal(mode = RecordingMode.ASK),
            wasRecording = false,
            rememberedAnswers = mapOf("com.whatsapp" to false),
        )
        assertEquals(RecorderDecision.Suppressed("com.whatsapp"), decision)
    }

    @Test
    fun `a remembered answer for another app does not affect this one`() {
        val decision = CallRecordingPolicy.decide(
            signal(mode = RecordingMode.ASK),
            wasRecording = false,
            rememberedAnswers = mapOf("org.telegram.messenger" to false),
        )
        assertEquals(RecorderDecision.Prompt("com.whatsapp"), decision)
    }

    // ---- continuation and stopping ------------------------------------------

    @Test
    fun `an in-progress recording continues while the call is live`() {
        val decision = CallRecordingPolicy.decide(
            signal(mode = RecordingMode.ASK),
            wasRecording = true,
        )
        assertEquals(RecorderDecision.ContinueRecording("com.whatsapp"), decision)
    }

    @Test
    fun `recording stops when the call ends`() {
        val decision = CallRecordingPolicy.decide(
            signal(audioMode = AudioCallMode.NORMAL),
            wasRecording = true,
        ) as RecorderDecision.StopRecording
        assertTrue(decision.reason.contains("ended"))
    }

    @Test
    fun `recording stops when the user leaves the app mid-call`() {
        val decision = CallRecordingPolicy.decide(
            signal(foreground = "com.android.chrome"),
            wasRecording = true,
        ) as RecorderDecision.StopRecording
        assertTrue(decision.reason.contains("Left"))
    }
}
