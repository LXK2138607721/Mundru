package com.lumina.cleaner.ui.deck

import kotlin.math.abs

/**
 * 手势拖拽阶段的意图方向
 */
enum class SwipeIntent {
    NONE,
    LEFT,
    UP,
    RIGHT
}

/**
 * 松手时刻的手势最终决策动作
 */
enum class ReleaseAction {
    COMMIT_RIGHT,
    BOUNCE_RIGHT_FIRST,
    COMMIT_LEFT,
    COMMIT_UP,
    BOUNCE
}

/**
 * 底层卡片在特定进度 p 下的几何尺寸与景深参数
 */
data class DeckDepthMetrics(
    val scale: Float,
    val offsetYDp: Float,
    val blurDp: Float,
    val scrimAlpha: Float
)

/**
 * 卡片舞台底层景深与手势决策纯函数模型
 *
 * 统一管理拖拽预览进度、松手提交裁决、以及第二/第三层卡片的几何与模糊插值。
 */
object DeckDepthModel {

    // 默认常量参数（与 CardStack 保持一致）
    const val FAST_SWIPE_MIN_VELOCITY_HORIZONTAL = 450f
    const val FAST_SWIPE_MIN_DISTANCE_HORIZONTAL = 25f
    const val FAST_SWIPE_MIN_VELOCITY_VERTICAL = -350f
    const val FAST_SWIPE_MIN_DISTANCE_VERTICAL = -18f

    const val HORIZONTAL_DIRECTION_RATIO = 0.6f
    const val VERTICAL_UP_DIRECTION_RATIO = 0.65f

    /**
     * 根据拖拽位移推导当前意图方向（对齐操作阈值与松手决策优先级）
     *
     * 规则：
     * 1. 若当前位移已满足某操作的提交阈值，按操作优先级（右 -> 左 -> 上）判定，确保预览与最终操作语义 100% 一致；
     * 2. 未达阈值阶段，按方向比例与优先级（右 -> 左 -> 上）判定；
     * 3. 其他情况（包括纯下滑、斜向平局等）-> 无推进意图 (NONE)。
     */
    fun resolveDragIntent(
        dx: Float,
        dy: Float,
        hThreshold: Float = 0f,
        vThreshold: Float = 0f
    ): SwipeIntent {
        val absDx = abs(dx)
        val absDy = abs(dy)

        // 阶段 1：若位移已满足某动作的提交阈值，优先使用与松手一致的操作优先级
        if (hThreshold > 0f && vThreshold > 0f) {
            val reachedRight = dx > hThreshold && absDx >= absDy * HORIZONTAL_DIRECTION_RATIO
            val reachedLeft = dx < -hThreshold && absDx >= absDy * HORIZONTAL_DIRECTION_RATIO
            val reachedUp = dy < -vThreshold && absDy >= absDx * VERTICAL_UP_DIRECTION_RATIO

            if (reachedRight) return SwipeIntent.RIGHT
            if (reachedLeft) return SwipeIntent.LEFT
            if (reachedUp) return SwipeIntent.UP
        }

        // 阶段 2：未达阈值阶段，按方向比例判定意图
        return when {
            dx > 0f && absDx >= absDy * HORIZONTAL_DIRECTION_RATIO -> SwipeIntent.RIGHT
            dx < 0f && absDx >= absDy * HORIZONTAL_DIRECTION_RATIO -> SwipeIntent.LEFT
            dy < 0f && absDy >= absDx * VERTICAL_UP_DIRECTION_RATIO -> SwipeIntent.UP
            else -> SwipeIntent.NONE
        }
    }

    /**
     * 根据拖拽位移与阈值计算目标揭露进度 p ∈ [0, 1]
     *
     * 规则：
     * - 左滑前进时跟随 (-dx / hThreshold)
     * - 上滑前进时跟随 (-dy / vThreshold)
     * - 右滑回看、下滑或无推进意图时目标为 0f
     */
    fun computeTargetProgress(
        dx: Float,
        dy: Float,
        hThreshold: Float,
        vThreshold: Float
    ): Float {
        if (hThreshold <= 0f || vThreshold <= 0f) return 0f

        return when (resolveDragIntent(dx, dy, hThreshold, vThreshold)) {
            SwipeIntent.LEFT -> (-dx / hThreshold).coerceIn(0f, 1f)
            SwipeIntent.UP -> (-dy / vThreshold).coerceIn(0f, 1f)
            SwipeIntent.RIGHT, SwipeIntent.NONE -> 0f
        }
    }

    /**
     * 松手时的裁决函数
     *
     * 严格复用现有 CardStack 的优先级：右滑 -> 左滑 -> 上滑 -> 回弹
     */
    fun resolveReleaseAction(
        dx: Float,
        dy: Float,
        vx: Float,
        vy: Float,
        hThreshold: Float,
        vThreshold: Float,
        hasPreviousPhoto: Boolean
    ): ReleaseAction {
        val absDx = abs(dx)
        val absDy = abs(dy)

        val isFastUp = vy < FAST_SWIPE_MIN_VELOCITY_VERTICAL && dy < FAST_SWIPE_MIN_DISTANCE_VERTICAL
        val isFastRight = vx > FAST_SWIPE_MIN_VELOCITY_HORIZONTAL && dx > FAST_SWIPE_MIN_DISTANCE_HORIZONTAL
        val isFastLeft = vx < -FAST_SWIPE_MIN_VELOCITY_HORIZONTAL && dx < -FAST_SWIPE_MIN_DISTANCE_HORIZONTAL

        val isRight = (dx > hThreshold || isFastRight) && absDx >= absDy * HORIZONTAL_DIRECTION_RATIO
        val isLeft = (dx < -hThreshold || isFastLeft) && absDx >= absDy * HORIZONTAL_DIRECTION_RATIO
        val isUp = (dy < -vThreshold || isFastUp) && absDy >= absDx * VERTICAL_UP_DIRECTION_RATIO

        return when {
            isRight -> if (hasPreviousPhoto) ReleaseAction.COMMIT_RIGHT else ReleaseAction.BOUNCE_RIGHT_FIRST
            isLeft -> ReleaseAction.COMMIT_LEFT
            isUp -> ReleaseAction.COMMIT_UP
            else -> ReleaseAction.BOUNCE
        }
    }

    /**
     * 第二层卡片景深插值：
     * p = 0: scale=0.95, offsetYDp=14, blurDp=12, scrimAlpha=0.18
     * p = 1: scale=1.00, offsetYDp=0,  blurDp=0,  scrimAlpha=0.00
     */
    fun interpolateSecondLayer(progress: Float): DeckDepthMetrics {
        val p = progress.coerceIn(0f, 1f)
        return DeckDepthMetrics(
            scale = 0.95f + 0.05f * p,
            offsetYDp = 14f * (1f - p),
            blurDp = 12f * (1f - p),
            scrimAlpha = 0.18f * (1f - p)
        )
    }

    /**
     * 第三层卡片景深插值：
     * p = 0: scale=0.90, offsetYDp=28, blurDp=16, scrimAlpha=0.26
     * p = 1: scale=0.95, offsetYDp=14, blurDp=12, scrimAlpha=0.18
     */
    fun interpolateThirdLayer(progress: Float): DeckDepthMetrics {
        val p = progress.coerceIn(0f, 1f)
        return DeckDepthMetrics(
            scale = 0.90f + 0.05f * p,
            offsetYDp = 28f - 14f * p,
            blurDp = 16f - 4f * p,
            scrimAlpha = 0.26f - 0.08f * p
        )
    }
}
