package com.haywan.filtercam.beautyfilter.render

import android.opengl.GLES20
import com.haywan.filtercam.beautyfilter.gl.GlUtils
import com.haywan.filtercam.beautyfilter.gl.Shaders
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Debug overlay: draws every landmark of every tracked face as a round dot,
 * in the same space as the mustache/beauty mask (so it reveals how the tracked
 * landmarks line up with the preview).
 */
internal class FaceMeshPass {
    private val program = GlUtils.buildProgram(Shaders.POINT_VS, Shaders.POINT_FS)

    // Reused per face (478 landmarks * 2 coords).
    private val meshBuffer = ByteBuffer.allocateDirect(478 * 2 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()

    fun draw(faces: Array<FloatArray>, viewport: Viewport) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, viewport.width, viewport.height)
        GLES20.glUseProgram(program)
        val posLoc = GLES20.glGetAttribLocation(program, "aPos")
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(program, "uPointSize"),
            (viewport.width * 0.012f).coerceAtLeast(4f)
        )
        GLES20.glUniform4f(
            GLES20.glGetUniformLocation(program, "uColor"), 0.15f, 1f, 0.55f, 1f
        )
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        for (face in faces) {
            val count = face.size / 2
            meshBuffer.clear()
            for (i in 0 until count) {
                meshBuffer.put(viewport.ndcX(face[i * 2])).put(viewport.ndcY(face[i * 2 + 1]))
            }
            meshBuffer.position(0)
            GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, meshBuffer)
            GLES20.glDrawArrays(GLES20.GL_POINTS, 0, count)
        }

        GLES20.glDisable(GLES20.GL_BLEND)
    }
}
