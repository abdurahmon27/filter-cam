//
//  MetalRenderer.swift
//  BeautyFilter
//
//  The Metal render pipeline. Direct port of Android's BeautyRenderer +
//  passes. Per frame:
//
//   1. camera texture -> scene target (center-crop so it holds exactly what's
//      displayed)
//   2. scene -> quarter-res two-pass gaussian blur (blurA h, blurB v)
//   3. TWO quarter-res feathered face masks, for every tracked face:
//        - skin mask (maskA): oval minus eyes/brows/lips  -> gates smoothing
//        - face mask (maskC): full oval                   -> gates tone/light
//      (maskB is the shared feather scratch)
//   4. composite -> outputTex: edge-aware smoothing + glow/clarity/warmth + a
//      global life grade (mix by the masks)
//   5. face-reshape (liquify) outputTex -> finalTex: eye-enlarge / nose-slim /
//      face-slim (a plain blit when no reshape is active)
//   6. mustache sprite + optional mesh dots -> finalTex (per face)
//   7. sharpen blit finalTex -> drawable (and -> staging for WYSIWYG capture)
//
//  Because CameraController hands us an already-upright, already-mirrored buffer,
//  there is no rotation math here (unlike Android): we only center-crop.
//
//  Coordinate space: landmarks arrive display-normalized (0..1, y-down), which
//  maps directly to the fullscreen uv space (uv.y = 0 == display top). `toNdc`
//  converts to clip space (y-up) for the mask/sprite/point geometry. Metal
//  texture rows are top-down, so capture readback needs NO vertical flip.
//

import Foundation
import Metal
import MetalKit
import CoreVideo
import UIKit

final class MetalRenderer: NSObject, MTKViewDelegate {

    // MARK: - Externally-driven state (the beauty sliders, 0..1)
    var smoothing: Float = 0
    var glow: Float = 0
    var clarity: Float = 0
    var warmth: Float = 0
    var sharpness: Float = 0
    var eyeEnlarge: Float = 0
    var noseSlim: Float = 0
    var faceSlim: Float = 0
    var mustacheEnabled = false
    var faceMeshEnabled = false

    private static let maxFaces = 5
    private static let maxEyes = 10 // 5 faces x 2 eyes
    private static let maxJaw = 16  // shared pool of jaw pinch points (~10/face)

    // Face-slim tuning, in fractions of the inter-eye distance. Keep in sync
    // with Android's FaceReshapePass (SLIM_AMP / SLIM_RADIUS). 0.042 chosen by
    // feel on device: the previous 0.14 max was caricature-strong (its 30%
    // point is the new 100%).
    private static let slimAmp: Float = 0.042
    private static let slimRadius: Float = 0.55

    private let device: MTLDevice
    private let commandQueue: MTLCommandQueue
    private var textureCache: CVMetalTextureCache!

    // Pipelines
    private var cameraPipeline: MTLRenderPipelineState!
    private var blurPipeline: MTLRenderPipelineState!
    private var compositePipeline: MTLRenderPipelineState!
    private var reshapePipeline: MTLRenderPipelineState!
    private var passthroughPipeline: MTLRenderPipelineState!
    private var sharpenPipeline: MTLRenderPipelineState!
    private var solidPipeline: MTLRenderPipelineState!
    private var spritePipeline: MTLRenderPipelineState!
    private var pointPipeline: MTLRenderPipelineState!

    // Offscreen render targets
    private var sceneTex: MTLTexture!
    private var blurA: MTLTexture!
    private var blurB: MTLTexture!
    private var maskA: MTLTexture!   // skin mask (smoothing)
    private var maskB: MTLTexture!   // shared feather scratch
    private var maskC: MTLTexture!   // face mask (tone/light)
    private var outputTex: MTLTexture!   // composited beauty frame (private)
    private var finalTex: MTLTexture!    // after reshape + overlays (private)
    private var stagingTex: MTLTexture!  // shared, sharpened, for capture readback

    private var mustacheTex: MTLTexture?

    // View sizing (pixels)
    private var viewWidth: Int = 1
    private var viewHeight: Int = 1

    // Crop of the upright camera frame that is visible (see updateCrop).
    private var cropOffX: Float = 0
    private var cropOffY: Float = 0
    private var cropScaleX: Float = 1
    private var cropScaleY: Float = 1

    // Reshape uniform scratch (fixed-size so the buffers are always bound).
    private var reNoseC = [SIMD2<Float>](repeating: .zero, count: maxFaces)
    private var reNoseR = [Float](repeating: 0, count: maxFaces)
    private var reJawP = [SIMD2<Float>](repeating: .zero, count: maxJaw) // jaw pinch anchors
    private var reJawD = [SIMD2<Float>](repeating: .zero, count: maxJaw) // full-slider sampling shift
    private var reJawR = [Float](repeating: 0, count: maxJaw)            // pinch falloff radii
    private var reEyes = [SIMD2<Float>](repeating: .zero, count: maxEyes)
    private var reEyeR = [Float](repeating: 0, count: maxEyes)

    // Latest frame + its paired landmarks, swapped in together (frame-locked;
    // written from the camera sample queue).
    private let bufferLock = NSLock()
    private var latestPixelBuffer: CVPixelBuffer?
    private var faces: [FaceLandmarks] = []

