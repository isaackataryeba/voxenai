package com.voxenai.cinecam.gl

import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.view.Surface
import com.voxenai.cinecam.camera.CameraState
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Downsampled RGBA frame handed to the waveform/histogram analyzer, off the GL thread's hot path. */
data class AnalysisFrame(val width: Int, val height: Int, val rgba: ByteBuffer)

interface CameraGLListener {
    /** Called once the camera input SurfaceTexture exists, so the caller can open Camera2 onto it. */
    fun onCameraSurfaceTextureAvailable(surfaceTexture: SurfaceTexture)
    fun onAnalysisFrame(frame: AnalysisFrame)
}

/**
 * Owns the single EGL context that both the on-screen preview and the video encoder draw
 * through. Every camera frame is rendered exactly once per destination surface, all on this
 * dedicated GL thread, so there's no contention between preview, recording, and monitoring.
 */
class CameraGLRenderThread(private val listener: CameraGLListener) :
    HandlerThread("CineCam-GLRender"), Handler.Callback {

    private lateinit var handler: Handler
    private lateinit var eglCore: EglCore
    private lateinit var offscreenSurface: WindowSurface
    private lateinit var filterProgram: CameraFilterProgram

    private var cameraTexId = -1
    private var cameraSurfaceTexture: SurfaceTexture? = null
    private val texMatrix = FloatArray(16)

    private var previewSurface: WindowSurface? = null
    private var previewWidth = 0
    private var previewHeight = 0

    private var encoderSurface: WindowSurface? = null
    private var encoderWidth = 0
    private var encoderHeight = 0
    private var encoderDrainCallback: (() -> Unit)? = null

    private var analysisSurface: WindowSurface? = null
    private var analysisBuffer: ByteBuffer? = null
    private var frameCount = 0

    @Volatile
    private var currentState = CameraState()
    private var lutTextureId = -1
    private var lutSize = 2
    private var hasRealLut = false
    private var startTimeNanos = 0L
    private var cameraFrameWidth = 1920
    private var cameraFrameHeight = 1080

    override fun start() {
        super.start()
        handler = Handler(looper, this)
        handler.sendEmptyMessage(MSG_INIT)
    }

    override fun handleMessage(msg: Message): Boolean {
        when (msg.what) {
            MSG_INIT -> onInit()
            MSG_SET_PREVIEW_SURFACE -> onSetPreviewSurface(msg.obj as Surface, msg.arg1, msg.arg2)
            MSG_FRAME_AVAILABLE -> onFrameAvailable()
            MSG_UPDATE_STATE -> currentState = msg.obj as CameraState
            MSG_SET_LUT -> onSetLut(msg.obj as CubeLut?)
            MSG_SET_CAMERA_SIZE -> { cameraFrameWidth = msg.arg1; cameraFrameHeight = msg.arg2 }
            MSG_SHUTDOWN -> onShutdown()
        }
        return true
    }

    private fun onInit() {
        eglCore = EglCore()
        offscreenSurface = WindowSurface.offscreen(eglCore, 1, 1)
        offscreenSurface.makeCurrent()

        filterProgram = CameraFilterProgram()
        cameraTexId = GlUtil.createExternalTexture()
        cameraSurfaceTexture = SurfaceTexture(cameraTexId).apply {
            setOnFrameAvailableListener({ handler.sendEmptyMessage(MSG_FRAME_AVAILABLE) }, handler)
        }
        val identity = CubeLut.identity()
        lutTextureId = identity.uploadTexture()
        lutSize = identity.size
        startTimeNanos = System.nanoTime()

        cameraSurfaceTexture?.let { listener.onCameraSurfaceTextureAvailable(it) }
    }

    private fun onSetPreviewSurface(surface: Surface, width: Int, height: Int) {
        previewSurface?.release()
        previewSurface = WindowSurface.forSurface(eglCore, surface, takeOwnership = false)
        previewWidth = width
        previewHeight = height
    }

    private fun onSetEncoderSurface(surface: Surface?, width: Int, height: Int) {
        encoderSurface?.release()
        if (surface == null) {
            encoderSurface = null
            encoderDrainCallback = null
            return
        }
        encoderSurface = WindowSurface.forSurface(eglCore, surface, takeOwnership = false)
        encoderWidth = width
        encoderHeight = height
    }

    private fun onSetLut(lut: CubeLut?) {
        if (lutTextureId >= 0) {
            GLES20.glDeleteTextures(1, intArrayOf(lutTextureId), 0)
        }
        val effective = lut ?: CubeLut.identity()
        lutTextureId = effective.uploadTexture()
        lutSize = effective.size
        hasRealLut = lut != null
    }

    private fun onFrameAvailable() {
        val surfaceTexture = cameraSurfaceTexture ?: return
        offscreenSurface.makeCurrent()
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(texMatrix)

        val timeSeconds = (System.nanoTime() - startTimeNanos) / 1_000_000_000f
        val state = currentState

        previewSurface?.let { surface ->
            surface.makeCurrent()
            GLES20.glViewport(0, 0, previewWidth, previewHeight)
            drawFrame(state, timeSeconds)
            surface.swapBuffers()
        }

        encoderSurface?.let { surface ->
            surface.makeCurrent()
            GLES20.glViewport(0, 0, encoderWidth, encoderHeight)
            drawFrame(state, timeSeconds)
            surface.setPresentationTime(surfaceTexture.timestamp)
            surface.swapBuffers()
            encoderDrainCallback?.invoke()
        }

        if (state.waveformEnabled || state.histogramEnabled) {
            frameCount++
            if (frameCount % ANALYSIS_FRAME_INTERVAL == 0) {
                captureAnalysisFrame(state, timeSeconds)
            }
        }
    }

    /** [uTexelSize] is derived from the camera's own texture resolution, not the destination
     *  viewport, since vTextureCoord samples the OES texture's UV space regardless of how large
     *  the surface we're drawing into is. */
    private fun drawFrame(state: CameraState, timeSeconds: Float) {
        filterProgram.draw(
            CameraFilterProgram.DrawParams(
                cameraTextureId = cameraTexId,
                texMatrix = texMatrix,
                lutTextureId = lutTextureId,
                lutSize = if (hasRealLut) lutSize else 2,
                lutStrength = state.lutStrength,
                logEnabled = state.logProfileEnabled,
                zebraEnabled = state.zebraEnabled,
                zebraThreshold = state.zebraThreshold,
                focusPeakingEnabled = state.focusPeakingEnabled,
                sourceWidth = cameraFrameWidth,
                sourceHeight = cameraFrameHeight,
                timeSeconds = timeSeconds,
            )
        )
    }

    private fun captureAnalysisFrame(state: CameraState, timeSeconds: Float) {
        if (analysisSurface == null) {
            analysisSurface = WindowSurface.offscreen(eglCore, ANALYSIS_WIDTH, ANALYSIS_HEIGHT)
            analysisBuffer = ByteBuffer.allocateDirect(ANALYSIS_WIDTH * ANALYSIS_HEIGHT * 4)
                .order(ByteOrder.nativeOrder())
        }
        val surface = analysisSurface ?: return
        val buffer = analysisBuffer ?: return

        surface.makeCurrent()
        GLES20.glViewport(0, 0, ANALYSIS_WIDTH, ANALYSIS_HEIGHT)
        drawFrame(state, timeSeconds)
        buffer.rewind()
        GLES20.glReadPixels(
            0, 0, ANALYSIS_WIDTH, ANALYSIS_HEIGHT, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer
        )
        buffer.rewind()
        listener.onAnalysisFrame(AnalysisFrame(ANALYSIS_WIDTH, ANALYSIS_HEIGHT, buffer))
    }

    private fun onShutdown() {
        previewSurface?.release()
        encoderSurface?.release()
        analysisSurface?.release()
        filterProgram.release()
        cameraSurfaceTexture?.release()
        offscreenSurface.release()
        eglCore.release()
        Looper.myLooper()?.quitSafely()
    }

    // --- Public thread-safe API -------------------------------------------------------------

    fun setPreviewSurface(surface: Surface, width: Int, height: Int) {
        handler.obtainMessage(MSG_SET_PREVIEW_SURFACE, width, height, surface).sendToTarget()
    }

    /**
     * Attaches the video encoder's input surface so subsequent camera frames are drawn into it
     * too, and starts polling [onFrameEncoded] (typically `videoEncoderCore.drain(false)`) once
     * per frame so the encoder's output buffers never back up. Runs on the GL thread, ordered
     * after any already-queued frame draws.
     */
    fun beginRecording(surface: Surface, width: Int, height: Int, onFrameEncoded: () -> Unit) {
        handler.post {
            onSetEncoderSurface(surface, width, height)
            encoderDrainCallback = onFrameEncoded
        }
    }

    /**
     * Detaches the encoder surface and runs [finalDrain] (typically
     * `videoEncoderCore.drain(true)`, which itself signals end-of-stream first) on the GL
     * thread — guaranteed to happen after every frame already drawn into that surface, and
     * before any new one could be. [onFinalized] is invoked once that's done.
     */
    fun endRecording(finalDrain: () -> Unit, onFinalized: () -> Unit) {
        handler.post {
            onSetEncoderSurface(null, 0, 0)
            finalDrain()
            onFinalized()
        }
    }

    fun updateState(state: CameraState) {
        handler.obtainMessage(MSG_UPDATE_STATE, state).sendToTarget()
    }

    fun setLut(lut: CubeLut?) {
        handler.obtainMessage(MSG_SET_LUT, lut).sendToTarget()
    }

    fun setCameraFrameSize(width: Int, height: Int) {
        handler.obtainMessage(MSG_SET_CAMERA_SIZE, width, height).sendToTarget()
    }

    fun shutdown() {
        handler.sendEmptyMessage(MSG_SHUTDOWN)
    }

    companion object {
        private const val MSG_INIT = 1
        private const val MSG_SET_PREVIEW_SURFACE = 2
        private const val MSG_FRAME_AVAILABLE = 4
        private const val MSG_UPDATE_STATE = 5
        private const val MSG_SET_LUT = 6
        private const val MSG_SHUTDOWN = 7
        private const val MSG_SET_CAMERA_SIZE = 8

        private const val ANALYSIS_FRAME_INTERVAL = 3
        const val ANALYSIS_WIDTH = 160
        const val ANALYSIS_HEIGHT = 90
    }
}
