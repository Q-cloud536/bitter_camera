import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

/// Dart wrapper for the native camera MethodChannel.
///
/// Communicates with NativeCameraPlugin.kt on Android to control:
///   - Camera preview (via Flutter Texture)
///   - Photo capture
///   - Video recording
///   - Face effect style / intensity
class NativeCameraController {
  static const _channel = MethodChannel('bitter_camera/camera');

  int? _textureId;
  bool _isRecording = false;
  String? _recordingPath;

  /// Whether the native camera is supported on this platform.
  bool get isSupported => !kIsWeb && (Platform.isAndroid || Platform.isIOS);

  /// Current texture ID for display with [Texture] widget. Null if not started.
  int? get textureId => _textureId;

  /// Whether currently recording video.
  bool get isRecording => _isRecording;

  /// Start the camera preview.
  /// [useFrontCamera] defaults to true (front-facing camera).
  /// Returns the texture ID for the [Texture] widget.
  Future<int?> startPreview({bool useFrontCamera = true}) async {
    if (!isSupported) return null;
    try {
      final result = await _channel.invokeMethod<int>('startPreview', {
        'cameraFacing': useFrontCamera ? 'front' : 'back',
      });
      _textureId = result;
      return result;
    } on PlatformException catch (e) {
      debugPrint('NativeCameraController.startPreview error: $e');
      return null;
    }
  }

  /// Request runtime permissions needed for camera features.
  /// Returns true if granted.
  Future<bool> requestPermissions() async {
    if (!isSupported) return false;
    try {
      final ok = await _channel.invokeMethod<bool>('requestPermissions');
      return ok == true;
    } on PlatformException catch (e) {
      debugPrint('NativeCameraController.requestPermissions error: $e');
      return false;
    }
  }

  /// Stop the camera preview and release resources.
  Future<void> stopPreview() async {
    if (!isSupported) return;
    try {
      await _channel.invokeMethod('stopPreview');
      _textureId = null;
    } on PlatformException catch (e) {
      debugPrint('NativeCameraController.stopPreview error: $e');
    }
  }

  /// Switch between front and back camera.
  /// Returns the new texture ID.
  Future<int?> switchCamera() async {
    if (!isSupported) return null;
    try {
      final result = await _channel.invokeMethod<int>('switchCamera');
      _textureId = result;
      return result;
    } on PlatformException catch (e) {
      debugPrint('NativeCameraController.switchCamera error: $e');
      return null;
    }
  }

  /// Capture the current processed frame as a photo.
  /// Returns the file path of the saved JPEG.
  Future<String?> capturePhoto() async {
    if (!isSupported) return null;
    try {
      return await _channel.invokeMethod<String>('capturePhoto');
    } on PlatformException catch (e) {
      debugPrint('NativeCameraController.capturePhoto error: $e');
      return null;
    }
  }

  /// Start recording video from the processed camera stream.
  /// Returns the output file path.
  Future<String?> startRecording() async {
    if (!isSupported || _isRecording) return null;
    try {
      final path = await _channel.invokeMethod<String>('startRecording');
      _isRecording = true;
      _recordingPath = path;
      return path;
    } on PlatformException catch (e) {
      debugPrint('NativeCameraController.startRecording error: $e');
      return null;
    }
  }

  /// Stop recording and finalize the video file.
  /// Returns the output file path.
  Future<String?> stopRecording() async {
    if (!isSupported || !_isRecording) return null;
    try {
      final path = await _channel.invokeMethod<String>('stopRecording');
      _isRecording = false;
      return path ?? _recordingPath;
    } on PlatformException catch (e) {
      debugPrint('NativeCameraController.stopRecording error: $e');
      _isRecording = false;
      return _recordingPath;
    }
  }

  /// Set the face effect style (1, 2, 3) or 0 to disable.
  Future<void> setEffectStyle(int style) async {
    if (!isSupported) return;
    try {
      await _channel.invokeMethod('setEffectStyle', {'style': style});
    } on PlatformException catch (e) {
      debugPrint('NativeCameraController.setEffectStyle error: $e');
    }
  }

  /// Set the face effect intensity (0.0 - 2.0, default 1.2).
  Future<void> setEffectIntensity(double intensity) async {
    if (!isSupported) return;
    try {
      await _channel.invokeMethod('setEffectIntensity', {'intensity': intensity});
    } on PlatformException catch (e) {
      debugPrint('NativeCameraController.setEffectIntensity error: $e');
    }
  }

  /// Release all resources.
  Future<void> dispose() async {
    if (_isRecording) {
      await stopRecording();
    }
    await stopPreview();
  }
}
