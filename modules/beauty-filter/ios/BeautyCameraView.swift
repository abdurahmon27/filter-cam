//
//  BeautyCameraView.swift
//  BeautyFilter
//
//  ExpoView that owns the capture session, the Metal preview, and the face
//  tracker, wiring them together. Counterpart to Android's BeautyCameraView.kt.
//
//  Data flow per frame:
//    CameraController -> CVPixelBuffer
//        -> MetalRenderer.updatePixelBuffer  (drawn on the next setNeedsDisplay)
//        -> FaceTracker.track                (Vision, async, keep-only-latest)
//    FaceTracker -> [FaceLandmarks] -> MetalRenderer.setFaces
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
        camera.onPixelBuffer = { [weak self] buffer in
            guard let self = self else { return }
            self.renderer?.updatePixelBuffer(buffer)
            self.faceTracker.track(pixelBuffer: buffer)
            DispatchQueue.main.async { self.mtkView?.setNeedsDisplay() }
        }
        faceTracker.onLandmarks = { [weak self] faces in
            self?.renderer?.setFaces(faces)
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
