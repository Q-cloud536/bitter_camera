import Foundation

/// 给 B/C 的示例（不是 D 的实现）：
/// - 真正苦瓜脸效果由 B/C 实现并注册：
///   EffectRegistry.shared.register(effectId: "bitter_face", processor: BitterFaceEffectProcessor())
public final class BitterFaceEffectProcessor: EffectProcessor {
  public init() {}

  public func processVideo(taskId: String, inputPath: String, outputPath: String, config: [String : Any?], callbacks: TaskCallbacks) {
    // TODO(B/C): 解码 -> 人脸关键点 -> 网格形变渲染 -> 编码 -> 音频保留
    callbacks.onError(taskId: taskId, errorCode: "UNKNOWN", errorMessage: "BitterFaceEffectProcessor not implemented (stub)")
  }

  public func processImage(taskId: String, inputPath: String, outputPath: String, config: [String : Any?], callbacks: TaskCallbacks) {
    callbacks.onError(taskId: taskId, errorCode: "UNKNOWN", errorMessage: "BitterFaceEffectProcessor not implemented (stub)")
  }

  public func cancel(taskId: String) {
    // TODO(B/C): 取消长任务
  }
}

