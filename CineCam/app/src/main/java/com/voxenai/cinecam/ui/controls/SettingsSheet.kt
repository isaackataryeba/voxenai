@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.voxenai.cinecam.ui.controls

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.voxenai.cinecam.camera.CameraState
import com.voxenai.cinecam.camera.VideoCodec

/** LUTs bundled in `assets/luts/`; "None" (null asset) means passthrough / LOG-only. */
val BUNDLED_LUTS = listOf(
    "None" to null,
    "Flat Log" to "cinecam_flat_log.cube",
    "Teal & Orange" to "teal_orange.cube",
    "Mono Contrast" to "mono_contrast.cube",
)

private val BITRATE_PRESETS = listOf(
    "20 Mbps" to 20_000_000,
    "50 Mbps" to 50_000_000,
    "80 Mbps" to 80_000_000,
    "100 Mbps" to 100_000_000,
)

private val FRAME_RATE_PRESETS = listOf(24, 25, 30, 50, 60)

@Composable
fun CineCamSettingsSheet(
    state: CameraState,
    onDismiss: () -> Unit,
    onCodecChange: (VideoCodec) -> Unit,
    onBitrateChange: (Int) -> Unit,
    onFrameRateChange: (Int) -> Unit,
    onLutSelected: (String?) -> Unit,
    onLutStrengthChange: (Float) -> Unit,
    onAudioGainChange: (Float) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(16.dp)) {
            Text("Codec", style = MaterialTheme.typography.titleSmall)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                VideoCodec.entries.forEachIndexed { index, codec ->
                    SegmentedButton(
                        selected = state.videoCodec == codec,
                        onClick = { onCodecChange(codec) },
                        shape = SegmentedButtonDefaults.itemShape(index, VideoCodec.entries.size),
                    ) {
                        Text(if (codec == VideoCodec.H264) "H.264" else "H.265 (HEVC)")
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            Text("Bitrate: ${state.videoBitrateBps / 1_000_000} Mbps", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                BITRATE_PRESETS.forEach { (label, bps) ->
                    FilterChip(
                        selected = state.videoBitrateBps == bps,
                        onClick = { onBitrateChange(bps) },
                        label = { Text(label) },
                    )
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            Text("Frame rate: ${state.frameRate} fps", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                FRAME_RATE_PRESETS.forEach { fps ->
                    FilterChip(
                        selected = state.frameRate == fps,
                        onClick = { onFrameRateChange(fps) },
                        label = { Text("$fps") },
                    )
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            Text("LUT", style = MaterialTheme.typography.titleSmall)
            LazyRow(Modifier.padding(vertical = 8.dp)) {
                items(BUNDLED_LUTS) { (label, asset) ->
                    FilterChip(
                        modifier = Modifier.padding(end = 8.dp),
                        selected = state.selectedLutAsset == asset,
                        onClick = { onLutSelected(asset) },
                        label = { Text(label) },
                    )
                }
            }
            if (state.selectedLutAsset != null) {
                Text("LUT strength: ${(state.lutStrength * 100).toInt()}%")
                Slider(value = state.lutStrength, onValueChange = onLutStrengthChange)
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            Text("Audio gain: ${"%.1f".format(state.audioGainDb)} dB", style = MaterialTheme.typography.titleSmall)
            Slider(
                value = (state.audioGainDb + 12f) / 36f,
                onValueChange = { t -> onAudioGainChange(t * 36f - 12f) },
            )
        }
    }
}
