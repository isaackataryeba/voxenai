package com.voxenai.cinecam.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat

/**
 * AAC-LC encoder fed raw PCM16 buffers from [com.voxenai.cinecam.audio.AudioCaptureThread].
 * Presentation timestamps are derived from a running sample count rather than wall-clock reads,
 * so encoded audio stays evenly paced even if a capture buffer is delivered a little late.
 */
class AudioEncoderCore(
    sampleRate: Int,
    channelCount: Int,
    bitrateBps: Int,
    private val muxer: MuxerWrapper,
) {
    private val mediaCodec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private var trackIndex = -1
    private val bufferInfo = MediaCodec.BufferInfo()
    private var sawEndOfStream = false
    private var framesEncoded = 0L
    private val sampleRateHz = sampleRate

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        mediaCodec.start()
    }

    /** [pcm16] holds `length` bytes of interleaved 16-bit PCM. */
    fun encode(pcm16: ByteArray, length: Int, channelCount: Int) {
        val inputBufferId = mediaCodec.dequeueInputBuffer(TIMEOUT_US)
        if (inputBufferId >= 0) {
            val inputBuffer = mediaCodec.getInputBuffer(inputBufferId)
                ?: throw RuntimeException("encoder input buffer $inputBufferId was null")
            inputBuffer.clear()
            inputBuffer.put(pcm16, 0, length)

            val presentationTimeUs = framesEncoded * 1_000_000L / sampleRateHz
            val frameCount = length / (2 * channelCount)
            framesEncoded += frameCount

            mediaCodec.queueInputBuffer(inputBufferId, 0, length, presentationTimeUs, 0)
        }
        drain(false)
    }

    fun signalEndOfStream() {
        val inputBufferId = mediaCodec.dequeueInputBuffer(TIMEOUT_US)
        if (inputBufferId >= 0) {
            val presentationTimeUs = framesEncoded * 1_000_000L / sampleRateHz
            mediaCodec.queueInputBuffer(inputBufferId, 0, 0, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }
        drain(true)
    }

    fun drain(endOfStream: Boolean) {
        if (sawEndOfStream) return

        drainLoop@ while (true) {
            val outputBufferId = mediaCodec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            if (outputBufferId == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (endOfStream) continue@drainLoop else return
            }

            if (outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                trackIndex = muxer.addAudioTrack(mediaCodec.outputFormat)
                continue@drainLoop
            }

            if (outputBufferId < 0) continue@drainLoop

            val encodedData = mediaCodec.getOutputBuffer(outputBufferId)
                ?: throw RuntimeException("encoder output buffer $outputBufferId was null")

            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                bufferInfo.size = 0
            }

            if (bufferInfo.size != 0 && trackIndex >= 0) {
                encodedData.position(bufferInfo.offset)
                encodedData.limit(bufferInfo.offset + bufferInfo.size)
                muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
            }

            mediaCodec.releaseOutputBuffer(outputBufferId, false)

            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                sawEndOfStream = true
                return
            }

            if (!endOfStream) return
        }
    }

    fun release() {
        runCatching { mediaCodec.stop() }
        mediaCodec.release()
    }

    companion object {
        private const val TIMEOUT_US = 10_000L
    }
}
