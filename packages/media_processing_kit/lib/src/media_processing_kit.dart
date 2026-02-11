import "dart:async";
import "dart:io";
import "package:flutter/foundation.dart";
import "package:flutter/services.dart";
import "package:ffmpeg_kit_flutter/ffmpeg_kit.dart";
import "package:ffmpeg_kit_flutter/ffmpeg_kit_config.dart";
import "package:ffmpeg_kit_flutter/ffprobe_kit.dart";
import "package:ffmpeg_kit_flutter/media_information_session.dart";
import "package:ffmpeg_kit_flutter/return_code.dart";

import "types.dart";

/// Part D 黑盒（Flutter 侧入口，做法 a：ffmpeg_kit_flutter）。
///
/// 人话解释：
/// - D 的黑盒先把"媒体管线"做稳：转码/保留音频/进度/取消/错误码。
/// - 真实"苦瓜脸效果"以后可以有两种接法：
///   - 先用 ffmpeg filter（可作为临时效果）；
///   - 或走 TRD 的做法 B：原生侧 effect registry 注入逐帧效果（后续再扩展）。
///
/// 现在先实现一个可用的 MVP：effectId="passthrough" 也会走 ffmpeg 生成新文件（保留音频）。
class MediaProcessingKit {
  final StreamController<MediaTaskEvent> _controller = StreamController.broadcast();
  final Map<String, dynamic> _sessions = {}; // taskId -> FFmpegSession

  static const _methodChannel = MethodChannel('media_processing_kit/methods');
  static const _eventChannel = EventChannel('media_processing_kit/events');
  StreamSubscription? _nativeEventSub;

  /// 初始化（这里先作为占位：未来可做 contractVersion 校验、日志开关等）
  Future<void> initialize({String contractVersionExpected = "1.0.0"}) async {
    // Subscribe to native events and forward them to our stream
    _nativeEventSub ??= _eventChannel.receiveBroadcastStream().listen((event) {
      if (event is Map) {
        final e = MediaTaskEvent.fromMap(event);
        _controller.add(e);
      }
    });

    try {
      await _methodChannel.invokeMethod('initialize');
    } catch (_) {
      // Native channel may not be available (e.g. Windows)
    }
  }

  Stream<MediaTaskEvent> get events => _controller.stream;

  Future<String> processVideo({
    required String inputPath,
    required String outputPath,
    String effectId = "passthrough",
    BitterFaceConfig config = const BitterFaceConfig(),
  }) async {
    final taskId = DateTime.now().microsecondsSinceEpoch.toString();

    // ffmpeg_kit_flutter 不支持 Windows 桌面调试
    if (kIsWeb || !(Platform.isAndroid || Platform.isIOS)) {
      _controller.add(MediaTaskEvent(
        taskId: taskId,
        type: MediaTaskEventType.error,
        errorCode: MediaProcessingErrorCode.unsupportedPlatform,
        errorMessage: "processVideo only supported on Android/iOS.",
      ));
      return taskId;
    }

    // Route bitter_face through native MethodChannel (ML Kit + OpenCV pipeline)
    if (effectId == "bitter_face") {
      try {
        final nativeTaskId = await _methodChannel.invokeMethod<String>('processVideo', {
          'inputPath': inputPath,
          'outputPath': outputPath,
          'effectId': effectId,
          'config': config.toMap(),
        });
        return nativeTaskId ?? taskId;
      } catch (e) {
        _controller.add(MediaTaskEvent(
          taskId: taskId,
          type: MediaTaskEventType.error,
          errorCode: "NATIVE_ERROR",
          errorMessage: e.toString(),
        ));
        return taskId;
      }
    }

    // 1) 先拿 duration，方便算进度（统计回调会给处理到的时间戳）
    final durationMs = await _probeDurationMs(inputPath);

    // 2) 构造 ffmpeg 命令
    // 说明：
    // - passthrough 模式直接流复制（不需要外部编码器，速度快且无损）。
    // - 需要重编码的效果（如 watermark/bitter_face）后续扩展时需切换到 min-gpl 包。
    final escapedIn = _q(inputPath);
    final escapedOut = _q(outputPath);

    // effectId 目前只支持 passthrough；未来可扩展：
    // - "watermark"：用 drawtext 证明效果注入（需 min-gpl 包提供 libx264）
    // - "bitter_face"：走原生 effect registry（做法 B）
    final bool isPassthrough = (effectId == "passthrough");

    final cmd = isPassthrough
        ? [
            "-y",
            "-i",
            escapedIn,
            "-c:v",
            "copy",
            "-c:a",
            "aac",
            "-movflags",
            "+faststart",
            escapedOut,
          ].join(" ")
        : [
            "-y",
            "-i",
            escapedIn,
            "-vf",
            "drawtext=text='${_escapeDrawText(effectId)}':x=10:y=10:fontsize=24:fontcolor=white",
            "-c:v",
            "libx264",
            "-pix_fmt",
            "yuv420p",
            "-c:a",
            "aac",
            "-movflags",
            "+faststart",
            escapedOut,
          ].join(" ");

    // 3) 发起异步任务，并通过 statistics 回调不断推送 progress
    _controller.add(MediaTaskEvent(taskId: taskId, type: MediaTaskEventType.progress, progress: 0));

    // 注意：这是全局 callback。我们用 taskId 是否在 _sessions 里来过滤事件。
    FFmpegKitConfig.enableStatisticsCallback((stats) {
      // stats.time 是"处理到的媒体时间"（ms），可用来算进度
      if (!_sessions.containsKey(taskId)) return; // 只认当前 task
      final t = stats.getTime();
      if (durationMs > 0 && t >= 0) {
        final p = (t / durationMs).clamp(0.0, 1.0);
        _controller.add(MediaTaskEvent(taskId: taskId, type: MediaTaskEventType.progress, progress: p));
      }
    });

    dynamic session;
    try {
      session = await FFmpegKit.executeAsync(cmd, (session) async {
      _sessions.remove(taskId);

      final rc = await session.getReturnCode();
      if (ReturnCode.isSuccess(rc)) {
        _controller.add(MediaTaskEvent(
          taskId: taskId,
          type: MediaTaskEventType.progress,
          progress: 1.0,
        ));
        _controller.add(MediaTaskEvent(
          taskId: taskId,
          type: MediaTaskEventType.completed,
          outputPath: outputPath,
        ));
      } else if (ReturnCode.isCancel(rc)) {
        _controller.add(MediaTaskEvent(
          taskId: taskId,
          type: MediaTaskEventType.error,
          errorCode: "VIDEO_PROCESS_CANCELLED",
          errorMessage: "Cancelled",
        ));
      } else {
        final failStack = await session.getFailStackTrace();
        _controller.add(MediaTaskEvent(
          taskId: taskId,
          type: MediaTaskEventType.error,
          errorCode: "ENCODE_FAILED",
          errorMessage: failStack ?? "ffmpeg failed",
        ));
      }
      });
    } catch (e) {
      _sessions.remove(taskId);
      _controller.add(MediaTaskEvent(
        taskId: taskId,
        type: MediaTaskEventType.error,
        errorCode: "ENCODE_FAILED",
        errorMessage: e.toString(),
      ));
      return taskId;
    }

    _sessions[taskId] = session;
    return taskId;
  }

