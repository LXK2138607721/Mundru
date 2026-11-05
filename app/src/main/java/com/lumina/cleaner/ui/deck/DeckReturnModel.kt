package com.lumina.cleaner.ui.deck

import kotlin.math.abs
import kotlin.math.sign

/** 松手时的实际显示值；不使用可能领先于画面的逻辑位移。位移单位为 px。 */
internal data class DeckReturnStart(
    val x: Float, val y: Float, val depth: Float, val vx: Float = 0f, val vy: Float = 0f,
    val scale: Float = 1f, val scaleVelocity: Float = 0f
)

internal data class DeckReturnCardMetrics(
    val scale: Float,
    val x: Float,
    val y: Float,
    val rotation: Float,
    val blurDp: Float,
    val scrimAlpha: Float
)

/** 右滑回看：当前卡片先收拢，滑入上一张下方，再展开为新的第二层。 */
internal object DeckReturnModel {
    const val DURATION_MILLIS = 420
    const val SMALLEST_AT = 0.20f
    const val PEAK_AT = 0.60f
    const val MIN_SCALE = 0.60f
    const val PEAK_SCALE = 1.035f
    private const val DRAG_MIN = 0.66f

    private val secondRest = DeckDepthModel.interpolateSecondLayer(0f)
    private val thirdRest = DeckDepthModel.interpolateThirdLayer(0f)

    private fun dragFraction(x: Float, y: Float, threshold: Float): Float =
        ((x - abs(y) * DeckDepthModel.HORIZONTAL_DIRECTION_RATIO) / (1.6f * threshold)).coerceIn(0f, 1f)

    fun dragScale(x: Float, y: Float, threshold: Float): Float =
        mix(1f, DRAG_MIN, smooth(dragFraction(x, y, threshold)))

    fun capture(x: Float, y: Float, depth: Float, vx: Float, vy: Float, threshold: Float): DeckReturnStart {
        val u = dragFraction(x, y, threshold)
        val axisSpeed = vx - sign(y) * vy * DeckDepthModel.HORIZONTAL_DIRECTION_RATIO
        val rate = (DRAG_MIN - 1f) * 6f * u * (1f - u) * axisSpeed / (1.6f * threshold)
        return DeckReturnStart(x, y, depth, vx, vy, mix(1f, DRAG_MIN, smooth(u)), rate)
    }

    private fun returnScale(p: Float, start: DeckReturnStart): Float {
        if (p <= SMALLEST_AT) {
            val t = p / SMALLEST_AT
            val slope = (start.scaleVelocity * DURATION_MILLIS * 0.001f * SMALLEST_AT)
                .coerceIn(-3f * (start.scale - MIN_SCALE), 0f)
            return mix(start.scale, MIN_SCALE, smooth(t)) + slope * t * (1f - t) * (1f - t)
        }
        if (p < PEAK_AT) return mix(MIN_SCALE, PEAK_SCALE, smooth((p - SMALLEST_AT) / (PEAK_AT - SMALLEST_AT)))
        return mix(PEAK_SCALE, secondRest.scale, smooth((p - PEAK_AT) / (1f - PEAK_AT)))
    }

    fun currentCard(progress: Float, start: DeckReturnStart, density: Float): DeckReturnCardMetrics {
        val p = progress.coerceIn(0f, 1f)
        val travel = smooth(p)
        val scale = returnScale(p, start)
        // Hermite 初速度项仅在起步时起作用，终点位置与速度均不受影响。
        val carry = p * (1f - p) * (1f - p)
        val limit = 128f * density
        return DeckReturnCardMetrics(
            scale = scale,
            x = mix(start.x, 0f, travel) + tangent(start.vx, limit) * carry,
            y = mix(start.y, secondRest.offsetYDp * density, travel) + tangent(start.vy, limit) * carry,
            rotation = mix(if (start.x < 0f) start.x * 0.035f else start.x * 0.012f, 0f, travel),
            blurDp = secondRest.blurDp * travel,
            scrimAlpha = secondRest.scrimAlpha * travel
        )
    }

    fun blurDp(progress: Float) = secondRest.blurDp * smooth(progress)
    fun scrimAlpha(progress: Float) = secondRest.scrimAlpha * smooth(progress)

    fun previousX(progress: Float, initialX: Float, vx: Float = 0f): Float {
        if (initialX == 0f) return 0f
        val p = progress.coerceIn(0f, 1f)
        // 根据剩余距离限制初速度，使上一张始终向中心靠拢而不会越过中心。
        val slope = (vx * (DURATION_MILLIS / 1000f) / -initialX).coerceIn(0f, 3f)
        return initialX * (1f - p) * (1f - p) * (1f + (2f - slope) * p)
    }

    private fun tangent(velocity: Float, limit: Float) =
        (velocity * (DURATION_MILLIS / 1000f)).coerceIn(-limit, limit)

    fun previousAlpha(progress: Float, start: DeckReturnStart): Float =
        mix(if (start.x > 0f) 1f else 0.001f, 1f, smooth(progress / SMALLEST_AT))

    // 拉入卡片的额外阴影随交接消退，终点与普通顶层卡片一致。
    fun previousShadowDp(progress: Float, start: DeckReturnStart): Float =
        (if (start.x > 0f) 16f else 0f) * (1f - smooth(progress))

    fun nextLayer(progress: Float, startDepth: Float): DeckDepthMetrics {
        val start = DeckDepthModel.interpolateSecondLayer(startDepth)
        val retreat = smooth(progress)
        return DeckDepthMetrics(
            scale = mix(start.scale, thirdRest.scale, retreat),
            offsetYDp = mix(start.offsetYDp, thirdRest.offsetYDp, retreat),
            blurDp = mix(start.blurDp, thirdRest.blurDp, retreat),
            scrimAlpha = mix(start.scrimAlpha, thirdRest.scrimAlpha, retreat)
        )
    }

    // 原第三张将移到可见堆栈之外，先淡出，避免索引交接时突然消失。
    fun thirdAlpha(progress: Float): Float = 1f - smooth(progress)

    private fun smooth(progress: Float): Float {
        val p = progress.coerceIn(0f, 1f)
        return p * p * (3f - 2f * p)
    }

    private fun mix(from: Float, to: Float, fraction: Float): Float =
        from + (to - from) * fraction
}
