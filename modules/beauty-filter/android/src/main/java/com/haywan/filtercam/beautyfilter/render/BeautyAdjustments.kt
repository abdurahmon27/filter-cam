package com.haywan.filtercam.beautyfilter.render

/**
 * The independently-controllable beauty parameters, each surfaced in the UI as
 * its own named "filter". All values are clamped to 0..1.
 */
internal data class BeautyAdjustments(
    val smooth: Float,
    val glow: Float,
    val clarity: Float,
    val warmth: Float,
    val eyeEnlarge: Float,
) {
    companion object {
        fun of(smooth: Float, glow: Float, clarity: Float, warmth: Float, eyeEnlarge: Float) =
            BeautyAdjustments(
                smooth.coerceIn(0f, 1f),
                glow.coerceIn(0f, 1f),
                clarity.coerceIn(0f, 1f),
                warmth.coerceIn(0f, 1f),
                eyeEnlarge.coerceIn(0f, 1f),
            )
    }
}
