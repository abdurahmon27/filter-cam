//
//  BeautyFilterModule.swift
//  BeautyFilter
//
//  Expo module definition. Contract is identical to the Android
//  BeautyFilterModule.kt so the shared JS (index.ts) works on both platforms.
//

import ExpoModulesCore

public class BeautyFilterModule: Module {
    public func definition() -> ModuleDefinition {
        Name("BeautyFilter")

        Function("isAvailable") { () -> Bool in
            true
        }

        // The live Metal pipeline already bakes the beauty filter into captures,
        // so post-processing is a no-op that returns the uri unchanged. This
        // exists purely for API parity with the (historical) Core Image path and
        // the Android module.
        AsyncFunction("applyBeauty") { (uri: String, _: Double) -> String in
            uri
        }

        View(BeautyCameraView.self) {
            Prop("facing") { (view: BeautyCameraView, facing: String?) in
                view.setFacing(facing ?? "front")
            }

            Prop("smoothing") { (view: BeautyCameraView, value: Double?) in
                view.setSmoothing(Float(value ?? 0))
            }

            Prop("mustache") { (view: BeautyCameraView, enabled: Bool?) in
                view.setMustache(enabled == true)
            }

            Prop("faceMesh") { (view: BeautyCameraView, enabled: Bool?) in
                view.setFaceMesh(enabled == true)
            }

            AsyncFunction("takePicture") { (view: BeautyCameraView, promise: Promise) in
                view.takePicture(promise)
            }
        }
    }
}
