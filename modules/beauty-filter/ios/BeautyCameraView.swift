//
//  BeautyCameraView.swift
//  BeautyFilter
//
//  ExpoView that owns the capture session, the Metal preview, and the face
//  tracker, wiring them together. Counterpart to Android's BeautyCameraView.kt.
//
//  Data flow per frame (frame-locked two-stage pipeline, mirrors Android):
//    CameraController -> CVPixelBuffer
//        -> FaceTracker.analyze             (parks buffer, keep-only-latest)
//        -> [detect queue] Vision           (on that exact frame)
//        -> FaceTracker.onFrame             (frame + its landmarks, together)
//        -> MetalRenderer.submitFrame
//        -> mtkView.setNeedsDisplay
//
//  While Vision handles frame N the capture queue is already delivering N+1,
//  so throughput is the slower stage, not the sum (same shape as Android's
//  MediaPipe LIVE_STREAM pairing). Backpressure: the pending slot replaces an
//  unstarted frame, and alwaysDiscardsLateVideoFrames drops camera frames if
//  everything is busy — fps dips slightly, the warp never misaligns.
//
//  Remaining intentional difference from Android: no CPU frame copies or
//  bitmap pool (buffers arrive display-oriented and GPU-ready here), and no
//  manual rotation (the capture connection does it).
//

import ExpoModulesCore
import MetalKit
import AVFoundation
import UIKit

final class BeautyCameraView: ExpoView {

    private let camera = CameraController()
    private let faceTracker = FaceTracker()
    private var mtkView: MTKView!
    private var renderer: MetalRenderer?

    private var facingFront = true
    private var started = false

    // Preview frame-rate event (~1/s) for the JS fps indicator. Keep in sync
    // with Android's BeautyCameraView.countFrameForFps.
    private let onFps = EventDispatcher()
    private var fpsWindowStart: CFTimeInterval = 0
    private var fpsWindowFrames = 0

    required init(appContext: AppContext? = nil) {
        super.init(appContext: appContext)
        setupMetal()
        wire()
    }

    // MARK: - Setup

    private func setupMetal() {
        guard let device = MTLCreateSystemDefaultDevice() else {
            // Simulator has no Metal-capable GPU for the camera path; the view
            // simply stays black. Real devices always have a Metal device.
            NSLog("[BeautyFilter] no Metal device available")
            return
        }
        let view = MTKView(frame: bounds, device: device)
        view.colorPixelFormat = .bgra8Unorm
        view.framebufferOnly = false           // we blit into the drawable
        view.isPaused = true                   // render on demand...
        view.enableSetNeedsDisplay = true      // ...via setNeedsDisplay()
        view.autoResizeDrawable = true
        addSubview(view)
        self.mtkView = view

        let renderer = MetalRenderer(device: device)
        self.renderer = renderer
        view.delegate = renderer
        // Prime the drawable-size dependent targets.
        renderer?.mtkView(view, drawableSizeWillChange: view.drawableSize)
    }

    private func wire() {
        // Stage 1: camera frames go to the tracker's keep-only-latest slot.
        camera.onPixelBuffer = { [weak self] buffer in
            self?.faceTracker.analyze(buffer)
        }
        // Stage 2 output: frame + its own landmarks, delivered together.
        faceTracker.onFrame = { [weak self] buffer, faces in
            guard let self = self else { return }
            self.renderer?.submitFrame(buffer, faces: faces)
            DispatchQueue.main.async { self.mtkView?.setNeedsDisplay() }
            self.countFrameForFps()
        }
    }

    /// Called once per delivered frame (detect queue); emits onFps ~1/s.
    private func countFrameForFps() {
        let now = CACurrentMediaTime()
        if fpsWindowStart == 0 { fpsWindowStart = now }
        fpsWindowFrames += 1
        let elapsed = now - fpsWindowStart
        if elapsed >= 1.0 {
            let fps = Int((Double(fpsWindowFrames) / elapsed).rounded())
            fpsWindowStart = now
            fpsWindowFrames = 0
            onFps(["fps": fps])
        }
    }

    // MARK: - Lifecycle

    override func didMoveToWindow() {
        super.didMoveToWindow()
        if window != nil {
            startCameraIfPermitted()
        } else {
            camera.stop()
        }
    }

    private func startCameraIfPermitted() {
        guard renderer != nil, !started else { return }
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            started = true
            camera.start(front: facingFront)
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                guard granted, let self = self else { return }
                DispatchQueue.main.async {
                    self.started = true
                    self.camera.start(front: self.facingFront)
                }
            }
        default:
            // Denied/restricted: leave the preview blank. The JS layer is
            // responsible for surfacing permission UI.
            NSLog("[BeautyFilter] camera permission not granted")
        }
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        mtkView?.frame = bounds
    }

    // MARK: - Props (mirror BeautyFilterModule / Android setters)

    func setFacing(_ facing: String) {
        let front = facing != "back"
        guard front != facingFront else { return }
        facingFront = front
        camera.setFront(front)
    }

    func setSmoothing(_ value: Float) {
        renderer?.smoothing = max(0, min(1, value))
    }

    func setGlow(_ value: Float) {
        renderer?.glow = max(0, min(1, value))
    }

    func setClarity(_ value: Float) {
        renderer?.clarity = max(0, min(1, value))
    }

    func setWarmth(_ value: Float) {
        renderer?.warmth = max(0, min(1, value))
    }

    func setSharpness(_ value: Float) {
        renderer?.sharpness = max(0, min(1, value))
    }

    func setEyeEnlarge(_ value: Float) {
        renderer?.eyeEnlarge = max(0, min(1, value))
    }

    func setNoseSlim(_ value: Float) {
        renderer?.noseSlim = max(0, min(1, value))
    }

    func setFaceSlim(_ value: Float) {
        renderer?.faceSlim = max(0, min(1, value))
    }

    func setMustache(_ enabled: Bool) {
        renderer?.mustacheEnabled = enabled
    }

    func setFaceMesh(_ enabled: Bool) {
        renderer?.faceMeshEnabled = enabled
    }

    // MARK: - Capture

    func takePicture(_ promise: Promise) {
        guard let renderer = renderer else {
            promise.reject("E_CAPTURE", "Renderer unavailable")
            return
        }
        renderer.captureNextFrame { image in
            guard let data = image.jpegData(compressionQuality: 0.92) else {
                promise.reject("E_CAPTURE", "Failed to encode JPEG")
                return
            }
            let filename = "filtercam_\(UUID().uuidString).jpg"
            let url = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent(filename)
            do {
                try data.write(to: url, options: .atomic)
                promise.resolve(url.absoluteString) // file:// URL
            } catch {
                promise.reject("E_CAPTURE", "Failed to save capture: \(error.localizedDescription)")
            }
        }
        // Ensure a frame renders so the capture callback fires.
        DispatchQueue.main.async { self.mtkView?.setNeedsDisplay() }
    }
}
