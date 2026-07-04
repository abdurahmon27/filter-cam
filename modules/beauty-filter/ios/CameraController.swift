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
        // .inputPriority so the device's activeFormat (set below) wins; the
        // old .photo preset delivered full-sensor 4:3 (~12MP) frames, far
        // heavier than needed and mismatched with Android's 1440x1080.
        session.sessionPreset = .inputPriority

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

        configureFormat(device)

        if session.outputs.isEmpty, session.canAddOutput(videoOutput) {
            session.addOutput(videoOutput)
        }

        applyOrientation()
        session.commitConfiguration()
    }

    /// Pick the smallest 4:3 format at least 1440 wide at 30fps — parity with
    /// Android's 1440x1080 (the 4:3 sibling of 720p): sharp on a phone screen,
    /// half the copy/detect cost of full-sensor frames. Falls back to the
    /// session default when no such format exists.
    private func configureFormat(_ device: AVCaptureDevice) {
        var best: AVCaptureDevice.Format?
        var bestWidth = Int32.max
        for format in device.formats {
            let dims = CMVideoFormatDescriptionGetDimensions(format.formatDescription)
            guard dims.width * 3 == dims.height * 4,      // 4:3 only
                  dims.width >= 1440,
                  format.videoSupportedFrameRateRanges.contains(where: { $0.maxFrameRate >= 30 })
            else { continue }
            if dims.width < bestWidth {
                bestWidth = dims.width
                best = format
            }
        }
        guard let format = best else {
            NSLog("[BeautyFilter] no 4:3 >=1440-wide format; using session default")
            return
        }
        do {
            try device.lockForConfiguration()
            device.activeFormat = format
            device.activeVideoMinFrameDuration = CMTime(value: 1, timescale: 30)
            device.activeVideoMaxFrameDuration = CMTime(value: 1, timescale: 30)
            device.unlockForConfiguration()
        } catch {
            NSLog("[BeautyFilter] lockForConfiguration failed: \(error.localizedDescription)")
        }
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
