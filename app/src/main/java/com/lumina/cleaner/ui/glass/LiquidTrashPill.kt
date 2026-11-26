package com.lumina.cleaner.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.cleaner.ui.deck.DeckTrashArcModel
import kotlinx.coroutines.launch

private val TrashVermilion = Color(0xFFEA4335)

/**
 * 顶部右上角真·液态玻璃三段式联动回收站胶囊 (LiquidTrashPill)
 *
 * 物理联动：
 * 1. 拉扯期：随上滑张力磁吸微倾、放大，自绘垃圾桶桶盖平滑掀开 (0° -> 34°，过阈值 44°)，玻璃染色微红；
 * 2. 命中瞬间 ([impactTrigger] 递增)：桶盖 Q 弹过冲啪地合拢 (44° -> -8° -> 0°)，
 *    液态玻璃本体果冻压扁回弹 (scaleX=1.25, scaleY=0.78 -> 1.0)，同步绽放玻璃涟漪光环并驱动 RollingCounter 滚轮 +1。
 */
@Composable
fun LiquidTrashPill(
    trashCount: Int,
    backdrop: LiquidBackdropState?,
    isDark: Boolean,
    trashPullState: MutableFloatState,
    trashThresholdState: MutableState<Boolean>,
    impactTrigger: Int,
    onCenterPositioned: (Offset) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    staticBackdropCache: Boolean = false,
    backdropCacheKey: Any? = null,
    liveBackdropUpdates: (() -> Boolean)? = null
) {
    val lidAngle = remember { Animatable(0f) }
    val impactScaleX = remember { Animatable(1f) }
    val impactScaleY = remember { Animatable(1f) }
    val rippleProgress = remember { Animatable(1f) }

    val pull = trashPullState.floatValue
    val thresholdReached = trashThresholdState.value

    // 拉扯张力实时驱动桶盖掀开与未达阈值柔和闭合
    LaunchedEffect(pull, thresholdReached) {
        val targetLid = DeckTrashArcModel.lidOpenDegrees(pull, thresholdReached)
        if (pull > 0.01f || thresholdReached) {
            lidAngle.animateTo(
                targetValue = targetLid,
                animationSpec = spring(dampingRatio = 0.68f, stiffness = 560f)
            )
        } else if (lidAngle.value > 0.5f && impactScaleX.value in 0.96f..1.04f) {
            lidAngle.animateTo(
                targetValue = 0f,
                animationSpec = spring(dampingRatio = 0.72f, stiffness = 420f)
            )
        }
    }

    // 卡片弧线命中瞬间：三轴并行的 Q 弹撞击反馈（桶盖过冲闭合 + 液态玻璃压扁弹跳 + 光环扩散）
    LaunchedEffect(impactTrigger) {
        if (impactTrigger <= 0) return@LaunchedEffect
        launch {
            lidAngle.snapTo(44f)
            lidAngle.animateTo(
                targetValue = 0f,
                animationSpec = spring(dampingRatio = 0.36f, stiffness = 620f)
            )
        }
        launch {
            impactScaleX.snapTo(1.25f)
            impactScaleX.animateTo(
                targetValue = 1f,
                animationSpec = spring(dampingRatio = 0.44f, stiffness = 480f)
            )
        }
        launch {
            impactScaleY.snapTo(0.78f)
            impactScaleY.animateTo(
                targetValue = 1f,
                animationSpec = spring(dampingRatio = 0.44f, stiffness = 480f)
            )
        }
        launch {
            rippleProgress.snapTo(0f)
            rippleProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 380, easing = FastOutSlowInEasing)
            )
        }
    }

    val baseTint = if (isDark) Color(0x4C1A1C24) else Color(0x74FFFFFF)
    val activeRedTint = if (isDark) Color(0x78EA4335) else Color(0x52EA4335)
    val dynamicTint = lerp(baseTint, activeRedTint, (pull * 0.85f).coerceIn(0f, 1f))

    val iconTint = when {
        thresholdReached || pull > 0.25f -> TrashVermilion
        trashCount > 0 -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }

    // 外层非变换锚点容器：确保测得的 trashTargetInRoot 始终为静息像素中心
    Box(
        modifier = modifier
            .onGloballyPositioned { coords ->
                onCenterPositioned(coords.boundsInRoot().center)
            }
            .drawBehind {
                val p = rippleProgress.value
                if (p < 0.99f) {
                    val alpha = ((1f - p) * 0.68f).coerceIn(0f, 0.68f)
                    val expand = p * 18.dp.toPx()
                    val r = (22.dp.toPx() + expand).coerceAtLeast(1f)
                    drawRoundRect(
                        color = TrashVermilion.copy(alpha = alpha),
                        topLeft = Offset(-expand, -expand),
                        size = Size(size.width + expand * 2f, size.height + expand * 2f),
                        cornerRadius = CornerRadius(r, r),
                        style = Stroke(width = ((1f - p) * 2.4f + 0.6f).dp.toPx())
                    )
                }
            },
        contentAlignment = Alignment.Center
    ) {
        LiquidGlassSurface(
            backdrop = backdrop,
            isDark = isDark,
            cornerRadius = 22.dp,
            blurRadius = 16.dp,
            refractionStrengthDp = 11.dp,
            tintColor = dynamicTint,
            elevation = if (isDark) 8.dp else 10.dp,
            staticBackdropCache = staticBackdropCache,
            backdropCacheKey = backdropCacheKey,
            liveBackdropUpdates = liveBackdropUpdates,
            onClick = onClick,
            modifier = Modifier
                .heightIn(min = 40.dp)
                .widthIn(min = 40.dp)
                .graphicsLayer {
                    val pullNow = trashPullState.floatValue
                    val pullScale = 1f + 0.14f * pullNow
                    scaleX = impactScaleX.value * pullScale
                    scaleY = impactScaleY.value * pullScale
                    translationX = -3.dp.toPx() * pullNow
                    translationY = 2.dp.toPx() * pullNow
                }
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(
                    horizontal = if (trashCount > 0) 11.dp else 10.dp,
                    vertical = 9.dp
                )
            ) {
                AnimatedTrashIcon(
                    tint = iconTint,
                    lidOpenDegrees = { lidAngle.value },
                    size = 20.dp
                )
                if (trashCount > 0) {
                    RollingCounter(
                        count = trashCount,
                        style = TextStyle(
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = iconTint
                    )
                }
            }
        }
    }
}
