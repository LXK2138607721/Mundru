package com.lumina.cleaner.ui.trash

import kotlin.math.abs
import kotlin.math.min

/**
 * 回收站桌面卸载级屏幕空间自动补位规划器（纯 Kotlin，可单测）。
 *
 * 解决 Compose 原生 LazyVerticalGrid.animateItem 在「整行删第 1 行 + 隔行删第 3/5 行」
 * 以及「向下滚动后列表高度骤减触发 scrollOffset 夹紧」时的位移动画丢失与同帧穿透叠压问题：
 * 1. 基于删前视口真实像素坐标 (oldX, oldY) 与删后视口真实坐标 (newX, newY) 计算精确偏移，
 *    完全免疫 firstVisibleItemIndex 跳变与 consumedScroll 夹紧；
 * 2. 对跨列/跨行补位项施加克制的 14ms 多米诺级联错峰（上限 140ms），让前格先让出空间、
 *    后格紧随丝滑汇入，彻底消除卡片空中互相撞击遮挡；
 * 3. 对下方新露出的底部行计算自然上滑入场距离与柔和渐显，杜绝底部突然闪现。
 */
data class ReflowTilePlan(
    val deltaX: Float,
    val deltaY: Float,
    val staggerDelayMs: Long,
    val isEnteringFromBottom: Boolean,
    val zOrder: Float
) {
    val hasMotion: Boolean
        get() = abs(deltaX) > 1.5f || abs(deltaY) > 1.5f || isEnteringFromBottom
}

object RecycleBinReflowPlanner {

    const val STAGGER_STEP_MS: Long = 14L
    const val MAX_STAGGER_MS: Long = 140L

    /**
     * 为一次网格项移除计算单个留存卡片的起始屏幕空间偏移与错峰节拍。
     *
     * @param newIndex 卡片在移除后的新列表索引（从 0 开始）
     * @param oldPosPx 卡片在移除前记录的视口相对坐标 (x, y)，若此前在屏外下方则为 null
     * @param newPosPx 卡片在移除后布局落定的视口相对坐标 (x, y)
     * @param rowPitchPx 单行步进高度（卡片高度 + 行间距，px）
     * @param removedBeforeNewCount 原列表中排在该卡片之前（且已被本次移除）的卡片数量
     * @param columns 网格列数（默认 3）
     * @param shiftRank 该卡片在所有发生移动的可见卡片中的先后序号（0, 1, 2...）
     * @param visibleViewportHeightPx 网格可见区高度，用于过滤明显在视口上方之外的新入项
     */
    fun planForTile(
        newIndex: Int,
        oldPosPx: Pair<Float, Float>?,
        newPosPx: Pair<Float, Float>,
        rowPitchPx: Float,
        removedBeforeNewCount: Int,
        columns: Int = 3,
        shiftRank: Int = 0,
        visibleViewportHeightPx: Float = 2400f
    ): ReflowTilePlan {
        // 靠前的卡片层级略高于靠后卡片，避免斜向跨行补位时后方卡片随机遮住前方正在滑动的卡片
        val zOrder = 1f + (1f / (newIndex + 2f))

        if (oldPosPx != null) {
            val dx = oldPosPx.first - newPosPx.first
            val dy = oldPosPx.second - newPosPx.second
            val moved = abs(dx) > 1.5f || abs(dy) > 1.5f
            if (!moved) {
                return ReflowTilePlan(
                    deltaX = 0f,
                    deltaY = 0f,
                    staggerDelayMs = 0L,
                    isEnteringFromBottom = false,
                    zOrder = 0f
                )
            }
            val delayMs = min(shiftRank.coerceAtLeast(0) * STAGGER_STEP_MS, MAX_STAGGER_MS)
            return ReflowTilePlan(
                deltaX = dx,
                deltaY = dy,
                staggerDelayMs = delayMs,
                isEnteringFromBottom = false,
                zOrder = zOrder
            )
        }

        // 原先在视口下方屏外、因上方卡片移除而新露出的底部行：从下方平滑上滑浮现
        val safeCols = columns.coerceAtLeast(1)
        val isWithinViewport = newPosPx.second >= -rowPitchPx * 0.5f && newPosPx.second <= visibleViewportHeightPx + rowPitchPx
        if (removedBeforeNewCount > 0 && rowPitchPx > 1f && isWithinViewport) {
            val rowsShifted = ((removedBeforeNewCount + safeCols - 1) / safeCols).coerceIn(1, 2)
            val entryDy = rowsShifted * rowPitchPx * 0.82f
            val delayMs = min(shiftRank.coerceAtLeast(0) * STAGGER_STEP_MS, MAX_STAGGER_MS)
            return ReflowTilePlan(
                deltaX = 0f,
                deltaY = entryDy,
                staggerDelayMs = delayMs,
                isEnteringFromBottom = true,
                zOrder = zOrder * 0.5f
            )
        }

        return ReflowTilePlan(
            deltaX = 0f,
            deltaY = 0f,
            staggerDelayMs = 0L,
            isEnteringFromBottom = false,
            zOrder = 0f
        )
    }

    /**
     * 统计在旧列表 [oldIds] 中，每个留存项之前共移除了多少项（用于底部新入行上滑距离估算）。
     */
    fun countRemovedBeforeEachSurvivor(
        oldIds: List<Long>,
        removedIds: Set<Long>
    ): Map<Long, Int> {
        if (oldIds.isEmpty() || removedIds.isEmpty()) return emptyMap()
        val result = HashMap<Long, Int>(oldIds.size)
        var removedSoFar = 0
        for (id in oldIds) {
            if (id in removedIds) {
                removedSoFar++
            } else if (removedSoFar > 0) {
                result[id] = removedSoFar
            }
        }
        return result
    }
}
