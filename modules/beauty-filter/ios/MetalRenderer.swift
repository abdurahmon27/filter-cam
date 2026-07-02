//
//  MetalRenderer.swift
//  BeautyFilter
//
//  The Metal render pipeline. Direct conceptual port of Android's BeautyRenderer:
//
//   1. camera texture -> scene target (with center-crop so the target holds
//      exactly what is displayed)
//   2. scene -> quarter-res two-pass gaussian blur (blurA h, blurB v)
//   3. face-mask target: fill face oval (white), punch out eyes/brows/lips
//      (black), then blur for a soft edge
//   4. composite -> output target: mix(scene, smoothed, mask * strength)
//   5. mustache sprite -> output (alpha blended)
//   6. optional face-mesh dots -> output
//   7. blit output -> drawable
//
//  Because CameraController hands us an already-upright, already-mirrored buffer,
//  there is no rotation math here (unlike Android): we only center-crop.
//
//  Coordinate space: landmarks arrive display-normalized (0..1, y-down). Metal
//  clip space is y-up with +1 at the top, so `toNdc` flips y. Metal texture rows
//  are top-down, so capture readback needs NO vertical flip (Android's GL path
//  did).
//

import Foundation
import Metal
import MetalKit
import CoreVideo
import UIKit

final class MetalRenderer: NSObject, MTKViewDelegate {

    // MARK: - Externally-driven state
    var smoothing: Float = 0.6
    var mustacheEnabled = false
    var faceMeshEnabled = false

    private let device: MTLDevice
    private let commandQueue: MTLCommandQueue
    private var textureCache: CVMetalTextureCache!

    // Pipelines
    private var cameraPipeline: MTLRenderPipelineState!
    private var blurPipeline: MTLRenderPipelineState!
    private var compositePipeline: MTLRenderPipelineState!
    private var blitPipeline: MTLRenderPipelineState!
    private var solidPipeline: MTLRenderPipelineState!
    private var spritePipeline: MTLRenderPipelineState!
    private var pointPipeline: MTLRenderPipelineState!

    // Offscreen render targets
    private var sceneTex: MTLTexture!
    private var blurA: MTLTexture!
    private var blurB: MTLTexture!
    private var maskA: MTLTexture!
    private var maskB: MTLTexture!
    private var outputTex: MTLTexture!   // final composited frame (private)
    private var stagingTex: MTLTexture!  // shared copy for capture readback

    private var mustacheTex: MTLTexture?

    // View sizing (pixels)
    private var viewWidth: Int = 1
    private var viewHeight: Int = 1

    // Crop of the upright camera frame that is visible (see updateCrop).
    private var cropOffX: Float = 0
    private var cropOffY: Float = 0
    private var cropScaleX: Float = 1
    private var cropScaleY: Float = 1

    // Latest inputs, guarded by locks (written from camera / vision queues).
    private let bufferLock = NSLock()
    private var latestPixelBuffer: CVPixelBuffer?

    private let landmarkLock = NSLock()
    private var landmarks: FaceLandmarks?
    private var landmarksAt: CFTimeInterval = 0

