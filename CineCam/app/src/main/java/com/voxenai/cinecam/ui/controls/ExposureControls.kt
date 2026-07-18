package com.voxenai.cinecam.ui.controls

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.voxenai.cinecam.camera.CameraCapabilities
import com.voxenai.cinecam.camera.CameraState
import kotlin.math.ln
import kotlin.math.exp

/** ISO slider on a perceptual (logarithmic) scale, since sensitivity ranges span decades. */
@Composable
fun IsoControl(state: CameraState, capabilities: CameraCapabilities, onIsoChange: (Int) -> Unit) {
    val lo = ln(capabilities.isoRange.lower.toFloat().coerceAtLeast(1f))
    val hi = ln(capabilities.isoRange.upper.toFloat().coerceAtLeast(lo + 1f))
    val current = ((ln(state.iso.toFloat().coerceAtLeast(1f)) - lo) / (hi - lo)).coerceIn(0f, 1f)

    LabeledSlider(
        label = "ISO ${state.iso}",
        value = current,
        onValueChange = { t ->
            val iso = exp(lo + t * (hi - lo)).toInt()
            onIsoChange(iso)
        },
    )
}

/** Shutter angle in degrees, the classic cinematography control (180° = natural motion blur). */
@Composable
fun ShutterAngleControl(state: CameraState, onAngleChange: (Float) -> Unit) {
    val angle = state.shutterAngleDegrees().coerceIn(MIN_ANGLE, MAX_ANGLE)
    LabeledSlider(
        label = "Shutter ${state.shutterSpeedLabel()} (${angle.toInt()}°)",
        value = (angle - MIN_ANGLE) / (MAX_ANGLE - MIN_ANGLE),
        onValueChange = { t -> onAngleChange(MIN_ANGLE + t * (MAX_ANGLE - MIN_ANGLE)) },
    )
}

@Composable
fun WhiteBalanceControl(state: CameraState, onKelvinChange: (Int) -> Unit) {
    val kelvin = state.whiteBalanceKelvin.coerceIn(MIN_KELVIN, MAX_KELVIN)
    LabeledSlider(
        label = "WB ${kelvin}K",
        value = (kelvin - MIN_KELVIN).toFloat() / (MAX_KELVIN - MIN_KELVIN),
        onValueChange = { t -> onKelvinChange((MIN_KELVIN + t * (MAX_KELVIN - MIN_KELVIN)).toInt()) },
    )
}

@Composable
fun ManualFocusControl(
    state: CameraState,
    capabilities: CameraCapabilities,
    onManualFocusToggled: (Boolean) -> Unit,
    onFocusDistanceChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Manual focus", color = MaterialTheme.colorScheme.onSurface)
            Switch(
                checked = state.manualFocus,
                onCheckedChange = onManualFocusToggled,
                enabled = capabilities.supportsManualFocus,
            )
        }
        if (state.manualFocus) {
            LabeledSlider(
                label = if (state.focusDistanceDiopters < 0.05f) "Focus ∞" else "Focus ${"%.2f".format(1f / state.focusDistanceDiopters)}m",
                value = (state.focusDistanceDiopters / capabilities.minFocusDistanceDiopters.coerceAtLeast(0.01f)).coerceIn(0f, 1f),
                onValueChange = { t -> onFocusDistanceChange(t * capabilities.minFocusDistanceDiopters) },
            )
        }
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, onValueChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelMedium)
        Slider(value = value.coerceIn(0f, 1f), onValueChange = onValueChange)
    }
}

private const val MIN_ANGLE = 11f
private const val MAX_ANGLE = 360f
private const val MIN_KELVIN = 2000
private const val MAX_KELVIN = 10000
