package com.lumina.cleaner.ui.deck

import androidx.compose.runtime.Immutable
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.pow

@Immutable
data class DeckTrashArcStart(
    val startX: Float,
    val startY: Float,
    val targetX: Float,
    val targetY: Float,
    val vx: Float,
    val vy: Float,
    val startScale: Float,
    val startRotationZ: Float,
    val durationMillis: Int
)

@Immutable
data class DeckTrashArcFrame(
    val x: Float,
    val y: Float,
    val scaleX: Float,
    val scaleY: Float,
    val rotationZ: Float,
    val alpha: Float,
    val glowAlpha: Float
)

/**
 * 三段式上滑回收物理动效模型 (DeckTrashArcModel)
 *
 * 1. 拉扯蓄力期 (Pull Tension)：
 *    卡片随上推柔和微缩、抬升边缘朱红微光；右上角液态玻璃回收站桶盖同步实时掀开 (0° -> 34°，过阈值弹至 44°)。
 * 2. 速度自适应神灯吸入弧线 (Velocity-Adaptive Genie Suction)：
 *    继承松手时的真实速度向量 (vx, vy)，沿二次贝塞尔轨迹精准飞向右上角回收站物理锚点，
 *    飞行时长随上滑快慢在 210ms ~ 295ms 间自适应缩放，并在尾段呈现挤压拉伸 (Squash & Stretch) 吸入感。
 * 3. 命中瞬间的视觉基准：
 *    progress = 1f 时恰好落入桶口中心且 alpha = 0f，同步触发重触感、桶盖过冲合拢与液态玻璃果冻压扁回弹。
 */
object DeckTrashArcModel {

    const val MIN_DURATION_MS = 210
    const val MAX_DURATION_MS = 295

    /**
     * 计算当前拖拽处于「上滑回收蓄力」的归一化进度 [0f, 1f]。
     * 仅当向上位移明显占优时递增，绝不干扰左滑保留与右滑回看。
     */
    fun dragTrashPull(
        dx: Float,
        dy: Float,
        vThreshold: Float
    ): Float {
        if (dy >= 0f || vThreshold <= 0f) return 0f
        val up = -dy
        val absX = abs(dx)
        // 与 DeckDepthModel 上滑裁决方向角保持一致，带平滑方向权重避免斜滑突跳
        val directionRatio = up / (up + absX * 0.85f + 1f)
        val directionWeight = ((directionRatio - 0.35f) / 0.30f).coerceIn(0f, 1f)
        val distProgress = (up / (vThreshold * 1.35f)).coerceIn(0f, 1f)
        return (distProgress * directionWeight).coerceIn(0f, 1f)
    }

    /** 上推拖拽时的卡片自然蓄力微缩 (1.0f -> 0.86f) */
    fun dragScale(pull: Float): Float {
        val p = pull.coerceIn(0f, 1f)
        val eased = p * (2f - p)
        return 1f - 0.14f * eased
    }

    /** 上推拖拽时的轻微 3D 向后仰角 (0° -> 9.5°)，增强"向上抛入"的空间立体感 */
    fun dragTiltXDegrees(pull: Float): Float {
        val p = pull.coerceIn(0f, 1f)
        return 9.5f * p * (2f - p)
    }

    /** 上推拖拽时朱红微光边框透明度 (0f -> 0.85f) */
    fun dragGlowAlpha(pull: Float, thresholdReached: Boolean): Float {
        val p = pull.coerceIn(0f, 1f)
        val base = (p * 0.72f).coerceIn(0f, 0.72f)
        return if (thresholdReached) (base + 0.18f).coerceAtMost(0.92f) else base
    }

    /** 拖拽阶段回收站桶盖掀开角度：平滑掀开 0° -> 34°，过阈值立刻弹至 44° */
    fun lidOpenDegrees(pull: Float, thresholdReached: Boolean): Float {
        val p = pull.coerceIn(0f, 1f)
        return if (thresholdReached) 44f else 34f * (p * (2f - p))
    }

