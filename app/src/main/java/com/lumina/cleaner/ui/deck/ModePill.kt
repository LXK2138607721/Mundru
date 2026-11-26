package com.lumina.cleaner.ui.deck

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.cleaner.core.model.SamplingMode
import com.lumina.cleaner.core.util.HapticFeedback
import com.lumina.cleaner.ui.glass.LiquidBackdropState
import com.lumina.cleaner.ui.glass.LiquidGlassSurface

/**
 * Mundru 顶部悬浮真·液态玻璃模式药丸胶囊 (ModePill)
 * 品牌释义：Mundru 源自格陵兰语（Greenlandic），意为「擦掉 / 擦除」。
 *
 * - 采用实时背景采样的 LiquidGlassSurface（Android 13+ AGSL 曲面透镜折射 + 微色散 + Q 弹按压）；
 * - 无论展开与否始终保持在顶部原位清晰可点，再次点击原位即可丝滑回弹收起菜单。
 */
@Composable
fun ModePill(
    currentMode: SamplingMode,
    onClick: () -> Unit,
    backdrop: LiquidBackdropState?,
    isDark: Boolean,
    modifier: Modifier = Modifier,
    isExpanded: Boolean = false,
    staticBackdropCache: Boolean = false,
    backdropCacheKey: Any? = null,
    liveBackdropUpdates: (() -> Boolean)? = null
) {
    val arrowRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 420f),
        label = "arrow_rotation"
    )

    LiquidGlassSurface(
        backdrop = backdrop,
        isDark = isDark,
        cornerRadius = 22.dp,
        blurRadius = 16.dp,
        refractionStrengthDp = 11.dp,
        elevation = if (isDark) 8.dp else 10.dp,
        staticBackdropCache = staticBackdropCache,
        backdropCacheKey = backdropCacheKey,
        liveBackdropUpdates = liveBackdropUpdates,
        onClick = onClick,
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
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
                contentDescription = if (isExpanded) "收起整理模式" else "展开整理模式",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(16.dp)
                    .graphicsLayer { rotationZ = arrowRotation }
            )
        }
    }
}

/**
 * Mundru 真·液态玻璃模式选择浮层 (ModeSelectionOverlay)
 * 品牌释义：Mundru 源自格陵兰语（Greenlandic），意为「擦掉 / 擦除」。
 *
 * 核心体验规范：
 * 1. 零黑色遮罩、零全屏模糊：底层三层景深相片卡堆 100% 保持原汁原味的明度与色彩；
 * 2. 真·液态玻璃折射菜单：菜单本体以 LiquidGlassSurface 渲染，实时弯曲折射下方相片色彩；
 * 3. 原位点击平滑收起：通过受控 [visible] 驱动弹性开合动画，无论点击顶部 ModePill 原位、
 *    点击外部空白区还是按系统返回键，均以同一套丝滑收缩动效回到药丸锚点。
 */
@Composable
fun ModeSelectionOverlay(
    visible: Boolean,
    currentMode: SamplingMode,
    backdrop: LiquidBackdropState?,
    isDark: Boolean,
    onSelectMode: (SamplingMode) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    backdropCacheKey: Any? = null
) {
    val context = LocalContext.current
    val animProgress = remember { Animatable(0f) }
    var keepMounted by remember { mutableStateOf(visible) }

    LaunchedEffect(visible) {
        if (visible) {
            keepMounted = true
            animProgress.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = 0.68f,
                    stiffness = 410f
                )
            )
        } else if (keepMounted) {
            animProgress.animateTo(
                targetValue = 0f,
                animationSpec = tween(
                    durationMillis = 125,
                    easing = FastOutSlowInEasing
                )
            )
            keepMounted = false
        }
    }

    if (!keepMounted && !visible) return

    BackHandler(enabled = visible) {
        HapticFeedback.click(context)
        onDismiss()
    }

    val currentProgress = animProgress.value
    val scale = 0.36f + 0.64f * currentProgress
    val alpha = currentProgress.coerceIn(0f, 1f)

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        // 纯透明外部点击退场层（严禁任何黑色遮罩或全屏模糊，底层三层相片卡堆 100% 透亮）
        if (visible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        HapticFeedback.click(context)
                        onDismiss()
                    }
            )
        }

        // 真·液态玻璃极简下拉浮层：外层测量未缩放稳定锚点并一次预烘焙折射背景，开合全程纯 GPU 缩放 120fps 零逐帧重模糊
        var unscaledOrigin by remember { mutableStateOf(Offset.Zero) }
        Box(
            modifier = Modifier
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(top = 60.dp)
                .width(196.dp)
                .onGloballyPositioned { coords ->
                    unscaledOrigin = coords.positionInRoot()
                }
        ) {
            LiquidGlassSurface(
                backdrop = backdrop,
                isDark = isDark,
                cornerRadius = 22.dp,
                blurRadius = 22.dp,
                refractionStrengthDp = 14.dp,
                tintColor = if (isDark) Color(0x7E181A22) else Color(0xA6FFFFFF),
                elevation = if (isDark) 14.dp else 16.dp,
                pressBounce = false,
                originInRootOverride = { unscaledOrigin },
                staticBackdropCache = true,
                backdropCacheKey = backdropCacheKey ?: isDark,
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(0.5f, 0f)
                        this.alpha = alpha
                    }
            ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(7.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                val selectedAccent = MaterialTheme.colorScheme.primary
                SamplingMode.entries.forEach { mode ->
                    val isSelected = mode == currentMode
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (isSelected) {
                            selectedAccent.copy(alpha = if (isDark) 0.14f else 0.08f)
                        } else {
                            Color.Transparent
                        },
                        border = if (isSelected) {
                            BorderStroke(0.75.dp, selectedAccent.copy(alpha = if (isDark) 0.28f else 0.18f))
                        } else null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable(enabled = visible) {
                                HapticFeedback.click(context)
                                onSelectMode(mode)
                            }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 13.dp, vertical = 12.dp)
                        ) {
                            Icon(
                                imageVector = mode.icon,
                                contentDescription = null,
                                tint = if (isSelected) {
                                    selectedAccent
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(19.dp)
                            )

                            Spacer(modifier = Modifier.width(11.dp))

                            Text(
                                text = mode.title,
                                fontSize = 14.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) {
                                    selectedAccent
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                modifier = Modifier.weight(1f)
                            )

                            if (isSelected) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = "已选择",
                                    tint = selectedAccent,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
        }
    }
}
