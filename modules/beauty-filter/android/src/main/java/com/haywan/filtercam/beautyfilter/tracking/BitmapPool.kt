package com.haywan.filtercam.beautyfilter.tracking

import android.graphics.Bitmap

/**
 * Tiny fixed-size pool for the full-res camera frame bitmaps. The analyzer
 * acquires one per frame and the renderer returns it after texture upload —
 * without this the pipeline allocates (and GCs) a ~11 MB bitmap up to 30x a
 * second, and the collector pauses show up as preview jank.
 */
internal class BitmapPool(private val maxPooled: Int = 3) {
    private val pool = ArrayDeque<Bitmap>()

    fun acquire(width: Int, height: Int): Bitmap {
        synchronized(pool) {
            while (pool.isNotEmpty()) {
                val b = pool.removeFirst()
                if (b.width == width && b.height == height && !b.isRecycled) return b
                b.recycle() // camera size changed; drop stale buffers
            }
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    }

    fun release(bitmap: Bitmap) {
        if (bitmap.isRecycled) return
        synchronized(pool) {
            if (pool.size < maxPooled) {
                pool.addLast(bitmap)
                return
            }
        }
        bitmap.recycle()
    }
}