  Future<String> processImage({
    required String inputPath,
    required String outputPath,
    String effectId = "passthrough",
    BitterFaceConfig config = const BitterFaceConfig(),
  }) async {
    final localTaskId = DateTime.now().microsecondsSinceEpoch.toString();

    // Route bitter_face through native MethodChannel (ML Kit + OpenCV pipeline)
    if (effectId == "bitter_face" && !kIsWeb && (Platform.isAndroid || Platform.isIOS)) {
      try {
        final taskId = await _methodChannel.invokeMethod<String>('processImage', {
          'inputPath': inputPath,
          'outputPath': outputPath,
          'effectId': effectId,
          'config': config.toMap(),
        });
        // Native side reports progress/completion via EventChannel.
        return taskId ?? localTaskId;
      } catch (e) {
        debugPrint('Native processImage error: $e');
        // Fall through to file copy as fallback
      }
    }

    // Fallback: file copy (passthrough), still emit events so callers can keep one flow.
    Future<void>(() async {
      _controller.add(MediaTaskEvent(
        taskId: localTaskId,
        type: MediaTaskEventType.progress,
        progress: 0.0,
      ));
      try {
        final inFile = File(inputPath);
        final outFile = File(outputPath);
        await outFile.parent.create(recursive: true);
        await inFile.copy(outputPath);
        _controller.add(MediaTaskEvent(
          taskId: localTaskId,
          type: MediaTaskEventType.progress,
          progress: 1.0,
        ));
        _controller.add(MediaTaskEvent(
          taskId: localTaskId,
          type: MediaTaskEventType.completed,
          outputPath: outputPath,
        ));
      } catch (e) {
        _controller.add(MediaTaskEvent(
          taskId: localTaskId,
          type: MediaTaskEventType.error,
          errorCode: "COPY_FAILED",
          errorMessage: e.toString(),
        ));
      }
    });
    return localTaskId;
  }

  Future<void> cancel(String taskId) async {
    final session = _sessions[taskId];
    if (session != null) {
      await session.cancel();
    }
    // Also try to cancel via native channel (for bitter_face tasks)
    try {
      await _methodChannel.invokeMethod('cancel', {'taskId': taskId});
    } catch (_) {
      // Native channel may not be available
    }
  }

  Future<double> _probeDurationMs(String inputPath) async {
    try {
      final MediaInformationSession s = await FFprobeKit.getMediaInformationAsync(inputPath);
      final info = s.getMediaInformation();
      final dur = info?.getDuration();
      if (dur == null) return 0;
      final seconds = double.tryParse(dur) ?? 0;
      return seconds * 1000.0;
    } catch (_) {
      return 0;
    }
  }

  String _q(String p) {
    // ffmpeg 命令行的简单引号包装（处理空格路径）
    if (p.contains(" ")) return "\"$p\"";
    return p;
  }

  String _escapeDrawText(String s) {
    // drawtext 里常见需要转义的字符
    return s.replaceAll(":", "\\:").replaceAll("'", "\\'");
  }
}
