package com.haywan.filtercam.beautyfilter.render

import android.opengl.GLES20
import com.haywan.filtercam.beautyfilter.gl.GlUtils
import com.haywan.filtercam.beautyfilter.gl.Shaders
import com.haywan.filtercam.beautyfilter.tracking.FaceTopology
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Alpha-blends the mustache sprite onto each face, anchored between the nose
 * bottom and upper-lip top, scaled to mouth width and rotated to head roll.
 * [setup] must run on the GL thread (it uploads the sprite texture).
 */
internal class MustachePass {
    private val program = GlUtils.buildProgram(Shaders.SPRITE_VS, Shaders.SPRITE_FS)
    private val spriteBuffer = ByteBuffer.allocateDirect(4 * 4 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()
    private var texture = 0

    /** Uploads the procedurally-drawn mustache texture. Call from the GL thread. */
    fun setup() {
        texture = MustacheTexture.create()
    }

    fun draw(faces: Array<FloatArray>, viewport: Viewport) {
        GLES20.glUseProgram(program)
        val posLoc = GLES20.glGetAttribLocation(program, "aPos")
        val uvLoc = GLES20.glGetAttribLocation(program, "aUV")
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glEnableVertexAttribArray(uvLoc)

        GLES20.glEnable(GLES20.GL_BLEND)
        // Android bitmaps upload premultiplied.
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTex"), 0)

        for (face in faces) {
            if (!fillSprite(face, viewport)) continue
            spriteBuffer.position(0)
            GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 16, spriteBuffer)
            spriteBuffer.position(2)
            GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, 16, spriteBuffer)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /** Fills [spriteBuffer] with the rotated quad for one face. Returns false if too small. */
    private fun fillSprite(lms: FloatArray, viewport: Viewport): Boolean {
        // Anchor: midway between the bottom of the nose and the top of the
        // upper lip, in view pixels (y grows downward).
        val ax = (viewport.pixelX(lms[FaceTopology.NOSE_BOTTOM * 2]) +
            viewport.pixelX(lms[FaceTopology.UPPER_LIP_TOP * 2])) / 2f
        val ay = (viewport.pixelY(lms[FaceTopology.NOSE_BOTTOM * 2 + 1]) +
            viewport.pixelY(lms[FaceTopology.UPPER_LIP_TOP * 2 + 1])) / 2f
        val lxp = viewport.pixelX(lms[FaceTopology.MOUTH_LEFT * 2])
        val lyp = viewport.pixelY(lms[FaceTopology.MOUTH_LEFT * 2 + 1])
        val rxp = viewport.pixelX(lms[FaceTopology.MOUTH_RIGHT * 2])
        val ryp = viewport.pixelY(lms[FaceTopology.MOUTH_RIGHT * 2 + 1])

        val mouthW = hypot(rxp - lxp, ryp - lyp)
        if (mouthW < 1f) return false
        val w = mouthW * 1.55f
        val h = w * MustacheTexture.ASPECT
        val angle = atan2(ryp - lyp, rxp - lxp)
        val ca = cos(angle)
        val sa = sin(angle)

        // Corner order matches a triangle strip: TL, TR, BL, BR.
        // UV v=0 is the top row of the bitmap.
        spriteBuffer.clear()
        for (c in CORNERS) {
            val localX = c[0] * w
            val localY = c[1] * h
            val x = ax + localX * ca - localY * sa
            val y = ay + localX * sa + localY * ca
            spriteBuffer.put(2f * x / viewport.width - 1f)
            spriteBuffer.put(1f - 2f * y / viewport.height)
            spriteBuffer.put(c[2]).put(c[3])
        }
        spriteBuffer.position(0)
        return true
    }

    companion object {
        // Half-extent x/y (in units of w/h) + UV per corner: TL, TR, BL, BR.
        private val CORNERS = arrayOf(
            floatArrayOf(-0.5f, -0.5f, 0f, 0f),
            floatArrayOf(0.5f, -0.5f, 1f, 0f),
            floatArrayOf(-0.5f, 0.5f, 0f, 1f),
            floatArrayOf(0.5f, 0.5f, 1f, 1f),
        )
    }
}
