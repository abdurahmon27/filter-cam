//
//  Shaders.metal
//  BeautyFilter
//
//  Metal port of the Android BeautyRenderer GLSL programs (gl/Shaders.kt). The
//  pass pipeline mirrors Android's exactly:
//    camera -> scene, two-pass gaussian blur at quarter res, TWO feathered face
//    masks (skin = oval minus eyes/brows/lips; face = full oval), composite
//    (edge-aware smoothing + glow/clarity/warmth + a global life grade), a
//    face-reshape (liquify) warp, then the mustache sprite / mesh dots, then a
//    sharpen blit.
//
//  Coordinate conventions (IMPORTANT):
//    - Metal clip space: y = +1 is the TOP of the render target.
//    - Metal texture sampling: v = 0 is the TOP row of the texture.
//    `fullscreen_vertex` maps its generated uv so uv.y = 0 lands on clip-space
//    y = +1 ("sampled image up == displayed image up"). Swift-side landmark
//    coordinates are display-normalized (0..1, y-down), which lines up directly
//    with this uv space (uv.y = 0 == display top == landmark y = 0).
//

#include <metal_stdlib>
using namespace metal;

// ---------------------------------------------------------------------------
// Fullscreen passes (camera, blur, composite, reshape, sharpen)
// ---------------------------------------------------------------------------

struct FSOut {
    float4 position [[position]];
    float2 uv;
};

// Emits a single oversized triangle covering the whole render target. No vertex
// buffer required -- driven purely by vertex_id (0,1,2).
vertex FSOut fullscreen_vertex(uint vid [[vertex_id]]) {
    float2 p[3] = { float2(-1.0, -1.0), float2(3.0, -1.0), float2(-1.0, 3.0) };
    FSOut out;
    out.position = float4(p[vid], 0.0, 1.0);
    // Flip v: clip-space top (y=+1) maps to texture top (v=0).
    out.uv = float2((p[vid].x + 1.0) * 0.5, (1.0 - p[vid].y) * 0.5);
    return out;
}

// Camera pass: sample the (already-upright, already-mirrored) camera texture,
// applying a center-crop so the visible region matches the view aspect ratio.
// crop = (offsetX, offsetY, scaleX, scaleY).
fragment float4 camera_fragment(FSOut in [[stage_in]],
                                texture2d<float> tex [[texture(0)]],
                                constant float4 &crop [[buffer(0)]]) {
    constexpr sampler s(filter::linear, address::clamp_to_edge);
    float2 uv = crop.xy + in.uv * crop.zw;
    return tex.sample(s, uv);
}

// Separable gaussian blur (5 taps). dir carries the per-pass step in uv space,
// e.g. (2.6/width, 0) horizontal then (0, 2.6/height) vertical.
fragment float4 blur_fragment(FSOut in [[stage_in]],
                              texture2d<float> tex [[texture(0)]],
                              constant float2 &dir [[buffer(0)]]) {
    constexpr sampler s(filter::linear, address::clamp_to_edge);
    float4 c = tex.sample(s, in.uv) * 0.227027;
    c += (tex.sample(s, in.uv + dir * 1.3846) + tex.sample(s, in.uv - dir * 1.3846)) * 0.316216;
    c += (tex.sample(s, in.uv + dir * 3.2308) + tex.sample(s, in.uv - dir * 3.2308)) * 0.070270;
    return c;
}

// Beauty composite -- direct port of Android COMPOSITE_FS.
//
// A natural, edge-preserving skin retouch via frequency separation: the
// high-frequency detail (scene - low) is kept in full at real edges and only
// partly on flat skin, blended over the original by (smooth * skin mask).
// Clarity evens skin colour at constant luma; glow is a global highlight bloom
// (image-driven, no mask boundary) plus a faint skin whitening; warmth is a
// soft global grade; a final micro-contrast/vibrance pass keeps the image alive.
//
//   skinMask = oval minus eyes/brows/lips (gates smoothing + tone-even)
//   faceMask = full oval                  (gates the beauty composite)
struct BeautyUniforms {
    float smooth;
    float glow;
    float clarity;
    float warmth;
    float sharp;   // structure + rich colour (deep shadows, vibrance)
};

