package com.lumina.cleaner.ui.deck

import com.lumina.cleaner.core.model.PhotoItem

/**
 * 卡片主舞台会话纯状态模型 (DeckSessionState)
 *
 * 核心语义铁律：
 * 1. 左滑保留 (keepCurrent)：当前照片保留在 [visibleDeck] 中，[currentIndex] 前进一步；
 * 2. 上滑回收 (trashCurrent)：当前照片立即移出 [visibleDeck]，并计入本轮回收统计，[currentIndex] 保持不变从而自然指向下一张；
 * 3. 右滑回看 (returnToPrevious)：严格返回 [visibleDeck] 中的上一张「保留照片」([currentIndex - 1])，
 *    已经进入回收站的照片绝不会因右滑而重新复活回到主界面。
 */
data class DeckSessionState(
    val visibleDeck: List<PhotoItem> = emptyList(),
    val currentIndex: Int = 0,
    val initialBatchCount: Int = 0,
    val trashedInBatchCount: Int = 0,
    val trashedBytesInBatch: Long = 0L
) {
    val currentPhoto: PhotoItem?
        get() = visibleDeck.getOrNull(currentIndex)

    val previousPhoto: PhotoItem?
        get() = if (currentIndex > 0) visibleDeck.getOrNull(currentIndex - 1) else null

    val hasPrevious: Boolean
        get() = currentIndex > 0 && previousPhoto != null

    val isBatchComplete: Boolean
        get() = initialBatchCount > 0 && (visibleDeck.isEmpty() || currentIndex >= visibleDeck.size)

    val reviewedCount: Int
        get() = (currentIndex + trashedInBatchCount).coerceIn(0, initialBatchCount)

    /** 左滑保留当前照片，前进至下一张 */
    fun keepCurrent(): DeckSessionState {
        if (currentIndex >= visibleDeck.size) return this
        return copy(currentIndex = currentIndex + 1)
    }

    /**
     * 上滑将指定照片（默认当前照片）移入本地回收站：
     * 立即从可见牌堆中移除该照片，使其彻底脱离右滑回看历史。
     */
    fun trashPhoto(photo: PhotoItem): DeckSessionState {
        val idx = visibleDeck.indexOfFirst { it.id == photo.id }
        if (idx < 0) return this
        val updated = visibleDeck.toMutableList().apply { removeAt(idx) }
        val nextIndex = when {
            idx < currentIndex -> (currentIndex - 1).coerceAtLeast(0)
            else -> currentIndex.coerceAtMost(updated.size)
        }
        return copy(
            visibleDeck = updated,
            currentIndex = nextIndex,
            trashedInBatchCount = trashedInBatchCount + 1,
            trashedBytesInBatch = trashedBytesInBatch + photo.size
        )
    }

    /** 右滑返回上一张已保留的照片（绝不复活已回收照片） */
    fun returnToPrevious(): DeckSessionState {
        if (currentIndex <= 0) return this
        return copy(currentIndex = currentIndex - 1)
    }

    /** 后台异步补齐实况/HDR 元数据时原地静默刷新对应照片，绝不干扰当前索引或活跃手势 */
    fun updatePhotoMetadata(updated: PhotoItem): DeckSessionState {
        val idx = visibleDeck.indexOfFirst { it.id == updated.id }
        if (idx < 0 || visibleDeck[idx] == updated) return this
        val copyList = visibleDeck.toMutableList()
        copyList[idx] = updated
        return copy(visibleDeck = copyList)
    }

    companion object {
        fun startBatch(batch: List<PhotoItem>): DeckSessionState = DeckSessionState(
            visibleDeck = batch,
            currentIndex = 0,
            initialBatchCount = batch.size,
            trashedInBatchCount = 0,
            trashedBytesInBatch = 0L
        )
    }
}
