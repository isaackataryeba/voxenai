package com.voxenai.cinecam.camera

import android.hardware.camera2.params.RggbChannelVector
import kotlin.math.ln
import kotlin.math.pow

/**
 * Converts a color temperature in Kelvin to camera sensor RGGB gains suitable for
 * CaptureRequest.COLOR_CORRECTION_GAINS when CONTROL_AWB_MODE is OFF.
 *
 * Uses the Tanner Helland blackbody approximation to get a target sRGB white point, then
 * derives per-channel gains relative to a neutral 6500K reference so 6500K maps to ~(1,1,1,1).
 */
object WhiteBalanceUtils {

    fun kelvinToRggbGains(kelvinIn: Int): RggbChannelVector {
        val kelvin = kelvinIn.coerceIn(2000, 12000)
        val (r6500, g6500, b6500) = blackbodyRgb(6500)
        val (r, g, b) = blackbodyRgb(kelvin)

        // Gain needed to cancel the tint introduced at this temperature, normalized against
        // the 6500K reference so the mid-point stays close to unity gain.
        val gainR = (g6500 / r6500) * (r6500 / r) * (g / g6500)
        val gainB = (g6500 / b6500) * (b6500 / b) * (g / g6500)

        val redGain = gainR.coerceIn(0.5f, 4.0f)
        val greenGain = 1.0f
        val blueGain = gainB.coerceIn(0.5f, 4.0f)

        return RggbChannelVector(redGain, greenGain, greenGain, blueGain)
    }

    /** Approximate blackbody sRGB response (0..1 per channel) for a given Kelvin temperature. */
    private fun blackbodyRgb(kelvin: Int): Triple<Float, Float, Float> {
        val temp = kelvin / 100.0

        val red: Double
        val green: Double
        val blue: Double

        if (temp <= 66.0) {
            red = 255.0
            green = (99.4708025861 * ln(temp) - 161.1195681661).coerceIn(0.0, 255.0)
        } else {
            red = (329.698727446 * (temp - 60.0).pow(-0.1332047592)).coerceIn(0.0, 255.0)
            green = (288.1221695283 * (temp - 60.0).pow(-0.0755148492)).coerceIn(0.0, 255.0)
        }

        blue = when {
            temp >= 66.0 -> 255.0
            temp <= 19.0 -> 0.0
            else -> (138.5177312231 * ln(temp - 10.0) - 305.0447927307).coerceIn(0.0, 255.0)
        }

        // Avoid a zero-division reference for the (rare) temp <= 66 green branch clamp.
        val g = if (temp <= 66.0) green.coerceAtLeast(1.0) else green

        return Triple((red / 255.0).toFloat(), (g / 255.0).toFloat(), (blue / 255.0).toFloat())
    }
}
