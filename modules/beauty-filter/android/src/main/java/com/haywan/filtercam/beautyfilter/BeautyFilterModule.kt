package com.haywan.filtercam.beautyfilter

import expo.modules.kotlin.Promise
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

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
            Prop("facing") { view, facing: String? ->
                view.setFacing(facing ?: "front")
            }

            Prop("smoothing") { view, value: Float? ->
                view.setSmoothing(value ?: 0f)
            }

            Prop("mustache") { view, enabled: Boolean? ->
                view.setMustache(enabled == true)
            }

            AsyncFunction("takePicture") { view: BeautyCameraView, promise: Promise ->
                view.takePicture(promise)
            }
        }
    }
}
