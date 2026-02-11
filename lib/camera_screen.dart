import 'dart:async';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';

import 'media_type.dart';
import 'native_camera_controller.dart';

class CameraScreen extends StatefulWidget {
  const CameraScreen({super.key});

  @override
  State<CameraScreen> createState() => _CameraScreenState();
}

class _CameraScreenState extends State<CameraScreen> {
  final NativeCameraController _camera = NativeCameraController();
  bool _isCameraInitialized = false;
  String? _error;

  // Effect state
  int _currentStyle = 1; // 1, 2, 3 or 0 for none
  double _intensity = 1.2;

  // Recording state
  bool _isRecording = false;
  Timer? _recordingTimer;
  int _recordingSeconds = 0;

  bool get _cameraSupported => !kIsWeb && (Platform.isAndroid || Platform.isIOS);

  @override
  void initState() {
    super.initState();
    _initializeCamera();
  }

  Future<void> _initializeCamera() async {
    if (!_cameraSupported) {
      setState(() {
        _error = '相机预览仅支持 Android/iOS（Windows 桌面端无原生相机实现）';
        _isCameraInitialized = false;
      });
      return;
    }

    try {
      final granted = await _camera.requestPermissions();
      if (!granted) {
        setState(() {
          _error = '未授予相机权限，请允许后重试';
          _isCameraInitialized = false;
        });
        return;
      }
      final textureId = await _camera.startPreview(useFrontCamera: true);
      if (textureId == null) {
        setState(() {
          _error = '无法启动相机';
          _isCameraInitialized = false;
        });
        return;
      }

      // Set initial effect
      await _camera.setEffectStyle(_currentStyle);
      await _camera.setEffectIntensity(_intensity);

      setState(() {
        _isCameraInitialized = true;
        _error = null;
      });
    } catch (e) {
      setState(() {
        _error = '相机初始化错误: $e';
        _isCameraInitialized = false;
      });
    }
  }

  Future<void> _switchCamera() async {
    if (!_cameraSupported || _isRecording) return;

    try {
      setState(() {
        _isCameraInitialized = false;
      });
      final newTextureId = await _camera.switchCamera();
      if (newTextureId == null) {
        setState(() {
          _error = '切换摄像头失败：无法启动相机预览';
          _isCameraInitialized = false;
        });
        return;
      }
      setState(() {
        _isCameraInitialized = true;
        _error = null;
      });
    } catch (e) {
      _showMessage('切换摄像头错误: $e');
    }
  }

  @override
  void dispose() {
    _stopRecordingTimer();
    _camera.dispose();
    super.dispose();
  }

  // ========== Photo capture ==========
  Future<void> _takePicture() async {
    if (!_cameraSupported) {
      _showMessage('请在 Android/iOS 运行拍照功能');
      return;
    }

    if (_isRecording) {
      _showMessage('请先停止视频录制');
      return;
    }

    try {
      final path = await _camera.capturePhoto();
      if (path == null) {
        _showMessage('拍照失败：无可用帧');
        return;
      }

      debugPrint('照片已保存到: $path');

      if (!mounted) return;
      Navigator.pushNamed(
        context,
        '/result',
        arguments: ResultRouteArgs(mediaType: ImportMediaType.image, outputPath: path),
      );
    } catch (e) {
      _showMessage('拍照错误: $e');
    }
  }

  // ========== Video recording ==========
  Future<void> _startRecording() async {
    if (!_cameraSupported) {
      _showMessage('请在 Android/iOS 运行录像功能');
      return;
    }

    try {
      await _camera.startRecording();

      setState(() {
        _isRecording = true;
        _recordingSeconds = 0;
      });

      _startRecordingTimer();
      _showMessage('开始录制视频');
    } catch (e) {
      _showMessage('开始录制失败: $e');
    }
  }

