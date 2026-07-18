package com.voxenai.cinecam.gl

import android.graphics.SurfaceTexture
import android.opengl.EGLSurface
import android.view.Surface

/**
 * An EGL surface a render pass can draw into: a platform [Surface] (encoder input, preview
 * window), a [SurfaceTexture] (rare, mostly for interop), or an offscreen pbuffer used for
 * throwaway/analysis rendering that never touches a real window.
 */
class WindowSurface private constructor(
    private val eglCore: EglCore,
    initialSurface: EGLSurface,
    private val ownedSurface: Surface? = null,
) {
    private var eglSurface: EGLSurface? = initialSurface

    fun makeCurrent() = eglSurface?.let { eglCore.makeCurrent(it) }

    fun swapBuffers(): Boolean = eglSurface?.let { eglCore.swapBuffers(it) } ?: false

    fun setPresentationTime(nsecs: Long) {
        eglSurface?.let { eglCore.setPresentationTime(it, nsecs) }
    }

    fun release() {
        eglSurface?.let { eglCore.releaseSurface(it) }
        eglSurface = null
        ownedSurface?.release()
    }

    companion object {
        /** Wraps a platform [Surface] (e.g. MediaCodec's input surface, or a TextureView's). */
        fun forSurface(eglCore: EglCore, surface: Surface, takeOwnership: Boolean): WindowSurface =
            WindowSurface(eglCore, eglCore.createWindowSurface(surface), if (takeOwnership) surface else null)

        /** Wraps an existing [SurfaceTexture] as a drawable EGL window surface. */
        fun forSurfaceTexture(eglCore: EglCore, surfaceTexture: SurfaceTexture): WindowSurface =
            WindowSurface(eglCore, eglCore.createWindowSurface(surfaceTexture))

        /** A throwaway pbuffer surface: used to hold a GL context current with no real window. */
        fun offscreen(eglCore: EglCore, width: Int, height: Int): WindowSurface =
            WindowSurface(eglCore, eglCore.createOffscreenSurface(width, height))
    }
}
