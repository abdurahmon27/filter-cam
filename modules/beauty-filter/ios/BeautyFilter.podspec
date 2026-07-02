require 'json'

package = JSON.parse(File.read(File.join(__dir__, '..', '..', '..', 'package.json')))

Pod::Spec.new do |s|
  s.name           = 'BeautyFilter'
  s.version        = '1.0.0'
  s.summary        = 'Live GPU beauty-filter camera (Vision + Metal) for FilterCam'
  s.description    = 'AVFoundation camera + Vision face landmarks + Metal render pipeline: face-only skin smoothing, tracked mustache, face-mesh overlay.'
  s.author         = package['author'] || ''
  s.homepage       = 'https://github.com/'
  s.platforms      = { :ios => '15.1' }
  s.source         = { git: '' }
  s.static_framework = true

  s.dependency 'ExpoModulesCore'

  # System frameworks used by the live camera pipeline.
  s.frameworks = 'AVFoundation', 'CoreVideo', 'CoreMedia', 'Metal', 'MetalKit', 'Vision', 'CoreGraphics', 'UIKit'

  s.pod_target_xcconfig = {
    'DEFINES_MODULE' => 'YES',
    'SWIFT_COMPILATION_MODE' => 'wholemodule'
  }

  s.source_files = '**/*.{h,m,mm,swift}'
  # The .metal shader source is compiled into a default.metallib at build time.
  s.resources = '**/*.metal'
end
