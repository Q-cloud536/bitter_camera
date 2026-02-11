import Flutter
import Foundation

public class MediaProcessingKitPlugin: NSObject, FlutterPlugin, FlutterStreamHandler {
  private var eventSink: FlutterEventSink?
  private let queue = DispatchQueue(label: "media_processing_kit.queue", qos: .userInitiated)

  public static func register(with registrar: FlutterPluginRegistrar) {
    let instance = MediaProcessingKitPlugin()

    let methodChannel = FlutterMethodChannel(name: "media_processing_kit/methods", binaryMessenger: registrar.messenger())
    registrar.addMethodCallDelegate(instance, channel: methodChannel)

    let eventChannel = FlutterEventChannel(name: "media_processing_kit/events", binaryMessenger: registrar.messenger())
    eventChannel.setStreamHandler(instance)

    // 默认注册 passthrough
    if EffectRegistry.shared.get(effectId: "passthrough") == nil {
      EffectRegistry.shared.register(effectId: "passthrough", processor: PassthroughEffectProcessor())
    }
  }

  public func onListen(withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink) -> FlutterError? {
    eventSink = events
    return nil
  }

  public func onCancel(withArguments arguments: Any?) -> FlutterError? {
    eventSink = nil
    return nil
  }

  public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
    switch call.method {
    case "initialize":
      // 未来可校验 contractVersionExpected
      result(nil)

    case "processVideo":
      guard let args = call.arguments as? [String: Any],
            let inputPath = args["inputPath"] as? String,
            let outputPath = args["outputPath"] as? String else {
        result("")
        return
      }
      let effectId = (args["effectId"] as? String) ?? "passthrough"
      let config = (args["config"] as? [String: Any?]) ?? [:]

      let taskId = UUID().uuidString
      result(taskId)

      guard let processor = EffectRegistry.shared.get(effectId: effectId) else {
        sendError(taskId: taskId, code: "UNKNOWN", message: "Effect not registered: \(effectId)")
        return
      }

      queue.async {
        processor.processVideo(taskId: taskId, inputPath: inputPath, outputPath: outputPath, config: config, callbacks: self.callbacks())
      }

    case "processImage":
      guard let args = call.arguments as? [String: Any],
            let inputPath = args["inputPath"] as? String,
            let outputPath = args["outputPath"] as? String else {
        result("")
        return
      }
      let effectId = (args["effectId"] as? String) ?? "passthrough"
      let config = (args["config"] as? [String: Any?]) ?? [:]

      let taskId = UUID().uuidString
      // Return task ID immediately; completion/progress/errors are delivered via EventChannel.
      result(taskId)

      guard let processor = EffectRegistry.shared.get(effectId: effectId) else {
        sendError(taskId: taskId, code: "UNKNOWN", message: "Effect not registered: \(effectId)")
        return
      }

      queue.async {
        processor.processImage(taskId: taskId, inputPath: inputPath, outputPath: outputPath, config: config, callbacks: self.callbacks())
      }

    case "cancel":
      guard let args = call.arguments as? [String: Any],
            let taskId = args["taskId"] as? String else {
        result(nil)
        return
      }
      // 简化：只取消已注册 processor 的任务（未知 taskId 会忽略）
      EffectRegistry.shared.get(effectId: "passthrough")?.cancel(taskId: taskId)
      result(nil)

    default:
      result(FlutterMethodNotImplemented)
    }
  }

  private func callbacks() -> TaskCallbacks {
    return Callbacks(
      onProgressBlock: { taskId, progress in
        self.send(["taskId": taskId, "type": "progress", "progress": progress])
      },
      onCompletedBlock: { taskId, outputPath in
        self.send(["taskId": taskId, "type": "completed", "outputPath": outputPath])
      },
      onErrorBlock: { taskId, code, message in
        self.send(["taskId": taskId, "type": "error", "errorCode": code, "errorMessage": message])
      }
    )
  }

  private func sendError(taskId: String, code: String, message: String) {
    send(["taskId": taskId, "type": "error", "errorCode": code, "errorMessage": message])
  }

  private func send(_ payload: [String: Any]) {
    eventSink?(payload)
  }
}

private final class Callbacks: TaskCallbacks {
  let onProgressBlock: (String, Double) -> Void
  let onCompletedBlock: (String, String) -> Void
  let onErrorBlock: (String, String, String) -> Void

  init(
    onProgressBlock: @escaping (String, Double) -> Void,
    onCompletedBlock: @escaping (String, String) -> Void,
    onErrorBlock: @escaping (String, String, String) -> Void
  ) {
    self.onProgressBlock = onProgressBlock
    self.onCompletedBlock = onCompletedBlock
    self.onErrorBlock = onErrorBlock
  }

  func onProgress(taskId: String, progress: Double) { onProgressBlock(taskId, progress) }
  func onCompleted(taskId: String, outputPath: String) { onCompletedBlock(taskId, outputPath) }
  func onError(taskId: String, errorCode: String, errorMessage: String) { onErrorBlock(taskId, errorCode, errorMessage) }
}