  Future<void> _stopRecording() async {
    try {
      if (!_isRecording) return;

      final videoPath = await _camera.stopRecording();
      _stopRecordingTimer();

      setState(() {
        _isRecording = false;
      });

      if (videoPath == null) {
        _showMessage('录制失败');
        return;
      }

      debugPrint('视频已保存到: $videoPath');

      if (!mounted) return;
      Navigator.pushNamed(
        context,
        '/result',
        arguments: ResultRouteArgs(mediaType: ImportMediaType.video, outputPath: videoPath),
      );
    } catch (e) {
      _showMessage('停止录制失败: $e');
    }
  }

  void _toggleRecording() {
    if (_isRecording) {
      _stopRecording();
    } else {
      _startRecording();
    }
  }

  void _startRecordingTimer() {
    _recordingTimer?.cancel();
    _recordingTimer = Timer.periodic(const Duration(seconds: 1), (_) {
      if (!mounted) return;
      setState(() {
        _recordingSeconds++;
      });
    });
  }

  void _stopRecordingTimer() {
    _recordingTimer?.cancel();
    _recordingTimer = null;
  }

  // ========== Effect controls ==========
  void _setStyle(int style) {
    setState(() {
      _currentStyle = style;
    });
    _camera.setEffectStyle(style);
  }

  void _setIntensity(double value) {
    setState(() {
      _intensity = value;
    });
    _camera.setEffectIntensity(value);
  }

