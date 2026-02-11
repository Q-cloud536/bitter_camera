package com.bittercamera.media_processing_kit

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 默认内置的 effect：passthrough
 *
 * 人话解释：
 * - 先不做任何“苦瓜脸”处理
 * - 只把文件复制一份，并按进度回调 0 -> 1
 * - 用于让 A/C/E 先跑通 D 黑盒的任务管理/进度/取消/错误码
 */
class PassthroughEffectProcessor : EffectProcessor {
    private val cancelled = ConcurrentHashMap<String, AtomicBoolean>()

    override fun processVideo(
        taskId: String,
        inputPath: String,
        outputPath: String,
        config: Map<String, Any?>,
        callbacks: TaskCallbacks
    ) {
        cancelled[taskId] = AtomicBoolean(false)
        try {
            callbacks.onProgress(taskId, 0.0)
            if (cancelled[taskId]?.get() == true) {
                callbacks.onError(taskId, "VIDEO_PROCESS_CANCELLED", "Cancelled")
                return
            }
            val inFile = File(inputPath)
            val outFile = File(outputPath)
            outFile.parentFile?.mkdirs()
            inFile.copyTo(outFile, overwrite = true)
            callbacks.onProgress(taskId, 1.0)
            callbacks.onCompleted(taskId, outFile.absolutePath)
        } catch (e: Exception) {
            callbacks.onError(taskId, "UNKNOWN", e.message ?: "unknown")
        } finally {
            cancelled.remove(taskId)
        }
    }

    override fun processImage(
        taskId: String,
        inputPath: String,
        outputPath: String,
        config: Map<String, Any?>,
        callbacks: TaskCallbacks
    ) {
        // 图片也先做 copy，便于联调
        processVideo(taskId, inputPath, outputPath, config, callbacks)
    }

    override fun cancel(taskId: String) {
        cancelled[taskId]?.set(true)
    }
}