fragment float4 composite_fragment(FSOut in [[stage_in]],
                                   texture2d<float> sceneTex [[texture(0)]],
                                   texture2d<float> blurTex  [[texture(1)]],
                                   texture2d<float> skinTex  [[texture(2)]],
                                   texture2d<float> faceTex  [[texture(3)]],
                                   constant BeautyUniforms &u [[buffer(0)]]) {
    constexpr sampler s(filter::linear, address::clamp_to_edge);
    const float3 LUMA = float3(0.299, 0.587, 0.114);

    float3 scene = sceneTex.sample(s, in.uv).rgb;
    float3 low   = blurTex.sample(s, in.uv).rgb;
    float skin   = skinTex.sample(s, in.uv).r;
    float face   = faceTex.sample(s, in.uv).r;

    // --- Edge-aware skin smoothing (frequency separation) ---
    // iOS-ONLY (Android keeps floor 0.35, band 0.065/0.24). Three signals decide
    // how much fine detail (`high`) survives on skin:
    //   1. structure  — real edges/contours (high amp) keep FULL detail so the
    //      face stays sharp; genuinely flat skin keeps a texture floor (not waxy).
    //   2. sheen      — the skin's BRIGHT specular micro-detail (nose bridge,
    //      cheekbones, forehead glints) is kept so the face reads lit, not matte.
    //   3. darkSpot   — the mole/blemish REMOVER (see below).
    float3 high = scene - low;
    float amp = length(high);
    float highL = dot(high, LUMA);      // + brighter than skin, - darker (spots)
    float lowL  = dot(low, LUMA);       // local skin brightness (bright cheek vs dark beard)

    float structure = smoothstep(0.045, 0.15, amp);
    // Floor 0.38: the target keeps NATURAL skin texture (visible pores on nose/
    // cheeks) — ours at 0.32 was over-smoothing past it into a poreless look.
    // 0.38 leaves more texture so the complexion reads real, like theirs.
    float keep = mix(0.38, 1.0, structure);

    // --- Mole / blemish / blotch remover (iOS-ONLY) ---
    // A mole is a distinct DARK spot, so the amp detector above mistakes it for
    // an "edge" and PROTECTS it — which is why ours kept moles sharp while the
    // target fades them. Fix: wherever a DARK detail (highL < 0) sits on BRIGHT
    // skin (lowL high), force `keep` down so the spot melts into the complexion.
    //   * gated to bright skin (onBrightSkin) so the dark BEARD/hair — dark
    //     detail on DARK surroundings — is spared, not blurred into mush;
    //   * scales with how dark the spot is, so moles/blotches fade while flat
    //     skin is untouched.
    // The old `defShadow` term did the OPPOSITE (it preserved dark detail) and
    // is removed — it was the main reason moles survived.
    float onBrightSkin = smoothstep(0.34, 0.55, lowL);
    // Onset 0.015: back off from 0.013 — the target KEEPS faint freckles/texture,
    // so only clearly-dark spots (real moles/blotches) should be removed.
    float darkSpot = smoothstep(0.015, 0.09, -highL) * onBrightSkin;
    keep = mix(keep, 0.08, darkSpot);

    // Preserve bright specular sheen LAST so a spot-remove can never dull the
    // face's natural highlights.
    float sheen = smoothstep(0.01, 0.05, highL);
    keep = max(keep, sheen * 0.85);

    float3 smoothed = low + high * keep;
    float3 c = mix(scene, smoothed, u.smooth * skin);

    // --- Clarity: even skin colour toward local tone at constant luma, then a
    // touch more saturation so skin stays warm/alive rather than gray.
    // iOS-ONLY TUNING (Android keeps 0.5): stronger tone-evening for the
    // reference look's uniform complexion (red patches, under-eye). ---
    float cl = dot(c, LUMA);
    float3 localTone = low + (cl - dot(low, LUMA));
    // 0.74: eased from 0.80 — the target's complexion is natural, not perfectly
    // uniform, so lighter tone-evening keeps some real variation in the skin.
    c = mix(c, localTone, u.clarity * skin * 0.74);
    float sl = dot(c, LUMA);
    // iOS-ONLY (Android keeps 0.08): a touch more saturation so the evened
    // skin keeps warm colour depth — part of the "alive" look.
    c = mix(float3(sl), c, 1.0 + u.clarity * face * 0.12);

    // --- Glow (in-mask part): only a faint skin whitening lives inside the
    // face gate; the visible glow is the GLOBAL bloom below. A flat brightness
    // lift here reads as a bright oval "layer" at the mask boundary.
    c += (float3(1.0) - c) * (u.glow * skin * 0.05);

    c = clamp(c, 0.0, 1.0);
    float3 outColor = mix(scene, c, face);

    // --- Sharp (structure): large-radius local contrast against the blurred
    // scene, gated OFF smoothed skin — hair strands, eyes, brows and the
    // background gain crisp definition while the retouched skin stays clean.
    // Runs before bloom/warmth so those global grades are not re-amplified. ---
    // iOS-ONLY TUNING (Android keeps 0.35): softer structure boost — the
    // iPhone feed is already crisp, and the full boost etched lashes/brows
    // into a hard, drawn-on outline. Trimmed further (0.28->0.18) so the
    // overall result reads as a NATURAL beauty pass, not an obvious filter.
    outColor += (outColor - low) * (u.sharp * 0.18 * (1.0 - skin * 0.8));
    outColor = clamp(outColor, 0.0, 1.0);

    // --- Glow (bloom): screen-blend the blurred scene's own soft highlights,
    // but ONLY onto pixels that are already lit (bloomGuard): the blur of a
    // bright background bleeds over dark subjects (black shirt, hair) and
    // screen-lifting them read as a milky fog over the whole frame — the
    // guard keeps blacks dense while light still spreads across walls/skin
    // like real optics.
    // iOS-ONLY TUNING (Android keeps knee 0.45 / strength 0.30, no guard):
    // the iPhone front camera delivers a flatter, brighter feed, so identical
    // constants read washed-out here — see ios-android-parity notes. ---
    float3 hl = clamp((low - 0.50) / 0.50, 0.0, 1.0);
    float bloomGuard = smoothstep(0.10, 0.45, dot(outColor, LUMA));
    outColor = 1.0 - (1.0 - outColor) * (1.0 - hl * (u.glow * 0.26 * bloomGuard));

    // --- Warmth: soft global grade, faded out on dark pixels so hair/brows/
    // beard keep their true colour. ---
    float darkGuard = smoothstep(0.08, 0.35, dot(outColor, LUMA));
    outColor += float3(0.045, 0.016, -0.026) * (u.warmth * darkGuard);

    // --- Fair skin (glow-driven): a GENTLE brightening/evening of skin-TONED
    // pixels across the whole frame — face, ears and neck alike, so there is
    // no mask seam. Detects warm skin hues (r > g > b, mid+ luma so hair/
    // beard/brows are untouched). Deliberately subtle (matches Android): the
    // target keeps most of the skin's own hue/saturation — heavier pale
    // targets turned the face into a flat, doll-like, cartoon mask. ---
    float wLuma = dot(outColor, LUMA);
    float skinHue = smoothstep(0.0, 0.12, outColor.r - outColor.g)
                  * smoothstep(0.0, 0.06, outColor.g - outColor.b)
                  * smoothstep(0.15, 0.40, wLuma);
    // iOS-ONLY: one notch whiter than the softest tuning (0.35/0.10/0.5) —
    // the reference reads a little paler — while staying far from the heavy
    // 0.70/0.24/1.0 that flattened the face into a doll mask.
    // Desat 0.36: enough graying to tame the skin's warm/orange SATURATION
    // (ours read too tan) while still leaving the healthy undertone for the
    // rosy tint below to turn pink. (Was 0.40 -> 0.32; 0.32 let too much orange
    // through, 0.40 flattened to a pale — 0.36 sits between.)
    float3 pale = mix(outColor, float3(wLuma), 0.36);
    pale += (float3(1.0) - pale) * 0.14;
    outColor = mix(outColor, pale, u.glow * 0.6 * skinHue);
    // A small multiplicative gain on the same skin-toned pixels keeps the face
    // reading bright and lit without shifting its (already matched) colour —
    // gain preserves hue/sat where a white-mix pales. Glow-gated like the rest
    // of the fair-skin block: every grade in this shader must vanish at
    // slider 0 so "Reset" means a raw frame.
    outColor *= 1.0 + 0.05 * skinHue * u.glow;

    // --- Dewy skin (iOS-ONLY): lift the LIT parts of the skin — cheekbones,
    // nose bridge, forehead — with a soft luminous highlight so the face reads
    // DEWY and 3D instead of flat/matte. This is the main quality that set the
    // reference apart from ours: same smoothing, but theirs GLOWS. Gated to
    // skin-toned pixels (skinHue) AND to already-bright ones (faceLit) so it
    // ADDS dimension to the highlights rather than washing the whole face flat —
    // mid-tones and shadowed skin stay put, which is what makes it read as sheen
    // and not a brightener. Screen-style toward white so it never clips hard.
    // Glow-driven (vanishes at Glow 0). ---
    float faceLit = smoothstep(0.50, 0.80, dot(outColor, LUMA));
    outColor += (float3(1.0) - outColor) * (faceLit * skinHue * u.glow * 0.10);

    // --- Life: global micro-contrast + vibrance so the image looks alive.
    // Fully sharp-driven (identity at 0; full-slider values match the old
    // 1.03+0.07 / 1.05+0.20 maxima). ---
    outColor = (outColor - 0.5) * (1.0 + u.sharp * 0.10) + 0.5;
    float gL = dot(outColor, LUMA);
    outColor = mix(float3(gL), outColor, 1.0 + u.sharp * 0.25);

    // --- Sharp (rich colour): deepen the shadows so dark hair, brows and
    // beard read dense and truly dark instead of lifted/washed.
    // iOS-ONLY TUNING (Android keeps 0.14): trimmed hard (0.10->0.05) — this
    // and the toe were giving the BEARD a harsh, over-contrasty edge vs the
    // reference's softer blend, and darkening the whole image. ---
    float shadow = 1.0 - smoothstep(0.04, 0.50, dot(outColor, LUMA));
    outColor *= 1.0 - u.sharp * 0.05 * shadow;

    // --- Rosy skin: the reference's fair skin carries a healthy RED/pink
    // undertone ("red via white"), not the neutral/sallow tone ours had. The
    // OLD code here did the opposite — it trimmed red and ADDED blue to cool
    // the skin, which is exactly why ours read colder. Now we lift red a touch
    // and trim a hint of blue on skin-toned pixels, so fair skin reads
    // pink-white like the target. Runs AFTER vibrance/contrast so they cannot
    // undo it. Glow-gated (companion of the glow-driven fair-skin whitening),
    // and rolled off on the very brightest skin (highlightGuard) so the nose/
    // forehead glints stay clean white instead of turning pink. iOS-ONLY. ---
    float highlightGuard = 1.0 - smoothstep(0.72, 0.92, dot(outColor, LUMA));
    float rosy = skinHue * u.glow * highlightGuard;
    // Rose is a COOL pink, NOT a warm orange. The previous version added red and
    // SUBTRACTED blue — which pushed skin yellow-orange, the opposite of the
    // target. The correct move for pink-white skin is to trim the yellow/green
    // (de-orange) with only a small red lift and a hair of blue back, so the
    // undertone reads rosy rather than tan.
    // Shifted from a cool PINK toward the target's GOLDEN-warm skin (ours read
    // pale/cool). The key move is dropping BLUE (b: +0.003 -> -0.016) — that
    // warms skin to gold WITHOUT the red/green boost that would tip it orange.
    // Green-trim eased (0.018 -> 0.010) so it's warm-golden, not magenta-pink;
    // red nudged up slightly for a healthy tan. Still gentle — a golden glow,
    // not a tan cast.
    outColor.r += 0.026 * rosy;
    outColor.g -= 0.010 * rosy;
    outColor.b -= 0.016 * rosy;

    // --- Bright: lift the whole frame toward the reference's light, airy
    // exposure. Two stages, both glow-driven:
    //   1. A multiplicative EXPOSURE gain — the real brightener. Because it
    //      scales (0*g = 0), hair/brows/pupils stay dense while skin and walls
    //      lift; the clamp lets near-white walls resolve to clean white. The
    //      old shader had only the gamma below (a ~2% nudge at default glow),
    //      which is why ours read darker than the target.
    //   2. A gentle gamma for midtone airiness on top.
    // iOS-ONLY TUNING. ---
    outColor *= 1.0 + 0.28 * u.glow;
    outColor = pow(clamp(outColor, 0.0, 1.0), float3(1.0 - 0.06 * u.glow));

    // --- Rich blacks: shadow toe applied LAST (the gamma above grays the
    // deepest tones; anything earlier gets re-lifted). Pulls sub-0.35-luma
    // tones toward true black so hair, brows, beard and pupils read dense.
    // Skin sits well above the toe, so the face brightness/whitening is
    // untouched. sharp-driven (up to 20% at full — iOS-ONLY TUNING dialled
    // back from 0.35: that heavy toe crushed shadows into HIGH contrast and
    // made the whole frame read dark/flat vs the reference's lower-contrast,
    // brighter look. 0.20 keeps hair/pupils dense without the harshness). Pure
    // ALU in this same pass — no extra texture reads, no performance cost. ---
    float toeL = dot(outColor, LUMA);
    outColor *= 1.0 - 0.20 * u.sharp * (1.0 - smoothstep(0.0, 0.35, toeL));

    return float4(clamp(outColor, 0.0, 1.0), 1.0);
}

