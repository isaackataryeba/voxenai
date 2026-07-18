package com.voxenai.cinecam.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Owns the Camera2 device/session and drives it with fully manual CaptureRequest parameters
 * (ISO, exposure time, white balance, focus). Camera2 (not CameraX) is used deliberately here
 * because CameraX's Camera2Interop only exposes a subset of these controls and fights the
 * per-frame manual pipeline this app needs.
 *
 * The camera streams into a single Surface backed by a SurfaceTexture owned by the GL render
 * thread; the GL thread fans that frame out to the preview window and (while recording) the
 * encoder's input surface, so this class never needs to know about rendering or encoding.
 */
class CameraController(context: Context) {

    private val cameraManager = context.applicationContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private val cameraThread = HandlerThread("CineCam-Camera").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var requestBuilder: CaptureRequest.Builder? = null
    private var targetSurface: Surface? = null
    private var cameraId: String? = null

    private val _capabilities = MutableStateFlow(CameraCapabilities.UNKNOWN)
    val capabilities: StateFlow<CameraCapabilities> = _capabilities

    private val _state = MutableStateFlow(CameraState())
    val state: StateFlow<CameraState> = _state

    /** Picks the primary back-facing camera, preferring one that exposes the manual sensor capability. */
    fun findPrimaryCameraId(): String {
        val ids = cameraManager.cameraIdList
        val backIds = ids.filter { id ->
            cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        }
        val candidates = backIds.ifEmpty { ids.toList() }
        return candidates.firstOrNull { id ->
            val caps = cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            caps?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
        } ?: candidates.first()
    }

    fun bestVideoSize(id: String, maxWidth: Int = 3840): Size {
        val map = cameraManager.getCameraCharacteristics(id)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(android.graphics.SurfaceTexture::class.java).orEmpty()
        return sizes.filter { it.width <= maxWidth }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: Size(1920, 1080)
    }

