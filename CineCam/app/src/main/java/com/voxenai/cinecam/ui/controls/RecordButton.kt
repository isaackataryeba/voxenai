package com.voxenai.cinecam.ui.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.voxenai.cinecam.ui.theme.CineCamRecordRed
import java.util.Locale

@Composable
fun RecordButton(isRecording: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(72.dp)) {
        Box(
            modifier = Modifier
                .size(if (isRecording) 32.dp else 56.dp)
                .background(CineCamRecordRed, CircleShape),
        )
    }
}

@Composable
fun RecordingTimer(elapsedMs: Long, modifier: Modifier = Modifier) {
    val totalSeconds = elapsedMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    Text(
        String.format(Locale.US, "REC %02d:%02d", minutes, seconds),
        color = CineCamRecordRed,
        modifier = modifier,
    )
}
