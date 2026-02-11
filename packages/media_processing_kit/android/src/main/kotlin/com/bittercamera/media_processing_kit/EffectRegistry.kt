package com.bittercamera.media_processing_kit

import java.util.concurrent.ConcurrentHashMap

/**
 * 效果注册表（做法 B 的关键）：
 * - D（本插件）只依赖这个接口，不依赖 B/C 的实现。
 * - B/C 在自己的插件 onAttachedToEngine 时调用 register(effectId, processor) 注入。
 */
object EffectRegistry {
    private val processors = ConcurrentHashMap<String, EffectProcessor>()

    fun register(effectId: String, processor: EffectProcessor) {
        processors[effectId] = processor
    }

    fun get(effectId: String): EffectProcessor? = processors[effectId]
}