    private fun readCapabilities(id: String): CameraCapabilities {
        val chars = cameraManager.getCameraCharacteristics(id)
        val availableCaps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES).orEmpty()
        val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            ?: CameraCapabilities.UNKNOWN.isoRange
        val exposureRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            ?: CameraCapabilities.UNKNOWN.exposureTimeRangeNs
        val minFocus = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val hyperfocal = chars.get(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE) ?: minFocus
        val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        val pixelArray = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)

        return CameraCapabilities(
            isoRange = isoRange,
            exposureTimeRangeNs = exposureRange,
            minFocusDistanceDiopters = minFocus,
            hyperfocalDistanceDiopters = hyperfocal,
            focalLengthMm = focalLengths?.firstOrNull() ?: CameraCapabilities.UNKNOWN.focalLengthMm,
            sensorWidth = pixelArray?.width ?: CameraCapabilities.UNKNOWN.sensorWidth,
            sensorHeight = pixelArray?.height ?: CameraCapabilities.UNKNOWN.sensorHeight,
            supportsManualSensor = availableCaps.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR
            ),
            supportsManualPostProcessing = availableCaps.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING
            ),
        )
    }

    @SuppressLint("MissingPermission")
    suspend fun open(id: String, surface: Surface) {
        cameraId = id
        targetSurface = surface
        _capabilities.value = readCapabilities(id)
        // Clamp the default manual state into whatever this sensor actually supports.
        _state.update { s ->
            s.copy(
                iso = s.iso.coerceIn(_capabilities.value.isoRange.lower, _capabilities.value.isoRange.upper),
                exposureTimeNs = s.exposureTimeNs.coerceIn(
                    _capabilities.value.exposureTimeRangeNs.lower,
                    _capabilities.value.exposureTimeRangeNs.upper
                ),
            )
        }

        cameraDevice = suspendCancellableCoroutine { cont ->
            cameraManager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (cont.isActive) cont.resume(device)
                }

                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    Log.w(TAG, "Camera disconnected: $id")
                }

                override fun onError(device: CameraDevice, error: Int) {
                    device.close()
                    if (cont.isActive) {
                        cont.resumeWithException(IllegalStateException("Camera error $error for $id"))
                    }
                }
            }, cameraHandler)
        }

        createSession(surface)
    }

    private suspend fun createSession(surface: Surface) {
        val device = cameraDevice ?: return
        val outputConfig = OutputConfiguration(surface)

        captureSession = suspendCancellableCoroutine { cont ->
            val stateCallback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cont.isActive) cont.resume(session)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    if (cont.isActive) {
                        cont.resumeWithException(IllegalStateException("Capture session config failed"))
                    }
                }
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                val sessionConfig = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(outputConfig),
                    { it.run() },
                    stateCallback,
                )
                device.createCaptureSession(sessionConfig)
            } else {
                @Suppress("DEPRECATION")
                device.createCaptureSession(listOf(surface), stateCallback, cameraHandler)
            }
        }

        requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(surface)
        }
        applyManualState(_state.value)
        startRepeating()
    }

    private fun startRepeating() {
        val session = captureSession ?: return
        val builder = requestBuilder ?: return
        try {
            session.setRepeatingRequest(builder.build(), null, cameraHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start repeating request", e)
        }
    }

    /** Applies every manual field from [newState] onto the live CaptureRequest.Builder. */
    private fun applyManualState(newState: CameraState) {
        val builder = requestBuilder ?: return
        val caps = _capabilities.value

        // Manual exposure: ISO + shutter + frame duration.
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        val clampedIso = newState.iso.coerceIn(caps.isoRange.lower, caps.isoRange.upper)
        val clampedExposure = newState.exposureTimeNs.coerceIn(
            caps.exposureTimeRangeNs.lower, caps.exposureTimeRangeNs.upper
        )
        builder.set(CaptureRequest.SENSOR_SENSITIVITY, clampedIso)
        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, clampedExposure)
        val frameDurationNs = (1_000_000_000L / newState.frameRate.coerceAtLeast(1))
            .coerceAtLeast(clampedExposure)
        builder.set(CaptureRequest.SENSOR_FRAME_DURATION, frameDurationNs)

        // Manual white balance via Kelvin -> RGGB gains, identity transform.
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
        builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
        builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, WhiteBalanceUtils.kelvinToRggbGains(newState.whiteBalanceKelvin))
        builder.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, IDENTITY_TRANSFORM)

        // Manual focus, or continuous video AF when the user hasn't pulled focus manually.
        if (newState.manualFocus && caps.supportsManualFocus) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.set(
                CaptureRequest.LENS_FOCUS_DISTANCE,
                newState.focusDistanceDiopters.coerceIn(0f, caps.minFocusDistanceDiopters)
            )
        } else {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        }

        // Flat picture profile / LOG-like tone response is applied downstream in the GLSL
        // pipeline, but disabling in-ISP edge/noise processing here keeps more detail for it
        // to work with when the sensor supports manual post-processing.
        if (caps.supportsManualPostProcessing) {
            builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
            builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL)
            builder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_FAST)
        }
    }

    private fun pushState(transform: (CameraState) -> CameraState) {
        _state.update(transform)
        applyManualState(_state.value)
        startRepeating()
    }

    fun setIso(iso: Int) = pushState { it.copy(iso = iso) }

    fun setExposureTimeNs(ns: Long) = pushState { it.copy(exposureTimeNs = ns) }

    /** Convenience setter driven by a shutter-angle dial (degrees) at the current frame rate. */
    fun setShutterAngle(degrees: Float) = pushState { s ->
        val seconds = (degrees / 360.0) / s.frameRate.coerceAtLeast(1)
        s.copy(exposureTimeNs = (seconds * 1_000_000_000.0).toLong())
    }

    fun setWhiteBalanceKelvin(kelvin: Int) = pushState { it.copy(whiteBalanceKelvin = kelvin) }

    fun setManualFocus(enabled: Boolean, distanceDiopters: Float? = null) = pushState {
        it.copy(manualFocus = enabled, focusDistanceDiopters = distanceDiopters ?: it.focusDistanceDiopters)
    }

    fun setFocusDistance(distanceDiopters: Float) = pushState {
        it.copy(manualFocus = true, focusDistanceDiopters = distanceDiopters)
    }

    fun setFrameRate(fps: Int) = pushState { it.copy(frameRate = fps) }

    fun updateUiOnlyState(transform: (CameraState) -> CameraState) {
        _state.update(transform)
    }

    fun close() {
        try {
            captureSession?.close()
            cameraDevice?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing camera", e)
        } finally {
            captureSession = null
            cameraDevice = null
            requestBuilder = null
        }
    }

    fun shutdown() {
        close()
        cameraThread.quitSafely()
    }

    companion object {
        private const val TAG = "CameraController"
        // 3x3 identity matrix as row-major (numerator, denominator) rational pairs.
        private val IDENTITY_TRANSFORM = ColorSpaceTransform(
            intArrayOf(
                1, 1, 0, 1, 0, 1,
                0, 1, 1, 1, 0, 1,
                0, 1, 0, 1, 1, 1,
            )
        )
    }
}
