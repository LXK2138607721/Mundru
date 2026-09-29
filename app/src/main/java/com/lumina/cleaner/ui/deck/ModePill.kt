package com.lumina.cleaner.ui.deck

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.cleaner.core.model.SamplingMode
import com.lumina.cleaner.core.util.HapticFeedback
import kotlinx.coroutines.launch

/**
 * Mundru 顶部悬浮极简模式药丸胶囊 (Mode Pill)
 * 品牌释义：Mundru 源自格陵兰语（Greenlandic），意为「擦掉 / 擦除」。
 *
 * 点击唤起从按钮物理锚点平滑舒展弹出的模式选择菜单。
 */
@Composable
fun ModePill(
    currentMode: SamplingMode,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isExpanded: Boolean = false
) {
    val arrowRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "arrow_rotation"
    )

    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        shadowElevation = 6.dp,
        modifier = modifier
            .clip(CircleShape)
            .clickable { onClick() }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Icon(
                imageVector = currentMode.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(17.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = currentMode.title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = "展开整理模式",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(16.dp)
                    .graphicsLayer { rotationZ = arrowRotation }
            )
        }
    }
}

/**
 * Mundru 模式选择动效浮层 (ModeSelectionOverlay)
 * 品牌释义：Mundru 源自格陵兰语（Greenlandic），意为「擦掉 / 擦除」。
 *
 * 核心动效与尺寸规范：
 * 1. 紧凑精致尺寸：固定 260dp 优雅宽度，垂直挂载在药丸正下方，告别臃肿大弹窗；
 * 2. 物理锚点动效：以顶部按钮底部中心 (TransformOrigin(0.5f, 0f)) 为原点，
 *    入场时从 0.35f 弹簧放大舒展至 1.0f，无暗幕遮罩，底层页面 100% 保持原本明度与色彩；
 *    退场时平滑反向收缩回药丸按钮并淡出；
 * 3. 点击外部空白区或按系统返回键，平滑弹性收拢。
 */
@Composable
fun ModeSelectionOverlay(
    currentMode: SamplingMode,
    onSelectMode: (SamplingMode) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val animProgress = remember { Animatable(0f) }
    var isClosing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        animProgress.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = 0.82f,
                stiffness = 340f
            )
        )
    }

    fun closeWithAnimation(action: () -> Unit) {
        if (isClosing) return
        isClosing = true
        HapticFeedback.click(context)
        coroutineScope.launch {
            animProgress.animateTo(
                targetValue = 0f,
                animationSpec = tween(
                    durationMillis = 180,
                    easing = FastOutSlowInEasing
                )
            )
            action()
        }
    }

    BackHandler(onBack = { closeWithAnimation(onDismiss) })

    val currentProgress = animProgress.value
    val scale = 0.35f + 0.65f * currentProgress
    val alpha = currentProgress.coerceIn(0f, 1f)

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        // 点击外部空白区平滑收起（纯透明无暗幕遮罩，底层页面完全保持原状）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    closeWithAnimation(onDismiss)
                }
        )

        // 浮层主体：小巧精致尺寸 (260.dp)，挂载在顶部 ModePill 正下方
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 14.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier
                .statusBarsPadding()
                .padding(top = 54.dp)
                .width(260.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0.5f, 0f)
                    this.alpha = alpha
                }
        ) {
            Column(
                modifier = Modifier.padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                SamplingMode.entries.forEach { mode ->
                    val isSelected = mode == currentMode
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                        } else {
                            Color.Transparent
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable {
                                closeWithAnimation {
                                    onSelectMode(mode)
                                }
                            }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp)
                        ) {
                            Icon(
                                imageVector = mode.icon,
                                contentDescription = null,
                                tint = if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(20.dp)
                            )

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = mode.title,
                                    fontSize = 14.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    }
                                )
                                Spacer(modifier = Modifier.height(1.dp))
                                Text(
                                    text = mode.description,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    lineHeight = 14.sp
                                )
                            }

                            if (isSelected) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = "已选择",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
