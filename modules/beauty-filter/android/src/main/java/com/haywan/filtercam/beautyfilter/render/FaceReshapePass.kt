package com.haywan.filtercam.beautyfilter.render

import android.opengl.GLES20
import com.haywan.filtercam.beautyfilter.gl.Framebuffer
import com.haywan.filtercam.beautyfilter.gl.GlUtils
import com.haywan.filtercam.beautyfilter.gl.ScreenQuad
import com.haywan.filtercam.beautyfilter.gl.Shaders
import com.haywan.filtercam.beautyfilter.tracking.FaceTopology
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sign

/**
 * Face-reshape (liquify) pass: enlarges eyes, narrows the nose, and slims the
 * face by warping the sampled UVs. Blits the composited image to [target];
 * with all reshape strengths at 0 it is a plain full-screen blit, so it is
 * always safe to keep in the pipeline. All region sizes are proportional to the
 * inter-eye distance, so they scale with the face automatically.
 *
 * Face slim is a chain of pinch points pinned to the jawline landmarks (the
 * Snapchat/TikTok approach): each pinch pulls the silhouette toward the face
 * midline inside a small local radius, so only the band along the jaw/cheek
 * contour — from below the eyes down to the chin — moves. The old radial
 * lower-face minify warped everything inside one big circle, background
 * included, which read as the whole video bending around the face.
 */
internal class FaceReshapePass {
    private val program = GlUtils.buildProgram(Shaders.FULLSCREEN_VS, Shaders.FACE_RESHAPE_FS)

    private val noseC = FloatArray(MAX_FACES * 2)
    private val noseR = FloatArray(MAX_FACES)
    private val eyeXY = FloatArray(MAX_EYES * 2)
    private val eyeR = FloatArray(MAX_EYES)
    private val jawP = FloatArray(MAX_JAW * 2)
    private val jawD = FloatArray(MAX_JAW * 2)
    private val jawR = FloatArray(MAX_JAW)

    fun draw(
        source: Framebuffer,
        target: Framebuffer,
        faces: Array<FloatArray>,
        adjust: BeautyAdjustments,
        viewport: Viewport,
        quad: ScreenQuad,
    ) {
        val aspect = viewport.width.toFloat() / viewport.height
        var faceCount = 0
        var eyeCount = 0
        var jawCount = 0

        if (adjust.reshapes) {
            for (face in faces) {
                if (faceCount >= MAX_FACES) break
                val le = ringCenter(face, FaceTopology.LEFT_EYE, viewport, aspect)
                val re = ringCenter(face, FaceTopology.RIGHT_EYE, viewport, aspect)
                val scale = hypot((le[0] - re[0]) * aspect, le[1] - re[1])
                val midX = (le[0] + re[0]) / 2f

                noseC[faceCount * 2] = ux(face, FaceTopology.NOSE_TIP, viewport)
                noseC[faceCount * 2 + 1] = uy(face, FaceTopology.NOSE_TIP, viewport)
                noseR[faceCount] = scale * 0.42f
                faceCount++

                if (adjust.eyeEnlarge > 0f) {
                    if (eyeCount < MAX_EYES) { putEye(eyeCount, le); eyeCount++ }
                    if (eyeCount < MAX_EYES) { putEye(eyeCount, re); eyeCount++ }
                }

                if (adjust.faceSlim > 0f && scale > 1e-4f) {
                    val eyeY = (le[1] + re[1]) / 2f
                    val chinY = uy(face, FaceTopology.CHIN, viewport)
                    val span = abs(eyeY - chinY) // eye-to-chin height (uv, y-up)
                    if (span > 1e-4f) {
                        for (idx in JAW_RING) {
                            if (jawCount >= MAX_JAW) break
                            val px = ux(face, idx, viewport)
                            val py = uy(face, idx, viewport)
                            // Fade in below the eye line (nothing above the eyes
                            // moves) and fade out at the midline (the chin is
                            // never pulled sideways).
                            val t = (eyeY - py) / span
                            val wy = smoothstep(0.10f, 0.40f, t)
                            val wx = smoothstep(0.10f, 0.30f, abs(px - midX) * aspect / scale)
                            val w = wy * wx
                            if (w < 0.02f) continue
                            jawP[jawCount * 2] = px
                            jawP[jawCount * 2 + 1] = py
                            // Full-slider sampling shift, AWAY from the midline
                            // (sampling outward moves the silhouette inward).
                            jawD[jawCount * 2] = sign(px - midX) * scale * SLIM_AMP * w / aspect
                            jawD[jawCount * 2 + 1] = 0f
                            jawR[jawCount] = scale * SLIM_RADIUS
                            jawCount++
                        }
                    }
                }
            }
        }

        // Only feed the nose arrays when that warp is active.
        val faceN = if (adjust.noseSlim > 0f) faceCount else 0

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, target.framebuffer)
        GLES20.glViewport(0, 0, target.width, target.height)
        GLES20.glUseProgram(program)
        quad.bind(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, source.texture)
        GLES20.glUniform1i(loc("uTex"), 0)
        GLES20.glUniform1f(loc("uAspect"), aspect)

