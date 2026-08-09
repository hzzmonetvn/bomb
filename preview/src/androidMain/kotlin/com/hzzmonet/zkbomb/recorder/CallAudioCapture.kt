package com.hzzmonet.zkbomb.recorder

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import java.io.File
import kotlin.math.abs

/**
 * Captures both sides of a VoIP call to one `.m4a` file.
 *
 * Two sources are read in lockstep and mixed:
 *
 *  - **Downlink** — the remote party, taken with an
 *    [AudioPlaybackCaptureConfiguration] over the caller's [MediaProjection],
 *    matching `USAGE_VOICE_COMMUNICATION`. This is the part that needs the
 *    privileged `CAPTURE_VOICE_COMMUNICATION_OUTPUT` grant; without it the
 *    builder throws and the recording falls back to mic-only, which [start]
 *    reports.
 *  - **Uplink** — the local microphone via `VOICE_COMMUNICATION`, which also
 *    gives the OS's echo-cancelled path so the remote voice is not captured
 *    twice.
 *
 * The whole capture runs on its own thread; [stop] asks it to finish and the
 * completion callback fires with a [Result] once the file is closed. The peak
 * amplitude seen across the recording is carried out so the caller can mark a
 * recording that turned out to be pure silence — the failure mode the capability
 * model exists to catch, since a permitted-but-silent capture looks like success
 * until someone plays it back.
 */
internal class CallAudioCapture(
    private val projection: MediaProjection,
    private val output: File,
) {
    /**
     * @param completed true if capture ran without a fatal error.
     * @param peakAmplitude highest absolute PCM sample seen (0..32767). A value
     *   below [SILENCE_PEAK_THRESHOLD] across the whole call means nothing
     *   audible was captured.
     * @param downlinkCaptured whether the playback-capture stream opened. False
     *   means only the local side was recorded.
     * @param error a human-readable failure, or null.
     */
    data class Result(
        val completed: Boolean,
        val peakAmplitude: Int,
        val downlinkCaptured: Boolean,
        val error: String?,
    ) {
        val silent: Boolean get() = peakAmplitude < SILENCE_PEAK_THRESHOLD
    }

    @Volatile
    private var running = false
    private var worker: Thread? = null

    /** Starts capture on a background thread. [onComplete] runs on that thread when done. */
    fun start(onComplete: (Result) -> Unit) {
        if (running) return
        running = true
        worker = Thread({ capture(onComplete) }, "voip-capture").apply { start() }
    }

    /** Requests a clean stop. The completion callback fires shortly after. */
    fun stop() {
        running = false
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO + CAPTURE_* are verified before the service starts capture.
    private fun capture(onComplete: (Result) -> Unit) {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
        val bufferBytes = maxOf(minBuffer, DEFAULT_BUFFER_BYTES)

        var mic: AudioRecord? = null
        var downlink: AudioRecord? = null
        val encoder = PcmAacEncoder(output, SAMPLE_RATE, CHANNEL_COUNT)
        var peak = 0
        var downlinkOpened = false
        var error: String? = null

        try {
            // Downlink first: if the privileged grant is missing this throws, and
            // we still want a mic-only recording rather than nothing.
            downlink = runCatching { buildDownlinkRecord(bufferBytes) }.getOrNull()
            downlinkOpened = downlink != null

            mic = buildMicRecord(bufferBytes)
            encoder.start()

            downlink?.startRecording()
            mic.startRecording()

            val micBuffer = ByteArray(bufferBytes)
            val downBuffer = ByteArray(bufferBytes)
            val mixBuffer = ByteArray(bufferBytes)

            while (running) {
                val micRead = mic.read(micBuffer, 0, bufferBytes).coerceAtLeast(0)
                val downRead = downlink?.read(downBuffer, 0, bufferBytes)?.coerceAtLeast(0) ?: 0
                val mixed = mixInto(micBuffer, micRead, downBuffer, downRead, mixBuffer)
                if (mixed == 0) {
                    // Both sources returned nothing this pass — yield rather than spin.
                    Thread.sleep(IDLE_SLEEP_MS)
                    continue
                }
                peak = maxOf(peak, peakOf(mixBuffer, mixed))
                encoder.encode(mixBuffer, mixed)
            }
        } catch (t: Throwable) {
            error = t.message ?: t::class.simpleName
        } finally {
            runCatching { mic?.stop() }
            runCatching { mic?.release() }
            runCatching { downlink?.stop() }
            runCatching { downlink?.release() }
            runCatching { encoder.stop() }
            running = false
        }

        onComplete(
            Result(
                completed = error == null,
                peakAmplitude = peak,
                downlinkCaptured = downlinkOpened,
                error = error,
            ),
        )
    }

    private fun buildDownlinkRecord(bufferBytes: Int): AudioRecord {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .addMatchingUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING)
            .build()
        return AudioRecord.Builder()
            .setAudioFormat(audioFormat())
            .setBufferSizeInBytes(bufferBytes)
            .setAudioPlaybackCaptureConfig(config)
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun buildMicRecord(bufferBytes: Int): AudioRecord =
        AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(audioFormat())
            .setBufferSizeInBytes(bufferBytes)
            .build()

    private fun audioFormat(): AudioFormat =
        AudioFormat.Builder()
            .setEncoding(ENCODING)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(CHANNEL_IN)
            .build()

    /**
     * Mixes two PCM-16 little-endian buffers into [out], sample by sample, with
     * saturating addition. Returns the number of bytes written. Where one source
     * is shorter, its missing samples count as silence rather than truncating the
     * other — a short read on the mic must not clip the remote voice.
     */
    private fun mixInto(a: ByteArray, aLen: Int, b: ByteArray, bLen: Int, out: ByteArray): Int {
        val length = maxOf(aLen, bLen)
        // Work in whole samples (2 bytes); an odd trailing byte is dropped.
        val sampleBytes = length and 1.inv()
        var i = 0
        while (i < sampleBytes) {
            val aSample = if (i + 1 < aLen) sampleAt(a, i) else 0
            val bSample = if (i + 1 < bLen) sampleAt(b, i) else 0
            val sum = (aSample + bSample).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[i] = (sum and 0xFF).toByte()
            out[i + 1] = ((sum shr 8) and 0xFF).toByte()
            i += 2
        }
        return sampleBytes
    }

    private fun sampleAt(buffer: ByteArray, index: Int): Int {
        val low = buffer[index].toInt() and 0xFF
        val high = buffer[index + 1].toInt() // sign-extends the high byte
        return (high shl 8) or low
    }

    private fun peakOf(buffer: ByteArray, length: Int): Int {
        var peak = 0
        var i = 0
        val sampleBytes = length and 1.inv()
        while (i < sampleBytes) {
            val magnitude = abs(sampleAt(buffer, i))
            if (magnitude > peak) peak = magnitude
            i += 2
        }
        return peak
    }

    internal companion object {
        const val SAMPLE_RATE = 48_000
        const val CHANNEL_COUNT = 1
        const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val DEFAULT_BUFFER_BYTES = 4096
        const val IDLE_SLEEP_MS = 10L

        /**
         * Peak below this over an entire call means silence. ~1.5% of full scale:
         * high enough to ignore line noise and DC bias, low enough that any real
         * speech clears it comfortably.
         */
        const val SILENCE_PEAK_THRESHOLD = 500
    }
}
