package com.haywan.filtercam.beautyfilter

import com.haywan.filtercam.beautyfilter.view.BeautyCameraView
import expo.modules.kotlin.Promise
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

/**
 * Expo module entry point. Declares the JS-facing contract:
 *  - `isAvailable` / `applyBeauty` module functions, and
 *  - the `BeautyFilter` view with its props and `takePicture` method.
 *
 * All rendering, camera and tracking work lives under the `view`, `render`,
 * `tracking` and `gl` sub-packages; this file is only the wiring.
 */
class BeautyFilterModule : Module() {
    override fun definition() = ModuleDefinition {
        Name("BeautyFilter")

        Function("isAvailable") { true }

        // On Android the beauty filter is applied live in the GL pipeline and
        // captures already include it, so post-processing is a no-op. This
        // exists for API parity with the iOS module.
        AsyncFunction("applyBeauty") { uri: String, _: Double ->
            uri
        }

        View(BeautyCameraView::class) {
            // ~1/s with {fps}: the preview frame rate, for the debug indicator.
            Events("onFps")

            Prop("facing") { view, facing: String? ->
                view.setFacing(facing ?: "front")
            }

            Prop("smoothing") { view, value: Float? ->
                view.setSmoothing(value ?: 0f)
            }

            Prop("glow") { view, value: Float? ->
                view.setGlow(value ?: 0f)
            }

            Prop("clarity") { view, value: Float? ->
                view.setClarity(value ?: 0f)
            }

            Prop("warmth") { view, value: Float? ->
                view.setWarmth(value ?: 0f)
            }

            Prop("sharpness") { view, value: Float? ->
                view.setSharpness(value ?: 0f)
            }

            Prop("eyeEnlarge") { view, value: Float? ->
                view.setEyeEnlarge(value ?: 0f)
            }

            Prop("noseSlim") { view, value: Float? ->
                view.setNoseSlim(value ?: 0f)
            }

            Prop("faceSlim") { view, value: Float? ->
                view.setFaceSlim(value ?: 0f)
            }

            Prop("mustache") { view, enabled: Boolean? ->
                view.setMustache(enabled == true)
            }

            Prop("faceMesh") { view, enabled: Boolean? ->
                view.setFaceMesh(enabled == true)
            }

            AsyncFunction("takePicture") { view: BeautyCameraView, promise: Promise ->
                view.takePicture(promise)
            }
        }
    }
}
