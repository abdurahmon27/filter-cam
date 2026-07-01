require 'json'

package = JSON.parse(File.read(File.join(__dir__, '..', '..', '..', 'package.json')))

Pod::Spec.new do |s|
  s.name           = 'BeautyFilter'
  s.version        = '1.0.0'
  s.summary        = 'Native Swift skin-smoothing beauty filter for FilterCam'
  s.description    = 'Applies a Core Image based skin-smoothing filter to captured photos.'
  s.author         = package['author'] || ''
  s.homepage       = 'https://github.com/'
  s.platforms      = { :ios => '15.1', :tvos => '15.1' }
  s.source         = { git: '' }
  s.static_framework = true

  s.dependency 'ExpoModulesCore'

  s.pod_target_xcconfig = {
    'DEFINES_MODULE' => 'YES',
    'SWIFT_COMPILATION_MODE' => 'wholemodule'
  }

  s.source_files = '**/*.{h,m,mm,swift,hpp,cpp}'
end
