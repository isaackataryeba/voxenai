package com.voxenai.cinecam.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.voxenai.cinecam.encoder.AudioEncoderCore
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Captures mono/stereo PCM16 from the microphone, applies a manual gain multiplier (the
 * "manual audio gain" control from the spec — a real hardware pre-amp trim isn't exposed by
 * AudioRecord, so this is a calibrated digital gain stage applied before AAC encoding), and
 * streams buffers into [AudioEncoderCore].
 */
class AudioCaptureThread(
    private val encoder: AudioEncoderCore,
    private val sampleRate: Int = 48_000,
    private val channelCount: Int = 1,
) : Thread("CineCam-AudioCapture") {

    /** Manual gain in dB, adjustable live while recording; 0 = unity (no change). */
    @Volatile
    var gainDb: Float = 0f

    @Volatile
    private var running = false

    private val channelConfig =
        if (channelCount == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize: Int

    private var audioRecord: AudioRecord? = null

    init {
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        require(minBufferSize > 0) { "Unsupported audio format for this device" }
        // ~100ms of headroom above the platform minimum so a scheduling hiccup doesn't drop audio.
        bufferSize = maxOf(minBufferSize, sampleRate / 10 * 2 * channelCount)
    }

    @SuppressLint("MissingPermission") // caller is responsible for RECORD_AUDIO having been granted
    fun startCapture() {
        val record = AudioRecord(
            MediaRecorder.AudioSource.CAMCORDER,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize,
        ).let {
            if (it.state == AudioRecord.STATE_INITIALIZED) it else {
                it.release()
                AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, bufferSize)
            }
        }
        audioRecord = record
        record.startRecording()
        running = true
        start()
    }

    override fun run() {
        val buffer = ByteArray(bufferSize)
        while (running) {
            val record = audioRecord ?: break
            val read = try {
                record.read(buffer, 0, buffer.size)
            } catch (e: Exception) {
                Log.e(TAG, "audio read failed, stopping capture", e)
                break
            }
            if (read > 0) {
                applyGain(buffer, read, gainDb)
                try {
                    encoder.encode(buffer, read, channelCount)
                } catch (e: Exception) {
                    Log.e(TAG, "audio encode failed", e)
                }
            }
        }
    }

    fun stopCapture() {
        running = false
        runCatching { join(500) }
        audioRecord?.let {
            runCatching { it.stop() }
            it.release()
        }
        audioRecord = null
        runCatching { encoder.signalEndOfStream() }
    }

    private fun applyGain(buffer: ByteArray, length: Int, gainDb: Float) {
        if (gainDb == 0f) return
        val gainLinear = 10.0.pow(gainDb / 20.0)
        var i = 0
        while (i + 1 < length) {
            val sample = (buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)
            val boosted = (sample * gainLinear).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            buffer[i] = (boosted and 0xFF).toByte()
            buffer[i + 1] = ((boosted shr 8) and 0xFF).toByte()
            i += 2
        }
    }

    companion object {
        private const val TAG = "AudioCaptureThread"
    }
}
