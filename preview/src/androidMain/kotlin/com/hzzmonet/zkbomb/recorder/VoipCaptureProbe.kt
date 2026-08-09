package com.hzzmonet.zkbomb.recorder

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.hzzmonet.zkbomb.domain.recorder.CaptureSupport

/**
 * Answers, before anything is recorded, whether this build can capture a call at
 * all — and gives a distinct reason when it cannot.
 *
 * What a probe can honestly establish up front is the *preconditions*: the two
 * permissions the chosen capture path needs, and that the microphone pipe opens
 * and yields frames. It cannot, without a live call and an active
 * `MediaProjection`, prove that the remote party's audio actually routes to the
 * playback-capture path on this specific HAL — that is only known once a real
 * call is captured, and [CallAudioCapture] reports it as silence if it does not.
 *
 * So the contract is deliberate:
 *
 *  - Missing microphone or privileged-capture permission ⇒ [CaptureSupport.DENIED].
 *  - Microphone will not open ⇒ [CaptureSupport.UNAVAILABLE].
 *  - Both permissions held and the mic pipe works ⇒ [CaptureSupport.SUPPORTED],
 *    with the downlink verified for real on the first recording.
 *
 * It never returns [CaptureSupport.SILENT]: silence is a property of a finished
 * recording, not of the preconditions, and claiming it here would be guessing.
 */
internal class VoipCaptureProbe(private val context: Context) {

    /** The privileged permission the both-sides path needs, granted only in ROM mode. */
    private val voiceCapturePermission = "android.permission.CAPTURE_VOICE_COMMUNICATION_OUTPUT"

    fun hasMicPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun hasPrivilegedCapturePermission(): Boolean =
        context.checkSelfPermission(voiceCapturePermission) == PackageManager.PERMISSION_GRANTED

    fun probe(): ProbeResult {
        if (!hasMicPermission()) {
            return ProbeResult(
                CaptureSupport.DENIED,
                "Microphone permission has not been granted",
            )
        }
        if (!hasPrivilegedCapturePermission()) {
            return ProbeResult(
                CaptureSupport.DENIED,
                "The privileged CAPTURE_VOICE_COMMUNICATION_OUTPUT permission is not held — " +
                    "Bomb must be a system app in a ROM that allowlists it",
            )
        }
        return if (microphonePipeWorks()) {
            ProbeResult(
                CaptureSupport.SUPPORTED,
                "Microphone verified. The remote side is confirmed on the first recorded call.",
            )
        } else {
            ProbeResult(
                CaptureSupport.UNAVAILABLE,
                "The microphone capture path would not open on this build",
            )
        }
    }

    /** Opens a short mic capture and confirms it delivers frames without erroring. */
    @SuppressLint("MissingPermission") // Guarded by hasMicPermission() above.
    private fun microphonePipeWorks(): Boolean {
        val minBuffer = AudioRecord.getMinBufferSize(
            CallAudioCapture.SAMPLE_RATE,
            CallAudioCapture.CHANNEL_IN,
            CallAudioCapture.ENCODING,
        )
        if (minBuffer <= 0) return false
        val record = runCatching {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(CallAudioCapture.ENCODING)
                        .setSampleRate(CallAudioCapture.SAMPLE_RATE)
                        .setChannelMask(CallAudioCapture.CHANNEL_IN)
                        .build(),
                )
                .setBufferSizeInBytes(minBuffer)
                .build()
        }.getOrNull() ?: return false

        return try {
            if (record.state != AudioRecord.STATE_INITIALIZED) return false
            record.startRecording()
            val buffer = ByteArray(minBuffer)
            // A couple of reads is enough to prove the pipe delivers frames; we do
            // not judge loudness here (a quiet room is not a failure).
            var frames = 0
            repeat(3) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) frames += read
            }
            frames > 0
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { record.stop() }
            runCatching { record.release() }
        }
    }

    data class ProbeResult(val support: CaptureSupport, val detail: String)
}
