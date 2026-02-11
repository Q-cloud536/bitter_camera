import 'dart:async';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:media_processing_kit/media_processing_kit.dart';
import 'package:path_provider/path_provider.dart';

/// Debug screen for testing the bitter face effect on bundled test images.
/// Shows before/after comparison for all 3 styles.
class TestEffectScreen extends StatefulWidget {
  const TestEffectScreen({super.key});

  @override
  State<TestEffectScreen> createState() => _TestEffectScreenState();
}

class _TestEffectScreenState extends State<TestEffectScreen> {
  final _kit = MediaProcessingKit();
  StreamSubscription<MediaTaskEvent>? _sub;

  final List<String> _testImageAssets = [
    'assets/test_images/image_015.jpg',
    'assets/test_images/image_016.jpg',
    'assets/test_images/image_017.jpg',
    'assets/test_images/image_018.jpg',
    'assets/test_images/image_019.jpg',
    'assets/test_images/image_020.jpg',
  ];

  int _selectedImageIndex = 0;
  int _selectedStyle = 1;
  bool _processing = false;
  String? _originalPath;
  String? _processedPath;
  String? _error;
  String? _taskId;

  @override
  void initState() {
    super.initState();
    _extractAndProcess();
  }

  @override
  void dispose() {
    _sub?.cancel();
    super.dispose();
  }

  Future<void> _extractAndProcess() async {
    if (kIsWeb || !(Platform.isAndroid || Platform.isIOS)) {
      setState(() {
        _error = '特效测试仅支持 Android/iOS';
      });
      return;
    }

    setState(() {
      _processing = true;
      _error = null;
      _processedPath = null;
      _taskId = null;
    });

    try {
      // Extract asset to temp file
      final assetPath = _testImageAssets[_selectedImageIndex];
      final byteData = await rootBundle.load(assetPath);
      final tmpDir = await getTemporaryDirectory();
      final inputFile = File('${tmpDir.path}/test_input_${_selectedImageIndex}.jpg');
      await inputFile.writeAsBytes(byteData.buffer.asUint8List());

      _originalPath = inputFile.path;

      // Process with bitter_face effect
      final outputPath = '${tmpDir.path}/test_output_${_selectedImageIndex}_style${_selectedStyle}.jpg';

      await _kit.initialize();
      await _sub?.cancel();

      // Listen for completion
      _sub = _kit.events.listen((e) {
        if (_taskId != null && e.taskId != _taskId) return;
        if (e.type == MediaTaskEventType.completed) {
          if (mounted) {
            setState(() {
              _processedPath = e.outputPath ?? outputPath;
              _processing = false;
            });
          }
        } else if (e.type == MediaTaskEventType.error) {
          if (mounted) {
            setState(() {
              _error = '${e.errorCode}: ${e.errorMessage}';
              _processing = false;
            });
          }
        }
      });

      final taskId = await _kit.processImage(
        inputPath: inputFile.path,
        outputPath: outputPath,
        effectId: 'bitter_face',
        config: BitterFaceConfig(
          style: _selectedStyle,
          intensity: 1.2,
        ),
      );
      if (!mounted) return;
      setState(() {
        _taskId = taskId;
      });

      // For image processing, the result may be returned synchronously
      if (File(outputPath).existsSync() && mounted) {
        setState(() {
          _processedPath = outputPath;
          _processing = false;
        });
      }
    } catch (e) {
      setState(() {
        _error = '处理失败: $e';
        _processing = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('测试苦瓜脸特效'),
      ),
      body: Column(
        children: [
          // Image selector
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
            color: Colors.grey[100],
            child: Row(
              children: [
                const Text('测试图片: '),
                DropdownButton<int>(
                  value: _selectedImageIndex,
                  items: List.generate(_testImageAssets.length, (i) {
                    return DropdownMenuItem(
                      value: i,
                      child: Text('image_${15 + i}'),
                    );
                  }),
                  onChanged: (v) {
                    if (v != null) {
                      _selectedImageIndex = v;
                      _extractAndProcess();
                    }
                  },
                ),
                const SizedBox(width: 16),
                const Text('样式: '),
                DropdownButton<int>(
                  value: _selectedStyle,
                  items: [1, 2, 3].map((s) {
                    return DropdownMenuItem(value: s, child: Text('样式 $s'));
                  }).toList(),
                  onChanged: (v) {
                    if (v != null) {
                      _selectedStyle = v;
                      _extractAndProcess();
                    }
                  },
                ),
              ],
            ),
          ),

          // Before/After comparison
          Expanded(
            child: _processing
                ? const Center(
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        CircularProgressIndicator(),
                        SizedBox(height: 16),
                        Text('正在处理特效...'),
                      ],
                    ),
                  )
                : _error != null
                    ? Center(
                        child: Padding(
                          padding: const EdgeInsets.all(24),
                          child: Text(
                            _error!,
                            style: const TextStyle(color: Colors.red),
                            textAlign: TextAlign.center,
                          ),
                        ),
                      )
                    : Row(
                        children: [
                          // Original
                          Expanded(
                            child: Column(
                              children: [
                                const Padding(
                                  padding: EdgeInsets.all(8),
                                  child: Text('原图',
                                      style: TextStyle(fontWeight: FontWeight.bold)),
                                ),
                                Expanded(
                                  child: _originalPath != null
                                      ? Image.file(
                                          File(_originalPath!),
                                          fit: BoxFit.contain,
                                        )
                                      : const SizedBox.shrink(),
                                ),
                              ],
                            ),
                          ),
                          const VerticalDivider(width: 2),
                          // Processed
                          Expanded(
                            child: Column(
                              children: [
                                Padding(
                                  padding: const EdgeInsets.all(8),
                                  child: Text('样式 $_selectedStyle',
                                      style: const TextStyle(fontWeight: FontWeight.bold)),
                                ),
                                Expanded(
                                  child: _processedPath != null
                                      ? Image.file(
                                          File(_processedPath!),
                                          fit: BoxFit.contain,
                                        )
                                      : const Center(child: Text('等待处理...')),
                                ),
                              ],
                            ),
                          ),
                        ],
                      ),
          ),

          // Quick test all styles button
          Padding(
            padding: const EdgeInsets.all(16),
            child: ElevatedButton(
              onPressed: _processing ? null : _extractAndProcess,
              child: const Text('重新处理'),
            ),
          ),
        ],
      ),
    );
  }
}
