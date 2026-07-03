package com.haywan.filtercam.beautyfilter.tracking

/**
 * Landmark index rings on the MediaPipe Face Mesh (478-point canonical topology).
 * Each ring is an ordered closed loop, so it can be drawn as a triangle fan
 * around its centroid.
 */
internal object FaceTopology {
    val FACE_OVAL = intArrayOf(
        10, 338, 297, 332, 284, 251, 389, 356, 454, 323, 361, 288,
        397, 365, 379, 378, 400, 377, 152, 148, 176, 149, 150, 136,
        172, 58, 132, 93, 234, 127, 162, 21, 54, 103, 67, 109
    )

    val LEFT_EYE = intArrayOf(
        263, 249, 390, 373, 374, 380, 381, 382, 362, 398, 384, 385, 386, 387, 388, 466
    )

    val RIGHT_EYE = intArrayOf(
        33, 7, 163, 144, 145, 153, 154, 155, 133, 173, 157, 158, 159, 160, 161, 246
    )

    val LEFT_BROW = intArrayOf(300, 293, 334, 296, 336, 285, 295, 282, 283, 276)

    val RIGHT_BROW = intArrayOf(70, 63, 105, 66, 107, 55, 65, 52, 53, 46)

    val LIPS_OUTER = intArrayOf(
        61, 146, 91, 181, 84, 17, 314, 405, 321, 375, 291, 409, 270, 269, 267, 0, 37, 39, 40, 185
    )

    // Anchors for the mustache sprite.
    const val NOSE_BOTTOM = 2      // subnasale (under the nose septum)
    const val UPPER_LIP_TOP = 0    // top-center of the upper lip
    const val MOUTH_LEFT = 61
    const val MOUTH_RIGHT = 291

    // Anchors for face reshaping (liquify).
    const val NOSE_TIP = 4
    const val NOSE_ALA_LEFT = 129   // left nostril wing
    const val NOSE_ALA_RIGHT = 358  // right nostril wing
    const val CHEEK_LEFT = 234      // left face edge (cheek)
    const val CHEEK_RIGHT = 454     // right face edge (cheek)
    const val CHIN = 152            // bottom of chin
}
