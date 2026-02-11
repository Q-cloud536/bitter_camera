package com.bittercamera.media_processing_kit

/**
 * 效果处理器接口（由 B/C 实现）：
 * - D 的媒体管线会把 task 分发给指定 effectId 对应的 processor
 * - processor 自己决定是否需要解码/逐帧处理/编码（MVP 可先简单实现）
 *
 * 注意：
 * - 这里先只定义“最小可用”的 file-in/file-out 接口，便于尽快联调。
 * - 未来要做真正逐帧处理，可以扩展为 surface/texture/bytebuffer 级别。
 */
interface EffectProcessor {
    fun processVideo(taskId: String, inputPath: String, outputPath: String, config: Map<String, Any?>, callbacks: TaskCallbacks)
    fun processImage(taskId: String, inputPath: String, outputPath: String, config: Map<String, Any?>, callbacks: TaskCallbacks)
    fun cancel(taskId: String)
}

interface TaskCallbacks {
    fun onProgress(taskId: String, progress: Double)
    fun onCompleted(taskId: String, outputPath: String)
    fun onError(taskId: String, errorCode: String, errorMessage: String)
}

