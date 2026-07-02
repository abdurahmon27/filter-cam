//
//  MustacheTexture.swift
//  BeautyFilter
//
//  Draws a handlebar mustache into a bitmap with Core Graphics (no binary asset
//  needed) and uploads it as an MTLTexture. Port of Android's MustacheTexture.kt.
//
//  The texture is 2:1 (512x256) and the shape is vertically centered, so a quad
//  centered on the philtrum lines up naturally. Pixels are premultiplied-alpha,
//  matching the Android upload, so the renderer blends with (1, 1-srcAlpha).
//

import Foundation
import Metal
import CoreGraphics
import UIKit

enum MustacheTexture {

    /// height / width of the texture (matches Android `ASPECT`).
    static let aspect: CGFloat = 0.5

    static func make(device: MTLDevice) -> MTLTexture? {
        let width = 512
        let height = 256
        guard let cgImage = draw(width: width, height: height) else { return nil }

        // Copy the CGImage into a tightly-packed BGRA buffer for Metal.
        let bytesPerRow = width * 4
        var data = [UInt8](repeating: 0, count: bytesPerRow * height)
        let colorSpace = CGColorSpaceCreateDeviceRGB()
        // premultipliedFirst + byteOrder32Little == BGRA bytes in memory.
        let bitmapInfo = CGImageAlphaInfo.premultipliedFirst.rawValue |
            CGBitmapInfo.byteOrder32Little.rawValue
        guard let ctx = CGContext(data: &data,
                                  width: width,
                                  height: height,
                                  bitsPerComponent: 8,
                                  bytesPerRow: bytesPerRow,
                                  space: colorSpace,
                                  bitmapInfo: bitmapInfo) else { return nil }
        ctx.draw(cgImage, in: CGRect(x: 0, y: 0, width: width, height: height))

        let descriptor = MTLTextureDescriptor.texture2DDescriptor(
            pixelFormat: .bgra8Unorm,
            width: width,
            height: height,
            mipmapped: false)
        descriptor.usage = [.shaderRead]
        guard let texture = device.makeTexture(descriptor: descriptor) else { return nil }
        texture.replace(region: MTLRegionMake2D(0, 0, width, height),
                        mipmapLevel: 0,
                        withBytes: &data,
                        bytesPerRow: bytesPerRow)
        return texture
    }

    /// Draws the mustache path. Core Graphics' default coordinate space is
    /// y-up, but we build a bitmap context whose row 0 is the top (see caller),
    /// so we flip the context to match Android's y-down Canvas coordinates and
    /// reuse its exact control points.
    private static func draw(width: Int, height: Int) -> CGImage? {
        let w = CGFloat(width)
        let h = CGFloat(height)

        let colorSpace = CGColorSpaceCreateDeviceRGB()
        let bitmapInfo = CGImageAlphaInfo.premultipliedLast.rawValue
        guard let ctx = CGContext(data: nil,
                                  width: width,
                                  height: height,
                                  bitsPerComponent: 8,
                                  bytesPerRow: 0,
                                  space: colorSpace,
                                  bitmapInfo: bitmapInfo) else { return nil }

        // Flip to y-down so the Android control points map 1:1.
        ctx.translateBy(x: 0, y: h)
        ctx.scaleBy(x: 1, y: -1)

        ctx.setShouldAntialias(true)

        // Right half of the mustache (left half is a mirrored draw). Control
        // points copied verbatim from MustacheTexture.kt (canvas 512x256).
        func mustacheHalfPath() -> CGPath {
            let p = CGMutablePath()
            p.move(to: CGPoint(x: 256, y: 130))
            // top edge sweeping out to the right
            p.addCurve(to: CGPoint(x: 392, y: 98),
                       control1: CGPoint(x: 300, y: 88),
                       control2: CGPoint(x: 352, y: 82))
            // rising into the curled tip
            p.addCurve(to: CGPoint(x: 480, y: 68),
                       control1: CGPoint(x: 432, y: 112),
                       control2: CGPoint(x: 462, y: 100))
            // tip curling back under
            p.addCurve(to: CGPoint(x: 438, y: 154),
                       control1: CGPoint(x: 486, y: 108),
                       control2: CGPoint(x: 470, y: 142))
            // bottom edge back to the center
            p.addCurve(to: CGPoint(x: 256, y: 150),
                       control1: CGPoint(x: 392, y: 170),
                       control2: CGPoint(x: 306, y: 176))
            p.closeSubpath()
            return p
        }

        // Vertical gradient fill (dark brown -> near black), same stops as Android.
        let colors = [
            UIColor(red: 0x3B / 255.0, green: 0x2A / 255.0, blue: 0x1E / 255.0, alpha: 1).cgColor,
            UIColor(red: 0x17 / 255.0, green: 0x10 / 255.0, blue: 0x0B / 255.0, alpha: 1).cgColor
        ]
        guard let gradient = CGGradient(colorsSpace: colorSpace,
                                        colors: colors as CFArray,
                                        locations: [0, 1]) else { return nil }

        func fillWithGradient(_ path: CGPath) {
            ctx.saveGState()
            ctx.addPath(path)
            ctx.clip()
            ctx.drawLinearGradient(gradient,
                                   start: CGPoint(x: 0, y: h * 0.25),
                                   end: CGPoint(x: 0, y: h * 0.8),
                                   options: [.drawsBeforeStartLocation, .drawsAfterEndLocation])
            ctx.restoreGState()
        }

        let half = mustacheHalfPath()
        fillWithGradient(half)

        // Mirror around the horizontal center and draw the left half.
        ctx.saveGState()
        ctx.translateBy(x: w, y: 0)
        ctx.scaleBy(x: -1, y: 1)
        fillWithGradient(half)
        ctx.restoreGState()

        return ctx.makeImage()
    }
}
