package com.haywan.filtercam.beautyfilter.render

import android.opengl.GLES20
import com.haywan.filtercam.beautyfilter.gl.GlUtils
import com.haywan.filtercam.beautyfilter.gl.ScreenQuad
import com.haywan.filtercam.beautyfilter.gl.Shaders

/**
 * Presents a 2D texture onto the currently-bound framebuffer with a fullscreen
 * quad, applying a light unsharp-mask sharpen so the upscaled preview reads crisp
 * (hair, brows, features, background) instead of soft. Used for both the screen
 * and the stream surface. The caller binds/sizes the target beforehand and passes
 * the SOURCE texture's pixel size so the sharpen samples one texel out.
 */
internal class BlitPass {
    private val program = GlUtils.buildProgram(Shaders.FULLSCREEN_VS, Shaders.SHARPEN_FS)

    fun draw(texture: Int, srcWidth: Int, srcHeight: Int, quad: ScreenQuad) {
        GLES20.glUseProgram(program)
        quad.bind(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTex"), 0)
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(program, "uTexel"),
            1f / srcWidth.coerceAtLeast(1), 1f / srcHeight.coerceAtLeast(1)
        )
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uAmount"), SHARPEN)
        quad.draw()
    }

    companion object {
        // Unsharp-mask strength. Enough to restore acutance on the upscaled frame
        // without ringing or amplifying skin noise.
        private const val SHARPEN = 0.85f
    }
}
