package com.haywan.filtercam.beautyfilter.render

import android.opengl.GLES20
import com.haywan.filtercam.beautyfilter.gl.Framebuffer
import com.haywan.filtercam.beautyfilter.gl.GlUtils
import com.haywan.filtercam.beautyfilter.gl.ScreenQuad
import com.haywan.filtercam.beautyfilter.gl.Shaders
import com.haywan.filtercam.beautyfilter.tracking.FaceTopology

/**
 * "Big Eyes" filter: blits the composited image to the screen, magnifying a
 * localized region around each eye. At strength 0 (or with no faces) it is a
 * plain full-screen blit, so it is always safe to run in the pipeline.
 */
internal class EyeWarpPass {
    private val program = GlUtils.buildProgram(Shaders.FULLSCREEN_VS, Shaders.EYE_WARP_FS)
    private val eyeXY = FloatArray(MAX_EYES * 2)
    private val eyeR = FloatArray(MAX_EYES)

    fun draw(
        source: Framebuffer,
        target: Framebuffer,
        faces: Array<FloatArray>,
        strength: Float,
        viewport: Viewport,
        quad: ScreenQuad,
    ) {
        val aspect = viewport.width.toFloat() / viewport.height
        var count = 0
        if (strength > 0f) {
            for (face in faces) {
                if (count >= MAX_EYES) break
                count = addEye(face, FaceTopology.LEFT_EYE, viewport, aspect, count)
                if (count >= MAX_EYES) break
                count = addEye(face, FaceTopology.RIGHT_EYE, viewport, aspect, count)
            }
        }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, target.framebuffer)
        GLES20.glViewport(0, 0, target.width, target.height)
        GLES20.glUseProgram(program)
        quad.bind(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, source.texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTex"), 0)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uEyeCount"), count)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uStrength"), strength.coerceIn(0f, 1f))
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uAspect"), aspect)
        if (count > 0) {
            GLES20.glUniform2fv(GLES20.glGetUniformLocation(program, "uEyes"), count, eyeXY, 0)
            GLES20.glUniform1fv(GLES20.glGetUniformLocation(program, "uEyeR"), count, eyeR, 0)
        }
        quad.draw()
    }

    /** Adds one eye's center/radius (in the source texture's UV space) to the arrays. */
    private fun addEye(
        lms: FloatArray,
        ring: IntArray,
        viewport: Viewport,
        aspect: Float,
        count: Int,
    ): Int {
        var cx = 0f
        var cy = 0f
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        for (idx in ring) {
            val px = viewport.pixelX(lms[idx * 2])
            val py = viewport.pixelY(lms[idx * 2 + 1])
            cx += px
            cy += py
            if (px < minX) minX = px
            if (px > maxX) maxX = px
        }
        cx /= ring.size
        cy /= ring.size

        // Texture UV: x same direction, y flipped (FBO texture is y-up).
        eyeXY[count * 2] = cx / viewport.width
        eyeXY[count * 2 + 1] = 1f - cy / viewport.height
        // Aspect-corrected radius, a bit larger than the eye for a soft falloff.
        val halfSpan = (maxX - minX) / 2f
        eyeR[count] = (halfSpan / viewport.width) * aspect * 1.7f
        return count + 1
    }

    companion object {
        // Matches the uEyes[10] / uEyeR[10] arrays in the shader (5 faces × 2 eyes).
        private const val MAX_EYES = 10
    }
}