// Final present with a light unsharp-mask sharpen -- port of Android SHARPEN_FS.
// texel = (1/srcWidth, 1/srcHeight); amount = strength.
// iOS-ONLY TUNING (Android has no gate): the difference signal is soft-gated
// so sub-~2%-amplitude detail — sensor noise on flat skin (the grainy nose) —
// is NOT amplified, while real edges (lashes, hair, features) pass through in
// full. A plain unsharp mask boosts noise and edges alike.
fragment float4 sharpen_fragment(FSOut in [[stage_in]],
                                 texture2d<float> tex [[texture(0)]],
                                 constant float2 &texel [[buffer(0)]],
                                 constant float &amount [[buffer(1)]]) {
    constexpr sampler s(filter::linear, address::clamp_to_edge);
    float3 c = tex.sample(s, in.uv).rgb;
    float3 blur = (
        tex.sample(s, in.uv + float2(texel.x, 0.0)).rgb +
        tex.sample(s, in.uv - float2(texel.x, 0.0)).rgb +
        tex.sample(s, in.uv + float2(0.0, texel.y)).rgb +
        tex.sample(s, in.uv - float2(0.0, texel.y)).rgb
    ) * 0.25;
    float3 delta = c - blur;
    float gate = smoothstep(0.004, 0.02, length(delta));
    c = c + delta * (amount * gate);
    return float4(clamp(c, 0.0, 1.0), 1.0);
}

