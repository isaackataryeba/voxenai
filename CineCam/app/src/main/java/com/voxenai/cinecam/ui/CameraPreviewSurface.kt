package com.voxenai.cinecam.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import android.view.Surface

/**
 * Hosts the raw output [Surface] the GL render thread draws the processed preview into. Plain
 * [SurfaceView] rather than TextureView: we manage our own EGL context/surface directly (see
 * [com.voxenai.cinecam.gl.CameraGLRenderThread]), so we just need the platform Surface + its
 * size, not a SurfaceTexture to wrap ourselves.
 */
@Composable
fun CameraPreviewSurface(
    onSurfaceAvailable: (Surface, Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) = Unit

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                        onSurfaceAvailable(holder.surface, width, height)
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
                })
            }
        },
    )
}
