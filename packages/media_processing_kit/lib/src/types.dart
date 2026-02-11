enum MediaTaskEventType { progress, completed, error }

/// 说明（人话）：
/// - Flutter 侧通过一个事件流拿到“进度/完成/失败”。
/// - 任务由 taskId 标识，调用方可以 cancel(taskId)。
class MediaTaskEvent {
  final String taskId;
  final MediaTaskEventType type;

  /// 0..1，只在 progress 时有意义
  final double? progress;

  /// 只有 completed 时会有
  final String? outputPath;

  /// 只有 error 时会有
  final String? errorCode;
  final String? errorMessage;

  MediaTaskEvent({
    required this.taskId,
    required this.type,
    this.progress,
    this.outputPath,
    this.errorCode,
    this.errorMessage,
  });

  static MediaTaskEvent fromMap(Map<Object?, Object?> m) {
    final typeStr = (m["type"] as String?) ?? "progress";
    final type = switch (typeStr) {
      "progress" => MediaTaskEventType.progress,
      "completed" => MediaTaskEventType.completed,
      "error" => MediaTaskEventType.error,
      _ => MediaTaskEventType.progress,
    };

    double? progress;
    final rawP = m["progress"];
    if (rawP is num) progress = rawP.toDouble();

    return MediaTaskEvent(
      taskId: (m["taskId"] as String?) ?? "",
      type: type,
      progress: progress,
      outputPath: m["outputPath"] as String?,
      errorCode: m["errorCode"] as String?,
      errorMessage: m["errorMessage"] as String?,
    );
  }
}

class BitterFaceConfig {
  final int style;
  final double intensity;
  final double smoothing;
  final double maxWarp;
  final int faceCountLimit;

  const BitterFaceConfig({
    this.style = 1,
    this.intensity = 1.2,
    this.smoothing = 0.5,
    this.maxWarp = 1.0,
    this.faceCountLimit = 3,
  });

  Map<String, Object?> toMap() => {
        "style": style,
        "intensity": intensity,
        "smoothing": smoothing,
        "maxWarp": maxWarp,
        "faceCountLimit": faceCountLimit,
      };
}

class MediaProcessingErrorCode {
  static const String unsupportedPlatform = "UNSUPPORTED_PLATFORM";
}

