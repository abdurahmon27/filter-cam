package com.haywan.filtercam.beautyfilter

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.opengl.GLES20
import android.opengl.GLUtils

/**
 * Draws a handlebar mustache into a bitmap with Canvas (no binary asset
 * needed) and uploads it as a GL texture. The texture is 2:1 and the shape is
 * vertically centered, so a quad centered on the philtrum lines up naturally.
 */
object MustacheTexture {

    const val ASPECT = 0.5f // height / width of the texture

    fun create(): Int {
        val bitmap = draw(512, 256)
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        bitmap.recycle()
        return tex[0]
    }

    private fun draw(w: Int, h: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = LinearGradient(
                0f, h * 0.25f, 0f, h * 0.8f,
                0xFF3B2A1E.toInt(), 0xFF17100B.toInt(),
                Shader.TileMode.CLAMP
            )
        }

        // Right half of the mustache; the left half is a mirrored draw.
        val half = Path().apply {
            moveTo(256f, 130f)
            // top edge sweeping out to the right
            cubicTo(300f, 88f, 352f, 82f, 392f, 98f)
            // rising into the curled tip
            cubicTo(432f, 112f, 462f, 100f, 480f, 68f)
            // tip curling back under
            cubicTo(486f, 108f, 470f, 142f, 438f, 154f)
            // bottom edge back to the center
            cubicTo(392f, 170f, 306f, 176f, 256f, 150f)
            close()
        }

        canvas.drawPath(half, paint)
        canvas.save()
        canvas.scale(-1f, 1f, w / 2f, 0f)
        canvas.drawPath(half, paint)
        canvas.restore()
        return bitmap
    }
}