// Straight texture copy (used where a plain blit is wanted).
fragment float4 passthrough_fragment(FSOut in [[stage_in]],
                                     texture2d<float> tex [[texture(0)]]) {
    constexpr sampler s(filter::linear, address::clamp_to_edge);
    return tex.sample(s, in.uv);
}

// ---------------------------------------------------------------------------
// Face reshape (liquify) -- port of Android FACE_RESHAPE_FS.
//
// Narrows the nose and jaw by pulling x toward an axis inside a soft circular
// region, and enlarges eyes by sampling nearer their centre. Centres/radii are
// in the sampled texture's uv space (y-down); radii are aspect-corrected
// (fraction of screen height). All-zero strengths (or zero counts) => plain copy.
// Arrays are fixed length (5 faces / 10 eyes) so the buffers are always bound;
// the loops break at the live counts.
// ---------------------------------------------------------------------------

struct ReshapeScalars {
    float aspect;    // width / height
    int faceCount;   // active nose faces
    int eyeCount;    // active eyes
    int jawCount;    // active jaw pinch points (face slim)
    float nose;      // nose-slim strength
    float slim;      // face-slim strength
    float eye;       // eye-enlarge strength
};

static inline float2 reshape_pullX(float2 uv, float2 c, float r, float axisX,
                                   float s, float aspect) {
    if (r <= 0.0 || s <= 0.0) return uv;
    float dist = length(float2((uv.x - c.x) * aspect, uv.y - c.y));
    if (dist < r) {
        float f = 1.0 - smoothstep(0.0, 1.0, dist / r);
        uv.x = axisX + (uv.x - axisX) * (1.0 + s * 0.30 * f);
    }
    return uv;
}

