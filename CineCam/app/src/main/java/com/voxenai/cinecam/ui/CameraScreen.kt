package com.voxenai.cinecam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.voxenai.cinecam.overlay.WaveformHistogramOverlay
import com.voxenai.cinecam.ui.controls.CineCamSettingsSheet
import com.voxenai.cinecam.ui.controls.IsoControl
import com.voxenai.cinecam.ui.controls.ManualFocusControl
import com.voxenai.cinecam.ui.controls.MonitoringChipsRow
import com.voxenai.cinecam.ui.controls.RecordButton
import com.voxenai.cinecam.ui.controls.RecordingTimer
import com.voxenai.cinecam.ui.controls.ShutterAngleControl
import com.voxenai.cinecam.ui.controls.WhiteBalanceControl
import kotlinx.coroutines.delay

@Composable
fun CameraScreen(viewModel: CameraViewModel = viewModel()) {
    val cameraState by viewModel.cameraState.collectAsStateWithLifecycle()
    val capabilities by viewModel.capabilities.collectAsStateWithLifecycle()
    val isRecording by viewModel.isRecording.collectAsStateWithLifecycle()
    val monitorData by viewModel.monitorData.collectAsStateWithLifecycle()

    var showSettings by remember { mutableStateOf(false) }
    var elapsedMs by remember { mutableStateOf(0L) }

    LaunchedEffect(isRecording) {
        if (isRecording) {
            val start = System.currentTimeMillis()
            while (true) {
                elapsedMs = System.currentTimeMillis() - start
                delay(200)
            }
        } else {
            elapsedMs = 0L
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        CameraPreviewSurface(
            onSurfaceAvailable = viewModel::setPreviewSurface,
            modifier = Modifier.fillMaxSize(),
        )

        WaveformHistogramOverlay(
            data = monitorData,
            showWaveform = cameraState.waveformEnabled,
            showHistogram = cameraState.histogramEnabled,
            modifier = Modifier.align(Alignment.TopStart).padding(top = 48.dp, start = 8.dp),
        )

        if (isRecording) {
            RecordingTimer(elapsedMs, modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp))
        }

        IconButton(
            onClick = { showSettings = true },
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
        ) {
            Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = Color.White)
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(bottom = 12.dp),
        ) {
            MonitoringChipsRow(
                state = cameraState,
                onToggleLog = viewModel::toggleLogProfile,
                onToggleZebra = viewModel::toggleZebra,
                onTogglePeaking = viewModel::toggleFocusPeaking,
                onToggleWaveform = viewModel::toggleWaveform,
                onToggleHistogram = viewModel::toggleHistogram,
            )
            IsoControl(cameraState, capabilities, onIsoChange = viewModel::setIso)
            ShutterAngleControl(cameraState, onAngleChange = viewModel::setShutterAngle)
            WhiteBalanceControl(cameraState, onKelvinChange = viewModel::setWhiteBalanceKelvin)
            ManualFocusControl(
                cameraState,
                capabilities,
                onManualFocusToggled = { enabled -> viewModel.setManualFocus(enabled) },
                onFocusDistanceChange = { distance -> viewModel.setManualFocus(true, distance) },
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                RecordButton(isRecording = isRecording, onClick = viewModel::toggleRecording)
            }
        }
    }

    if (showSettings) {
        CineCamSettingsSheet(
            state = cameraState,
            onDismiss = { showSettings = false },
            onCodecChange = viewModel::setVideoCodec,
            onBitrateChange = viewModel::setBitrateBps,
            onFrameRateChange = viewModel::setFrameRate,
            onLutSelected = viewModel::selectLut,
            onLutStrengthChange = viewModel::setLutStrength,
            onAudioGainChange = viewModel::setAudioGainDb,
        )
    }
}
