package com.lumina.cleaner.ui.onboarding

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.cleaner.R
import kotlinx.coroutines.launch

private data class OnboardingPageData(
    val tag: String,
    val title: String,
    val desc: String,
    val tip: String
)

private val onboardingPages = listOf(
    OnboardingPageData(
        tag = "核心手势 01",
        title = "左滑下一张 · 保留相片",
        desc = "向左轻扫卡片，保留心仪相片并快速查看下一张，整理相册轻快如飞。",
        tip = "极轻手感 · 左滑即下一张"
    ),
    OnboardingPageData(
        tag = "核心手势 02",
        title = "右滑上一张 · 回看相片",
        desc = "向右轻扫卡片随时回到上一张照片。若上一张已暂存删除，回看时自动撤销还原。",
        tip = "无忧回退 · 右滑看上一张"
    ),
    OnboardingPageData(
        tag = "核心手势 03",
        title = "向上滑脱 · 暂存清理",
        desc = "向上轻推不需要的废片移入回收站，随时可撤回还原，安全无担忧。",
        tip = "暂存回收站 · 系统确认才物理删除"
    ),
    OnboardingPageData(
        tag = "沉浸体验 04",
        title = "点图查看 · 实况音画",
        desc = "轻点 LIVE 按钮持续播放实况音画，长按快速预览；轻触大图沉浸查看，再次轻点平滑缩小归位。",
        tip = "轻点常播 · 长按预览 · 轻触归位"
    ),
    OnboardingPageData(
        tag = "智能引擎 05",
        title = "多维专清 · 离线绝对隐私",
        desc = "时光考古、图集专清、月份专清与随心漫步；0 个网络权限申请，100% 纯本地离线运行，隐私绝对属于你。",
        tip = "0 网络权限 · 本地安全加密存储"
    )
)

