package com.lumina.cleaner.ui.onboarding

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

internal enum class DemoCoachDirection(val caption: String) {
    LEFT("← 左滑保留"),
    UP("↑ 上滑入桶"),
    RIGHT("→ 右滑回看")
}

/**
 * 教学第二页非侵入式手势演示状态：
 * 在用户未触摸卡片且仍有未完成手势时，循环演示「左滑 / 上滑 / 右滑」真实卡面带动与触控轨迹；
 * 只要手指一碰屏幕立即原子清零让路，绝不与自由操作抢手势。
 */
@Stable
internal class DemoCoachState {
    var activeDirection by mutableStateOf<DemoCoachDirection?>(null)
    var orbAlpha by mutableFloatStateOf(0f)
    var orbScale by mutableFloatStateOf(1f)
    var orbStartX by mutableFloatStateOf(0f)
    var orbStartY by mutableFloatStateOf(0f)
    var orbX by mutableFloatStateOf(0f)
    var orbY by mutableFloatStateOf(0f)
    var cardOffsetX by mutableFloatStateOf(0f)
    var cardOffsetY by mutableFloatStateOf(0f)
    var cardDepthP by mutableFloatStateOf(0f)
    var cardLidP by mutableFloatStateOf(0f)

    fun resetImmediate() {
        Snapshot.withMutableSnapshot {
            activeDirection = null
            orbAlpha = 0f
            orbScale = 1f
            orbStartX = 0f
            orbStartY = 0f
            orbX = 0f
            orbY = 0f
            cardOffsetX = 0f
            cardOffsetY = 0f
            cardDepthP = 0f
            cardLidP = 0f
        }
    }
}

/**
 * 优雅循环演示尚未体验的方向（左滑保留 -> 上滑入桶 -> 右滑回看）：
 * 每次演示仅以真实物理节奏带动卡面轻滑并柔和弹回，触摸时由协程取消瞬时交权。
 */
internal suspend fun runDemoCoachLoop(
    state: DemoCoachState,
    density: Float,
    directions: List<DemoCoachDirection>,
    initialDelayMs: Long
) {
    if (directions.isEmpty()) {
        state.resetImmediate()
        return
    }
    val glideEase = CubicBezierEasing(0.24f, 0.88f, 0.32f, 1.0f)
    delay(initialDelayMs)
    var step = 0
    try {
        while (true) {
            val dir = directions[step % directions.size]
            step++

            val sx: Float
            val sy: Float
            val ex: Float
            val ey: Float
            val cardTx: Float
            val cardTy: Float
            when (dir) {
                DemoCoachDirection.LEFT -> {
                    sx = 34f * density; sy = 8f * density
                    ex = -46f * density; ey = 4f * density
                    cardTx = -54f * density; cardTy = 0f
                }
                DemoCoachDirection.UP -> {
                    sx = 0f; sy = 40f * density
                    ex = 0f; ey = -42f * density
                    cardTx = 0f; cardTy = -50f * density
                }
                DemoCoachDirection.RIGHT -> {
                    sx = -38f * density; sy = 8f * density
                    ex = 52f * density; ey = 4f * density
                    cardTx = 198f * density; cardTy = 0f
                }
            }

            Snapshot.withMutableSnapshot {
                state.activeDirection = dir
                state.orbStartX = sx
                state.orbStartY = sy
                state.orbX = sx
                state.orbY = sy
                state.orbAlpha = 0f
                state.orbScale = 1.24f
            }

            // 1. 触控光球轻柔落指
            animate(0f, 1f, animationSpec = tween(220, easing = FastOutSlowInEasing)) { p, _ ->
                Snapshot.withMutableSnapshot {
                    state.orbAlpha = p
                    state.orbScale = 1.24f - 0.24f * p
                }
            }
            delay(110L)

            // 2. 沿手势方向滑动并同步带动主卡/次卡/回收桶盖
            animate(0f, 1f, animationSpec = tween(640, easing = glideEase)) { p, _ ->
                Snapshot.withMutableSnapshot {
                    state.orbX = sx + (ex - sx) * p
                    state.orbY = sy + (ey - sy) * p
                    state.cardOffsetX = cardTx * p
                    state.cardOffsetY = cardTy * p
                    state.cardDepthP = when (dir) {
                        DemoCoachDirection.LEFT -> p * 0.62f
                        DemoCoachDirection.UP -> p * 0.56f
                        DemoCoachDirection.RIGHT -> 0f
                    }
                    state.cardLidP = if (dir == DemoCoachDirection.UP) p * 0.72f else 0f
                }
            }
            delay(200L)

            // 3. 松手淡出并优雅回弹复位
            val startX = state.cardOffsetX
            val startY = state.cardOffsetY
            val startDepth = state.cardDepthP
            val startLid = state.cardLidP
            animate(1f, 0f, animationSpec = spring(dampingRatio = 0.80f, stiffness = 340f)) { rem, _ ->
                Snapshot.withMutableSnapshot {
                    state.orbAlpha = (rem * 1.4f - 0.4f).coerceIn(0f, 1f)
                    state.cardOffsetX = startX * rem
                    state.cardOffsetY = startY * rem
                    state.cardDepthP = startDepth * rem
                    state.cardLidP = startLid * rem
                }
            }
            state.resetImmediate()
            delay(680L)
        }
    } finally {
        state.resetImmediate()
    }
}

