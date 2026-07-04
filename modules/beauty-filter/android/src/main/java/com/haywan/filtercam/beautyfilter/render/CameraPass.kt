package com.haywan.filtercam.beautyfilter.render

import android.opengl.GLES20
import com.haywan.filtercam.beautyfilter.gl.Framebuffer
import com.haywan.filtercam.beautyfilter.gl.GlUtils
import com.haywan.filtercam.beautyfilter.gl.ScreenQuad
import com.haywan.filtercam.beautyfilter.gl.Shaders

/**
 * Draws the raw camera frame texture into the scene FBO in upright display
 * orientation. The frame is uploaded un-rotated/un-mirrored (a full-res CPU
 * rotate per frame was the pipeline's bottleneck); the vertex shader maps
 * upright display coords into buffer coords via an affine UV transform, so the
 * rotation, mirror and cover-crop all happen in the one sampling pass.
 */
internal class CameraPass {
    private val program = GlUtils.buildProgram(Shaders.CAMERA_VS, Shaders.CAMERA_FS)

    // upright(top-down) -> buffer UV: u' = uvX·(u,v,1), v' = uvY·(u,v,1)
    private val uvX = FloatArray(3)
    private val uvY = FloatArray(3)

    fun draw(
        frameTexture: Int,
        rotationDegrees: Int,
        mirror: Boolean,
        viewport: Viewport,
        scene: Framebuffer,
        quad: ScreenQuad,
    ) {
        computeUvTransform(rotationDegrees, mirror)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, scene.framebuffer)
        GLES20.glViewport(0, 0, scene.width, scene.height)
        GLES20.glUseProgram(program)
        quad.bind(program)
        GLES20.glUniform4f(
            GLES20.glGetUniformLocation(program, "uCrop"),
            viewport.cropOffX, viewport.cropOffY, viewport.cropScaleX, viewport.cropScaleY
        )
        GLES20.glUniform3f(GLES20.glGetUniformLocation(program, "uUvX"), uvX[0], uvX[1], uvX[2])
        GLES20.glUniform3f(GLES20.glGetUniformLocation(program, "uUvY"), uvY[0], uvY[1], uvY[2])
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frameTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTex"), 0)
        quad.draw()
    }

    /**
     * The upright frame is defined as the buffer rotated clockwise by
     * [rotationDegrees] then (for the front camera) mirrored horizontally —
     * the same transform the CPU path used to bake into the bitmap. This is
     * its inverse: where in the buffer an upright (top-down) UV samples from.
     */
    private fun computeUvTransform(rotationDegrees: Int, mirror: Boolean) {
        when ((rotationDegrees % 360 + 360) % 360) {
            90 -> {   // upright(u,v) came from buffer(v, 1-u)
                uvX[0] = 0f; uvX[1] = 1f; uvX[2] = 0f
                uvY[0] = -1f; uvY[1] = 0f; uvY[2] = 1f
            }
            180 -> {  // buffer(1-u, 1-v)
                uvX[0] = -1f; uvX[1] = 0f; uvX[2] = 1f
                uvY[0] = 0f; uvY[1] = -1f; uvY[2] = 1f
            }
            270 -> {  // buffer(1-v, u)
                uvX[0] = 0f; uvX[1] = -1f; uvX[2] = 1f
                uvY[0] = 1f; uvY[1] = 0f; uvY[2] = 0f
            }
            else -> { // buffer(u, v)
                uvX[0] = 1f; uvX[1] = 0f; uvX[2] = 0f
                uvY[0] = 0f; uvY[1] = 1f; uvY[2] = 0f
            }
        }
        if (mirror) {
            // Mirror was applied after the rotation (in upright space), so its
            // inverse comes first: substitute u -> 1-u in both rows.
            uvX[2] += uvX[0]; uvX[0] = -uvX[0]
            uvY[2] += uvY[0]; uvY[0] = -uvY[0]
        }
    }
}
