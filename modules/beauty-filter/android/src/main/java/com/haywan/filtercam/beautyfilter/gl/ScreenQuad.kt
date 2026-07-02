package com.haywan.filtercam.beautyfilter.gl

import android.opengl.GLES20

/**
 * The fullscreen triangle-strip quad shared by every fullscreen pass. Holds the
 * position/UV vertex buffers once and binds them into a program's `aPos`/`aUV`
 * attributes (UV is optional — passes like the mask fill only use position).
 */
internal class ScreenQuad {
    private val pos = GlUtils.floatBuffer(
        floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    )
    private val uv = GlUtils.floatBuffer(
        floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
    )

    fun bind(program: Int) {
        val posLoc = GLES20.glGetAttribLocation(program, "aPos")
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, pos)
        val uvLoc = GLES20.glGetAttribLocation(program, "aUV")
        if (uvLoc >= 0) {
            GLES20.glEnableVertexAttribArray(uvLoc)
            GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, 0, uv)
        }
    }

    /** Draws the quad as a 4-vertex triangle strip. */
    fun draw() {
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }
}
