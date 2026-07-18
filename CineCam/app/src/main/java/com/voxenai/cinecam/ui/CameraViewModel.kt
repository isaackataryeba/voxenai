package com.voxenai.cinecam.ui

import android.app.Application
import android.graphics.SurfaceTexture
import android.net.Uri
import android.util.Size
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voxenai.cinecam.camera.CameraCapabilities
import com.voxenai.cinecam.camera.CameraController
import com.voxenai.cinecam.camera.CameraState
import com.voxenai.cinecam.camera.VideoCodec
import com.voxenai.cinecam.encoder.RecordingController
import com.voxenai.cinecam.gl.AnalysisFrame
import com.voxenai.cinecam.gl.CameraGLListener
import com.voxenai.cinecam.gl.CameraGLRenderThread
import com.voxenai.cinecam.gl.CubeLut
import com.voxenai.cinecam.overlay.MonitorData
import com.voxenai.cinecam.overlay.WaveformHistogramAnalyzer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CameraViewModel(application: Application) : AndroidViewModel(application), CameraGLListener {

    private val cameraController = CameraController(application)
    private val glRenderThread = CameraGLRenderThread(this).apply { start() }
    private val recordingController = RecordingController(application, glRenderThread)
    private val analysisScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    val capabilities: StateFlow<CameraCapabilities> = cameraController.capabilities
    val cameraState: StateFlow<CameraState> = cameraController.state
    val isRecording: StateFlow<Boolean> = recordingController.isRecording

    private val _monitorData = MutableStateFlow<MonitorData?>(null)
    val monitorData: StateFlow<MonitorData?> = _monitorData.asStateFlow()

    private val _lastSavedUri = MutableStateFlow<Uri?>(null)
    val lastSavedUri: StateFlow<Uri?> = _lastSavedUri.asStateFlow()

    private var videoSize: Size = Size(1920, 1080)

    init {
        viewModelScope.launch {
            cameraController.state.collect { state ->
                glRenderThread.updateState(state)
                recordingController.updateAudioGain(state.audioGainDb)
            }
        }
    }

    // --- CameraGLListener (called from the GL render thread) --------------------------------

    override fun onCameraSurfaceTextureAvailable(surfaceTexture: SurfaceTexture) {
        viewModelScope.launch {
            val id = cameraController.findPrimaryCameraId()
            val size = cameraController.bestVideoSize(id)
            videoSize = size
            surfaceTexture.setDefaultBufferSize(size.width, size.height)
            glRenderThread.setCameraFrameSize(size.width, size.height)
            cameraController.open(id, Surface(surfaceTexture))
        }
    }

    override fun onAnalysisFrame(frame: AnalysisFrame) {
        // The render thread reuses/overwrites this buffer on the next analysis frame, so make a
        // real byte-for-byte copy here (synchronously, on the GL thread) before hopping threads.
        val bytes = ByteArray(frame.rgba.remaining())
        frame.rgba.get(bytes)
        val copy = AnalysisFrame(frame.width, frame.height, java.nio.ByteBuffer.wrap(bytes))
        analysisScope.launch {
            _monitorData.value = WaveformHistogramAnalyzer.analyze(copy)
        }
    }

    // --- Preview surface lifecycle (from the Compose SurfaceView host) ----------------------

    fun setPreviewSurface(surface: Surface, width: Int, height: Int) {
        glRenderThread.setPreviewSurface(surface, width, height)
    }

    // --- Manual controls ----------------------------------------------------------------------

    fun setIso(iso: Int) = cameraController.setIso(iso)
    fun setShutterAngle(degrees: Float) = cameraController.setShutterAngle(degrees)
    fun setWhiteBalanceKelvin(kelvin: Int) = cameraController.setWhiteBalanceKelvin(kelvin)
    fun setManualFocus(enabled: Boolean, distanceDiopters: Float? = null) =
        cameraController.setManualFocus(enabled, distanceDiopters)
    fun setFrameRate(fps: Int) = cameraController.setFrameRate(fps)

    fun setVideoCodec(codec: VideoCodec) = cameraController.updateUiOnlyState { it.copy(videoCodec = codec) }
    fun setBitrateBps(bps: Int) = cameraController.updateUiOnlyState { it.copy(videoBitrateBps = bps) }
    fun setAudioGainDb(db: Float) = cameraController.updateUiOnlyState { it.copy(audioGainDb = db) }
    fun setLutStrength(strength: Float) = cameraController.updateUiOnlyState { it.copy(lutStrength = strength) }

    fun toggleLogProfile() = cameraController.updateUiOnlyState { it.copy(logProfileEnabled = !it.logProfileEnabled) }
    fun toggleZebra() = cameraController.updateUiOnlyState { it.copy(zebraEnabled = !it.zebraEnabled) }
    fun toggleFocusPeaking() = cameraController.updateUiOnlyState { it.copy(focusPeakingEnabled = !it.focusPeakingEnabled) }
    fun toggleWaveform() = cameraController.updateUiOnlyState { it.copy(waveformEnabled = !it.waveformEnabled) }
    fun toggleHistogram() = cameraController.updateUiOnlyState { it.copy(histogramEnabled = !it.histogramEnabled) }

    fun selectLut(assetName: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            val lut = if (assetName == null) {
                null
            } else {
                runCatching {
                    getApplication<Application>().assets.open("luts/$assetName").use { CubeLut.parse(it) }
                }.getOrNull()
            }
            glRenderThread.setLut(lut)
            cameraController.updateUiOnlyState { it.copy(selectedLutAsset = if (lut != null) assetName else null) }
        }
    }

    // --- Recording ------------------------------------------------------------------------

    fun toggleRecording() {
        // Both directions touch MediaStore/MediaCodec/AudioRecord I/O (file creation, codec
        // setup/teardown, thread join), so keep them off the caller's thread — that's the
        // Compose click handler, i.e. the main thread.
        if (isRecording.value) {
            cameraController.updateUiOnlyState { it.copy(isRecording = false) }
            viewModelScope.launch(Dispatchers.Default) {
                recordingController.stopRecording { uri -> _lastSavedUri.value = uri }
            }
        } else {
            cameraController.updateUiOnlyState {
                it.copy(isRecording = true, recordingStartMs = System.currentTimeMillis())
            }
            val snapshot = cameraState.value
            viewModelScope.launch(Dispatchers.Default) {
                recordingController.startRecording(videoSize.width, videoSize.height, snapshot)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        if (isRecording.value) {
            recordingController.stopRecording {}
        }
        cameraController.shutdown()
        glRenderThread.shutdown()
    }
}
