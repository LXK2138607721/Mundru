package com.lumina.cleaner.ui.deck

/** 每个卡片图层只保留当前效果；半径以实际像素精确匹配，不量化动画。 */
internal class DeckBlurCache<T : Any>(private val create: (Float) -> T) {
    private var radius = 0f
    private var effect: T? = null

    fun at(px: Float): T? {
        if (px != radius) {
            effect = if (px > 0f) create(px) else null
            radius = px
        }
        return effect
    }
}
