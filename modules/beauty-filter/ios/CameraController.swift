//
//  CameraController.swift
//  BeautyFilter
//
//  Owns the AVCaptureSession and delivers BGRA CVPixelBuffers. Configures the
//  video connection so the delivered buffers are ALREADY upright (portrait) and,
//  for the front camera, ALREADY mirrored -- so both the Metal preview and the
//  Vision landmarks work in one consistent display space, and the front preview
//  is a natural selfie mirror (matching Android).
//
//  Contrast with Android: CameraX hands out sensor-oriented frames and the
//  Kotlin side rotates the texture + bitmap by hand. On iOS we let the capture
//  connection do the rotate/mirror, which removes the fiddly PREVIEW_ROTATION
//  offset math entirely.
//

import Foundation
import AVFoundation

final class CameraController: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate {

    /// Delivered on `sampleQueue` for every frame. The buffer is upright BGRA,
    /// front-mirrored when `isFront` is true.
    var onPixelBuffer: ((CVPixelBuffer) -> Void)?

    private let session = AVCaptureSession()
    private let videoOutput = AVCaptureVideoDataOutput()
    private let sampleQueue = DispatchQueue(label: "beautyfilter.camera.samples")
    private let configQueue = DispatchQueue(label: "beautyfilter.camera.config")

    private var currentInput: AVCaptureDeviceInput?
    private(set) var isFront = true

    override init() {
        super.init()
        videoOutput.videoSettings = [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA
        ]
        videoOutput.alwaysDiscardsLateVideoFrames = true // keep-only-latest
        videoOutput.setSampleBufferDelegate(self, queue: sampleQueue)
    }

    // MARK: - Lifecycle

    func start(front: Bool) {
        configQueue.async { [weak self] in
            guard let self = self else { return }
            self.isFront = front
            self.configureSession()
            if !self.session.isRunning {
                self.session.startRunning()
            }
        }
    }

    func stop() {
        configQueue.async { [weak self] in
            guard let self = self else { return }
            if self.session.isRunning {
                self.session.stopRunning()
            }
        }
    }

    /// Switch cameras. Cheap: only swaps the input + re-applies orientation.
    func setFront(_ front: Bool) {
        configQueue.async { [weak self] in
            guard let self = self, front != self.isFront else { return }
            self.isFront = front
            self.configureSession()
        }
    }

    // MARK: - Configuration

    private func configureSession() {
        session.beginConfiguration()
        session.sessionPreset = .photo // 4:3, matches Android's RATIO_4_3

        if let existing = currentInput {
            session.removeInput(existing)
            currentInput = nil
        }

        let position: AVCaptureDevice.Position = isFront ? .front : .back
        guard let device = Self.camera(for: position),
              let input = try? AVCaptureDeviceInput(device: device) else {
            NSLog("[BeautyFilter] no camera for position \(position.rawValue)")
            session.commitConfiguration()
            return
        }
        if session.canAddInput(input) {
            session.addInput(input)
            currentInput = input
        }

        if session.outputs.isEmpty, session.canAddOutput(videoOutput) {
            session.addOutput(videoOutput)
        }

        applyOrientation()
        session.commitConfiguration()
    }

    /// Force portrait + front mirroring on the video-data connection so the
    /// delivered buffers are display-oriented.
    private func applyOrientation() {
        guard let connection = videoOutput.connection(with: .video) else { return }

        // TODO(ios): `videoOrientation` is deprecated in iOS 17 in favour of
        // `videoRotationAngle`. Kept here for the iOS 15.1 deployment target; if
        // the target is raised, switch to `isVideoRotationAngleSupported(90)` /
        // `videoRotationAngle = 90`.
        if connection.isVideoOrientationSupported {
            connection.videoOrientation = .portrait
        }
        if connection.isVideoMirroringSupported {
            connection.automaticallyAdjustsVideoMirroring = false
            connection.isVideoMirrored = isFront // selfie mirror on front only
        }
    }

    private static func camera(for position: AVCaptureDevice.Position) -> AVCaptureDevice? {
        let discovery = AVCaptureDevice.DiscoverySession(
            deviceTypes: [.builtInWideAngleCamera],
            mediaType: .video,
            position: position)
        return discovery.devices.first
    }

    // MARK: - AVCaptureVideoDataOutputSampleBufferDelegate

    func captureOutput(_ output: AVCaptureOutput,
                       didOutput sampleBuffer: CMSampleBuffer,
                       from connection: AVCaptureConnection) {
        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        onPixelBuffer?(pixelBuffer)
    }
}
