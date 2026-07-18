package com.voxenai.cinecam.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val CineCamColorScheme = darkColorScheme(
    primary = CineCamAccent,
    secondary = CineCamRecordRed,
    background = CineCamBlack,
    surface = CineCamSurface,
    onBackground = CineCamOnSurface,
    onSurface = CineCamOnSurface,
)

@Composable
fun CineCamTheme(content: @Composable () -> Unit) {
    // Always dark: a cinema camera's UI shouldn't blow out night-shoot visibility with a light theme.
    MaterialTheme(colorScheme = CineCamColorScheme, content = content)
}