@Composable
fun OnboardingScreen(
    onComplete: () -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { onboardingPages.size })
    val coroutineScope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_mundru_logo),
                                contentDescription = "Mundru Logo",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Mundru",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }

                if (pagerState.currentPage < onboardingPages.size - 1) {
                    TextButton(onClick = onComplete) {
                        Text(
                            text = "跳过",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.size(48.dp, 36.dp))
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) { pageIndex ->
                val page = onboardingPages[pageIndex]
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // 动态演示交互舞台（自适应弹性高度，杜绝系统大字号裁切）
                    Box(
                        modifier = Modifier
                            .height(135.dp)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        when (pageIndex) {
                            0 -> AnimatedSwipeLeftGestureDemo()
                            1 -> AnimatedSwipeRightGestureDemo()
                            2 -> AnimatedSwipeUpGestureDemo()
                            3 -> AnimatedTapAndLivePhotoDemo()
                            4 -> AnimatedLocalPrivacyDemo()
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = page.tag,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = page.title,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onBackground,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = page.desc,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        lineHeight = 20.sp,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    ) {
                        Text(
                            text = page.tip,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    repeat(onboardingPages.size) { index ->
                        val isSelected = pagerState.currentPage == index
                        val width by animateDpAsState(
                            targetValue = if (isSelected) 24.dp else 8.dp,
                            label = "dot_width"
                        )
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .height(8.dp)
                                .width(width)
                                .clip(CircleShape)
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                if (pagerState.currentPage < onboardingPages.size - 1) {
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            }
                        },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text(
                            text = "下一步",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    Button(
                        onClick = onComplete,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text(
                            text = "开启极简整理",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

// ==========================================
// 纯 Compose 矢量与物理手势动效组件 (0 额外依赖)
// ==========================================

/**
 * 模拟相片卡片骨架
 */
@Composable
private fun MiniCard(
    modifier: Modifier = Modifier,
    alpha: Float = 1f,
    gradient: Brush = Brush.linearGradient(
        listOf(
            Color(0xFF4A6572),
            Color(0xFF344955)
        )
    ),
    content: @Composable () -> Unit = {}
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 8.dp,
        modifier = modifier
            .size(105.dp, 130.dp)
            .graphicsLayer { this.alpha = alpha }
            .clip(RoundedCornerShape(16.dp))
            .background(gradient)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Photo,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.25f),
                modifier = Modifier.size(36.dp)
            )
            content()
        }
    }
}

/**
 * 虚拟指尖触控光标
 */
@Composable
private fun VirtualTouchPointer(
    modifier: Modifier = Modifier,
    alpha: Float = 1f
) {
    Box(
        modifier = modifier
            .size(24.dp)
            .graphicsLayer { this.alpha = alpha }
            .shadow(4.dp, CircleShape)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}

/**
 * Page 1: 左滑下一张手势演示
 */
@Composable
private fun AnimatedSwipeLeftGestureDemo() {
    val infiniteTransition = rememberInfiniteTransition(label = "swipe_left_demo")
    val progress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2300, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "progress"
    )

    val dragX = when {
        progress < 0.15f -> 0f
        progress < 0.65f -> -65f * ((progress - 0.15f) / 0.50f)
        progress < 0.85f -> -65f - 75f * ((progress - 0.65f) / 0.20f)
        else -> 0f
    }
    val rotation = when {
        progress < 0.15f -> 0f
        progress < 0.65f -> -7f * ((progress - 0.15f) / 0.50f)
        progress < 0.85f -> -7f - 5f * ((progress - 0.65f) / 0.20f)
        else -> 0f
    }
    val cardAlpha = when {
        progress < 0.15f -> (progress / 0.15f).coerceIn(0f, 1f)
        progress < 0.65f -> 1f
        progress < 0.85f -> 1f - ((progress - 0.65f) / 0.20f)
        else -> 0f
    }
    val checkAlpha = when {
        progress < 0.25f -> 0f
        progress < 0.65f -> ((progress - 0.25f) / 0.40f).coerceIn(0f, 1f)
        progress < 0.85f -> 1f - ((progress - 0.65f) / 0.20f)
        else -> 0f
    }
    val fingerAlpha = when {
        progress < 0.15f -> progress / 0.15f
        progress < 0.65f -> 1f
        progress < 0.80f -> 1f - ((progress - 0.65f) / 0.15f)
        else -> 0f
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // 底层下一张卡片预览
        MiniCard(
            modifier = Modifier
                .scale(0.90f)
                .offset(y = 8.dp),
            gradient = Brush.linearGradient(listOf(Color(0xFF2C3E50), Color(0xFF3498DB)))
        )

        // 顶层被拖拽卡片
        MiniCard(
            modifier = Modifier
                .offset(x = dragX.dp)
                .graphicsLayer { rotationZ = rotation },
            alpha = cardAlpha,
            gradient = Brush.linearGradient(listOf(Color(0xFF1ABC9C), Color(0xFF16A085)))
        ) {
            // 右侧保留成功指示徽标
            Surface(
                shape = CircleShape,
                color = Color.White.copy(alpha = checkAlpha * 0.9f),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(26.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        tint = Color(0xFF16A085),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        // 虚拟指尖
        VirtualTouchPointer(
            modifier = Modifier
                .offset(x = (dragX + 15).dp, y = 20.dp),
            alpha = fingerAlpha
        )
    }
}

/**
 * Page 2: 右滑上一张手势演示
 */
@Composable
private fun AnimatedSwipeRightGestureDemo() {
    val infiniteTransition = rememberInfiniteTransition(label = "swipe_right_demo")
    val progress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2300, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "progress"
    )

    val dragX = when {
        progress < 0.15f -> 0f
        progress < 0.65f -> 65f * ((progress - 0.15f) / 0.50f)
        progress < 0.85f -> 65f + 75f * ((progress - 0.65f) / 0.20f)
        else -> 0f
    }
    val rotation = when {
        progress < 0.15f -> 0f
        progress < 0.65f -> 7f * ((progress - 0.15f) / 0.50f)
        progress < 0.85f -> 7f + 5f * ((progress - 0.65f) / 0.20f)
        else -> 0f
    }
    val cardAlpha = when {
        progress < 0.15f -> (progress / 0.15f).coerceIn(0f, 1f)
        progress < 0.65f -> 1f
        progress < 0.85f -> 1f - ((progress - 0.65f) / 0.20f)
        else -> 0f
    }
    val undoAlpha = when {
        progress < 0.25f -> 0f
        progress < 0.65f -> ((progress - 0.25f) / 0.40f).coerceIn(0f, 1f)
        progress < 0.85f -> 1f - ((progress - 0.65f) / 0.20f)
        else -> 0f
    }
    val prevCardInX = when {
        progress < 0.35f -> -90f
        progress < 0.85f -> -90f + 90f * ((progress - 0.35f) / 0.50f)
        else -> 0f
    }
    val fingerAlpha = when {
        progress < 0.15f -> progress / 0.15f
        progress < 0.65f -> 1f
        progress < 0.80f -> 1f - ((progress - 0.65f) / 0.15f)
        else -> 0f
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // 上一张卡片从左后方滑入
        MiniCard(
            modifier = Modifier
                .offset(x = prevCardInX.dp)
                .scale(0.92f),
            gradient = Brush.linearGradient(listOf(Color(0xFF8E44AD), Color(0xFF9B59B6)))
        )

        // 顶层被拖拽卡片
        MiniCard(
            modifier = Modifier
                .offset(x = dragX.dp)
                .graphicsLayer { rotationZ = rotation },
            alpha = cardAlpha,
            gradient = Brush.linearGradient(listOf(Color(0xFF2980B9), Color(0xFF3498DB)))
        ) {
            // 左侧返回上一张/撤销徽标
            Surface(
                shape = CircleShape,
                color = Color.White.copy(alpha = undoAlpha * 0.9f),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .size(26.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Undo,
                        contentDescription = null,
                        tint = Color(0xFF2980B9),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        // 虚拟指尖
        VirtualTouchPointer(
            modifier = Modifier
                .offset(x = (dragX - 15).dp, y = 20.dp),
            alpha = fingerAlpha
        )
    }
}

/**
 * Page 3: 向上滑脱移入回收站
 */
@Composable
private fun AnimatedSwipeUpGestureDemo() {
    val infiniteTransition = rememberInfiniteTransition(label = "swipe_up_demo")
    val progress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2300, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "progress"
    )

    val dragY = when {
        progress < 0.15f -> 12f
        progress < 0.65f -> 12f - 60f * ((progress - 0.15f) / 0.50f)
        progress < 0.85f -> -48f - 30f * ((progress - 0.65f) / 0.20f)
        else -> 12f
    }
    val cardScale = when {
        progress < 0.15f -> 1f
        progress < 0.65f -> 1f - 0.20f * ((progress - 0.15f) / 0.50f)
        progress < 0.85f -> 0.80f - 0.35f * ((progress - 0.65f) / 0.20f)
        else -> 0.45f
    }
    val cardAlpha = when {
        progress < 0.15f -> (progress / 0.15f).coerceIn(0f, 1f)
        progress < 0.65f -> 1f
        progress < 0.85f -> 1f - ((progress - 0.65f) / 0.20f)
        else -> 0f
    }
    val trashScale = when {
        progress < 0.55f -> 1f
        progress < 0.72f -> 1f + 0.28f * ((progress - 0.55f) / 0.17f)
        progress < 0.88f -> 1.28f - 0.28f * ((progress - 0.72f) / 0.16f)
        else -> 1f
    }
    val fingerAlpha = when {
        progress < 0.15f -> progress / 0.15f
        progress < 0.65f -> 1f
        progress < 0.80f -> 1f - ((progress - 0.65f) / 0.15f)
        else -> 0f
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // 顶部回收站图标
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = 4.dp)
                .scale(trashScale)
                .size(42.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // 向上滑脱卡片
        MiniCard(
            modifier = Modifier
                .offset(y = dragY.dp)
                .scale(cardScale),
            alpha = cardAlpha,
            gradient = Brush.linearGradient(listOf(Color(0xFFE74C3C), Color(0xFFC0392B)))
        )

        // 虚拟指尖
        VirtualTouchPointer(
            modifier = Modifier
                .offset(y = (dragY + 45).dp),
            alpha = fingerAlpha
        )
    }
}

/**
 * Page 4: 点图查看与实况长按播映演示
 */
@Composable
private fun AnimatedTapAndLivePhotoDemo() {
    val infiniteTransition = rememberInfiniteTransition(label = "live_photo_demo")
    val progress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "progress"
    )

    val wave1 by infiniteTransition.animateFloat(
        initialValue = 4f, targetValue = 18f,
        animationSpec = infiniteRepeatable(tween(350, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "w1"
    )
    val wave2 by infiniteTransition.animateFloat(
        initialValue = 16f, targetValue = 6f,
        animationSpec = infiniteRepeatable(tween(280, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "w2"
    )
    val wave3 by infiniteTransition.animateFloat(
        initialValue = 8f, targetValue = 22f,
        animationSpec = infiniteRepeatable(tween(420, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "w3"
    )

    val isHolding = progress in 0.25f..0.85f
    val cardScale = if (isHolding) 1.04f else 1f
    val fingerAlpha = when {
        progress < 0.15f -> progress / 0.15f
        progress < 0.85f -> 1f
        else -> 1f - ((progress - 0.85f) / 0.15f)
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        MiniCard(
            modifier = Modifier.scale(cardScale),
            gradient = Brush.linearGradient(listOf(Color(0xFF34495E), Color(0xFF2C3E50)))
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // LIVE 标签
                Surface(
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = if (isHolding) 0.7f else 0.4f),
                    modifier = Modifier.padding(2.dp)
                ) {
                    Text(
                        text = if (isHolding) "▶ LIVE" else "LIVE",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isHolding) Color(0xFF2ECC71) else Color.White,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                // 声波均衡器
                if (isHolding) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(wave1.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF2ECC71))
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(wave2.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF2ECC71))
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(wave3.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF2ECC71))
                        )
                    }
                }
            }
        }

        // 虚拟按压指尖
        VirtualTouchPointer(
            modifier = Modifier.offset(y = 25.dp),
            alpha = fingerAlpha
        )
    }
}

/**
 * Page 5: 0 网络权限与纯本地离线隐私防护
 */
@Composable
private fun AnimatedLocalPrivacyDemo() {
    val infiniteTransition = rememberInfiniteTransition(label = "privacy_demo")
    val waveProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave"
    )
    val shieldScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shield"
    )

    val primaryColor = MaterialTheme.colorScheme.primary

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // Canvas 扩散水波纹
        Canvas(modifier = Modifier.size(160.dp)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            for (i in 0..2) {
                val ringProgress = (waveProgress + i * 0.333f) % 1f
                val radius = 26.dp.toPx() + ringProgress * 48.dp.toPx()
                val alpha = (1f - ringProgress) * 0.45f
                drawCircle(
                    color = primaryColor.copy(alpha = alpha),
                    radius = radius,
                    center = center,
                    style = Stroke(width = 2.dp.toPx())
                )
            }
        }

        // 居中安全护盾徽标
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
            shadowElevation = 6.dp,
            modifier = Modifier
                .scale(shieldScale)
                .size(72.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Rounded.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
            }
        }
    }
}
