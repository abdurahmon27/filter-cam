package com.haywan.filtercam.beautyfilter.gl

/**
 * All GLSL ES 2.0 shader sources for the beauty pipeline, grouped in one place
 * so the render passes read as orchestration rather than a wall of shader text.
 *
 * Naming: `*_VS` is a vertex shader, `*_FS` a fragment shader. The fullscreen
 * passes share [FULLSCREEN_VS]; passes that need a texture matrix or point size
 * have their own vertex shader.
 */
internal object Shaders {

    /** Fullscreen quad that simply forwards its UVs. Used by blur/composite/sprite. */
    const val FULLSCREEN_VS = """
        attribute vec2 aPos;
        attribute vec2 aUV;
        varying vec2 vUV;
        void main() { gl_Position = vec4(aPos, 0.0, 1.0); vUV = aUV; }
    """

    /** Camera pass: applies the crop window and the SurfaceTexture/rotation matrix. */
    const val CAMERA_VS = """
        attribute vec2 aPos;
        attribute vec2 aUV;
        uniform mat4 uTexMatrix;
        uniform vec4 uCrop; // offset.xy, scale.xy
        varying vec2 vUV;
        void main() {
            gl_Position = vec4(aPos, 0.0, 1.0);
            vec2 uv = uCrop.xy + aUV * uCrop.zw;
            vUV = (uTexMatrix * vec4(uv, 0.0, 1.0)).xy;
        }
    """

    /** Samples the external OES camera texture. */
    val CAMERA_FS = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vUV;
        uniform samplerExternalOES uTex;
        void main() { gl_FragColor = texture2D(uTex, vUV); }
    """.trimIndent()

    /** Separable gaussian blur (call once per axis via uDir). */
    const val BLUR_FS = """
        precision mediump float;
        varying vec2 vUV;
        uniform sampler2D uTex;
        uniform vec2 uDir;
        void main() {
            vec4 c = texture2D(uTex, vUV) * 0.227027;
            c += (texture2D(uTex, vUV + uDir * 1.3846) + texture2D(uTex, vUV - uDir * 1.3846)) * 0.316216;
            c += (texture2D(uTex, vUV + uDir * 3.2308) + texture2D(uTex, vUV - uDir * 3.2308)) * 0.070270;
            gl_FragColor = c;
        }
    """

    /** Flat-colour fill for the face-mask polygons. */
    const val SOLID_VS = """
        attribute vec2 aPos;
        void main() { gl_Position = vec4(aPos, 0.0, 1.0); }
    """

    const val SOLID_FS = """
        precision mediump float;
        uniform vec4 uColor;
        void main() { gl_FragColor = uColor; }
    """

    /**
     * Beauty composite: blends the blurred (smoothed) scene back over the sharp
     * scene, but only inside the mask and scaled by strength, then evens the skin
     * tone, tames redness, and lifts brightness for a clean, radiant look.
     *
     * uStrength   overall beauty amount (0..1)
     * uGlow       radiance / brightness lift (0..1)
     */
    const val COMPOSITE_FS = """
        precision mediump float;
        varying vec2 vUV;
        uniform sampler2D uScene;
        uniform sampler2D uBlur;
        uniform sampler2D uMask;
        uniform float uStrength;
        uniform float uGlow;
        void main() {
            vec3 scene = texture2D(uScene, vUV).rgb;
            vec3 blurred = texture2D(uBlur, vUV).rgb;
            float m = texture2D(uMask, vUV).r * uStrength;

            // Smooth skin: keep only a little real texture so it isn't plastic.
            vec3 smoothed = mix(blurred, scene, 0.18);

            float lum = dot(smoothed, vec3(0.299, 0.587, 0.114));
            // Even the complexion: pull slightly toward its own luminance to cut
            // blotchiness, then specifically knock back excess red (redness/acne).
            smoothed = mix(smoothed, vec3(lum), 0.12);
            float redExcess = max(smoothed.r - (smoothed.g + smoothed.b) * 0.5, 0.0);
            smoothed.r -= redExcess * 0.20;

            // Brighten + lift shadows (under-eye) with a touch of warmth.
            smoothed = smoothed * (1.05 + 0.05 * uGlow) + vec3(0.025, 0.020, 0.014) * (0.6 + uGlow);
            // Soft radiance in the midtones — NOT a specular highlight, so oily
            // spots on the nose/forehead don't blow out.
            float radiance = smoothstep(0.35, 0.75, lum) * (1.0 - smoothstep(0.75, 1.0, lum));
            smoothed += radiance * uGlow * 0.05;
            smoothed = clamp(smoothed, 0.0, 1.0);

            gl_FragColor = vec4(mix(scene, smoothed, m), 1.0);
        }
    """

    /** Textured sprite (mustache). */
    const val SPRITE_VS = """
        attribute vec2 aPos;
        attribute vec2 aUV;
        varying vec2 vUV;
        void main() { gl_Position = vec4(aPos, 0.0, 1.0); vUV = aUV; }
    """

    const val SPRITE_FS = """
        precision mediump float;
        varying vec2 vUV;
        uniform sampler2D uTex;
        void main() { gl_FragColor = texture2D(uTex, vUV); }
    """

    /** Round point sprite for the debug face-mesh overlay. */
    const val POINT_VS = """
        attribute vec2 aPos;
        uniform float uPointSize;
        void main() {
            gl_Position = vec4(aPos, 0.0, 1.0);
            gl_PointSize = uPointSize;
        }
    """

    const val POINT_FS = """
        precision mediump float;
        uniform vec4 uColor;
        void main() {
            // Carve each square point into a round dot.
            vec2 c = gl_PointCoord - vec2(0.5);
            if (dot(c, c) > 0.25) discard;
            gl_FragColor = uColor;
        }
    """
}
