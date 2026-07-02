package com.haywan.filtercam.beautyfilter.render

/**
 * Maps normalized, upright, display-oriented landmark coordinates (0..1, y down)
 * into the visible on-screen region, accounting for the center-crop that makes
 * the camera frame **cover** the view.
 *
 * The crop is derived from the camera buffer aspect vs the view aspect, using
 * the landmark/display orientation as the source of truth (independent of the
 * preview texture's rotation offset). One axis is always full; the other is
 * inset so the frame fills the screen with no letterbox bars.
 */
internal class Viewport {
    var width = 1
        private set
    var height = 1
        private set

    var cropScaleX = 1f
        private set
    var cropScaleY = 1f
        private set
    var cropOffX = 0f
        private set
    var cropOffY = 0f
        private set

    fun setSize(w: Int, h: Int) {
        width = w.coerceAtLeast(1)
        height = h.coerceAtLeast(1)
    }

    fun updateCrop(cameraRotationDegrees: Int, bufferWidth: Int, bufferHeight: Int) {
        if (bufferWidth <= 0 || bufferHeight <= 0) {
            cropScaleX = 1f; cropScaleY = 1f; cropOffX = 0f; cropOffY = 0f
            return
        }
        val rotated = cameraRotationDegrees % 180 != 0
        val uprightW = if (rotated) bufferHeight else bufferWidth
        val uprightH = if (rotated) bufferWidth else bufferHeight
        val camAspect = uprightW.toFloat() / uprightH
        val viewAspect = width.toFloat() / height
        if (camAspect > viewAspect) {
            cropScaleX = viewAspect / camAspect
            cropScaleY = 1f
        } else {
            cropScaleX = 1f
            cropScaleY = camAspect / viewAspect
        }
        cropOffX = (1f - cropScaleX) / 2f
        cropOffY = (1f - cropScaleY) / 2f
    }

    /** Normalized landmark x/y -> clip-space NDC (-1..1). */
    fun ndcX(nx: Float) = 2f * (nx - cropOffX) / cropScaleX - 1f
    fun ndcY(ny: Float) = 1f - 2f * (ny - cropOffY) / cropScaleY

    /** Normalized landmark x/y -> view pixels (y down). */
    fun pixelX(nx: Float) = (nx - cropOffX) / cropScaleX * width
    fun pixelY(ny: Float) = (ny - cropOffY) / cropScaleY * height
}
