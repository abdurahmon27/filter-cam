package com.haywan.filtercam.beautyfilter.render

import android.opengl.GLES20
import com.haywan.filtercam.beautyfilter.gl.GlUtils
import com.haywan.filtercam.beautyfilter.gl.ScreenQuad
import com.haywan.filtercam.beautyfilter.gl.Shaders

/**
 * Copies a 2D texture onto the currently-bound framebuffer with a fullscreen
 * quad. Used to present the final image to the screen and to the stream surface.
 * The caller binds/sizes the target framebuffer (or EGL surface) beforehand.
 */
internal class BlitPass {
    private val program = GlUtils.buildProgram(Shaders.FULLSCREEN_VS, Shaders.SPRITE_FS)

    fun draw(texture: Int, quad: ScreenQuad) {
        GLES20.glUseProgram(program)
        quad.bind(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTex"), 0)
        quad.draw()
    }
}
