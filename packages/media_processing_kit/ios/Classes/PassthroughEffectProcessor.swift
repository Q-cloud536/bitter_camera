import Foundation

/// 默认内置 passthrough effect：
/// - 仅 copy 文件，用于联调任务/进度/取消/事件回调
public final class PassthroughEffectProcessor: EffectProcessor {
  private var cancelled: Set<String> = []
  private let lock = NSLock()

  public init() {}

  public func processVideo(taskId: String, inputPath: String, outputPath: String, config: [String : Any?], callbacks: TaskCallbacks) {
    callbacks.onProgress(taskId: taskId, progress: 0.0)
    if isCancelled(taskId: taskId) {
      callbacks.onError(taskId: taskId, errorCode: "VIDEO_PROCESS_CANCELLED", errorMessage: "Cancelled")
      return
    }

    do {
      let fm = FileManager.default
      let outURL = URL(fileURLWithPath: outputPath)
      try fm.createDirectory(at: outURL.deletingLastPathComponent(), withIntermediateDirectories: true)
      if fm.fileExists(atPath: outputPath) {
        try fm.removeItem(atPath: outputPath)
      }
      try fm.copyItem(atPath: inputPath, toPath: outputPath)
      callbacks.onProgress(taskId: taskId, progress: 1.0)
      callbacks.onCompleted(taskId: taskId, outputPath: outputPath)
    } catch {
      callbacks.onError(taskId: taskId, errorCode: "UNKNOWN", errorMessage: error.localizedDescription)
    }
    clear(taskId: taskId)
  }

  public func processImage(taskId: String, inputPath: String, outputPath: String, config: [String : Any?], callbacks: TaskCallbacks) {
    processVideo(taskId: taskId, inputPath: inputPath, outputPath: outputPath, config: config, callbacks: callbacks)
  }

  public func cancel(taskId: String) {
    lock.lock(); defer { lock.unlock() }
    cancelled.insert(taskId)
  }

  private func isCancelled(taskId: String) -> Bool {
    lock.lock(); defer { lock.unlock() }
    return cancelled.contains(taskId)
  }

  private func clear(taskId: String) {
    lock.lock(); defer { lock.unlock() }
    cancelled.remove(taskId)
  }
}

