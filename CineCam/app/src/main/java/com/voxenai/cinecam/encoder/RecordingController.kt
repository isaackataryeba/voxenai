package com.voxenai.cinecam.encoder

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.voxenai.cinecam.audio.AudioCaptureThread
import com.voxenai.cinecam.camera.CameraState
import com.voxenai.cinecam.gl.CameraGLRenderThread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Ties the video encoder (surface-fed by the GL render thread), the audio capture + AAC
 * encoder, and the shared muxer together into simple start/stop recording calls. Output is
 * written straight into the device's Movies/CineCam collection via MediaStore on API 29+ (so
 * clips show up in Gallery immediately, matching what any stock camera app does), falling back
 * to public external storage on API 26-28.
 */
class RecordingController(
    private val context: Context,
    private val glRenderThread: CameraGLRenderThread,
) {
    private var videoEncoder: VideoEncoderCore? = null
    private var audioEncoder: AudioEncoderCore? = null
    private var audioCapture: AudioCaptureThread? = null
    private var muxer: MuxerWrapper? = null
    private var output: RecordingOutput? = null

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

    fun startRecording(width: Int, height: Int, state: CameraState) {
        if (_isRecording.value) return

        val displayName = "CineCam_${TIMESTAMP_FORMAT.format(java.util.Date())}.mp4"
        val recordingOutput = createOutput(displayName)
        output = recordingOutput

        val muxerWrapper = recordingOutput.createMuxer()
        muxer = muxerWrapper

        val video = VideoEncoderCore(width, height, state.videoBitrateBps, state.videoCodec, state.frameRate, muxerWrapper)
        videoEncoder = video

        val audio = AudioEncoderCore(sampleRate = AUDIO_SAMPLE_RATE, channelCount = 1, bitrateBps = AUDIO_BITRATE_BPS, muxerWrapper)
        audioEncoder = audio
        val capture = AudioCaptureThread(audio, sampleRate = AUDIO_SAMPLE_RATE, channelCount = 1).apply {
            gainDb = state.audioGainDb
        }
        audioCapture = capture

        glRenderThread.beginRecording(video.inputSurface, width, height) { video.drain(false) }
        capture.startCapture()

        _isRecording.value = true
    }

    fun updateAudioGain(gainDb: Float) {
        audioCapture?.gainDb = gainDb
    }

    /** [onSaved] is invoked off the GL thread once the file is fully finalized and visible in Gallery. */
    fun stopRecording(onSaved: (Uri?) -> Unit) {
        if (!_isRecording.value) return
        _isRecording.value = false

        audioCapture?.stopCapture()
        val video = videoEncoder ?: return

        glRenderThread.endRecording(
            finalDrain = { video.drain(true) },
            onFinalized = {
                video.release()
                audioEncoder?.release()
                muxer?.stopAndRelease()
                val finishedOutput = output
                val uri = finishedOutput?.finalize(context)

                videoEncoder = null
                audioEncoder = null
                audioCapture = null
                muxer = null
                output = null

                onSaved(uri)
            },
        )
    }

    private fun createOutput(displayName: String): RecordingOutput {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/CineCam")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Failed to create MediaStore entry for $displayName")
            val pfd = resolver.openFileDescriptor(uri, "w")
                ?: throw IllegalStateException("Failed to open descriptor for $uri")
            RecordingOutput.MediaStoreOutput(uri, pfd)
        } else {
            @Suppress("DEPRECATION")
            val moviesDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "CineCam")
            moviesDir.mkdirs()
            RecordingOutput.FileOutput(File(moviesDir, displayName))
        }
    }

    companion object {
        private const val AUDIO_SAMPLE_RATE = 48_000
        private const val AUDIO_BITRATE_BPS = 192_000
        private val TIMESTAMP_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    }
}

private sealed class RecordingOutput {
    abstract fun createMuxer(): MuxerWrapper
    /** Marks the output visible/complete and returns its content [Uri] (or null for legacy file paths). */
    abstract fun finalize(context: Context): Uri?

    class FileOutput(private val file: File) : RecordingOutput() {
        override fun createMuxer() = MuxerWrapper(file.absolutePath, expectedTrackCount = 2)
        override fun finalize(context: Context): Uri? {
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("video/mp4"), null)
            return null
        }
    }

    class MediaStoreOutput(private val uri: Uri, private val pfd: ParcelFileDescriptor) : RecordingOutput() {
        override fun createMuxer() = MuxerWrapper(pfd.fileDescriptor, expectedTrackCount = 2)
        override fun finalize(context: Context): Uri {
            runCatching { pfd.close() }
            val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            context.contentResolver.update(uri, values, null, null)
            return uri
        }
    }
}
