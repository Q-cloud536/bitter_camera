Pod::Spec.new do |s|
  s.name             = 'media_processing_kit'
  s.version          = '0.0.1'
  s.summary          = 'Part D blackbox for Flutter: media pipeline + effect injection (Approach B).'
  s.description      = <<-DESC
Part D blackbox for Flutter: media pipeline + effect injection (Approach B).
DESC
  s.homepage         = 'https://example.invalid'
  s.license          = { :file => '../LICENSE' }
  s.author           = { 'BitterCamera' => 'dev@example.invalid' }
  s.source           = { :path => '.' }
  s.source_files     = 'Classes/**/*'
  s.dependency 'Flutter'
  s.platform = :ios, '12.0'
  s.swift_version = '5.0'
end

