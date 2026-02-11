import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:gallery_saver/gallery_saver.dart';
import 'package:video_player/video_player.dart';

import 'media_type.dart';

/// 结果页（人话解释）：
/// - 展示处理后的图片/视频
/// - 点击“保存”写入系统相册（toast 文案对齐 PRD）
class ResultScreen extends StatefulWidget {
  final ImportMediaType mediaType;
  final String outputPath;

  const ResultScreen({
    super.key,
    required this.mediaType,
    required this.outputPath,
  });

  @override
  State<ResultScreen> createState() => _ResultScreenState();
}

class _ResultScreenState extends State<ResultScreen> {
  VideoPlayerController? _video;
  bool _saving = false;
  String? _videoInitError;

  @override
  void initState() {
    super.initState();
    if (widget.mediaType == ImportMediaType.video && !kIsWeb) {
      _initVideo();
    }
  }

  Future<void> _initVideo() async {
    final file = File(widget.outputPath);
    if (!await file.exists()) {
      setState(() => _videoInitError = '视频文件不存在：${widget.outputPath}');
      return;
    }
    final size = await file.length();
    if (size <= 0) {
      setState(() => _videoInitError = '视频文件为空（录制可能失败）：${widget.outputPath}');
      return;
    }

    final controller = VideoPlayerController.file(file);
    _video = controller;
    try {
      // Avoid infinite spinner: time out initialization.
      await controller.initialize().timeout(const Duration(seconds: 10));
      if (!mounted) return;
      setState(() {});
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _videoInitError = '视频初始化失败：$e';
      });
    }
  }

  @override
  void dispose() {
    _video?.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    setState(() => _saving = true);
    bool? ok;
    try {
      if (widget.mediaType == ImportMediaType.image) {
        ok = await GallerySaver.saveImage(widget.outputPath);
      } else {
        ok = await GallerySaver.saveVideo(widget.outputPath);
      }
    } catch (_) {
      ok = false;
    } finally {
      if (mounted) setState(() => _saving = false);
    }

    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(ok == true ? '已保存至手机相册' : '保存失败，请重试'),
        duration: const Duration(seconds: 2),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final file = File(widget.outputPath);

    return Scaffold(
      appBar: AppBar(
        title: const Text('结果'),
      ),
      body: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          children: [
            Expanded(
              child: Center(
                child: widget.mediaType == ImportMediaType.image
                    ? Image.file(file, fit: BoxFit.contain)
                    : (_videoInitError != null)
                        ? Padding(
                            padding: const EdgeInsets.all(24),
                            child: Text(
                              _videoInitError!,
                              style: const TextStyle(color: Colors.red),
                              textAlign: TextAlign.center,
                            ),
                          )
                        : (_video != null && _video!.value.isInitialized)
                            ? AspectRatio(
                                aspectRatio: _video!.value.aspectRatio,
                                child: VideoPlayer(_video!),
                              )
                            : const CircularProgressIndicator(),
              ),
            ),
            const SizedBox(height: 12),
            if (widget.mediaType == ImportMediaType.video && _video != null && _video!.value.isInitialized)
              Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  IconButton(
                    onPressed: () {
                      if (_video!.value.isPlaying) {
                        _video!.pause();
                      } else {
                        _video!.play();
                      }
                      setState(() {});
                    },
                    icon: Icon(_video!.value.isPlaying ? Icons.pause : Icons.play_arrow),
                  ),
                ],
              ),
            Row(
              children: [
                Expanded(
                  child: OutlinedButton(
                    onPressed: () => Navigator.pop(context),
                    child: const Text('取消'),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: ElevatedButton(
                    onPressed: _saving ? null : _save,
                    child: Text(_saving ? '保存中...' : '保存'),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),
            OutlinedButton(
              onPressed: () {
                Navigator.pushReplacementNamed(context, '/import');
              },
              child: const Text('继续导入'),
            ),
          ],
        ),
      ),
    );
  }
}

