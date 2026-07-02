package com.haywan.filtercam.beautyfilter.render

import android.opengl.GLES20
import com.haywan.filtercam.beautyfilter.gl.Framebuffer
import com.haywan.filtercam.beautyfilter.gl.GlUtils
import com.haywan.filtercam.beautyfilter.gl.ScreenQuad
import com.haywan.filtercam.beautyfilter.gl.Shaders

/**
 * Separable gaussian blur. [blurBoth] runs a horizontal then vertical pass
 * (source -> a -> b); [run] is a single directional pass, reused by the face
 * mask to soften its edges.
 */
internal class BlurPass {
    private val program = GlUtils.buildProgram(Shaders.FULLSCREEN_VS, Shaders.BLUR_FS)

    /** Two-pass blur of [sourceTexture] into [a] (h) then [b] (v). Result is in [b]. */
    fun blurBoth(sourceTexture: Int, a: Framebuffer, b: Framebuffer, quad: ScreenQuad) {
        // Wider radius = smoother skin (the composite keeps a little sharpness back).
        run(sourceTexture, a, 2.3f / a.width, 0f, quad)
        run(a.texture, b, 0f, 2.3f / a.height, quad)
    }

    fun run(sourceTexture: Int, target: Framebuffer, dx: Float, dy: Float, quad: ScreenQuad) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, target.framebuffer)
        GLES20.glViewport(0, 0, target.width, target.height)
        GLES20.glUseProgram(program)
        quad.bind(program)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uDir"), dx, dy)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sourceTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTex"), 0)
        quad.draw()
    }
}
