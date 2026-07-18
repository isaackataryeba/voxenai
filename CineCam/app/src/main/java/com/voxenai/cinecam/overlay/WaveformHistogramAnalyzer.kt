package com.voxenai.cinecam.overlay

import com.voxenai.cinecam.gl.AnalysisFrame

/** Per-channel exposure histogram (binned 0..255 into [BUCKET_COUNT] buckets) plus a luma waveform. */
data class MonitorData(
    val histogramR: IntArray,
    val histogramG: IntArray,
    val histogramB: IntArray,
    /** waveform[column][lumaBucket] = pixel count, column count == source frame width. */
    val waveform: Array<IntArray>,
    val waveformColumns: Int,
    val waveformLumaBuckets: Int,
)

/**
 * Turns a small downsampled RGBA frame (captured off the live GL pipeline, see
 * [com.voxenai.cinecam.gl.CameraGLRenderThread]) into histogram + waveform data. Cheap enough
 * (a few thousand pixels) to run on a background dispatcher per analysis frame without
 * competing with the render thread.
 */
object WaveformHistogramAnalyzer {
    const val BUCKET_COUNT = 64
    private const val LUMA_BUCKETS = 64

    fun analyze(frame: AnalysisFrame): MonitorData {
        val buffer = frame.rgba
        buffer.rewind()
        val pixelCount = frame.width * frame.height
        val bytes = ByteArray(pixelCount * 4)
        buffer.get(bytes)

        val histR = IntArray(BUCKET_COUNT)
        val histG = IntArray(BUCKET_COUNT)
        val histB = IntArray(BUCKET_COUNT)
        val waveform = Array(frame.width) { IntArray(LUMA_BUCKETS) }
        // BUCKET_COUNT is 64, i.e. 256 possible byte values collapsed 4-to-1 (shift right by 2).
        val bucketShift = 2

        var idx = 0
        for (y in 0 until frame.height) {
            for (x in 0 until frame.width) {
                val r = bytes[idx].toInt() and 0xFF
                val g = bytes[idx + 1].toInt() and 0xFF
                val b = bytes[idx + 2].toInt() and 0xFF
                idx += 4

                histR[(r shr bucketShift).coerceIn(0, BUCKET_COUNT - 1)]++
                histG[(g shr bucketShift).coerceIn(0, BUCKET_COUNT - 1)]++
                histB[(b shr bucketShift).coerceIn(0, BUCKET_COUNT - 1)]++

                val luma = (0.299f * r + 0.587f * g + 0.114f * b).toInt().coerceIn(0, 255)
                val lumaBucket = (luma * LUMA_BUCKETS / 256).coerceIn(0, LUMA_BUCKETS - 1)
                waveform[x][lumaBucket]++
            }
        }

        return MonitorData(histR, histG, histB, waveform, frame.width, LUMA_BUCKETS)
    }
}
