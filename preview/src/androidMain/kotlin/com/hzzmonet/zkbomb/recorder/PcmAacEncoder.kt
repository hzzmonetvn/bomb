package com.hzzmonet.zkbomb.recorder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File

/**
 * Encodes a stream of raw PCM-16 mono audio into an AAC-LC `.m4a` file, using
 * only the platform `MediaCodec`/`MediaMuxer` — no third-party library.
 *
 * The lifecycle is strict and single-threaded on the caller's capture thread:
 * [start] once, [encode] repeatedly with successive PCM chunks, [stop] once. It
 * is not safe to call these concurrently; the [CallAudioCapture] loop owns the
 * instance for the whole recording and no one else touches it.
 *
 * Presentation timestamps are derived from the number of samples fed, not from
 * the wall clock: a dropped read must not stretch the timeline, and the encoder
 * has no other truth about how much audio it has seen.
 */
internal class PcmAacEncoder(
    private val output: File,
    private val sampleRate: Int,
    private val channelCount: Int,
    private val bitRate: Int = 96_000,
) {
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private val bufferInfo = MediaCodec.BufferInfo()
    private var presentationUs = 0L
    private val bytesPerFrame = 2 * channelCount

    fun start() {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            sampleRate,
            channelCount,
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        codec = encoder
        // The muxer is created here but only started once the codec reports its
        // real output format — MediaMuxer.addTrack must be given the codec's
        // format, not the input format, or the file's decoder config is wrong.
        muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    /** Feed one chunk of interleaved PCM-16 little-endian. [length] bytes of [pcm] are used. */
    fun encode(pcm: ByteArray, length: Int) {
        val encoder = codec ?: return
        var offset = 0
        while (offset < length) {
            val inputIndex = encoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (inputIndex < 0) {
                // No input buffer free yet: drain some output to unblock it and retry.
                drain(endOfStream = false)
                continue
            }
            val inputBuffer = encoder.getInputBuffer(inputIndex) ?: continue
            inputBuffer.clear()
            val chunk = minOf(inputBuffer.remaining(), length - offset)
            inputBuffer.put(pcm, offset, chunk)
            encoder.queueInputBuffer(inputIndex, 0, chunk, presentationUs, 0)
            presentationUs += framesFor(chunk) * 1_000_000L / sampleRate
            offset += chunk
            drain(endOfStream = false)
        }
    }

    /**
     * Flushes the encoder, finalises the container and releases everything.
     *
     * Safe to call even if [start] partially failed: each resource is released
     * independently, so a muxer that never started does not prevent the codec
     * from being freed.
     */
    fun stop() {
        val encoder = codec
        if (encoder != null) {
            runCatching {
                val inputIndex = encoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                if (inputIndex >= 0) {
                    encoder.queueInputBuffer(
                        inputIndex, 0, 0, presentationUs,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                    )
                }
                drain(endOfStream = true)
            }
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
        }
        val activeMuxer = muxer
        if (activeMuxer != null) {
            if (muxerStarted) runCatching { activeMuxer.stop() }
            runCatching { activeMuxer.release() }
        }
        codec = null
        muxer = null
    }

    private fun framesFor(byteCount: Int): Long = (byteCount / bytesPerFrame).toLong()

    private fun drain(endOfStream: Boolean) {
        val encoder = codec ?: return
        while (true) {
            val outputIndex = encoder.dequeueOutputBuffer(
                bufferInfo,
                if (endOfStream) DEQUEUE_TIMEOUT_US else 0L,
            )
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    // Nothing ready. Return during normal encoding; keep waiting
                    // only while flushing to end-of-stream.
                    if (!endOfStream) return
                }

                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val activeMuxer = muxer ?: return
                    if (!muxerStarted) {
                        trackIndex = activeMuxer.addTrack(encoder.outputFormat)
                        activeMuxer.start()
                        muxerStarted = true
                    }
                }

                outputIndex >= 0 -> {
                    val outputBuffer = encoder.getOutputBuffer(outputIndex)
                    // The codec-config buffer carries the AAC decoder setup, which
                    // MediaMuxer folds into the track format on addTrack — writing
                    // it as a sample would corrupt the stream.
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0
                    }
                    if (outputBuffer != null && bufferInfo.size > 0 && muxerStarted) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        muxer?.writeSampleData(trackIndex, outputBuffer, bufferInfo)
                    }
                    encoder.releaseOutputBuffer(outputIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private companion object {
        const val DEQUEUE_TIMEOUT_US = 10_000L
        const val MAX_INPUT_SIZE = 16 * 1024
    }
}