/**
 * 瑞士黑白单色极简触控光球与方向尾迹演示层（纯 Canvas + graphicsLayer，0 重组）。
 */
@Composable
internal fun OnboardingDemoCoachOverlay(
    state: DemoCoachState,
    isDark: Boolean,
    modifier: Modifier = Modifier
) {
    val caption = state.activeDirection?.caption
    val orbFill = if (isDark) Color(0xFFF4F4F6) else Color(0xFF141518)
    val orbStroke = if (isDark) Color(0xFF141518) else Color.White
    val trailBase = if (isDark) Color.White else Color(0xFF141518)

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val a = state.orbAlpha.coerceIn(0f, 1f)
            if (a <= 0.01f) return@Canvas

            val cx = size.width * 0.5f
            val cy = size.height * 0.5f
            val start = Offset(cx + state.orbStartX, cy + state.orbStartY)
            val curr = Offset(cx + state.orbX, cy + state.orbY)
            val dist = (curr - start).getDistance()

            // 1. 柔和方向渐变尾迹线
            if (dist > 4f) {
                drawLine(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            trailBase.copy(alpha = 0f),
                            trailBase.copy(alpha = 0.50f * a)
                        ),
                        start = start,
                        end = curr
                    ),
                    start = start,
                    end = curr,
                    strokeWidth = 3.2.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }

            // 2. 触控指尖呼吸外圈 + 中心实心单色触点
            val outerR = 17.dp.toPx() * state.orbScale
            val innerR = 7.5.dp.toPx() * state.orbScale
            drawCircle(
                color = orbFill.copy(alpha = 0.16f * a),
                radius = outerR,
                center = curr
            )
            drawCircle(
                color = orbStroke.copy(alpha = 0.85f * a),
                radius = outerR * 0.72f,
                center = curr,
                style = Stroke(width = 1.6.dp.toPx())
            )
            drawCircle(
                color = orbFill.copy(alpha = 0.94f * a),
                radius = innerR,
                center = curr
            )
        }

        // 3. 悬浮极简方向说明微胶囊（同步淡入淡出，清晰告知用户当前演示手势）
        if (caption != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 14.dp)
                    .graphicsLayer {
                        val a = state.orbAlpha.coerceIn(0f, 1f)
                        alpha = a
                        translationY = (1f - a) * 6.dp.toPx()
                    }
                    .clip(CircleShape)
                    .background(Color(0xC8141518))
                    .border(0.6.dp, Color.White.copy(alpha = 0.24f), CircleShape)
                    .padding(horizontal = 12.dp, vertical = 5.dp)
            ) {
                Text(
                    text = caption,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.3.sp,
                    color = Color.White.copy(alpha = 0.94f)
                )
            }
        }
    }
}