    /** 根据松手瞬间向上速度自适应计算飞行耗时 (210ms ~ 295ms) */
    fun durationForVelocity(vy: Float, density: Float): Int {
        val upSpeedDpPerSec = (-vy).coerceAtLeast(0f) / density.coerceAtLeast(1f)
        // 0 dp/s -> 295ms; >= 1100 dp/s -> 210ms
        val fastFactor = (upSpeedDpPerSec / 1100f).coerceIn(0f, 1f)
        return (MAX_DURATION_MS - (MAX_DURATION_MS - MIN_DURATION_MS) * fastFactor).toInt()
            .coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)
    }

    /** 捕获上滑起飞瞬间的全部物理初值 */
    fun capture(
        startX: Float,
        startY: Float,
        targetX: Float,
        targetY: Float,
        vx: Float,
        vy: Float,
        startScale: Float,
        startRotationZ: Float,
        density: Float
    ): DeckTrashArcStart = DeckTrashArcStart(
        startX = startX,
        startY = startY,
        targetX = targetX,
        targetY = targetY,
        vx = vx,
        vy = vy,
        startScale = startScale.coerceIn(0.6f, 1f),
        startRotationZ = startRotationZ,
        durationMillis = durationForVelocity(vy, density)
    )

    /**
     * 采样神灯吸入弧线的第 [progress] 帧 ([0f, 1f])。
     * - 轨迹：继承初速度的二次贝塞尔曲线，丝滑兜弧冲向右上角回收站中心；
     * - 缩放：前段自然缩小，中尾段叠加方向性微挤压拉伸（Genie 吸入张力）；
     * - 透明：前 76% 保持晶莹清晰，末段 24% 优雅沉入桶口。
     */
    fun frameAt(
        progress: Float,
        start: DeckTrashArcStart,
        density: Float
    ): DeckTrashArcFrame {
        val rawT = progress.coerceIn(0f, 1f)
        // 先快后收紧的吸入缓动：起飞继承手指速度、尾段高速吸进桶口
        val t = easeInOutBackless(rawT)
        val invT = 1f - t

        // 根据松手速度计算控制点偏移（带安全限幅，防止甩飞出屏过远）
        val maxBoostX = 110f * density
        val maxBoostY = 140f * density
        val velBoostX = (start.vx * 0.085f).coerceIn(-maxBoostX, maxBoostX)
        val velBoostY = (start.vy * 0.095f).coerceIn(-maxBoostY, 0f)

        val controlX = start.startX + (start.targetX - start.startX) * 0.32f + velBoostX
        val controlY = minOf(start.startY, start.targetY) - 48f * density + velBoostY * 0.55f

        val x = invT * invT * start.startX + 2f * invT * t * controlX + t * t * start.targetX
        val y = invT * invT * start.startY + 2f * invT * t * controlY + t * t * start.targetY

        // 二次贝塞尔一阶导数（切线方向），用于尾段微小吸入偏航倾角
        val dxDt = 2f * invT * (controlX - start.startX) + 2f * t * (start.targetX - controlX)
        val dyDt = 2f * invT * (controlY - start.startY) + 2f * t * (start.targetY - controlY)
        val flightAngleDeg = if (abs(dxDt) + abs(dyDt) > 1f) {
            Math.toDegrees(atan2(dxDt.toDouble(), (-dyDt).toDouble())).toFloat().coerceIn(-26f, 26f)
        } else 0f

        // 基础缩放：从 startScale 指数收敛至 0.13f（恰好缩进垃圾桶口）
        val endScale = 0.13f
        val shrinkCurve = t.pow(1.18f)
        val baseScale = start.startScale + (endScale - start.startScale) * shrinkCurve

        // Genie 尾段挤压拉伸：在 t = 0.30..0.95 之间呈现轻微瘦长吸入感
        val geniePulse = if (rawT in 0.25f..0.98f) {
            val u = ((rawT - 0.25f) / 0.73f).coerceIn(0f, 1f)
            4f * u * (1f - u) // 钟形脉冲 [0..1..0]
        } else 0f
        val scaleX = (baseScale * (1f - 0.24f * geniePulse)).coerceAtLeast(0.08f)
        val scaleY = (baseScale * (1f + 0.18f * geniePulse)).coerceAtLeast(0.10f)

        val rotationZ = start.startRotationZ * (1f - t) + flightAngleDeg * 0.72f * t

        val alpha = if (rawT <= 0.76f) {
            1f
        } else {
            (1f - (rawT - 0.76f) / 0.24f).coerceIn(0f, 1f)
        }

        val glowAlpha = (0.90f * (1f - rawT * 0.55f)).coerceIn(0f, 0.90f)

        return DeckTrashArcFrame(
            x = x,
            y = y,
            scaleX = scaleX,
            scaleY = scaleY,
            rotationZ = rotationZ,
            alpha = alpha,
            glowAlpha = glowAlpha
        )
    }

    /** 兼顾跟手初速与末段吸入加速的三次混合曲线 */
    private fun easeInOutBackless(t: Float): Float {
        val clamped = t.coerceIn(0f, 1f)
        // Cubic Bezier近似 (0.28, 0.05, 0.72, 1.0) -> 起步顺滑承接手指速度，尾段强劲吸入
        val p1 = 0.15f
        val p2 = 0.92f
        val u = 1f - clamped
        return 3f * u * u * clamped * p1 + 3f * u * clamped * clamped * p2 + clamped * clamped * clamped
    }
}
