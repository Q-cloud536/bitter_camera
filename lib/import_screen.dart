import 'dart:async';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';
import 'package:media_processing_kit/media_processing_kit.dart';
import 'package:path_provider/path_provider.dart';

import 'media_type.dart';

/// 导入页（人话解释）：
/// - 用 image_picker 从系统相册选照片/视频
/// - 选完后调用 D 黑盒（MediaProcessingKit）做离线处理
/// - 展示进度 + 允许取消
class ImportScreen extends StatefulWidget {
  const ImportScreen({super.key});

  @override
  State<ImportScreen> createState() => _ImportScreenState();
}

class _ImportScreenState extends State<ImportScreen> {
  final _picker = ImagePicker();
  final _kit = MediaProcessingKit();

  StreamSubscription<MediaTaskEvent>? _sub;
  String? _taskId;
  double _progress = 0;
  String? _error;
  bool _processing = false;

  void _showMessage(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(message), duration: const Duration(seconds: 2)),
    );
  }

  @override
  void dispose() {
    _sub?.cancel();
    super.dispose();
  }

  Future<void> _pickAndProcess(ImportMediaType type) async {
    if (kIsWeb || !(Platform.isAndroid || Platform.isIOS)) {
      setState(() {
        _error = '导入功能仅支持 Android/iOS（请用手机或模拟器运行）';
      });
      return;
    }

    setState(() {
      _error = null;
      _progress = 0;
      _processing = false;
      _taskId = null;
    });

    XFile? picked;
    if (type == ImportMediaType.image) {
      picked = await _picker.pickImage(source: ImageSource.gallery);
    } else {
      picked = await _picker.pickVideo(source: ImageSource.gallery);
    }

    if (picked == null) return;

    // 输出路径：写到 app 临时目录（后面在结果页提供“保存到相册”）
    final tmp = await getTemporaryDirectory();
    final outName = type == ImportMediaType.image
        ? 'bitter_out_${DateTime.now().millisecondsSinceEpoch}.jpg'
        : 'bitter_out_${DateTime.now().millisecondsSinceEpoch}.mp4';
    final outputPath = File('${tmp.path}/$outName').path;

    await _kit.initialize();

    await _sub?.cancel();
    _sub = _kit.events.listen((e) async {
      if (_taskId != null && e.taskId != _taskId) return;
      if (!mounted) return;

      if (e.type == MediaTaskEventType.progress) {
        setState(() {
          _progress = e.progress ?? 0;
        });
      } else if (e.type == MediaTaskEventType.completed) {
        _sub?.cancel();
        _sub = null;
        setState(() {
          _processing = false;
        });
        Navigator.pushReplacementNamed(
          context,
          '/result',
          arguments: ResultRouteArgs(
            mediaType: type,
            outputPath: e.outputPath ?? outputPath,
          ),
        );
      } else {
        setState(() {
          _processing = false;
          _error = '${e.errorCode ?? 'UNKNOWN'}: ${e.errorMessage ?? ''}';
        });
      }
    });

    setState(() {
      _processing = true;
    });

    if (type == ImportMediaType.image) {
      final taskId = await _kit.processImage(
        inputPath: picked.path,
        outputPath: outputPath,
        effectId: 'bitter_face',
      );
      if (!mounted) return;
      setState(() {
        _taskId = taskId;
      });
      return;
    }

    final taskId = await _kit.processVideo(
      inputPath: picked.path,
      outputPath: outputPath,
      effectId: 'bitter_face',
    );
    if (!mounted) return;

    setState(() {
      _taskId = taskId;
    });
    // Current Android implementation may fall back to passthrough for stability.
    _showMessage('提示：视频导入特效可能会降级为直通（无形变），用于稳定测试流程');
  }

  Future<void> _cancel() async {
    final id = _taskId;
    if (id == null) return;
    await _kit.cancel(id);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('导入'),
      ),
      body: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          children: [
            Row(
              children: [
                Expanded(
                  child: ElevatedButton(
                    onPressed: _processing ? null : () => _pickAndProcess(ImportMediaType.image),
                    child: const Text('导入照片'),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: ElevatedButton(
                    onPressed: _processing ? null : () => _pickAndProcess(ImportMediaType.video),
                    child: const Text('导入视频'),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 24),
            if (_processing) ...[
              LinearProgressIndicator(value: _progress.clamp(0, 1)),
              const SizedBox(height: 12),
              Text('处理中：${(_progress * 100).toStringAsFixed(1)}%'),
              const SizedBox(height: 12),
              OutlinedButton(
                onPressed: _cancel,
                child: const Text('取消'),
              ),
            ],
            if (_error != null) ...[
              const SizedBox(height: 16),
              Text(
                _error!,
                style: const TextStyle(color: Colors.red),
              ),
            ],
            const Spacer(),
            const Text(
              '提示：Windows 桌面无法使用相册/相机插件，请在 Android/iOS 运行。',
              style: TextStyle(color: Colors.grey),
            ),
          ],
        ),
      ),
    );
  }
}