    // Capture
    private let captureLock = NSLock()
    private var pendingCapture: ((UIImage) -> Void)?

    // MARK: - Uniform structs (must match Shaders.metal layouts)

    private struct BeautyUniforms {
        var smooth: Float
        var glow: Float
        var clarity: Float
        var warmth: Float
        var sharp: Float
    }

    private struct ReshapeScalars {
        var aspect: Float
        var faceCount: Int32
        var eyeCount: Int32
        var jawCount: Int32
        var nose: Float
        var slim: Float
        var eye: Float
    }

    // MARK: - Init

    init?(device: MTLDevice) {
        guard let queue = device.makeCommandQueue() else { return nil }
        self.device = device
        self.commandQueue = queue
        super.init()

        var cache: CVMetalTextureCache?
        CVMetalTextureCacheCreate(kCFAllocatorDefault, nil, device, nil, &cache)
        guard let cache = cache else { return nil }
        self.textureCache = cache

        do {
            try buildPipelines()
        } catch {
            NSLog("[BeautyFilter] pipeline build failed: \(error)")
            return nil
        }
        mustacheTex = MustacheTexture.make(device: device)
    }

    private func buildPipelines() throws {
        let library = try makeShaderLibrary()

        func fn(_ name: String) throws -> MTLFunction {
            guard let f = library.makeFunction(name: name) else {
                throw NSError(domain: "BeautyFilter", code: 1,
                              userInfo: [NSLocalizedDescriptionKey: "missing shader \(name)"])
            }
            return f
        }

        func makePipeline(_ vertex: String, _ fragment: String,
                          blend: BlendMode = .none) throws -> MTLRenderPipelineState {
            let desc = MTLRenderPipelineDescriptor()
            desc.vertexFunction = try fn(vertex)
            desc.fragmentFunction = try fn(fragment)
            let attachment = desc.colorAttachments[0]!
            attachment.pixelFormat = .bgra8Unorm
            switch blend {
            case .none:
                attachment.isBlendingEnabled = false
            case .premultiplied:
                attachment.isBlendingEnabled = true
                attachment.rgbBlendOperation = .add
                attachment.alphaBlendOperation = .add
                attachment.sourceRGBBlendFactor = .one
                attachment.sourceAlphaBlendFactor = .one
                attachment.destinationRGBBlendFactor = .oneMinusSourceAlpha
                attachment.destinationAlphaBlendFactor = .oneMinusSourceAlpha
            case .straightAlpha:
                attachment.isBlendingEnabled = true
                attachment.rgbBlendOperation = .add
                attachment.alphaBlendOperation = .add
                attachment.sourceRGBBlendFactor = .sourceAlpha
                attachment.sourceAlphaBlendFactor = .sourceAlpha
                attachment.destinationRGBBlendFactor = .oneMinusSourceAlpha
                attachment.destinationAlphaBlendFactor = .oneMinusSourceAlpha
            }
            return try device.makeRenderPipelineState(descriptor: desc)
        }

        cameraPipeline = try makePipeline("fullscreen_vertex", "camera_fragment")
        blurPipeline = try makePipeline("fullscreen_vertex", "blur_fragment")
        compositePipeline = try makePipeline("fullscreen_vertex", "composite_fragment")
        reshapePipeline = try makePipeline("fullscreen_vertex", "reshape_fragment")
        passthroughPipeline = try makePipeline("fullscreen_vertex", "passthrough_fragment")
        sharpenPipeline = try makePipeline("fullscreen_vertex", "sharpen_fragment")
        solidPipeline = try makePipeline("solid_vertex", "solid_fragment")
        spritePipeline = try makePipeline("sprite_vertex", "sprite_fragment", blend: .premultiplied)
        pointPipeline = try makePipeline("point_vertex", "point_fragment", blend: .straightAlpha)
    }

    private enum BlendMode { case none, premultiplied, straightAlpha }

    /// Loads the shader library without assuming HOW the pod was built: a
    /// precompiled default.metallib is used when one exists (pod bundle, then
    /// app bundle), otherwise the Shaders.metal SOURCE that the podspec ships
    /// as a resource is compiled at runtime (~100ms, once). A static pod
    /// target does not reliably produce a metallib — loading one blindly is
    /// how the preview black-screened: library load threw, init? returned
    /// nil, and the camera session never started.
    private func makeShaderLibrary() throws -> MTLLibrary {
        // A metallib is only "ours" if it holds our entry points — the app (or
        // another pod) may ship its own default.metallib.
        let probe = "fullscreen_vertex"
        if let lib = try? device.makeDefaultLibrary(bundle: Bundle(for: MetalRenderer.self)),
           lib.makeFunction(name: probe) != nil {
            return lib
        }
        if let lib = device.makeDefaultLibrary(), lib.makeFunction(name: probe) != nil {
            return lib
        }
        for bundle in [Bundle(for: MetalRenderer.self), Bundle.main] {
            if let url = bundle.url(forResource: "Shaders", withExtension: "metal"),
               let source = try? String(contentsOf: url, encoding: .utf8) {
                return try device.makeLibrary(source: source, options: nil)
            }
        }
        throw NSError(domain: "BeautyFilter", code: 1, userInfo: [
            NSLocalizedDescriptionKey:
                "no default.metallib and no Shaders.metal resource in any bundle"
        ])
    }

