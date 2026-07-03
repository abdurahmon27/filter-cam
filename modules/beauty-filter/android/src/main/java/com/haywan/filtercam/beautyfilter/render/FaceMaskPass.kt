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
 * Builds the two beauty masks for every tracked face.
 *
 * [skinMask] is the face oval with the eyes, brows and lips punched back to
 * black, so it gates ONLY the skin-smoothing/blur (features stay sharp).
 * [faceMask] is the full face oval with nothing punched out, so it gates the
 * tone/light adjustments (glow, clarity, warmth): the whole face — eyes, lips
 * and beard included — brightens uniformly instead of leaving those regions
 * looking dim/"shaded" against lifted skin.
 *
 * Both masks are feathered with a two-pass edge blur that bounces through
 * [scratch]. The skin feather fully resolves back into [skinMask] before the
 * face feather starts, so the shared scratch buffer is never clobbered mid-pass.
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
        skinMask: Framebuffer,
        faceMask: Framebuffer,
        scratch: Framebuffer,
        blur: BlurPass,
        quad: ScreenQuad,
    ) {
        if (faces.isEmpty()) {
            // No face: both masks must be fully black so the composite is a no-op.
            clear(skinMask)
            clear(faceMask)
            return
        }

        // --- skin mask: oval MINUS eyes/brows/lips (gates smoothing only) ---
        clear(skinMask) // leaves skinMask bound for the fans below
        for (face in faces) {
            // Draw the oval INSET from the silhouette (see OVAL_INSET), so the
            // feather below falls off INSIDE the face edge and reaches ~0 by the
            // true silhouette. The effect fades gradually at the jaw (no hard line)
            // yet never spills onto the neck/background (no "face melting into the
            // background" halo).
            drawFan(face, FaceTopology.FACE_OVAL, 1f, OVAL_INSET, viewport)
            drawFan(face, FaceTopology.LEFT_EYE, 0f, 1.5f, viewport)
            drawFan(face, FaceTopology.RIGHT_EYE, 0f, 1.5f, viewport)
            drawFan(face, FaceTopology.LEFT_BROW, 0f, 1.3f, viewport)
            drawFan(face, FaceTopology.RIGHT_BROW, 0f, 1.3f, viewport)
            drawFan(face, FaceTopology.LIPS_OUTER, 0f, 1.1f, viewport)
        }
        // Feather so the smoothing fades gradually — soft edge, contained by the
        // inset so it lands on the face, not the background.
        blur.run(skinMask.texture, scratch, FEATHER / skinMask.width, 0f, quad)
        blur.run(scratch.texture, skinMask, 0f, FEATHER / skinMask.height, quad)

        // --- face mask: full oval only (gates tone/light over the whole face) ---
        clear(faceMask) // leaves faceMask bound for the fan below
        for (face in faces) {
            drawFan(face, FaceTopology.FACE_OVAL, 1f, OVAL_INSET, viewport)
        }
        // Same inset + feather so tone/light also fades out inside the face edge
        // and never halos the background.
        blur.run(faceMask.texture, scratch, FEATHER / faceMask.width, 0f, quad)
        blur.run(scratch.texture, faceMask, 0f, FEATHER / faceMask.height, quad)
    }

    /** Binds [fbo], clears it to black, and leaves it bound for subsequent fans. */
    private fun clear(fbo: Framebuffer) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo.framebuffer)
        GLES20.glViewport(0, 0, fbo.width, fbo.height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
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

    companion object {
        // Cover the FULL face out to the silhouette landmarks so the whole face is
        // smoothed (no visible unfiltered rim / patch). The feather below then
        // fades the effect off right at the silhouette.
        private const val OVAL_INSET = 1.0f
        // Feather radius (mask is quarter-res). Soft enough for a seamless edge,
        // tight enough not to bleed the effect far onto the background.
        private const val FEATHER = 4f
    }
}
