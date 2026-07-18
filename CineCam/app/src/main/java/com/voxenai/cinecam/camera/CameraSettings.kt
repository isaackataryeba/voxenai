package com.voxenai.cinecam.camera

import android.util.Range

/** Codec choice exposed to the UI for the recording pipeline. */
enum class VideoCodec { H264, H265 }

/** Supported hardware manual-control ranges for the currently opened camera device. */
data class CameraCapabilities(
    val isoRange: Range<Int>,
    val exposureTimeRangeNs: Range<Long>,
    val minFocusDistanceDiopters: Float,
    val hyperfocalDistanceDiopters: Float,
    val focalLengthMm: Float,
    val sensorWidth: Int,
    val sensorHeight: Int,
    val supportsManualSensor: Boolean,
    val supportsManualPostProcessing: Boolean,
) {
    val supportsManualFocus: Boolean get() = minFocusDistanceDiopters > 0f

    companion object {
        val UNKNOWN = CameraCapabilities(
            isoRange = Range(50, 3200),
            exposureTimeRangeNs = Range(1_000_000L, 250_000_000L),
            minFocusDistanceDiopters = 10f,
            hyperfocalDistanceDiopters = 0.5f,
            focalLengthMm = 4.3f,
            sensorWidth = 4000,
            sensorHeight = 3000,
            supportsManualSensor = false,
            supportsManualPostProcessing = false,
        )
    }
}

/**
 * User-facing manual controls. [exposureTimeNs] doubles as "shutter speed"; shutter-angle-style
 * display is derived from it and the active frame rate (angle = exposureTime * fps * 360).
 * [focusDistanceDiopters] of 0 means focused at infinity; higher values focus nearer.
 */
data class CameraState(
    val iso: Int = 400,
    val exposureTimeNs: Long = 1_000_000_000L / 50, // ~1/50s, classic 180-degree shutter at 25fps
    val whiteBalanceKelvin: Int = 5600,
    val manualFocus: Boolean = false,
    val focusDistanceDiopters: Float = 0f,
    val frameRate: Int = 25,
    val videoCodec: VideoCodec = VideoCodec.H265,
    val videoBitrateBps: Int = 50_000_000,
    val audioGainDb: Float = 0f,
    val logProfileEnabled: Boolean = true,
    val selectedLutAsset: String? = null,
    val lutStrength: Float = 1f,
    val zebraEnabled: Boolean = false,
    val zebraThreshold: Float = 0.95f,
    val focusPeakingEnabled: Boolean = false,
    val waveformEnabled: Boolean = false,
    val histogramEnabled: Boolean = false,
    val isRecording: Boolean = false,
    val recordingStartMs: Long = 0L,
) {
    /** Shutter angle for the current exposure time + frame rate, e.g. 172.8 degrees at 1/50 @ 24fps. */
    fun shutterAngleDegrees(): Float =
        (exposureTimeNs / 1_000_000_000.0 * frameRate * 360.0).toFloat()

    /** Human-readable "1/50" style shutter speed label. */
    fun shutterSpeedLabel(): String {
        val seconds = exposureTimeNs / 1_000_000_000.0
        if (seconds <= 0) return "0s"
        val denominator = (1.0 / seconds).let { if (it >= 1) Math.round(it) else it }
        return if (seconds >= 1.0) "${"%.1f".format(seconds)}s" else "1/${denominator}"
    }
}