    // Capture
    private let captureLock = NSLock()
    private var pendingCapture: ((UIImage) -> Void)?

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
        // Shaders.metal is compiled into the pod's default.metallib.
        let library = try device.makeDefaultLibrary(bundle: Bundle(for: MetalRenderer.self))

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
        blitPipeline = try makePipeline("fullscreen_vertex", "passthrough_fragment")
        solidPipeline = try makePipeline("solid_vertex", "solid_fragment")
        spritePipeline = try makePipeline("sprite_vertex", "sprite_fragment", blend: .premultiplied)
        pointPipeline = try makePipeline("point_vertex", "point_fragment", blend: .straightAlpha)
    }

    private enum BlendMode { case none, premultiplied, straightAlpha }

    // MARK: - Inputs

    func updatePixelBuffer(_ buffer: CVPixelBuffer) {
        bufferLock.lock()
        latestPixelBuffer = buffer
        bufferLock.unlock()
    }

    func setLandmarks(_ lms: FaceLandmarks?) {
        landmarkLock.lock()
        landmarks = lms
        landmarksAt = (lms != nil) ? CACurrentMediaTime() : 0
        landmarkLock.unlock()
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
        guard let sceneTex = sceneTex else { return }

        // Grab latest camera buffer -> Metal texture.
        bufferLock.lock()
        let buffer = latestPixelBuffer
        bufferLock.unlock()
        guard let pixelBuffer = buffer,
              let cameraCV = makeCameraTexture(pixelBuffer),
              let cameraTexture = CVMetalTextureGetTexture(cameraCV) else { return }

        updateCrop(cameraWidth: cameraTexture.width, cameraHeight: cameraTexture.height)

        // Snapshot landmarks (used only if fresh, matching Android's 400ms gate).
        landmarkLock.lock()
        let fresh = (CACurrentMediaTime() - landmarksAt) < 0.4
        let lms = fresh ? landmarks : nil
        landmarkLock.unlock()

        guard let commandBuffer = commandQueue.makeCommandBuffer(),
              let drawable = view.currentDrawable else { return }

        // 1. camera -> scene
        renderFullscreen(commandBuffer, target: sceneTex, pipeline: cameraPipeline, clear: false) { encoder in
            var crop = SIMD4<Float>(cropOffX, cropOffY, cropScaleX, cropScaleY)
            encoder.setFragmentTexture(cameraTexture, index: 0)
            encoder.setFragmentBytes(&crop, length: MemoryLayout<SIMD4<Float>>.stride, index: 0)
        }

        // 2. blur scene (quarter res): horizontal into blurA, vertical into blurB
        let qw = Float(blurA.width), qh = Float(blurA.height)
        blur(commandBuffer, source: sceneTex, target: blurA, dir: SIMD2<Float>(1.5 / qw, 0))
        blur(commandBuffer, source: blurA, target: blurB, dir: SIMD2<Float>(0, 1.5 / qh))

        // 3. face mask (quarter res) + soften
        renderMask(commandBuffer, landmarks: lms)

        // 4. composite -> output
        renderFullscreen(commandBuffer, target: outputTex, pipeline: compositePipeline, clear: false) { encoder in
            var strength = max(0, min(1, smoothing))
            encoder.setFragmentTexture(sceneTex, index: 0)
            encoder.setFragmentTexture(blurB, index: 1)
            encoder.setFragmentTexture(maskA, index: 2)
            encoder.setFragmentBytes(&strength, length: MemoryLayout<Float>.stride, index: 0)
        }

        // 5. mustache + 6. mesh (drawn onto output, preserving contents)
        if let lms = lms, mustacheEnabled { drawMustache(commandBuffer, lms) }
        if let lms = lms, faceMeshEnabled { drawFaceMesh(commandBuffer, lms) }

        // Capture: blit output -> shared staging before the final blit.
        captureLock.lock()
        let capture = pendingCapture
        pendingCapture = nil
        captureLock.unlock()
        if let capture = capture {
            if let blit = commandBuffer.makeBlitCommandEncoder() {
                blit.copy(from: outputTex, sourceSlice: 0, sourceLevel: 0,
                          sourceOrigin: MTLOrigin(x: 0, y: 0, z: 0),
                          sourceSize: MTLSize(width: outputTex.width, height: outputTex.height, depth: 1),
                          to: stagingTex, destinationSlice: 0, destinationLevel: 0,
                          destinationOrigin: MTLOrigin(x: 0, y: 0, z: 0))
                blit.endEncoding()
            }
            commandBuffer.addCompletedHandler { [weak self] _ in
                guard let self = self, let image = self.readbackImage() else { return }
                capture(image)
            }
        }

        // 7. blit output -> drawable
        let rpd = MTLRenderPassDescriptor()
        rpd.colorAttachments[0].texture = drawable.texture
        rpd.colorAttachments[0].loadAction = .clear
        rpd.colorAttachments[0].clearColor = MTLClearColor(red: 0, green: 0, blue: 0, alpha: 1)
        rpd.colorAttachments[0].storeAction = .store
        if let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: rpd) {
            encoder.setRenderPipelineState(blitPipeline)
            encoder.setFragmentTexture(outputTex, index: 0)
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
                                  configure: (MTLRenderCommandEncoder) -> Void) {
        let rpd = MTLRenderPassDescriptor()
        rpd.colorAttachments[0].texture = target
        rpd.colorAttachments[0].loadAction = clear ? .clear : .dontCare
        rpd.colorAttachments[0].clearColor = MTLClearColor(red: 0, green: 0, blue: 0, alpha: 1)
        rpd.colorAttachments[0].storeAction = .store
        guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: rpd) else { return }
        encoder.setRenderPipelineState(pipeline)
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

    private func renderMask(_ commandBuffer: MTLCommandBuffer, landmarks lms: FaceLandmarks?) {
        // Clear mask to black, then fill polygons.
        let rpd = MTLRenderPassDescriptor()
        rpd.colorAttachments[0].texture = maskA
        rpd.colorAttachments[0].loadAction = .clear
        rpd.colorAttachments[0].clearColor = MTLClearColor(red: 0, green: 0, blue: 0, alpha: 1)
        rpd.colorAttachments[0].storeAction = .store
        guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: rpd) else { return }
        encoder.setRenderPipelineState(solidPipeline)

        if let lms = lms {
            // Face oval white; features punched out black (inflated slightly so
            // the soft-edge blur doesn't smooth right up to the eyes/lips).
            fill(encoder, ring: lms.faceOval, value: 1.0, inflate: 1.0)
            fill(encoder, ring: lms.leftEye, value: 0.0, inflate: 1.6)
            fill(encoder, ring: lms.rightEye, value: 0.0, inflate: 1.6)
            fill(encoder, ring: lms.leftBrow, value: 0.0, inflate: 1.4)
            fill(encoder, ring: lms.rightBrow, value: 0.0, inflate: 1.4)
            fill(encoder, ring: lms.outerLips, value: 0.0, inflate: 1.1)
        }
        encoder.endEncoding()

        // Soften edges: blur maskA -> maskB (h) -> maskA (v), ending in maskA.
        let mw = Float(maskA.width), mh = Float(maskA.height)
        blur(commandBuffer, source: maskA, target: maskB, dir: SIMD2<Float>(2 / mw, 0))
        blur(commandBuffer, source: maskB, target: maskA, dir: SIMD2<Float>(0, 2 / mh))
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

        loadEncoder(commandBuffer, target: outputTex) { encoder in
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

        loadEncoder(commandBuffer, target: outputTex) { encoder in
            encoder.setRenderPipelineState(pointPipeline)
            encoder.setVertexBuffer(buf, offset: 0, index: 0)
            encoder.setVertexBytes(&pointSize, length: MemoryLayout<Float>.stride, index: 1)
            encoder.setFragmentBytes(&color, length: MemoryLayout<SIMD4<Float>>.stride, index: 0)
            encoder.drawPrimitives(type: .point, vertexStart: 0, vertexCount: verts.count)
        }
    }

    /// A render pass that preserves the target's existing contents (loadAction
    /// .load) so overlays composite on top of the composited frame.
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

    /// Landmark (display-normalized, y-down) -> clip-space NDC (y-up), through
    /// the visible crop. Mirrors Android's toNdcX/toNdcY.
    private func toNdc(_ p: CGPoint) -> SIMD2<Float> {
        let sx = (Float(p.x) - cropOffX) / cropScaleX
        let sy = (Float(p.y) - cropOffY) / cropScaleY
        return SIMD2<Float>(2 * sx - 1, 1 - 2 * sy)
    }

    // Landmark -> visible view pixels (y-down). Mirrors Android's px/py.
    private func pxX(_ p: CGPoint) -> CGFloat {
        CGFloat((Float(p.x) - cropOffX) / cropScaleX) * CGFloat(viewWidth)
    }
    private func pxY(_ p: CGPoint) -> CGFloat {
        CGFloat((Float(p.y) - cropOffY) / cropScaleY) * CGFloat(viewHeight)
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
        stagingTex = makeTarget(viewWidth, viewHeight, shared: true)

        let qw = max(viewWidth / 4, 1)
        let qh = max(viewHeight / 4, 1)
        blurA = makeTarget(qw, qh, shared: false)
        blurB = makeTarget(qw, qh, shared: false)
        maskA = makeTarget(qw, qh, shared: false)
        maskB = makeTarget(qw, qh, shared: false)
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
