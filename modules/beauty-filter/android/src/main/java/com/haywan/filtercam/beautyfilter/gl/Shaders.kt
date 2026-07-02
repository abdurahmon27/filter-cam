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
     * scene inside the face mask, then applies independently-controlled
     * adjustments so the UI can expose each as its own named "filter".
     *
     * uSmooth   skin smoothing amount / mask blend (0..1)
     * uGlow     brightness + radiance / highlight (0..1)
     * uClarity  even skin tone + redness knockback (0..1)
     * uWarmth   warm tone (0..1)
     */
    const val COMPOSITE_FS = """
        precision mediump float;
        varying vec2 vUV;
        uniform sampler2D uScene;
        uniform sampler2D uBlur;
        uniform sampler2D uMask;
        uniform float uSmooth;
        uniform float uGlow;
        uniform float uClarity;
        uniform float uWarmth;
        void main() {
            vec3 scene = texture2D(uScene, vUV).rgb;
            vec3 blurred = texture2D(uBlur, vUV).rgb;
            float mask = texture2D(uMask, vUV).r;

            vec3 c = scene;
            // Smooth: blend toward blurred but keep a lot of real texture so
            // beard/skin detail survives (over-smoothing reads as "obviously a
            // filter"). Only where the smoothed and original differ modestly is
            // it softened; high-contrast beard edges keep more of themselves.
            vec3 softened = mix(blurred, scene, 0.35);
            float detail = clamp(length(scene - blurred) * 3.0, 0.0, 1.0);
            softened = mix(softened, scene, detail * 0.5);
            c = mix(c, softened, uSmooth);

            float lum = dot(c, vec3(0.299, 0.587, 0.114));
            // Clarity: even the complexion toward luminance + knock back excess red.
            vec3 clar = mix(c, vec3(lum), 0.16);
            float redExcess = max(clar.r - (clar.g + clar.b) * 0.5, 0.0);
            clar.r -= redExcess * 0.35;
            c = mix(c, clar, uClarity);

            // Glow: brighten using the face's OWN skin colour, never flat white.
            // Multiplicative lift preserves hue for any skin tone, and the added
            // radiance is tinted by the local skin colour (uBlur) so dark skin
            // lifts to a brighter skin tone instead of turning grey/ashy.
            vec3 glow = c * 1.12;
            float radiance = smoothstep(0.35, 0.75, lum) * (1.0 - smoothstep(0.75, 1.0, lum));
            glow += radiance * 0.14 * blurred;
            c = mix(c, glow, uGlow);

            // Warmth: push toward warm (more red, less blue).
            c = mix(c, c + vec3(0.055, 0.020, -0.030), uWarmth);

            c = clamp(c, 0.0, 1.0);
            gl_FragColor = vec4(mix(scene, c, mask), 1.0);
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

    /**
     * Eye-enlarge warp: a localized bulge around each eye center that magnifies
     * the eyes. At uStrength 0 (or with no eyes) it is a pure blit, so it is safe
     * to always run. Eye centers/radii are in the sampled texture's UV space.
     */
    const val EYE_WARP_FS = """
        precision mediump float;
        varying vec2 vUV;
        uniform sampler2D uTex;
        uniform int uEyeCount;
        uniform vec2 uEyes[10];   // centers in UV (0..1)
        uniform float uEyeR[10];  // radius in aspect-corrected UV units
        uniform float uStrength;  // 0..1 enlarge amount
        uniform float uAspect;    // width / height
        void main() {
            vec2 uv = vUV;
            for (int i = 0; i < 10; i++) {
                if (i >= uEyeCount) break;
                vec2 d = uv - uEyes[i];
                vec2 da = vec2(d.x * uAspect, d.y);
                float dist = length(da);
                float r = uEyeR[i];
                if (r > 0.0 && dist < r) {
                    float pct = dist / r;
                    // Sample nearer the center inside the eye radius; ease to no
                    // change at the edge so there is no visible seam.
                    float factor = 1.0 - uStrength * 0.5 * (1.0 - smoothstep(0.0, 1.0, pct));
                    uv = uEyes[i] + d * factor;
                }
            }
            gl_FragColor = texture2D(uTex, uv);
        }
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
