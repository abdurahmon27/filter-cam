package com.haywan.filtercam.beautyfilter.render

import android.opengl.GLES20
import com.haywan.filtercam.beautyfilter.gl.Framebuffer
import com.haywan.filtercam.beautyfilter.gl.GlUtils
import com.haywan.filtercam.beautyfilter.gl.ScreenQuad
import com.haywan.filtercam.beautyfilter.gl.Shaders

/**
 * Draws the (already display-oriented) camera frame texture into the scene FBO,
 * applying the cover-crop so it fills the view. The frame comes from the
 * single-stream pipeline: the same bitmap the landmarks were detected on, so no
 * rotation is needed here — only the crop and the GL y-flip (in the shader).
 */
internal class CameraPass {
    private val program = GlUtils.buildProgram(Shaders.CAMERA_VS, Shaders.CAMERA_FS)

    fun draw(frameTexture: Int, viewport: Viewport, scene: Framebuffer, quad: ScreenQuad) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, scene.framebuffer)
        GLES20.glViewport(0, 0, scene.width, scene.height)
        GLES20.glUseProgram(program)
        quad.bind(program)
        GLES20.glUniform4f(
            GLES20.glGetUniformLocation(program, "uCrop"),
            viewport.cropOffX, viewport.cropOffY, viewport.cropScaleX, viewport.cropScaleY
        )
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frameTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTex"), 0)
        quad.draw()
    }
}
