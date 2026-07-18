@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.voxenai.cinecam.ui.controls

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.voxenai.cinecam.camera.CameraState

@Composable
fun MonitoringChipsRow(
    state: CameraState,
    onToggleLog: () -> Unit,
    onToggleZebra: () -> Unit,
    onTogglePeaking: () -> Unit,
    onToggleWaveform: () -> Unit,
    onToggleHistogram: () -> Unit,
) {
    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        MonitorChip("LOG", state.logProfileEnabled, onToggleLog)
        MonitorChip("Zebra", state.zebraEnabled, onToggleZebra)
        MonitorChip("Peaking", state.focusPeakingEnabled, onTogglePeaking)
        MonitorChip("Waveform", state.waveformEnabled, onToggleWaveform)
        MonitorChip("Histogram", state.histogramEnabled, onToggleHistogram)
    }
}

@Composable
private fun MonitorChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        modifier = Modifier.padding(horizontal = 4.dp),
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(),
    )
}
