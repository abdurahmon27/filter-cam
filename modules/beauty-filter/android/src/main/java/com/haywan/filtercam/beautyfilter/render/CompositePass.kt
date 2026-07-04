package com.haywan.filtercam.beautyfilter.render

import android.opengl.GLES20
import com.haywan.filtercam.beautyfilter.gl.Framebuffer
import com.haywan.filtercam.beautyfilter.gl.GlUtils
import com.haywan.filtercam.beautyfilter.gl.ScreenQuad
import com.haywan.filtercam.beautyfilter.gl.Shaders

/**
 * Beauty composite into [target]. Takes two masks: [skinMask] gates the
 * smoothing (so eyes/brows/lips/beard stay sharp) and [faceMask] gates the
 * tone/light so the whole face — features included — brightens uniformly.
 * See [Shaders.COMPOSITE_FS].
 */
internal class CompositePass {
    private val program = GlUtils.buildProgram(Shaders.FULLSCREEN_VS, Shaders.COMPOSITE_FS)

    fun draw(
        scene: Framebuffer,
        blurred: Framebuffer,
        skinMask: Framebuffer,
        faceMask: Framebuffer,
        target: Framebuffer,
        adjust: BeautyAdjustments,
        viewport: Viewport,
        quad: ScreenQuad,
    ) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, target.framebuffer)
        GLES20.glViewport(0, 0, target.width, target.height)
        GLES20.glUseProgram(program)
        quad.bind(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, scene.texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uScene"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, blurred.texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uBlur"), 1)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, skinMask.texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uSkinMask"), 2)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, faceMask.texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uFaceMask"), 3)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uSmooth"), adjust.smooth)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uGlow"), adjust.glow)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uClarity"), adjust.clarity)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uWarmth"), adjust.warmth)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uSharp"), adjust.sharp)
        quad.draw()
    }
}