    // MARK: - Inputs

    /// Deliver a camera frame together with the landmarks detected on that
    /// exact frame (frame-locked, mirrors Android's submitFrame). Empty faces
    /// = no face detected.
    func submitFrame(_ buffer: CVPixelBuffer, faces: [FaceLandmarks]) {
        bufferLock.lock()
        latestPixelBuffer = buffer
        self.faces = faces
        bufferLock.unlock()
    }

    /// Capture the next rendered frame as a UIImage. Callback fires on a
    /// background queue.
    func captureNextFrame(_ callback: @escaping (UIImage) -> Void) {
        captureLock.lock()
        pendingCapture = callback
        captureLock.unlock()
    }

    // MARK: - MTKViewDelegate

    func mtkView(_ view: MTKView, drawableSizeWillChange size: CGSize) {
        viewWidth = max(Int(size.width), 1)
        viewHeight = max(Int(size.height), 1)
        allocateTargets()
    }

    func draw(in view: MTKView) {
        guard sceneTex != nil, finalTex != nil else { return }

        // Grab the latest camera buffer AND its paired landmarks in one
        // snapshot, so the warp always matches the pixels it is applied to.
        bufferLock.lock()
        let buffer = latestPixelBuffer
        let lms = faces
        bufferLock.unlock()
        guard let pixelBuffer = buffer,
              let cameraCV = makeCameraTexture(pixelBuffer),
              let cameraTexture = CVMetalTextureGetTexture(cameraCV) else { return }

        updateCrop(cameraWidth: cameraTexture.width, cameraHeight: cameraTexture.height)

        guard let commandBuffer = commandQueue.makeCommandBuffer(),
              let drawable = view.currentDrawable else { return }

        let adjust = BeautyUniforms(smooth: clamp01(smoothing), glow: clamp01(glow),
                                    clarity: clamp01(clarity), warmth: clamp01(warmth),
                                    sharp: clamp01(sharpness))

        // 1. camera -> scene
        renderFullscreen(commandBuffer, target: sceneTex, pipeline: cameraPipeline, clear: false) { encoder in
            var crop = SIMD4<Float>(cropOffX, cropOffY, cropScaleX, cropScaleY)
            encoder.setFragmentTexture(cameraTexture, index: 0)
            encoder.setFragmentBytes(&crop, length: MemoryLayout<SIMD4<Float>>.stride, index: 0)
        }

        // 2. blur scene (quarter res): horizontal into blurA, vertical into blurB
        let qw = Float(blurA.width), qh = Float(blurA.height)
        blur(commandBuffer, source: sceneTex, target: blurA, dir: SIMD2<Float>(2.6 / qw, 0))
        blur(commandBuffer, source: blurA, target: blurB, dir: SIMD2<Float>(0, 2.6 / qh))

        // 3. two feathered face masks (skin -> maskA, face -> maskC)
        renderMasks(commandBuffer, faces: lms)

        // 4. composite -> outputTex
        renderFullscreen(commandBuffer, target: outputTex, pipeline: compositePipeline, clear: false) { encoder in
            var u = adjust
            encoder.setFragmentTexture(sceneTex, index: 0)
            encoder.setFragmentTexture(blurB, index: 1)
            encoder.setFragmentTexture(maskA, index: 2)  // skin
            encoder.setFragmentTexture(maskC, index: 3)  // face
            encoder.setFragmentBytes(&u, length: MemoryLayout<BeautyUniforms>.stride, index: 0)
        }

        // 5. face reshape (liquify) outputTex -> finalTex (plain blit if inactive)
        renderReshape(commandBuffer, faces: lms)

        // 6. overlays onto finalTex (per face)
        if mustacheEnabled { for f in lms { drawMustache(commandBuffer, f) } }
        if faceMeshEnabled { for f in lms { drawFaceMesh(commandBuffer, f) } }

        // Capture: sharpen finalTex -> shared staging (WYSIWYG, includes sharpen).
        captureLock.lock()
        let capture = pendingCapture
        pendingCapture = nil
        captureLock.unlock()
        if let capture = capture {
            renderFullscreen(commandBuffer, target: stagingTex, pipeline: sharpenPipeline, clear: false) { encoder in
                bindSharpen(encoder, source: finalTex)
            }
            commandBuffer.addCompletedHandler { [weak self] _ in
                guard let self = self, let image = self.readbackImage() else { return }
                capture(image)
            }
        }

        // 7. sharpen blit finalTex -> drawable
        let rpd = MTLRenderPassDescriptor()
        rpd.colorAttachments[0].texture = drawable.texture
        rpd.colorAttachments[0].loadAction = .clear
        rpd.colorAttachments[0].clearColor = MTLClearColor(red: 0, green: 0, blue: 0, alpha: 1)
        rpd.colorAttachments[0].storeAction = .store
        if let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: rpd) {
            encoder.setRenderPipelineState(sharpenPipeline)
            bindSharpen(encoder, source: finalTex)
            encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
            encoder.endEncoding()
        }

