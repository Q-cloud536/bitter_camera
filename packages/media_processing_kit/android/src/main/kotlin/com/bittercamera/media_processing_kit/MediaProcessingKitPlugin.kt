package com.bittercamera.media_processing_kit

import android.os.Handler
import android.os.Looper
import androidx.annotation.NonNull
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Flutter 插件入口（浅显解释）：
 * - Dart 调用 MethodChannel：initialize / processVideo / processImage / cancel
 * - Native 通过 EventChannel 持续把“进度/完成/失败”事件发回 Dart
 *
 * 做法 B 的关键点：
 * - effectId -> EffectRegistry.get(effectId) -> EffectProcessor
 * - D 不实现苦瓜脸；默认只内置 passthrough processor 让大家联调
 */
class MediaProcessingKitPlugin : FlutterPlugin, MethodChannel.MethodCallHandler, EventChannel.StreamHandler {
    private lateinit var methodChannel: MethodChannel
    private lateinit var eventChannel: EventChannel

    @Volatile private var eventSink: EventChannel.EventSink? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val executor = Executors.newCachedThreadPool()

    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        methodChannel = MethodChannel(flutterPluginBinding.binaryMessenger, "media_processing_kit/methods")
        methodChannel.setMethodCallHandler(this)

        eventChannel = EventChannel(flutterPluginBinding.binaryMessenger, "media_processing_kit/events")
        eventChannel.setStreamHandler(this)

        // 默认注册 passthrough processor
        if (EffectRegistry.get("passthrough") == null) {
            EffectRegistry.register("passthrough", PassthroughEffectProcessor())
        }
        // 注册真正的苦瓜脸特效处理器
        if (EffectRegistry.get("bitter_face") == null) {
            EffectRegistry.register("bitter_face", BitterFaceEffectProcessor())
        }
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        methodChannel.setMethodCallHandler(null)
        eventChannel.setStreamHandler(null)
        eventSink = null
        executor.shutdown()
    }

    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        eventSink = events
    }

    override fun onCancel(arguments: Any?) {
        eventSink = null
    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: MethodChannel.Result) {
        when (call.method) {
            "initialize" -> {
                // 目前只做占位：未来可校验 contractVersionExpected 等
                result.success(null)
            }
            "processVideo" -> {
                val inputPath = call.argument<String>("inputPath") ?: ""
                val outputPath = call.argument<String>("outputPath") ?: ""
                val effectId = call.argument<String>("effectId") ?: "passthrough"
                val config = call.argument<Map<String, Any?>>("config") ?: emptyMap()

                val taskId = UUID.randomUUID().toString()
                result.success(taskId)

                val processor = EffectRegistry.get(effectId)
                if (processor == null) {
                    sendError(taskId, "UNKNOWN", "Effect not registered: $effectId")
                    return
                }

                executor.execute {
                    try {
                        processor.processVideo(taskId, inputPath, outputPath, config, callbacks())
                    } catch (t: Throwable) {
                        sendError(taskId, "NATIVE_CRASH", t.message ?: "Native processor crashed")
                    }
                }
            }
            "processImage" -> {
                val inputPath = call.argument<String>("inputPath") ?: ""
                val outputPath = call.argument<String>("outputPath") ?: ""
                val effectId = call.argument<String>("effectId") ?: "passthrough"
                val config = call.argument<Map<String, Any?>>("config") ?: emptyMap()

                val taskId = UUID.randomUUID().toString()
                // Return task ID immediately; completion/progress/errors are delivered via EventChannel.
                result.success(taskId)

                val processor = EffectRegistry.get(effectId)
                if (processor == null) {
                    sendError(taskId, "UNKNOWN", "Effect not registered: $effectId")
                    return
                }

                executor.execute {
                    try {
                        processor.processImage(taskId, inputPath, outputPath, config, callbacks())
                    } catch (t: Throwable) {
                        sendError(taskId, "NATIVE_CRASH", t.message ?: "Native processor crashed")
                    }
                }
            }
            "cancel" -> {
                val taskId = call.argument<String>("taskId") ?: ""
                // 广播给所有 processor（简单但有效；未来可做 taskId->processor 映射）
                // 如果 processor 没有这个 taskId，会忽略。
                // 注意：为了简单，这里只调用已注册的 processors。
                listOfNotNull(
                    EffectRegistry.get("passthrough"),
                    EffectRegistry.get("bitter_face")
                ).forEach { it.cancel(taskId) }
                result.success(null)
            }
            else -> result.notImplemented()
        }
    }

    private fun callbacks(): TaskCallbacks {
        return object : TaskCallbacks {
            override fun onProgress(taskId: String, progress: Double) {
                send(mapOf("taskId" to taskId, "type" to "progress", "progress" to progress))
            }

            override fun onCompleted(taskId: String, outputPath: String) {
                send(mapOf("taskId" to taskId, "type" to "completed", "outputPath" to outputPath))
            }

            override fun onError(taskId: String, errorCode: String, errorMessage: String) {
                sendError(taskId, errorCode, errorMessage)
            }
        }
    }

    private fun sendError(taskId: String, code: String, message: String) {
        send(mapOf("taskId" to taskId, "type" to "error", "errorCode" to code, "errorMessage" to message))
    }

    private fun send(payload: Map<String, Any?>) {
        val sink = eventSink ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            sink.success(payload)
            return
        }
        mainHandler.post {
            eventSink?.success(payload)
        }
    }
}

