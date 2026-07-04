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
     * Camera pass (single-stream): the frame texture holds the RAW camera buffer
     * (un-rotated, un-mirrored — rotating the full-res bitmap on the CPU was the
     * pipeline's fps bottleneck). Applies the cover-crop window in upright display
     * space, then maps upright coords into buffer coords with an affine transform
     * ([uUvX]/[uUvY], set from the frame's rotation + mirror) so the GPU does the
     * rotation for free during sampling.
     */
    const val CAMERA_VS = """
        attribute vec2 aPos;
        attribute vec2 aUV;
        uniform vec4 uCrop; // offset.xy, scale.xy (upright space)
        uniform vec3 uUvX;  // bufferU = dot(uUvX, vec3(uprightUV, 1))
        uniform vec3 uUvY;  // bufferV = dot(uUvY, vec3(uprightUV, 1))
        varying vec2 vUV;
        void main() {
            gl_Position = vec4(aPos, 0.0, 1.0);
            vec2 uv = uCrop.xy + aUV * uCrop.zw;
            uv = vec2(uv.x, 1.0 - uv.y); // upright, top-down (bitmap orientation)
            vUV = vec2(dot(uUvX, vec3(uv, 1.0)), dot(uUvY, vec3(uv, 1.0)));
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
     * instead of gray; glow is a GLOBAL highlight bloom (screen blend of the
     * blurred scene's bright end) plus a faint skin whitening — image-driven,
     * so there is no mask boundary to see; warmth is a soft GLOBAL grade.
     *
     * [uSkinMask] = oval minus eyes/brows/lips (gates smoothing + tone-even).
     * [uFaceMask] = full oval (gates the beauty composite).
     *
     * uSmooth   skin-smoothing opacity (0..1)
     * uGlow     highlight bloom + skin brightening (0..1)
     * uClarity  even skin tone + life (0..1)
     * uWarmth   global warm tone (0..1)
     * uSharp    structure + rich colour (0..1) — large-radius local contrast
     *           (hair/eyes/background definition, gated off smoothed skin),
     *           deeper shadows and extra vibrance for the crisp, saturated
     *           "high-quality" reference look.
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
        uniform float uSharp;

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

            // --- Glow (in-mask part): only a faint skin whitening lives inside
            // the face gate; the visible glow is the GLOBAL bloom below. A flat
            // brightness lift here reads as a bright oval "layer" at the mask
            // boundary — never add one. ---
            c += (vec3(1.0) - c) * (uGlow * skin * 0.05);

            c = clamp(c, 0.0, 1.0);
            vec3 outColor = mix(scene, c, face);

            // --- Sharp (structure): large-radius local contrast against the
            // blurred scene, gated OFF smoothed skin — hair strands, eyes,
            // brows and the background gain crisp definition while the
            // retouched skin stays clean. Runs before bloom/warmth so those
            // global grades are not re-amplified by the difference signal. ---
            outColor += (outColor - low) * (uSharp * 0.35 * (1.0 - skin * 0.8));
            outColor = clamp(outColor, 0.0, 1.0);

            // --- Glow (bloom): screen-blend the blurred scene's own soft
            // highlights over the WHOLE frame. Light spreads where light
            // already exists — like real optics — so there is no geometric
            // boundary to see; it reads as better lighting, not a filter.
            // Blacks are untouched (highlights only), so no washed-out haze. ---
            vec3 hl = clamp((low - 0.45) / 0.55, 0.0, 1.0);
            outColor = 1.0 - (1.0 - outColor) * (1.0 - hl * (uGlow * 0.30));

            // --- Warmth: soft GLOBAL grade (whole frame) for a cohesive, natural
            // warm tone with no face-oval seam. Faded out on very dark pixels so
            // hair, eyebrows, beard, lashes and pupils keep their true, un-tinted
            // colour and stay crisp — the warm layer never browns/softens them. ---
            float darkGuard = smoothstep(0.08, 0.35, dot(outColor, LUMA));
            outColor += vec3(0.045, 0.016, -0.026) * (uWarmth * darkGuard);

            // --- Fair skin (glow-driven): pale, porcelain whitening of
            // skin-TONED pixels across the whole frame — face, ears and neck
            // alike, so there is no mask seam. Detects warm skin hues
            // (r > g > b, mid+ luma so hair/beard/brows are untouched) and
            // fades them toward a desaturated, white-lifted version. ---
            float wLuma = dot(outColor, LUMA);
            float skinHue = smoothstep(0.0, 0.12, outColor.r - outColor.g)
                          * smoothstep(0.0, 0.06, outColor.g - outColor.b)
                          * smoothstep(0.15, 0.40, wLuma);
            vec3 pale = mix(outColor, vec3(wLuma), 0.70);
            pale += (vec3(1.0) - pale) * 0.24;
            outColor = mix(outColor, pale, uGlow * 1.0 * skinHue);
            // A small multiplicative gain on the same skin-toned pixels keeps
            // the face reading bright and lit without shifting its (already
            // matched) colour — gain preserves hue/sat where a white-mix pales.
            outColor *= 1.0 + 0.08 * skinHue;

            // --- Life: global micro-contrast + vibrance so the image looks ALIVE,
            // not flat/"dead" — deepens blacks (hair/brows/beard read truly black),
            // lifts highlights (facial dimensionality) and enriches colour.
            // uSharp scales both up for a punchier, more saturated grade. ---
            outColor = (outColor - 0.5) * (1.03 + uSharp * 0.07) + 0.5;
            float gL = dot(outColor, LUMA);
            outColor = mix(vec3(gL), outColor, 1.05 + uSharp * 0.20);

            // --- Sharp (rich colour): deepen the shadows so dark hair, brows
            // and beard read dense and truly dark instead of lifted/washed. ---
            float shadow = 1.0 - smoothstep(0.04, 0.50, dot(outColor, LUMA));
            outColor *= 1.0 - uSharp * 0.14 * shadow;

            // --- Skin neutralize: runs AFTER vibrance/contrast so they cannot
            // re-add the warm cast the fair-skin grade removed. Trims the
            // red-over-green excess on skin-toned pixels (porcelain, not
            // orange) and returns a touch of blue to cool the tone. ---
            float warmCast = outColor.r - outColor.g;
            outColor.r -= warmCast * 0.18 * skinHue;
            outColor.b += warmCast * 0.10 * skinHue;

            // --- Bright: gentle global gamma lift so the frame reads light and
            // airy (whiter walls/skin, the reference look). A gamma curve pins
            // black and white, so hair keeps its depth and highlights never
            // clip — it only lifts the mids. ---
            outColor = pow(clamp(outColor, 0.0, 1.0), vec3(0.92));

            // --- Rich blacks: shadow toe applied LAST (the gamma above grays
            // the deepest tones ~25%; anything earlier gets re-lifted). Pulls
            // sub-0.35-luma tones toward true black so hair, brows, beard and
            // pupils read dense. Skin sits well above the toe, so the face
            // brightness/whitening is untouched. Pure ALU in this same pass —
            // no extra texture reads, no performance cost. ---
            float toeL = dot(outColor, LUMA);
            outColor *= 1.0 - 0.25 * (1.0 - smoothstep(0.0, 0.35, toeL));

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
        uniform vec2 uNoseC[5];
        uniform float uNoseR[5];
        uniform float uNose;        // nose-slim strength
        uniform float uSlim;        // face-slim strength

        // Face slim: pinch points pinned to the jawline landmarks (below the
        // eyes down to the chin). uJawD is the full-slider sampling shift
        // (points AWAY from the face midline; sampling outward moves the
        // silhouette inward). Radii are aspect-corrected.
        uniform int uJawCount;
        uniform vec2 uJawP[16];
        uniform vec2 uJawD[16];
        uniform float uJawR[16];

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

        // Face slim: sum of localized jaw-contour pinches. Each pinch shifts
        // sampling horizontally away from the face midline inside its own small
        // radius, so the jaw/cheek silhouette moves INWARD and everything
        // outside the thin band around the jawline (background, hair, the other
        // side of the frame) stays put. Overlapping falloffs are normalized
        // (divide by the weight sum when it exceeds 1) so adjacent pinches
        // blend along the contour instead of stacking.
        vec2 slimJaw(vec2 uv, float s) {
            if (uJawCount <= 0 || s <= 0.0) return uv;
            vec2 sumD = vec2(0.0);
            float sumW = 0.0;
            for (int i = 0; i < 16; i++) {
                if (i >= uJawCount) break;
                float dist = length(vec2((uv.x - uJawP[i].x) * uAspect, uv.y - uJawP[i].y));
                float f = 1.0 - smoothstep(0.0, 1.0, dist / uJawR[i]);
                sumD += uJawD[i] * f;
                sumW += f;
            }
            return uv + sumD * (s / max(sumW, 1.0));
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
            // Slim the jaw/cheek contour (below the eyes only; face-local).
            uv = slimJaw(uv, uSlim);
            for (int i = 0; i < 5; i++) {
                if (i >= uFaceCount) break;
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