        commandBuffer.present(drawable)
        // Keep the CVMetalTexture alive until the GPU is done sampling it.
        commandBuffer.addCompletedHandler { _ in _ = cameraCV }
        commandBuffer.commit()
    }

    // MARK: - Passes

    private func makeCameraTexture(_ buffer: CVPixelBuffer) -> CVMetalTexture? {
        let w = CVPixelBufferGetWidth(buffer)
        let h = CVPixelBufferGetHeight(buffer)
        var cvTex: CVMetalTexture?
        let status = CVMetalTextureCacheCreateTextureFromImage(
            kCFAllocatorDefault, textureCache, buffer, nil,
            .bgra8Unorm, w, h, 0, &cvTex)
        guard status == kCVReturnSuccess else { return nil }
        return cvTex
    }

    /// Runs a fullscreen-triangle pass into `target`. The `configure` closure
    /// binds textures/uniforms.
    private func renderFullscreen(_ commandBuffer: MTLCommandBuffer,
                                  target: MTLTexture,
                                  pipeline: MTLRenderPipelineState,
                                  clear: Bool,
                                  scissor: MTLScissorRect? = nil,
                                  configure: (MTLRenderCommandEncoder) -> Void) {
        let rpd = MTLRenderPassDescriptor()
        rpd.colorAttachments[0].texture = target
        // A scissored draw must keep the pixels outside the rect.
        rpd.colorAttachments[0].loadAction = clear ? .clear : (scissor != nil ? .load : .dontCare)
        rpd.colorAttachments[0].clearColor = MTLClearColor(red: 0, green: 0, blue: 0, alpha: 1)
        rpd.colorAttachments[0].storeAction = .store
        guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: rpd) else { return }
        encoder.setRenderPipelineState(pipeline)
        if let scissor = scissor {
            encoder.setScissorRect(scissor)
        }
        configure(encoder)
        encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
        encoder.endEncoding()
    }

    private func blur(_ commandBuffer: MTLCommandBuffer,
                      source: MTLTexture, target: MTLTexture, dir: SIMD2<Float>) {
        renderFullscreen(commandBuffer, target: target, pipeline: blurPipeline, clear: false) { encoder in
            var d = dir
            encoder.setFragmentTexture(source, index: 0)
            encoder.setFragmentBytes(&d, length: MemoryLayout<SIMD2<Float>>.stride, index: 0)
        }
    }

    private func bindSharpen(_ encoder: MTLRenderCommandEncoder, source: MTLTexture) {
        var texel = SIMD2<Float>(1 / Float(max(source.width, 1)), 1 / Float(max(source.height, 1)))
        // iOS-ONLY TUNING (Android keeps 0.85 + sharp * 0.75): Android's big
        // base exists because it UPSCALES the frame to the screen and needs
        // acutance restored; on iOS the 1440-wide capture DOWNSCALES to the
        // display, so the same base over-etches (drawn-on eyelashes) and
        // re-amplifies sensor noise (grainy nose).
        // Trimmed the always-on base 0.35->0.18 and the slope 0.55->0.42: the
        // reference reads SOFTER, and the old base made the frame look noisy/
        // over-detailed even at sharp=0. Real edges still get acutance.
        var amount: Float = 0.18 + clamp01(sharpness) * 0.42
        encoder.setFragmentTexture(source, index: 0)
        encoder.setFragmentBytes(&texel, length: MemoryLayout<SIMD2<Float>>.stride, index: 0)
        encoder.setFragmentBytes(&amount, length: MemoryLayout<Float>.stride, index: 1)
    }

    /// Builds the two feathered masks. maskA = skin (oval minus eyes/brows/lips);
    /// maskC = full face oval. maskB is the shared feather scratch. Mirrors
    /// Android's FaceMaskPass (OVAL_INSET 1.0, feather 4 texels, quarter res).
    private func renderMasks(_ commandBuffer: MTLCommandBuffer, faces: [FaceLandmarks]) {
        let mw = Float(maskA.width), mh = Float(maskA.height)

        // --- skin mask: oval MINUS eyes/brows/lips ---
        drawMaskFans(commandBuffer, target: maskA) { encoder in
            for f in faces {
                self.fill(encoder, ring: f.faceOval, value: 1.0, inflate: 1.0)
                self.fill(encoder, ring: f.leftEye, value: 0.0, inflate: 1.5)
                self.fill(encoder, ring: f.rightEye, value: 0.0, inflate: 1.5)
                self.fill(encoder, ring: f.leftBrow, value: 0.0, inflate: 1.3)
                self.fill(encoder, ring: f.rightBrow, value: 0.0, inflate: 1.3)
                self.fill(encoder, ring: f.outerLips, value: 0.0, inflate: 1.1)
            }
        }
        blur(commandBuffer, source: maskA, target: maskB, dir: SIMD2<Float>(4 / mw, 0))
        blur(commandBuffer, source: maskB, target: maskA, dir: SIMD2<Float>(0, 4 / mh))

        // --- face mask: full oval only ---
        drawMaskFans(commandBuffer, target: maskC) { encoder in
            for f in faces {
                self.fill(encoder, ring: f.faceOval, value: 1.0, inflate: 1.0)
            }
        }
        blur(commandBuffer, source: maskC, target: maskB, dir: SIMD2<Float>(4 / mw, 0))
        blur(commandBuffer, source: maskB, target: maskC, dir: SIMD2<Float>(0, 4 / mh))
    }

    /// Clears `target` to black and runs `body` to fill mask polygons into it.
    private func drawMaskFans(_ commandBuffer: MTLCommandBuffer,
                              target: MTLTexture,
                              body: (MTLRenderCommandEncoder) -> Void) {
        let rpd = MTLRenderPassDescriptor()
        rpd.colorAttachments[0].texture = target
        rpd.colorAttachments[0].loadAction = .clear
        rpd.colorAttachments[0].clearColor = MTLClearColor(red: 0, green: 0, blue: 0, alpha: 1)
        rpd.colorAttachments[0].storeAction = .store
        guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: rpd) else { return }
        encoder.setRenderPipelineState(solidPipeline)
        body(encoder)
        encoder.endEncoding()
    }

    private func fill(_ encoder: MTLRenderCommandEncoder,
                      ring: [CGPoint], value: Float, inflate: Float) {
        let verts = fanTriangles(ring, inflate: inflate)
        guard verts.count >= 3 else { return }
        let byteLength = verts.count * MemoryLayout<SIMD2<Float>>.stride
        guard let buf = device.makeBuffer(bytes: verts, length: byteLength, options: .storageModeShared) else { return }
        var color = SIMD4<Float>(value, value, value, 1)
        encoder.setVertexBuffer(buf, offset: 0, index: 0)
        encoder.setFragmentBytes(&color, length: MemoryLayout<SIMD4<Float>>.stride, index: 0)
        encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: verts.count)
    }

    /// Face-reshape warp outputTex -> finalTex. Computes per-face nose/jaw/eye
    /// centres exactly like Android's FaceReshapePass, then runs the warp shader.
    /// With no reshape active it degrades to a plain blit.
    private func renderReshape(_ commandBuffer: MTLCommandBuffer, faces: [FaceLandmarks]) {
        let aspect = Float(viewWidth) / Float(max(viewHeight, 1))
        var faceCount = 0
        var eyeCount = 0
        var jawCount = 0
        let reshapesActive = eyeEnlarge > 0 || noseSlim > 0 || faceSlim > 0

        // Warp-region bounding box in vis coords (y-down), grown region by
        // region. Radii are aspect-corrected (dist uses (dx*aspect, dy)), so
        // the x extent is r/aspect and the y extent is r. Mirrors Android's
        // FaceReshapePass scissor.
        var minU: Float = .greatestFiniteMagnitude
        var minV: Float = .greatestFiniteMagnitude
        var maxU: Float = -.greatestFiniteMagnitude
        var maxV: Float = -.greatestFiniteMagnitude
        func grow(_ c: SIMD2<Float>, _ r: Float) {
            minU = min(minU, c.x - r / aspect)
            maxU = max(maxU, c.x + r / aspect)
            minV = min(minV, c.y - r)
            maxV = max(maxV, c.y + r)
        }

        if reshapesActive {
            for f in faces {
                if faceCount >= Self.maxFaces { break }
                let (le, leR) = ringCenterVis(f.leftEye)
                let (re, reR) = ringCenterVis(f.rightEye)
                let dx = (le.x - re.x) * aspect
                let dy = le.y - re.y
                let scale = (dx * dx + dy * dy).squareRoot()
                let mid = (le.x + re.x) / 2

                // Centre sits slightly ABOVE the nose centre (vis space is
                // y-down) and the radius grows by the same amount: the
                // slimming band reaches a little up the bridge while coverage
                // below the tip (nostrils) is unchanged. Mirrors Android.
                reNoseC[faceCount] = SIMD2<Float>(visX(f.noseCenter), visY(f.noseCenter) - scale * 0.08)
                reNoseR[faceCount] = scale * 0.50
                if noseSlim > 0 { grow(reNoseC[faceCount], reNoseR[faceCount]) }
                faceCount += 1

                if eyeEnlarge > 0 {
                    if eyeCount < Self.maxEyes { reEyes[eyeCount] = le; reEyeR[eyeCount] = leR; grow(le, leR); eyeCount += 1 }
                    if eyeCount < Self.maxEyes { reEyes[eyeCount] = re; reEyeR[eyeCount] = reR; grow(re, reR); eyeCount += 1 }
                }

                // Face slim (see reshape_slimJaw): pinch anchors pinned to the
                // face-oval points below the eye line, so only the band along
                // the jaw/cheek silhouette moves. Weights fade in below the
                // eyes (wy) and fade out at the midline (wx — the chin is never
                // pulled sideways); mirrors Android's FaceReshapePass, which
                // reads fixed jawline mesh indices.
                if faceSlim > 0, scale > 1e-4,
                   let chin = f.faceOval.max(by: { $0.y < $1.y }) {
                    let eyeY = (le.y + re.y) / 2
                    let chinY = visY(chin)
                    let span = abs(chinY - eyeY)   // eye-to-chin height (vis, y-down)
                    if span > 1e-4 {
                        var left: [(p: SIMD2<Float>, w: Float)] = []
                        var right: [(p: SIMD2<Float>, w: Float)] = []
                        for pt in f.faceOval {
                            let px = visX(pt)
                            let py = visY(pt)
                            let t = (py - eyeY) / span
                            let wy = Self.smoothstep(0.10, 0.40, t)
                            let wx = Self.smoothstep(0.10, 0.30, abs(px - mid) * aspect / scale)
                            let w = wy * wx
                            if w < 0.02 { continue }
                            if px < mid {
                                left.append((SIMD2<Float>(px, py), w))
                            } else {
                                right.append((SIMD2<Float>(px, py), w))
                            }
                        }
                        for side in [left, right] {
                            let alongJaw = side.sorted { $0.p.y < $1.p.y }
                            for (p, w) in Self.pickEvenly(alongJaw, 5) {
                                if jawCount >= Self.maxJaw { break }
                                reJawP[jawCount] = p
                                // Full-slider sampling shift, AWAY from the
                                // midline (sampling outward moves the
                                // silhouette inward).
                                let dir: Float = p.x < mid ? -1 : 1
                                reJawD[jawCount] = SIMD2<Float>(dir * scale * Self.slimAmp * w / aspect, 0)
                                reJawR[jawCount] = scale * Self.slimRadius
                                grow(p, reJawR[jawCount])
                                jawCount += 1
                            }
                        }
                    }
                }
            }
        }

        // Nose uniforms only feed the shader when that warp is active (slim is
        // gated by jawCount).
        let faceN = noseSlim > 0 ? faceCount : 0

        // 1) Cheap 1-tap blit of the whole frame. The warp shader evaluates
        // every pinch/magnify region per fragment — far too expensive to run
        // over the whole frame, so it is confined to the face box below.
        renderFullscreen(commandBuffer, target: finalTex, pipeline: passthroughPipeline, clear: false) { encoder in
            encoder.setFragmentTexture(outputTex, index: 0)
        }

        // 2) The warp, scissored to the face's bounding box (plus margin) —
        // its cost scales with the face size, not the frame size.
        if faceN == 0 && eyeCount == 0 && jawCount == 0 { return }
        let pad: Float = 0.02
        let w = Float(finalTex.width)
        let h = Float(finalTex.height)
        let x0 = Int(max(0, min(w, (minU - pad) * w)))
        let y0 = Int(max(0, min(h, (minV - pad) * h)))
        let x1 = Int(max(0, min(w, (maxU + pad) * w)))
        let y1 = Int(max(0, min(h, (maxV + pad) * h)))
        if x1 <= x0 || y1 <= y0 { return }
        let scissor = MTLScissorRect(x: x0, y: y0, width: x1 - x0, height: y1 - y0)

        renderFullscreen(commandBuffer, target: finalTex, pipeline: reshapePipeline,
                         clear: false, scissor: scissor) { encoder in
            var sc = ReshapeScalars(aspect: aspect, faceCount: Int32(faceN),
                                    eyeCount: Int32(eyeCount), jawCount: Int32(jawCount),
                                    nose: clamp01(noseSlim), slim: clamp01(faceSlim),
                                    eye: clamp01(eyeEnlarge))
            encoder.setFragmentTexture(outputTex, index: 0)
            encoder.setFragmentBytes(&sc, length: MemoryLayout<ReshapeScalars>.stride, index: 0)
            encoder.setFragmentBytes(&reNoseC, length: Self.maxFaces * MemoryLayout<SIMD2<Float>>.stride, index: 1)
            encoder.setFragmentBytes(&reNoseR, length: Self.maxFaces * MemoryLayout<Float>.stride, index: 2)
            encoder.setFragmentBytes(&reJawP, length: Self.maxJaw * MemoryLayout<SIMD2<Float>>.stride, index: 3)
            encoder.setFragmentBytes(&reJawD, length: Self.maxJaw * MemoryLayout<SIMD2<Float>>.stride, index: 4)
            encoder.setFragmentBytes(&reJawR, length: Self.maxJaw * MemoryLayout<Float>.stride, index: 5)
            encoder.setFragmentBytes(&reEyes, length: Self.maxEyes * MemoryLayout<SIMD2<Float>>.stride, index: 6)
            encoder.setFragmentBytes(&reEyeR, length: Self.maxEyes * MemoryLayout<Float>.stride, index: 7)
        }
    }

    /// GLSL-style smoothstep.
    private static func smoothstep(_ e0: Float, _ e1: Float, _ x: Float) -> Float {
        let t = min(max((x - e0) / (e1 - e0), 0), 1)
        return t * t * (3 - 2 * t)
    }

    /// Up to `k` items spread evenly across `items` (all of them when fewer).
    private static func pickEvenly<T>(_ items: [T], _ k: Int) -> [T] {
        guard items.count > k, k > 1 else { return items }
        return (0..<k).map { items[$0 * (items.count - 1) / (k - 1)] }
    }

    private func drawMustache(_ commandBuffer: MTLCommandBuffer, _ lms: FaceLandmarks) {
        guard let mustacheTex = mustacheTex else { return }

        // Work in view pixels (y-down) exactly like Android, so the rotation and
        // scale are not distorted by the view aspect ratio.
        let anchor = CGPoint(
            x: (pxX(lms.noseBottom) + pxX(lms.upperLipTop)) / 2,
            y: (pxY(lms.noseBottom) + pxY(lms.upperLipTop)) / 2)
        let l = CGPoint(x: pxX(lms.mouthLeft), y: pxY(lms.mouthLeft))
        let r = CGPoint(x: pxX(lms.mouthRight), y: pxY(lms.mouthRight))

        let mouthW = hypot(r.x - l.x, r.y - l.y)
        guard mouthW >= 1 else { return }
        let w = mouthW * 1.55
        let h = w * MustacheTexture.aspect
        let angle = atan2(r.y - l.y, r.x - l.x)
        let ca = cos(angle), sa = sin(angle)

        // Corner order for a triangle strip: TL, TR, BL, BR. uv v=0 == top of texture.
        let corners: [(CGFloat, CGFloat, Float, Float)] = [
            (-w / 2, -h / 2, 0, 0),
            ( w / 2, -h / 2, 1, 0),
            (-w / 2,  h / 2, 0, 1),
            ( w / 2,  h / 2, 1, 1),
        ]
        var verts = [SpriteVertex]()
        verts.reserveCapacity(4)
        for c in corners {
            let x = anchor.x + c.0 * ca - c.1 * sa
            let y = anchor.y + c.0 * sa + c.1 * ca
            let ndc = SIMD2<Float>(Float(2 * x / CGFloat(viewWidth) - 1),
                                   Float(1 - 2 * y / CGFloat(viewHeight)))
            verts.append(SpriteVertex(position: ndc, uv: SIMD2<Float>(c.2, c.3)))
        }

        let byteLength = verts.count * MemoryLayout<SpriteVertex>.stride
        guard let buf = device.makeBuffer(bytes: verts, length: byteLength, options: .storageModeShared) else { return }

        loadEncoder(commandBuffer, target: finalTex) { encoder in
            encoder.setRenderPipelineState(spritePipeline)
            encoder.setVertexBuffer(buf, offset: 0, index: 0)
            encoder.setFragmentTexture(mustacheTex, index: 0)
            encoder.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4)
        }
    }

    private func drawFaceMesh(_ commandBuffer: MTLCommandBuffer, _ lms: FaceLandmarks) {
        let pts = lms.allPoints
        guard !pts.isEmpty else { return }
        var verts = [SIMD2<Float>]()
        verts.reserveCapacity(pts.count)
        for p in pts { verts.append(toNdc(p)) }
        let byteLength = verts.count * MemoryLayout<SIMD2<Float>>.stride
        guard let buf = device.makeBuffer(bytes: verts, length: byteLength, options: .storageModeShared) else { return }

        var pointSize = max(Float(viewWidth) * 0.012, 4)
        var color = SIMD4<Float>(0.15, 1.0, 0.55, 1.0)

        loadEncoder(commandBuffer, target: finalTex) { encoder in
            encoder.setRenderPipelineState(pointPipeline)
            encoder.setVertexBuffer(buf, offset: 0, index: 0)
            encoder.setVertexBytes(&pointSize, length: MemoryLayout<Float>.stride, index: 1)
            encoder.setFragmentBytes(&color, length: MemoryLayout<SIMD4<Float>>.stride, index: 0)
            encoder.drawPrimitives(type: .point, vertexStart: 0, vertexCount: verts.count)
        }
    }

    /// A render pass that preserves the target's existing contents (loadAction
    /// .load) so overlays composite on top of the reshaped frame.
    private func loadEncoder(_ commandBuffer: MTLCommandBuffer,
                             target: MTLTexture,
                             body: (MTLRenderCommandEncoder) -> Void) {
        let rpd = MTLRenderPassDescriptor()
        rpd.colorAttachments[0].texture = target
        rpd.colorAttachments[0].loadAction = .load
        rpd.colorAttachments[0].storeAction = .store
        guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: rpd) else { return }
        body(encoder)
        encoder.endEncoding()
    }

    // MARK: - Geometry helpers

    private struct SpriteVertex {
        var position: SIMD2<Float>
        var uv: SIMD2<Float>
    }

    private func clamp01(_ v: Float) -> Float { max(0, min(1, v)) }

    /// Landmark (display-normalized, y-down) -> clip-space NDC (y-up), through
    /// the visible crop. Mirrors Android's ndcX/ndcY.
    private func toNdc(_ p: CGPoint) -> SIMD2<Float> {
        SIMD2<Float>(2 * visX(p) - 1, 1 - 2 * visY(p))
    }

    // Landmark -> visible-normalized coords (0..1, y-down) inside the crop.
    // Same space as the fullscreen uv, so reshape centres use these directly.
    private func visX(_ p: CGPoint) -> Float { (Float(p.x) - cropOffX) / cropScaleX }
    private func visY(_ p: CGPoint) -> Float { (Float(p.y) - cropOffY) / cropScaleY }

    // Landmark -> visible view pixels (y-down). Mirrors Android's pixelX/pixelY.
    private func pxX(_ p: CGPoint) -> CGFloat { CGFloat(visX(p)) * CGFloat(viewWidth) }
    private func pxY(_ p: CGPoint) -> CGFloat { CGFloat(visY(p)) * CGFloat(viewHeight) }

    /// Returns a ring's centre in visible-normalized coords (y-down) plus its
    /// aspect-corrected radius, matching Android FaceReshapePass.ringCenter
    /// (radius = half x-span * aspect * 1.7).
    private func ringCenterVis(_ ring: [CGPoint]) -> (SIMD2<Float>, Float) {
        guard !ring.isEmpty else { return (SIMD2<Float>(0, 0), 0) }
        var cx: Float = 0, cy: Float = 0
        var minX = Float.greatestFiniteMagnitude, maxX = -Float.greatestFiniteMagnitude
        for p in ring {
            let vx = visX(p), vy = visY(p)
            cx += vx; cy += vy
            if vx < minX { minX = vx }
            if vx > maxX { maxX = vx }
        }
        let n = Float(ring.count)
        cx /= n; cy /= n
        let aspect = Float(viewWidth) / Float(max(viewHeight, 1))
        let r = (maxX - minX) / 2 * aspect * 1.7
        return (SIMD2<Float>(cx, cy), r)
    }

    /// Triangle-list fan around the ring centroid (Metal has no triangle-fan
    /// primitive), optionally inflated outward from the centroid.
    private func fanTriangles(_ ring: [CGPoint], inflate: Float) -> [SIMD2<Float>] {
        guard ring.count >= 3 else { return [] }
        var cx: CGFloat = 0, cy: CGFloat = 0
        for p in ring { cx += p.x; cy += p.y }
        cx /= CGFloat(ring.count); cy /= CGFloat(ring.count)
        let centroid = CGPoint(x: cx, y: cy)
        let cNdc = toNdc(centroid)
        let inf = CGFloat(inflate)

        func inflated(_ p: CGPoint) -> CGPoint {
            CGPoint(x: centroid.x + (p.x - centroid.x) * inf,
                    y: centroid.y + (p.y - centroid.y) * inf)
        }

        var verts = [SIMD2<Float>]()
        verts.reserveCapacity(ring.count * 3)
        let n = ring.count
        for i in 0..<n {
            let a = inflated(ring[i])
            let b = inflated(ring[(i + 1) % n])
            verts.append(cNdc)
            verts.append(toNdc(a))
            verts.append(toNdc(b))
        }
        return verts
    }

    private func updateCrop(cameraWidth: Int, cameraHeight: Int) {
        guard cameraWidth > 0, cameraHeight > 0 else {
            cropOffX = 0; cropOffY = 0; cropScaleX = 1; cropScaleY = 1
            return
        }
        // Buffer is already upright, so no rotation swap (unlike Android).
        let camAspect = Float(cameraWidth) / Float(cameraHeight)
        let viewAspect = Float(viewWidth) / Float(viewHeight)
        if camAspect > viewAspect {
            cropScaleX = viewAspect / camAspect
            cropScaleY = 1
        } else {
            cropScaleX = 1
            cropScaleY = camAspect / viewAspect
        }
        cropOffX = (1 - cropScaleX) / 2
        cropOffY = (1 - cropScaleY) / 2
    }

    // MARK: - Targets

    private func allocateTargets() {
        sceneTex = makeTarget(viewWidth, viewHeight, shared: false)
        outputTex = makeTarget(viewWidth, viewHeight, shared: false)
        finalTex = makeTarget(viewWidth, viewHeight, shared: false)
        stagingTex = makeTarget(viewWidth, viewHeight, shared: true)

        let qw = max(viewWidth / 4, 1)
        let qh = max(viewHeight / 4, 1)
        blurA = makeTarget(qw, qh, shared: false)
        blurB = makeTarget(qw, qh, shared: false)
        maskA = makeTarget(qw, qh, shared: false)
        maskB = makeTarget(qw, qh, shared: false)
        maskC = makeTarget(qw, qh, shared: false)
    }

    private func makeTarget(_ w: Int, _ h: Int, shared: Bool) -> MTLTexture {
        let desc = MTLTextureDescriptor.texture2DDescriptor(
            pixelFormat: .bgra8Unorm, width: w, height: h, mipmapped: false)
        desc.usage = [.renderTarget, .shaderRead]
        // Staging must be CPU-readable for capture; others stay GPU-private.
        desc.storageMode = shared ? .shared : .private
        return device.makeTexture(descriptor: desc)!
    }

    // MARK: - Capture readback

    private func readbackImage() -> UIImage? {
        let w = stagingTex.width, h = stagingTex.height
        let bytesPerRow = w * 4
        var data = [UInt8](repeating: 0, count: bytesPerRow * h)
        stagingTex.getBytes(&data, bytesPerRow: bytesPerRow,
                            from: MTLRegionMake2D(0, 0, w, h), mipmapLevel: 0)

        let colorSpace = CGColorSpaceCreateDeviceRGB()
        // BGRA bytes -> premultipliedFirst + byteOrder32Little.
        let bitmapInfo = CGImageAlphaInfo.premultipliedFirst.rawValue |
            CGBitmapInfo.byteOrder32Little.rawValue
        guard let ctx = CGContext(data: &data, width: w, height: h,
                                  bitsPerComponent: 8, bytesPerRow: bytesPerRow,
                                  space: colorSpace, bitmapInfo: bitmapInfo),
              let cgImage = ctx.makeImage() else { return nil }
        // Metal texture row 0 is the top row, so no vertical flip is needed
        // (Android's glReadPixels path had to flip).
        return UIImage(cgImage: cgImage)
    }
}