// Face slim: sum of localized jaw-contour pinches (port of Android slimJaw).
// Each pinch shifts sampling horizontally away from the face midline inside its
// own small radius, so the jaw/cheek silhouette moves INWARD and everything
// outside the thin band around the jawline (background, hair, the other side of
// the frame) stays put. Overlapping falloffs are normalized (divide by the
// weight sum when it exceeds 1) so adjacent pinches blend along the contour
// instead of stacking. jawD is the full-slider sampling shift.
static inline float2 reshape_slimJaw(float2 uv, int jawCount,
                                     constant float2 *jawP,
                                     constant float2 *jawD,
                                     constant float *jawR,
                                     float s, float aspect) {
    if (jawCount <= 0 || s <= 0.0) return uv;
    float2 sumD = float2(0.0);
    float sumW = 0.0;
    for (int i = 0; i < 16; i++) {
        if (i >= jawCount) break;
        float dist = length(float2((uv.x - jawP[i].x) * aspect, uv.y - jawP[i].y));
        float f = 1.0 - smoothstep(0.0, 1.0, dist / jawR[i]);
        sumD += jawD[i] * f;
        sumW += f;
    }
    return uv + sumD * (s / max(sumW, 1.0));
}

static inline float2 reshape_magnify(float2 uv, float2 c, float r, float s,
                                     float aspect) {
    if (r <= 0.0 || s <= 0.0) return uv;
    float2 d = uv - c;
    float dist = length(float2(d.x * aspect, d.y));
    if (dist < r) {
        float f = 1.0 - smoothstep(0.0, 1.0, dist / r);
        uv = c + d * (1.0 - s * 0.35 * f);
    }
    return uv;
}

