import Foundation

/// 效果处理器协议（由 B/C 实现）：
/// - D 只负责任务管理、事件回调、文件 I/O（MVP 默认 passthrough）
/// - 真正的苦瓜脸效果由 B/C 通过 register(effectId:processor:) 注入
public protocol EffectProcessor {
  func processVideo(taskId: String, inputPath: String, outputPath: String, config: [String: Any?], callbacks: TaskCallbacks)
  func processImage(taskId: String, inputPath: String, outputPath: String, config: [String: Any?], callbacks: TaskCallbacks)
  func cancel(taskId: String)
}

public protocol TaskCallbacks {
  func onProgress(taskId: String, progress: Double)
  func onCompleted(taskId: String, outputPath: String)
  func onError(taskId: String, errorCode: String, errorMessage: String)
}

public final class EffectRegistry {
  public static let shared = EffectRegistry()
  private var processors: [String: EffectProcessor] = [:]
  private let lock = NSLock()

  public func register(effectId: String, processor: EffectProcessor) {
    lock.lock(); defer { lock.unlock() }
    processors[effectId] = processor
  }

  public func get(effectId: String) -> EffectProcessor? {
    lock.lock(); defer { lock.unlock() }
    return processors[effectId]
  }
}

