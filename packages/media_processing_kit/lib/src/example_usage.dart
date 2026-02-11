import "package:media_processing_kit/media_processing_kit.dart";

/// 这个文件是“最小用法”示例（可拷贝到你们 Flutter App 里）。
///
/// 人话解释：
/// - 监听 events：拿到进度/完成/失败
/// - 发起 processVideo：指定 effectId（默认 passthrough）
Future<void> exampleUsage() async {
  final kit = MediaProcessingKit();
  await kit.initialize();

  final sub = kit.events.listen((e) {
    switch (e.type) {
      case MediaTaskEventType.progress:
        print("task=${e.taskId} progress=${e.progress}");
      case MediaTaskEventType.completed:
        print("task=${e.taskId} completed output=${e.outputPath}");
      case MediaTaskEventType.error:
        print("task=${e.taskId} error=${e.errorCode} msg=${e.errorMessage}");
    }
  });

  final taskId = await kit.processVideo(
    inputPath: "/path/in.mp4",
    outputPath: "/path/out.mp4",
    effectId: "passthrough",
    config: const BitterFaceConfig(intensity: 0.8),
  );

  // 取消示例（真实 UI 里由“取消按钮”触发）
  // await kit.cancel(taskId);

  await Future<void>.delayed(const Duration(seconds: 1));
  await sub.cancel();
}