        GLES20.glUniform1i(loc("uFaceCount"), faceN)
        GLES20.glUniform1f(loc("uNose"), adjust.noseSlim)
        if (faceN > 0) {
            GLES20.glUniform2fv(loc("uNoseC"), faceN, noseC, 0)
            GLES20.glUniform1fv(loc("uNoseR"), faceN, noseR, 0)
        }

        GLES20.glUniform1f(loc("uSlim"), adjust.faceSlim)
        GLES20.glUniform1i(loc("uJawCount"), jawCount)
        if (jawCount > 0) {
            GLES20.glUniform2fv(loc("uJawP"), jawCount, jawP, 0)
            GLES20.glUniform2fv(loc("uJawD"), jawCount, jawD, 0)
            GLES20.glUniform1fv(loc("uJawR"), jawCount, jawR, 0)
        }

        GLES20.glUniform1i(loc("uEyeCount"), eyeCount)
        GLES20.glUniform1f(loc("uEye"), adjust.eyeEnlarge)
        if (eyeCount > 0) {
            GLES20.glUniform2fv(loc("uEyes"), eyeCount, eyeXY, 0)
            GLES20.glUniform1fv(loc("uEyeR"), eyeCount, eyeR, 0)
        }

        quad.draw()
    }

    private fun loc(name: String) = GLES20.glGetUniformLocation(program, name)

    private fun putEye(i: Int, ring: FloatArray) {
        eyeXY[i * 2] = ring[0]
        eyeXY[i * 2 + 1] = ring[1]
        eyeR[i] = ring[2]
    }

    /** GLSL-style smoothstep. */
    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** UV x of a landmark in the target texture space. */
    private fun ux(lms: FloatArray, idx: Int, viewport: Viewport) =
        viewport.pixelX(lms[idx * 2]) / viewport.width

    /** UV y of a landmark (texture is y-up, so flip the y-down pixel). */
    private fun uy(lms: FloatArray, idx: Int, viewport: Viewport) =
        1f - viewport.pixelY(lms[idx * 2 + 1]) / viewport.height

    /** Returns {u, v, radius(da)} for a landmark ring (center + aspect-corrected half-span). */
    private fun ringCenter(lms: FloatArray, ring: IntArray, viewport: Viewport, aspect: Float): FloatArray {
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
        val u = cx / viewport.width
        val v = 1f - cy / viewport.height
        val r = (maxX - minX) / 2f / viewport.width * aspect * 1.7f
        return floatArrayOf(u, v, r)
    }

    companion object {
        private const val MAX_FACES = 5
        private const val MAX_EYES = 10 // 5 faces × 2 eyes
        private const val MAX_JAW = 16  // shared pool; ~10 used per face

        // Jawline pinch anchors on the face-oval ring, cheek → near-chin, both
        // sides (MediaPipe canonical indices). The chin (152) is deliberately
        // absent: it sits on the midline and must not be pulled sideways.
        private val JAW_RING = intArrayOf(
            93, 132, 172, 136, 150,   // left cheek → left jaw
            323, 361, 397, 365, 379,  // right cheek → right jaw
        )

        // Slim tuning, in fractions of the inter-eye distance. Keep in sync
        // with MetalRenderer (iOS).
        // SLIM_AMP: max silhouette inset per side at full slider. 0.042 was
        // chosen by feel on device: the previous 0.14 max was caricature-strong
        // (its 30% point is the new 100%).
        // SLIM_RADIUS: falloff radius of each pinch (how wide a band around the
        // jawline is affected — background beyond it does not move).
        private const val SLIM_AMP = 0.042f
        private const val SLIM_RADIUS = 0.55f
    }
}
