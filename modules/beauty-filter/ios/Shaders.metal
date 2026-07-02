//
//  Shaders.metal
//  BeautyFilter
//
//  Metal port of the Android BeautyRenderer GLSL programs. The pass pipeline is
//  the same conceptually:
//    camera texture -> scene, two-pass gaussian blur at quarter res,
//    face-mask render target, composite mix(scene, smoothed, mask*strength),
//    then mustache sprite, then optional face-mesh dots.
//
//  Coordinate conventions (IMPORTANT):
//    - Metal clip space: y = +1 is the TOP of the render target.
//    - Metal texture sampling: v = 0 is the TOP row of the texture.
//    The `fullscreen_vertex` below maps its generated uv so that uv.y = 0 lands
//    on clip-space y = +1, keeping "sampled image up == displayed image up".
//    Swift-side helpers (`toNdc`) convert display-normalized (y-down) landmark
//    coordinates into this same clip space.
//

#include <metal_stdlib>
using namespace metal;

// ---------------------------------------------------------------------------
// Fullscreen passes (camera, blur, composite, blit)
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
// e.g. (1.5/width, 0) horizontal then (0, 1.5/height) vertical.
fragment float4 blur_fragment(FSOut in [[stage_in]],
                              texture2d<float> tex [[texture(0)]],
                              constant float2 &dir [[buffer(0)]]) {
    constexpr sampler s(filter::linear, address::clamp_to_edge);
    float4 c = tex.sample(s, in.uv) * 0.227027;
    c += (tex.sample(s, in.uv + dir * 1.3846) + tex.sample(s, in.uv - dir * 1.3846)) * 0.316216;
    c += (tex.sample(s, in.uv + dir * 3.2308) + tex.sample(s, in.uv - dir * 3.2308)) * 0.070270;
    return c;
}

// Composite: mix the sharp scene with the smoothed (blurred) result only inside
// the face mask, scaled by `strength` (the `smoothing` prop). Mirrors the
// Android COMPOSITE_FS exactly, including the mild brighten/lift.
fragment float4 composite_fragment(FSOut in [[stage_in]],
                                   texture2d<float> sceneTex [[texture(0)]],
                                   texture2d<float> blurTex  [[texture(1)]],
                                   texture2d<float> maskTex  [[texture(2)]],
                                   constant float &strength [[buffer(0)]]) {
    constexpr sampler s(filter::linear, address::clamp_to_edge);
    float3 scene = sceneTex.sample(s, in.uv).rgb;
    float3 blurred = blurTex.sample(s, in.uv).rgb;
    float m = maskTex.sample(s, in.uv).r * strength;
    float3 smoothed = blurred + (scene - blurred) * 0.35;
    smoothed = min(smoothed * 1.04 + 0.015, 1.0);
    return float4(mix(scene, smoothed, m), 1.0);
}

// Straight texture copy (final blit of the offscreen output to the drawable).
fragment float4 passthrough_fragment(FSOut in [[stage_in]],
                                     texture2d<float> tex [[texture(0)]]) {
    constexpr sampler s(filter::linear, address::clamp_to_edge);
    return tex.sample(s, in.uv);
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
