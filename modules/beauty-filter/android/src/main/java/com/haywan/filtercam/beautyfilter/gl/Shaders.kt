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

    /**
     * Camera pass (single-stream): the frame is a normal 2D texture uploaded from
     * an already display-oriented bitmap. Applies the cover-crop window and flips
     * the y axis (bitmaps are top-down, GL textures are bottom-up).
     */
    const val CAMERA_VS = """
        attribute vec2 aPos;
        attribute vec2 aUV;
        uniform vec4 uCrop; // offset.xy, scale.xy
        varying vec2 vUV;
        void main() {
            gl_Position = vec4(aPos, 0.0, 1.0);
            vec2 uv = uCrop.xy + aUV * uCrop.zw;
            vUV = vec2(uv.x, 1.0 - uv.y);
        }
    """

    val CAMERA_FS = """
        precision mediump float;
        varying vec2 vUV;
        uniform sampler2D uTex;
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
     * Beauty composite — a natural, edge-preserving skin retouch (the look real
     * live-stream filters use), NOT a heavy blur or whitening.
     *
     * Method: frequency separation. [uBlur] is a low-pass of the scene; the
     * high-frequency detail `scene - low` (pores, beard, lashes, lip/nostril
     * edges) is added back with an EDGE-AWARE weight — kept in full at real edges
     * (features stay razor-sharp, no halos) and only partly on flat skin (pores
     * survive, blemishes soften). Nothing is replaced: the retouch is blended
     * over the original by opacity (uSmooth × skin mask), so texture and the
     * face's own contours/shadows are preserved.
     *
     * Tone is deliberately light to keep contrast + dynamic range: clarity evens
     * skin colour at CONSTANT luma (no flattening) and keeps skin warm/alive
     * instead of gray; glow lifts ONLY midtones (never the black point, so no
     * haze/washout); warmth is a soft GLOBAL grade for a cohesive natural tone.
     *
     * [uSkinMask] = oval minus eyes/brows/lips (gates smoothing + tone-even).
     * [uFaceMask] = full oval (gates glow so the whole face reads cohesive).
     *
     * uSmooth   skin-smoothing opacity (0..1)
     * uGlow     midtone brightness (0..1)
     * uClarity  even skin tone + life (0..1)
     * uWarmth   global warm tone (0..1)
     */
    const val COMPOSITE_FS = """
        precision mediump float;
        varying vec2 vUV;
        uniform sampler2D uScene;
        uniform sampler2D uBlur;
        uniform sampler2D uSkinMask;   // oval minus eyes/brows/lips
        uniform sampler2D uFaceMask;   // full face oval
        uniform float uSmooth;
        uniform float uGlow;
        uniform float uClarity;
        uniform float uWarmth;

        const vec3 LUMA = vec3(0.299, 0.587, 0.114);

        void main() {
            vec3 scene = texture2D(uScene, vUV).rgb;
            vec3 low   = texture2D(uBlur, vUV).rgb;
            float skin = texture2D(uSkinMask, vUV).r;
            float face = texture2D(uFaceMask, vUV).r;

            // --- Edge-aware skin smoothing (frequency separation) ---
            vec3 high = scene - low;              // fine texture + edges (signed)
            float amp = length(high);
            // Keep ALL detail at real edges (sharp features, no halos); keep only
            // a small fraction on flat skin so acne, pores and blemishes are
            // cleaned away (like the reference) while beard/lash/brow stay sharp.
            float edge = smoothstep(0.055, 0.22, amp);
            float keep = mix(0.28, 1.0, edge);
            vec3 smoothed = low + high * keep;
            // Opacity blend over the ORIGINAL, skin mask only.
            vec3 c = mix(scene, smoothed, uSmooth * skin);

            // --- Clarity: even skin colour toward the local tone at CONSTANT luma
            // (evens blotch/redness without flattening), then a touch more
            // saturation so skin stays warm & alive rather than gray/pale. ---
            float cl = dot(c, LUMA);
            vec3 localTone = low + (cl - dot(low, LUMA));
            c = mix(c, localTone, uClarity * skin * 0.5);   // evens redness/acne colour
            float sl = dot(c, LUMA);
            c = mix(vec3(sl), c, 1.0 + uClarity * face * 0.08);

            // --- Glow: gentle MIDTONE lift (whole face) + a soft skin whitening
            // toward a lighter, cleaner tone (skin only, so no background haze) —
            // matches the reference's slightly brighter/whiter skin. Neither lifts
            // the black point, so no washed-out haze. ---
            float lum = dot(c, LUMA);
            float mid = smoothstep(0.12, 0.55, lum) * (1.0 - smoothstep(0.72, 1.0, lum));
            c += c * (uGlow * face * 0.11 * mid);
            c += (vec3(1.0) - c) * (uGlow * skin * 0.04);

            c = clamp(c, 0.0, 1.0);
            vec3 outColor = mix(scene, c, face);

            // --- Warmth: soft GLOBAL grade (whole frame) for a cohesive, natural
            // warm tone with no face-oval seam. Faded out on very dark pixels so
            // hair, eyebrows, beard, lashes and pupils keep their true, un-tinted
            // colour and stay crisp — the warm layer never browns/softens them. ---
            float darkGuard = smoothstep(0.08, 0.35, dot(outColor, LUMA));
            outColor += vec3(0.045, 0.016, -0.026) * (uWarmth * darkGuard);

            // --- Life: global micro-contrast + vibrance so the image looks ALIVE,
            // not flat/"dead" — deepens blacks (hair/brows/beard read truly black),
            // lifts highlights (facial dimensionality) and enriches colour. ---
            outColor = (outColor - 0.5) * 1.07 + 0.5;
            float gL = dot(outColor, LUMA);
            outColor = mix(vec3(gL), outColor, 1.10);

            gl_FragColor = vec4(clamp(outColor, 0.0, 1.0), 1.0);
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
     * Final present with a light unsharp-mask sharpen. The camera frame is a lower
     * resolution upscaled to the screen, which reads soft; this restores crisp
     * acutance on edges (hair, brows, features, background) for the sharp,
     * "high-quality" reference look. It self-limits on flat smoothed skin (where
     * centre ≈ neighbourhood), so it sharpens edges without re-adding skin noise.
     */
    const val SHARPEN_FS = """
        precision mediump float;
        varying vec2 vUV;
        uniform sampler2D uTex;
        uniform vec2 uTexel;    // 1/srcWidth, 1/srcHeight
        uniform float uAmount;  // sharpen strength
        void main() {
            vec3 c = texture2D(uTex, vUV).rgb;
            vec3 blur = (
                texture2D(uTex, vUV + vec2(uTexel.x, 0.0)).rgb +
                texture2D(uTex, vUV - vec2(uTexel.x, 0.0)).rgb +
                texture2D(uTex, vUV + vec2(0.0, uTexel.y)).rgb +
                texture2D(uTex, vUV - vec2(0.0, uTexel.y)).rgb
            ) * 0.25;
            c = c + (c - blur) * uAmount;
            gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
        }
    """

    /**
     * Face-reshape (liquify) warp: enlarges eyes, and narrows the nose and the
     * face/jaw by pulling those regions toward the face midline. All strengths 0
     * (or no faces) is a pure blit, so it is safe to always run. Centers/radii are
     * in the sampled texture's UV space; radii are aspect-corrected (fraction of
     * screen height).
     */
    const val FACE_RESHAPE_FS = """
        precision mediump float;
        varying vec2 vUV;
        uniform sampler2D uTex;
        uniform float uAspect;    // width / height

        uniform int uFaceCount;
        uniform float uMidX[5];   // face midline x
        uniform vec2 uNoseC[5];
        uniform float uNoseR[5];
        uniform vec2 uJawC[5];
        uniform float uJawR[5];
        uniform float uNose;      // nose-slim strength
        uniform float uSlim;      // face-slim strength

        uniform int uEyeCount;
        uniform vec2 uEyes[10];
        uniform float uEyeR[10];
        uniform float uEye;       // eye-enlarge strength

        // Compress x toward axisX inside a soft circular region (narrowing).
        // Max ~30% narrowing at full slider so it stays natural, not a caricature.
        vec2 pullX(vec2 uv, vec2 c, float r, float axisX, float s) {
            if (r <= 0.0 || s <= 0.0) return uv;
            float dist = length(vec2((uv.x - c.x) * uAspect, uv.y - c.y));
            if (dist < r) {
                float f = 1.0 - smoothstep(0.0, 1.0, dist / r);
                uv.x = axisX + (uv.x - axisX) * (1.0 + s * 0.30 * f);
            }
            return uv;
        }

        // Sample nearer a center inside a soft region (magnify). Max ~18% at full.
        vec2 magnify(vec2 uv, vec2 c, float r, float s) {
            if (r <= 0.0 || s <= 0.0) return uv;
            vec2 d = uv - c;
            float dist = length(vec2(d.x * uAspect, d.y));
            if (dist < r) {
                float f = 1.0 - smoothstep(0.0, 1.0, dist / r);
                uv = c + d * (1.0 - s * 0.35 * f);
            }
            return uv;
        }

        void main() {
            vec2 uv = vUV;
            for (int i = 0; i < 5; i++) {
                if (i >= uFaceCount) break;
                uv = pullX(uv, uJawC[i], uJawR[i], uMidX[i], uSlim);
                uv = pullX(uv, uNoseC[i], uNoseR[i], uNoseC[i].x, uNose);
            }
            for (int i = 0; i < 10; i++) {
                if (i >= uEyeCount) break;
                uv = magnify(uv, uEyes[i], uEyeR[i], uEye);
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