  void _showMessage(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(message),
        duration: const Duration(seconds: 2),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(_isRecording ? '录制中... ($_recordingSeconds秒)' : '拍摄苦瓜脸'),
        leading: IconButton(
          icon: const Icon(Icons.arrow_back),
          onPressed: () => Navigator.pop(context),
        ),
      ),
      body: Column(
        children: [
          // Camera preview area
          Expanded(
            child: _error != null
                ? Center(
                    child: Padding(
                      padding: const EdgeInsets.all(24),
                      child: Column(
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: [
                          const Icon(Icons.videocam_off, size: 56, color: Colors.grey),
                          const SizedBox(height: 16),
                          Text(
                            _error!,
                            textAlign: TextAlign.center,
                            style: const TextStyle(color: Colors.grey),
                          ),
                          const SizedBox(height: 16),
                          OutlinedButton(
                            onPressed: () => Navigator.pop(context),
                            child: const Text('返回'),
                          ),
                        ],
                      ),
                    ),
                  )
                : Stack(
                    children: [
                      _isCameraInitialized && _camera.textureId != null
                          ? SizedBox.expand(
                              child: FittedBox(
                                fit: BoxFit.cover,
                                child: SizedBox(
                                  width: 480,
                                  height: 640,
                                  child: Texture(textureId: _camera.textureId!),
                                ),
                              ),
                            )
                          : const Center(
                              child: Column(
                                mainAxisAlignment: MainAxisAlignment.center,
                                children: [
                                  CircularProgressIndicator(),
                                  SizedBox(height: 20),
                                  Text('正在初始化相机和特效引擎...'),
                                ],
                              ),
                            ),
                      if (_isRecording)
                        Positioned(
                          top: 20,
                          right: 20,
                          child: Container(
                            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                            decoration: BoxDecoration(
                              color: Colors.red.withAlpha(204),
                              borderRadius: BorderRadius.circular(20),
                            ),
                            child: Row(
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                Container(
                                  width: 10,
                                  height: 10,
                                  decoration: const BoxDecoration(
                                    color: Colors.white,
                                    shape: BoxShape.circle,
                                  ),
                                ),
                                const SizedBox(width: 6),
                                Text(
                                  '录制中 $_recordingSeconds秒',
                                  style: const TextStyle(
                                    color: Colors.white,
                                    fontWeight: FontWeight.bold,
                                  ),
                                ),
                              ],
                            ),
                          ),
                        ),
                    ],
                  ),
          ),

          // Effect style selector
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
            color: Colors.black87,
            child: Column(
              children: [
                const Text(
                  '选择苦瓜脸样式',
                  style: TextStyle(color: Colors.white70, fontSize: 12),
                ),
                const SizedBox(height: 6),
                Row(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    _buildStyleButton(0, '无特效'),
                    const SizedBox(width: 8),
                    _buildStyleButton(1, '样式1'),
                    const SizedBox(width: 8),
                    _buildStyleButton(2, '样式2'),
                    const SizedBox(width: 8),
                    _buildStyleButton(3, '样式3'),
                  ],
                ),
                const SizedBox(height: 6),
                // Intensity slider
                Row(
                  children: [
                    const Text('强度', style: TextStyle(color: Colors.white70, fontSize: 12)),
                    Expanded(
                      child: Slider(
                        value: _intensity,
                        min: 0.0,
                        max: 2.5,
                        divisions: 25,
                        label: _intensity.toStringAsFixed(1),
                        onChanged: _setIntensity,
                      ),
                    ),
                    Text(
                      _intensity.toStringAsFixed(1),
                      style: const TextStyle(color: Colors.white70, fontSize: 12),
                    ),
                  ],
                ),
              ],
            ),
          ),

          // Bottom control area
          Container(
            padding: const EdgeInsets.all(20),
            color: Colors.black,
            child: Column(
              children: [
                Text(
                  _isRecording ? '视频录制模式' : '拍照模式',
                  style: const TextStyle(color: Colors.white, fontSize: 14),
                ),
                const SizedBox(height: 10),
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceAround,
                  children: [
                    // Gallery button
                    IconButton(
                      onPressed: () => Navigator.pushNamed(context, '/import'),
                      icon: const Icon(Icons.photo_library, size: 40, color: Colors.white),
                    ),

                    // Capture / stop button
                    GestureDetector(
                      onTap: !_cameraSupported
                          ? null
                          : (_isRecording ? _stopRecording : _takePicture),
                      child: Container(
                        width: 70,
                        height: 70,
                        decoration: BoxDecoration(
                          shape: BoxShape.circle,
                          border: Border.all(
                            color: _isRecording ? Colors.red : Colors.white,
                            width: 3,
                          ),
                        ),
                        child: Container(
                          margin: const EdgeInsets.all(5),
                          decoration: BoxDecoration(
                            shape: BoxShape.circle,
                            color: _isRecording ? Colors.red : Colors.white,
                          ),
                        ),
                      ),
                    ),

                    // Switch camera button
                    IconButton(
                      onPressed: (!_cameraSupported || _isRecording) ? null : _switchCamera,
                      icon: Icon(
                        Icons.switch_camera,
                        size: 40,
                        color: (_cameraSupported && !_isRecording)
                            ? Colors.white
                            : Colors.grey,
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 10),
                ElevatedButton(
                  onPressed: _cameraSupported ? _toggleRecording : null,
                  style: ElevatedButton.styleFrom(
                    backgroundColor: _isRecording ? Colors.red : Colors.blue,
                    foregroundColor: Colors.white,
                    minimumSize: const Size(200, 50),
                  ),
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(_isRecording ? Icons.stop : Icons.videocam),
                      const SizedBox(width: 8),
                      Text(_isRecording ? '停止录制' : '开始录制视频'),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildStyleButton(int style, String label) {
    final isSelected = _currentStyle == style;
    return GestureDetector(
      onTap: () => _setStyle(style),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
        decoration: BoxDecoration(
          color: isSelected ? Colors.blue : Colors.grey[800],
          borderRadius: BorderRadius.circular(20),
          border: Border.all(
            color: isSelected ? Colors.blueAccent : Colors.grey[600]!,
            width: isSelected ? 2 : 1,
          ),
        ),
        child: Text(
          label,
          style: TextStyle(
            color: isSelected ? Colors.white : Colors.grey[400],
            fontSize: 13,
            fontWeight: isSelected ? FontWeight.bold : FontWeight.normal,
          ),
        ),
      ),
    );
  }
}
