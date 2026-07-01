import ExpoModulesCore
import CoreImage
import UIKit

/// Native beauty filter for FilterCam.
///
/// `applyBeauty` runs a Core Image skin-smoothing pass on a captured photo:
/// a light blur blended toward the original to soften skin while keeping
/// edges, plus a gentle highlight/shadow and saturation lift. It writes the
/// result to a temporary JPEG and returns its `file://` URI.
public class BeautyFilterModule: Module {
  private let context = CIContext()

  public func definition() -> ModuleDefinition {
    Name("BeautyFilter")

    Function("isAvailable") { () -> Bool in
      return true
    }

    AsyncFunction("applyBeauty") { (uri: String, intensity: Double) -> String in
      let clamped = max(0.0, min(1.0, intensity))

      guard let sourceURL = URL(string: uri) ?? URL(fileURLWithPath: uri) as URL?,
            let data = try? Data(contentsOf: sourceURL),
            let uiImage = UIImage(data: data),
            let input = CIImage(image: uiImage) else {
        throw BeautyFilterError.invalidImage(uri)
      }

      // 1. Soften: blur the image, then blend it back over the sharp original
      //    so texture (eyes, hair) stays crisp while skin smooths out.
      let blur = CIFilter(name: "CIGaussianBlur")!
      blur.setValue(input, forKey: kCIInputImageKey)
      blur.setValue(2.5 + clamped * 4.0, forKey: kCIInputRadiusKey)
      let blurred = (blur.outputImage ?? input).cropped(to: input.extent)

      let blend = CIFilter(name: "CIDissolveTransition")!
      blend.setValue(input, forKey: kCIInputImageKey)
      blend.setValue(blurred, forKey: kCIInputTargetImageKey)
      blend.setValue(clamped * 0.7, forKey: kCIInputTimeKey)
      let softened = blend.outputImage ?? input

      // 2. Gentle tone lift: brighten shadows a touch and add warmth.
      let tone = CIFilter(name: "CIHighlightShadowAdjust")!
      tone.setValue(softened, forKey: kCIInputImageKey)
      tone.setValue(0.9, forKey: "inputHighlightAmount")
      tone.setValue(0.15 + clamped * 0.2, forKey: "inputShadowAmount")
      let toned = tone.outputImage ?? softened

      let color = CIFilter(name: "CIColorControls")!
      color.setValue(toned, forKey: kCIInputImageKey)
      color.setValue(1.0 + clamped * 0.08, forKey: kCIInputSaturationKey)
      color.setValue(clamped * 0.03, forKey: kCIInputBrightnessKey)
      let output = (color.outputImage ?? toned).cropped(to: input.extent)

      guard let cgImage = context.createCGImage(output, from: input.extent) else {
        throw BeautyFilterError.renderFailed
      }

      let result = UIImage(cgImage: cgImage)
      guard let jpeg = result.jpegData(compressionQuality: 0.9) else {
        throw BeautyFilterError.renderFailed
      }

      let outURL = FileManager.default.temporaryDirectory
        .appendingPathComponent("beauty-\(UUID().uuidString).jpg")
      try jpeg.write(to: outURL)
      return outURL.absoluteString
    }
  }
}

enum BeautyFilterError: Error {
  case invalidImage(String)
  case renderFailed
}