fragment float4 reshape_fragment(FSOut in [[stage_in]],
                                 texture2d<float> tex [[texture(0)]],
                                 constant ReshapeScalars &sc [[buffer(0)]],
                                 constant float2 *noseC [[buffer(1)]],
                                 constant float  *noseR [[buffer(2)]],
                                 constant float2 *jawP  [[buffer(3)]],
                                 constant float2 *jawD  [[buffer(4)]],
                                 constant float  *jawR  [[buffer(5)]],
                                 constant float2 *eyes  [[buffer(6)]],
                                 constant float  *eyeR  [[buffer(7)]]) {
    constexpr sampler s(filter::linear, address::clamp_to_edge);
    float2 uv = in.uv;
    // Slim the jaw/cheek contour (below the eyes only; face-local).
    uv = reshape_slimJaw(uv, sc.jawCount, jawP, jawD, jawR, sc.slim, sc.aspect);
    for (int i = 0; i < 5; i++) {
        if (i >= sc.faceCount) break;
        uv = reshape_pullX(uv, noseC[i], noseR[i], noseC[i].x, sc.nose, sc.aspect);
    }
    for (int i = 0; i < 10; i++) {
        if (i >= sc.eyeCount) break;
        uv = reshape_magnify(uv, eyes[i], eyeR[i], sc.eye, sc.aspect);
    }
    return tex.sample(s, uv);
}

// ---------------------------------------------------------------------------
// Solid fill (face-mask polygons). Positions are pre-computed clip-space NDC.
// ---------------------------------------------------------------------------

vertex float4 solid_vertex(const device float2 *positions [[buffer(0)]],
                           uint vid [[vertex_id]]) {
    return float4(positions[vid], 0.0, 1.0);
}

fragment float4 solid_fragment(constant float4 &color [[buffer(0)]]) {
    return color;
}

// ---------------------------------------------------------------------------
// Textured sprite (mustache). Interleaved [pos.xy, uv.xy] in clip space.
// ---------------------------------------------------------------------------

struct SpriteVertex {
    float2 position;
    float2 uv;
};

struct SpriteOut {
    float4 position [[position]];
    float2 uv;
};

vertex SpriteOut sprite_vertex(const device SpriteVertex *verts [[buffer(0)]],
                               uint vid [[vertex_id]]) {
    SpriteOut out;
    out.position = float4(verts[vid].position, 0.0, 1.0);
    out.uv = verts[vid].uv;
    return out;
}

fragment float4 sprite_fragment(SpriteOut in [[stage_in]],
                                texture2d<float> tex [[texture(0)]]) {
    constexpr sampler s(filter::linear, address::clamp_to_edge);
    return tex.sample(s, in.uv);
}

// ---------------------------------------------------------------------------
// Point sprites (face-mesh debug dots).
// ---------------------------------------------------------------------------

struct PointOut {
    float4 position [[position]];
    float pointSize [[point_size]];
};

vertex PointOut point_vertex(const device float2 *positions [[buffer(0)]],
                             uint vid [[vertex_id]],
                             constant float &pointSize [[buffer(1)]]) {
    PointOut out;
    out.position = float4(positions[vid], 0.0, 1.0);
    out.pointSize = pointSize;
    return out;
}

fragment float4 point_fragment(constant float4 &color [[buffer(0)]],
                               float2 pointCoord [[point_coord]]) {
    // Carve each square point into a round dot (matches Android POINT_FS).
    float2 c = pointCoord - float2(0.5);
    if (dot(c, c) > 0.25) {
        discard_fragment();
    }
    return color;
}
