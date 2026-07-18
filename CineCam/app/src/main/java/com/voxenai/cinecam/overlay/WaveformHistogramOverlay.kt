package com.voxenai.cinecam.overlay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.sqrt

/** Draws a broadcast-style RGB parade histogram and a luma waveform, fed by [MonitorData]. */
@Composable
fun WaveformHistogramOverlay(
    data: MonitorData?,
    showWaveform: Boolean,
    showHistogram: Boolean,
    modifier: Modifier = Modifier,
) {
    if (data == null || (!showWaveform && !showHistogram)) return

    Column(modifier = modifier) {
        if (showWaveform) {
            Canvas(
                modifier = Modifier
                    .width(220.dp)
                    .height(120.dp)
                    .padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.55f))
            ) {
                val maxCount = data.waveform.maxOf { column -> column.maxOrNull() ?: 0 }.coerceAtLeast(1)
                val colWidth = size.width / data.waveformColumns
                val rowHeight = size.height / data.waveformLumaBuckets

                for (x in 0 until data.waveformColumns) {
                    val column = data.waveform[x]
                    for (bucket in 0 until data.waveformLumaBuckets) {
                        val count = column[bucket]
                        if (count == 0) continue
                        val alpha = sqrt(count.toFloat() / maxCount).coerceIn(0.05f, 1f)
                        val py = size.height - (bucket + 1) * rowHeight
                        drawRect(
                            color = Color(0xFF7CFF7C).copy(alpha = alpha),
                            topLeft = Offset(x * colWidth, py),
                            size = androidx.compose.ui.geometry.Size(colWidth.coerceAtLeast(1f), rowHeight.coerceAtLeast(1f)),
                        )
                    }
                }
            }
        }

        if (showHistogram) {
            Canvas(
                modifier = Modifier
                    .width(220.dp)
                    .height(90.dp)
                    .padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.55f))
            ) {
                drawHistogramChannel(data.histogramR, Color(0xFFFF5A5A))
                drawHistogramChannel(data.histogramG, Color(0xFF5AFF7A))
                drawHistogramChannel(data.histogramB, Color(0xFF5A9CFF))
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHistogramChannel(bucket: IntArray, color: Color) {
    val maxCount = bucket.maxOrNull()?.coerceAtLeast(1) ?: 1
    val colWidth = size.width / bucket.size
    val path = androidx.compose.ui.graphics.Path()
    for (i in bucket.indices) {
        val normalized = sqrt(bucket[i].toFloat() / maxCount)
        val x = i * colWidth
        val y = size.height - normalized * size.height
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, color = color.copy(alpha = 0.85f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f))
}
