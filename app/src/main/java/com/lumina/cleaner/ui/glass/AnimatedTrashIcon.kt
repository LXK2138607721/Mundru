package com.lumina.cleaner.ui.glass

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/**
 * 自绘双部件可掀盖矢量垃圾桶图标 (AnimatedTrashIcon)
 *
 * 专用于上滑回收动效的三段式物理联动：
 * - 上推卡片时，桶盖绕右上铰链平滑向上掀开 (0° -> 42°)，实时响应卡片靠近；
 * - 卡片飞入命中瞬间，桶盖“啪”地 Q 弹合拢（带微负角过冲），全部在 Draw-Phase 零重组绘制。
 */
@Composable
fun AnimatedTrashIcon(
    tint: Color,
    lidOpenDegrees: () -> Float,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = (w * 0.095f).coerceAtLeast(1.5f)

        // 1. 垃圾桶桶身（圆角梯形风格 + 两条精致竖肋）
        val bodyLeft = w * 0.23f
        val bodyRight = w * 0.77f
        val bodyTop = h * 0.34f
        val bodyBottom = h * 0.87f
        val bodyW = bodyRight - bodyLeft
        val bodyH = bodyBottom - bodyTop

        drawRoundRect(
            color = tint,
            topLeft = Offset(bodyLeft, bodyTop),
            size = Size(bodyW, bodyH),
            cornerRadius = CornerRadius(w * 0.11f, w * 0.11f),
            style = Stroke(width = stroke)
        )

        // 两条内部细竖纹
        val ribTop = bodyTop + bodyH * 0.24f
        val ribBottom = bodyBottom - bodyH * 0.22f
        drawLine(
            color = tint,
            start = Offset(w * 0.42f, ribTop),
            end = Offset(w * 0.42f, ribBottom),
            strokeWidth = stroke * 0.85f,
            cap = StrokeCap.Round
        )
        drawLine(
            color = tint,
            start = Offset(w * 0.58f, ribTop),
            end = Offset(w * 0.58f, ribBottom),
            strokeWidth = stroke * 0.85f,
            cap = StrokeCap.Round
        )

        // 2. 独立可旋转桶盖（以右上铰链为圆心向外掀开）
        val angle = lidOpenDegrees()
        val pivot = Offset(w * 0.82f, h * 0.25f)
        rotate(degrees = angle, pivot = pivot) {
            // 盖沿横杠
            drawLine(
                color = tint,
                start = Offset(w * 0.16f, h * 0.25f),
                end = Offset(w * 0.84f, h * 0.25f),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
            // 顶部提手
            drawRoundRect(
                color = tint,
                topLeft = Offset(w * 0.38f, h * 0.12f),
                size = Size(w * 0.24f, h * 0.13f),
                cornerRadius = CornerRadius(w * 0.06f, w * 0.06f),
                style = Stroke(width = stroke * 0.9f)
            )
        }
    }
}

/**
 * 机械表盘式 Q 弹垂直滚轮数字 (RollingCounter)
 */
@Composable
fun RollingCounter(
    count: Int,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified
) {
    val tabularStyle = style.copy(fontFeatureSettings = "tnum")
    val rollSpring = spring<IntOffset>(dampingRatio = 0.62f, stiffness = 460f)
    AnimatedContent(
        targetState = count,
        modifier = modifier,
        transitionSpec = {
            if (targetState >= initialState) {
                (slideInVertically(rollSpring) { it * 2 / 3 } + fadeIn(tween(140)))
                    .togetherWith(slideOutVertically(rollSpring) { -it * 2 / 3 } + fadeOut(tween(110)))
            } else {
                (slideInVertically(rollSpring) { -it * 2 / 3 } + fadeIn(tween(140)))
                    .togetherWith(slideOutVertically(rollSpring) { it * 2 / 3 } + fadeOut(tween(110)))
            }
        },
        label = "trash_rolling_counter"
    ) { value ->
        Text(
            text = value.toString(),
            style = tabularStyle,
            color = color,
            maxLines = 1
        )
    }
}
