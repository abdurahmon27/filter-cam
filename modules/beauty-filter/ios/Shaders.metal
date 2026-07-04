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
    float3 high = scene - low;
    float amp = length(high);
    float edge = smoothstep(0.055, 0.22, amp);
    float keep = mix(0.28, 1.0, edge);
    float3 smoothed = low + high * keep;
    float3 c = mix(scene, smoothed, u.smooth * skin);

    // --- Clarity: even skin colour toward local tone at constant luma, then a
    // touch more saturation so skin stays warm/alive rather than gray. ---
    float cl = dot(c, LUMA);
    float3 localTone = low + (cl - dot(low, LUMA));
    c = mix(c, localTone, u.clarity * skin * 0.5);
    float sl = dot(c, LUMA);
    c = mix(float3(sl), c, 1.0 + u.clarity * face * 0.08);

    // --- Glow (in-mask part): only a faint skin whitening lives inside the
    // face gate; the visible glow is the GLOBAL bloom below. A flat brightness
    // lift here reads as a bright oval "layer" at the mask boundary. ---
    c += (float3(1.0) - c) * (u.glow * skin * 0.05);

    c = clamp(c, 0.0, 1.0);
    float3 outColor = mix(scene, c, face);

    // --- Sharp (structure): large-radius local contrast against the blurred
    // scene, gated OFF smoothed skin — hair strands, eyes, brows and the
    // background gain crisp definition while the retouched skin stays clean.
    // Runs before bloom/warmth so those global grades are not re-amplified. ---
    outColor += (outColor - low) * (u.sharp * 0.35 * (1.0 - skin * 0.8));
    outColor = clamp(outColor, 0.0, 1.0);

    // --- Glow (bloom): screen-blend the blurred scene's own soft highlights
    // over the WHOLE frame (port of Android). Light spreads where light
    // already exists, so there is no geometric boundary to see. ---
    float3 hl = clamp((low - 0.45) / 0.55, 0.0, 1.0);
    outColor = 1.0 - (1.0 - outColor) * (1.0 - hl * (u.glow * 0.30));

    // --- Warmth: soft global grade, faded out on dark pixels so hair/brows/
    // beard keep their true colour. ---
    float darkGuard = smoothstep(0.08, 0.35, dot(outColor, LUMA));
    outColor += float3(0.045, 0.016, -0.026) * (u.warmth * darkGuard);

    // --- Fair skin (glow-driven): pale, porcelain whitening of skin-TONED
    // pixels across the whole frame — face, ears and neck alike, so there is
    // no mask seam. Detects warm skin hues (r > g > b, mid+ luma so hair/
    // beard/brows are untouched) and fades them toward a desaturated,
    // white-lifted version. ---
    float wLuma = dot(outColor, LUMA);
    float skinHue = smoothstep(0.0, 0.12, outColor.r - outColor.g)
                  * smoothstep(0.0, 0.06, outColor.g - outColor.b)
                  * smoothstep(0.15, 0.40, wLuma);
    float3 pale = mix(outColor, float3(wLuma), 0.70);
    pale += (float3(1.0) - pale) * 0.24;
    outColor = mix(outColor, pale, u.glow * 1.0 * skinHue);
    // A small multiplicative gain on the same skin-toned pixels keeps the face
    // reading bright and lit without shifting its (already matched) colour —
    // gain preserves hue/sat where a white-mix pales.
    outColor *= 1.0 + 0.08 * skinHue;

    // --- Life: global micro-contrast + vibrance so the image looks alive.
    // sharp scales both up for a punchier, more saturated grade. ---
    outColor = (outColor - 0.5) * (1.03 + u.sharp * 0.07) + 0.5;
    float gL = dot(outColor, LUMA);
    outColor = mix(float3(gL), outColor, 1.05 + u.sharp * 0.20);

    // --- Sharp (rich colour): deepen the shadows so dark hair, brows and
    // beard read dense and truly dark instead of lifted/washed. ---
    float shadow = 1.0 - smoothstep(0.04, 0.50, dot(outColor, LUMA));
    outColor *= 1.0 - u.sharp * 0.14 * shadow;

    // --- Skin neutralize: runs AFTER vibrance/contrast so they cannot re-add
    // the warm cast the fair-skin grade removed. Trims the red-over-green
    // excess on skin-toned pixels (porcelain, not orange) and returns a touch
    // of blue to cool the tone. ---
    float warmCast = outColor.r - outColor.g;
    outColor.r -= warmCast * 0.18 * skinHue;
    outColor.b += warmCast * 0.10 * skinHue;

    // --- Bright: gentle global gamma lift so the frame reads light and airy
    // (whiter walls/skin, the reference look). A gamma curve pins black and
    // white, so hair keeps its depth and highlights never clip. ---
    outColor = pow(clamp(outColor, 0.0, 1.0), float3(0.92));

    // --- Rich blacks: shadow toe applied LAST (the gamma above grays the
    // deepest tones ~25%; anything earlier gets re-lifted). Pulls sub-0.35-
    // luma tones toward true black so hair, brows, beard and pupils read
    // dense. Skin sits well above the toe, so the face brightness/whitening
    // is untouched. Pure ALU in this same pass — no extra texture reads, no
    // performance cost. ---
    float toeL = dot(outColor, LUMA);
    outColor *= 1.0 - 0.25 * (1.0 - smoothstep(0.0, 0.35, toeL));

    return float4(clamp(outColor, 0.0, 1.0), 1.0);
}

// Final present with a light unsharp-mask sharpen -- port of Android SHARPEN_FS.
// texel = (1/srcWidth, 1/srcHeight); amount = strength.
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
    c = c + (c - blur) * amount;
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
