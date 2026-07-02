package com.haywan.filtercam.beautyfilter.render

import android.opengl.GLES20
import com.haywan.filtercam.beautyfilter.gl.Framebuffer
import com.haywan.filtercam.beautyfilter.gl.GlUtils
import com.haywan.filtercam.beautyfilter.gl.ScreenQuad
import com.haywan.filtercam.beautyfilter.gl.Shaders

/**
 * Final beauty composite to the screen: `mix(scene, smoothed, mask * strength)`,
 * where `smoothed` is the blurred scene lifted for a youthful glow. Writes to
 * the default framebuffer (0).
 */
internal class CompositePass {
    private val program = GlUtils.buildProgram(Shaders.FULLSCREEN_VS, Shaders.COMPOSITE_FS)

    fun draw(
        scene: Framebuffer,
        blurred: Framebuffer,
        mask: Framebuffer,
        strength: Float,
        glow: Float,
        viewport: Viewport,
        quad: ScreenQuad,
    ) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, viewport.width, viewport.height)
        GLES20.glUseProgram(program)
        quad.bind(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, scene.texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uScene"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, blurred.texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uBlur"), 1)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mask.texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uMask"), 2)
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(program, "uStrength"), strength.coerceIn(0f, 1f)
        )
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(program, "uGlow"), glow.coerceIn(0f, 1f)
        )
        quad.draw()
    }
}
