package com.haywan.filtercam.beautyfilter.render

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import com.haywan.filtercam.beautyfilter.gl.Framebuffer
import com.haywan.filtercam.beautyfilter.gl.GlUtils
import com.haywan.filtercam.beautyfilter.gl.ScreenQuad
import com.haywan.filtercam.beautyfilter.gl.Shaders

/**
 * Draws the external camera (OES) texture into the scene FBO, applying the
 * rotation that makes it upright plus the cover-crop window, so the scene FBO
 * holds exactly what is displayed.
 */
internal class CameraPass {
    private val program = GlUtils.buildProgram(Shaders.CAMERA_VS, Shaders.CAMERA_FS)
    private val rotMatrix = FloatArray(16)
    private val combinedMatrix = FloatArray(16)

    fun draw(
        oesTexture: Int,
        surfaceTexMatrix: FloatArray,
        cameraRotationDegrees: Int,
        viewport: Viewport,
        scene: Framebuffer,
        quad: ScreenQuad,
    ) {
        // Rotate sampling coordinates around the UV center so the buffer shows
        // upright, then apply the SurfaceTexture matrix.
        Matrix.setIdentityM(rotMatrix, 0)
        Matrix.translateM(rotMatrix, 0, 0.5f, 0.5f, 0f)
        Matrix.rotateM(
            rotMatrix, 0,
            -(cameraRotationDegrees + PREVIEW_ROTATION_OFFSET).toFloat(), 0f, 0f, 1f
        )
        Matrix.translateM(rotMatrix, 0, -0.5f, -0.5f, 0f)
        Matrix.multiplyMM(combinedMatrix, 0, surfaceTexMatrix, 0, rotMatrix, 0)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, scene.framebuffer)
        GLES20.glViewport(0, 0, scene.width, scene.height)
        GLES20.glUseProgram(program)
        quad.bind(program)
        GLES20.glUniformMatrix4fv(
            GLES20.glGetUniformLocation(program, "uTexMatrix"), 1, false, combinedMatrix, 0
        )
        GLES20.glUniform4f(
            GLES20.glGetUniformLocation(program, "uCrop"),
            viewport.cropOffX, viewport.cropOffY, viewport.cropScaleX, viewport.cropScaleY
        )
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTex"), 0)
        quad.draw()
    }

    companion object {
        // Extra rotation applied to the camera preview texture so it matches the
        // upright landmark space. Determined empirically for the front sensor +
        // SurfaceTexture transform on this pipeline.
        private const val PREVIEW_ROTATION_OFFSET = 90
    }
}
