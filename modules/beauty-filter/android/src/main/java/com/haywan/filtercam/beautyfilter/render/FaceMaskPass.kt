package com.haywan.filtercam.beautyfilter.render

import android.opengl.GLES20
import com.haywan.filtercam.beautyfilter.gl.Framebuffer
import com.haywan.filtercam.beautyfilter.gl.GlUtils
import com.haywan.filtercam.beautyfilter.gl.ScreenQuad
import com.haywan.filtercam.beautyfilter.gl.Shaders
import com.haywan.filtercam.beautyfilter.tracking.FaceTopology
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builds the skin-smoothing mask for every tracked face: the face oval is
 * filled white, then the eyes, brows and lips are punched back to black so the
 * blur only softens skin. The mask is finally blurred for a soft feathered edge.
 *
 * Renders into [maskA]; the two-pass edge blur bounces through [maskB] and ends
 * back in [maskA], which the composite reads.
 */
internal class FaceMaskPass {
    private val program = GlUtils.buildProgram(Shaders.SOLID_VS, Shaders.SOLID_FS)

    // Scratch buffer for one polygon fan (largest ring + centroid + closing vertex).
    private val fanBuffer = ByteBuffer
        .allocateDirect((FaceTopology.FACE_OVAL.size + 2) * 2 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()

    fun draw(
        faces: Array<FloatArray>,
        viewport: Viewport,
        maskA: Framebuffer,
        maskB: Framebuffer,
        blur: BlurPass,
        quad: ScreenQuad,
    ) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, maskA.framebuffer)
        GLES20.glViewport(0, 0, maskA.width, maskA.height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (faces.isEmpty()) return

        for (face in faces) {
            drawFan(face, FaceTopology.FACE_OVAL, 1f, 1f, viewport)
            drawFan(face, FaceTopology.LEFT_EYE, 0f, 1.6f, viewport)
            drawFan(face, FaceTopology.RIGHT_EYE, 0f, 1.6f, viewport)
            drawFan(face, FaceTopology.LEFT_BROW, 0f, 1.4f, viewport)
            drawFan(face, FaceTopology.RIGHT_BROW, 0f, 1.4f, viewport)
            drawFan(face, FaceTopology.LIPS_OUTER, 0f, 1.1f, viewport)
        }

        // Soften the mask edges (two blur passes, ending back in maskA).
        blur.run(maskA.texture, maskB, 2f / maskA.width, 0f, quad)
        blur.run(maskB.texture, maskA, 0f, 2f / maskA.height, quad)
    }

    /** Draws a landmark ring as a triangle fan, optionally inflated around its centroid. */
    private fun drawFan(lms: FloatArray, ring: IntArray, value: Float, inflate: Float, viewport: Viewport) {
        var cx = 0f
        var cy = 0f
        for (idx in ring) {
            cx += lms[idx * 2]
            cy += lms[idx * 2 + 1]
        }
        cx /= ring.size
        cy /= ring.size

        fanBuffer.clear()
        fanBuffer.put(viewport.ndcX(cx)).put(viewport.ndcY(cy))
        for (i in 0..ring.size) {
            val idx = ring[i % ring.size]
            val x = cx + (lms[idx * 2] - cx) * inflate
            val y = cy + (lms[idx * 2 + 1] - cy) * inflate
            fanBuffer.put(viewport.ndcX(x)).put(viewport.ndcY(y))
        }
        fanBuffer.position(0)

        GLES20.glUseProgram(program)
        val posLoc = GLES20.glGetAttribLocation(program, "aPos")
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, fanBuffer)
        GLES20.glUniform4f(
            GLES20.glGetUniformLocation(program, "uColor"), value, value, value, 1f
        )
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, ring.size + 2)
    }
}
