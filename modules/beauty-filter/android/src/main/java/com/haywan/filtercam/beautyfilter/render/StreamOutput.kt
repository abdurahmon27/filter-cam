package com.haywan.filtercam.beautyfilter.render

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.view.Surface
import com.haywan.filtercam.beautyfilter.gl.ScreenQuad

/**
 * Presents the final filtered texture into an external [Surface] (e.g. a WebRTC
 * `SurfaceTextureHelper` surface or a media-encoder input surface) using an EGL
 * window surface that **shares the renderer's GL context**. Frames stay on the
 * GPU end-to-end — no `glReadPixels` — which is exactly what a real-time stream
 * needs.
 *
 * This is the output seam a LiveKit custom video capturer plugs into: create a
 * capturer backed by a `SurfaceTextureHelper`, hand its `Surface` to
 * `BeautyCameraView.setStreamSurface(...)`, and every rendered frame is pushed
 * to that surface. See docs/STREAMING.md.
 *
 * Must be constructed and used on the GL thread (it captures the current
 * EGLDisplay/EGLContext at construction).
 */
internal class StreamOutput(surface: Surface) {
    private val display: EGLDisplay = EGL14.eglGetCurrentDisplay()
    private val context: EGLContext = EGL14.eglGetCurrentContext()
    private val eglSurface: EGLSurface

    init {
        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfig = IntArray(1)
        EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfig, 0)
        require(numConfig[0] > 0 && configs[0] != null) { "No EGL config for stream surface" }
        eglSurface = EGL14.eglCreateWindowSurface(
            display, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0
        )
    }

    /** Draws [texture] into the stream surface, then swaps. Saves/restores the
     *  caller's current EGL surface so the on-screen render is unaffected. */
    fun present(
        texture: Int,
        width: Int,
        height: Int,
        presentationTimeNs: Long,
        blit: BlitPass,
        quad: ScreenQuad,
        sharp: Float,
    ) {
        val prevDraw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
        val prevRead = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
        val prevContext = EGL14.eglGetCurrentContext()

        EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)
        GLES20.glViewport(0, 0, width, height)
        blit.draw(texture, width, height, quad, sharp)
        EGLExt.eglPresentationTimeANDROID(display, eglSurface, presentationTimeNs)
        EGL14.eglSwapBuffers(display, eglSurface)

        EGL14.eglMakeCurrent(display, prevDraw, prevRead, prevContext)
    }

    fun release() {
        EGL14.eglDestroySurface(display, eglSurface)
    }
}
