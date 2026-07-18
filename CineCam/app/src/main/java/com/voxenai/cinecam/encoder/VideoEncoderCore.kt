package com.voxenai.cinecam.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import com.voxenai.cinecam.camera.VideoCodec
import java.nio.ByteBuffer

/**
 * Surface-input H.264/H.265 encoder. The GL render thread draws directly into [inputSurface],
 * so there's no extra YUV conversion or buffer copy between "what the shader produced" and
 * "what gets encoded" — the recorded file matches the monitored preview exactly.
 */
class VideoEncoderCore(
    width: Int,
    height: Int,
    bitrateBps: Int,
    codec: VideoCodec,
    frameRate: Int,
    private val muxer: MuxerWrapper,
) {
    private val mimeType = when (codec) {
        VideoCodec.H264 -> MediaFormat.MIMETYPE_VIDEO_AVC
        VideoCodec.H265 -> MediaFormat.MIMETYPE_VIDEO_HEVC
    }

    private val mediaCodec: MediaCodec = MediaCodec.createEncoderByType(mimeType)
    val inputSurface: Surface
    private var trackIndex = -1
    private val bufferInfo = MediaCodec.BufferInfo()
    private var sawEndOfStream = false

    init {
        val format = MediaFormat.createVideoFormat(mimeType, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            runCatching {
                if (codec == VideoCodec.H265) {
                    setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
                } else {
                    setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                    setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41)
                }
            }
        }
        mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = mediaCodec.createInputSurface()
        mediaCodec.start()
    }

    fun signalEndOfStream() {
        mediaCodec.signalEndOfInputStream()
    }

    /**
     * Drains any encoded output currently queued. Call this frequently (e.g. after every camera
     * frame while recording) so encoder buffers never back up; call once more with
     * `endOfStream = true` after [signalEndOfStream] to flush the tail.
     */
    fun drain(endOfStream: Boolean) {
        if (sawEndOfStream) return
        if (endOfStream) signalEndOfStream()

        drainLoop@ while (true) {
            val outputBufferId = mediaCodec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            if (outputBufferId == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // No output ready right now. If we're just doing routine per-frame draining,
                // stop and try again next frame; if flushing the tail at end-of-stream, keep
                // polling until the EOS-flagged buffer actually shows up.
                if (endOfStream) continue@drainLoop else return
            }

            if (outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                trackIndex = muxer.addVideoTrack(mediaCodec.outputFormat)
                continue@drainLoop
            }

            if (outputBufferId < 0) continue@drainLoop // ignore other legacy INFO_* codes

            val encodedData: ByteBuffer = mediaCodec.getOutputBuffer(outputBufferId)
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
        try {
            mediaCodec.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "codec stop failed, already stopped?", e)
        }
        mediaCodec.release()
        inputSurface.release()
    }

    companion object {
        private const val TAG = "VideoEncoderCore"
        private const val TIMEOUT_US = 10_000L
    }
}
