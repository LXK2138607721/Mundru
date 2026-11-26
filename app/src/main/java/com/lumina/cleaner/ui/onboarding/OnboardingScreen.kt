package com.lumina.cleaner.ui.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.cleaner.R
import com.lumina.cleaner.core.util.HapticFeedback
import com.lumina.cleaner.ui.settings.SettingsGlassCard
import com.lumina.cleaner.ui.settings.SettingsInsetDivider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Mundru 瑞士黑白曜石 3 幕动态画廊首次安装指南 (OnboardingScreen)
 *
 * - 全程自由往返翻页：任意幕均支持外部区域自由左右翻页 + 顶栏「← 上一页」返回键 + 底部圆点直跳；
 * - 第 1 幕：动态星轨光学环 + 三层悬浮影卡 + 原版黑白小猫徽标序章；
 * - 第 2 幕：自由顺序、双向无限循环、轻滑即切且左侧真实补图的演练场；
 * - 第 3 幕：纯离线脉冲盾徽 + 错峰滑入隐私契约 + 一键融合相册授权。
 */
@Composable
fun OnboardingScreen(
    hasPermission: Boolean = false,
    isReplayMode: Boolean = false,
    onComplete: () -> Unit
) {
    val context = LocalContext.current
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val pagerState = rememberPagerState(pageCount = { 3 })
    val coroutineScope = rememberCoroutineScope()

    var doneLeft by remember { mutableStateOf(false) }
    var doneUp by remember { mutableStateOf(false) }
    var doneRight by remember { mutableStateOf(false) }
    val allGesturesDone = doneLeft && doneUp && doneRight

    // 首帧仅渲染第 1 幕保证秒开，220ms 静置后后台预组合第 2 幕演练场，彻底消除翻到第 2 幕时的首次组合掉帧
    var precomposeNext by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(220L)
        precomposeNext = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // 顶部柔和中性光学氛围晕
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.06f else 0.04f),
                            Color.Transparent
                        )
                    )
                )
        )

        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部极简导航栏：左侧固定品牌徽标稳固对齐，右侧支持随时跳过（三页均支持直接横滑自由往返）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (isDark) Color(0xFF1D1E24) else Color.White)
                            .border(
                                width = 0.7.dp,
                                color = if (isDark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.08f),
                                shape = RoundedCornerShape(9.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_mundru_logo),
                            contentDescription = "Mundru",
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Mundru",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.3.sp,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }

                if (pagerState.currentPage < 2) {
                    Text(
                        text = "跳过",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable {
                                HapticFeedback.tick(context)
                                onComplete()
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                } else {
                    Text(
                        text = "03 / 03",
                        style = TextStyle(fontFeatureSettings = "tnum"),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp)
                    )
                }
            }

            // 全程允许自由左右翻页；pageOffset 读取完全下沉至 RenderThread graphicsLayer，翻页全程 0 重组
            HorizontalPager(
                state = pagerState,
                beyondViewportPageCount = if (precomposeNext) 1 else 0,
                userScrollEnabled = true,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) { page ->
                val pageModifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val pageOffset = abs(
                            (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
                        ).coerceIn(0f, 1f)
                        val s = 1f - 0.055f * pageOffset
                        scaleX = s
                        scaleY = s
                        alpha = 1f - 0.55f * pageOffset
                    }

                Box(modifier = pageModifier) {
                    when (page) {
                        0 -> BrandProloguePage(isDark = isDark)
                        1 -> GesturePlaygroundPage(
                            isDark = isDark,
                            doneLeft = doneLeft,
                            doneUp = doneUp,
                            doneRight = doneRight,
                            onDoneLeft = { doneLeft = true },
                            onDoneUp = { doneUp = true },
                            onDoneRight = { doneRight = true }
                        )
                        else -> OfflinePledgePage(
                            isDark = isDark,
                            isReplayMode = isReplayMode,
                            isVisible = pagerState.currentPage == 2
                        )
                    }
                }
            }

            // 底部可点击进度指示条 + 炭黑/象牙白主操作按钮
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(top = 8.dp, bottom = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(3) { idx ->
                        val active = pagerState.currentPage == idx
                        val pillWidth by animateDpAsState(
                            targetValue = if (active) 24.dp else 7.dp,
                            animationSpec = spring(dampingRatio = 0.76f, stiffness = 420f),
                            label = "onboarding_step_dot"
                        )
                        Box(
                            modifier = Modifier
                                .height(7.dp)
                                .width(pillWidth)
                                .clip(CircleShape)
                                .background(
                                    if (active) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = if (isDark) 0.20f else 0.15f)
                                )
                                .clickable {
                                    HapticFeedback.tick(context)
                                    coroutineScope.launch { pagerState.animateScrollToPage(idx) }
                                }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                val ctaText = when (pagerState.currentPage) {
                    0 -> "试一试手势"
                    1 -> if (allGesturesDone) "手感已就位，继续" else "继续"
                    else -> when {
                        isReplayMode -> "返回设置"
                        hasPermission -> "开始整理相册"
                        else -> "授予相册权限并开始"
                    }
                }

                Button(
                    onClick = {
                        HapticFeedback.click(context)
                        if (pagerState.currentPage < 2) {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            }
                        } else {
                            onComplete()
                        }
                    },
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                ) {
                    Text(
                        text = ctaText,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.3.sp
                    )
                }
            }
        }
    }
}

/**
 * 第 1 幕：动态星轨光学环 + 三层微呼吸胶片影卡 + 原版黑白小猫徽标
 */
@Composable
private fun BrandProloguePage(isDark: Boolean) {
    val infinite = rememberInfiniteTransition(label = "prologue_motion")
    val orbitDeg by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(14000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "prologue_orbit"
    )
    val floatWave by infinite.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "prologue_float"
    )

    val ringInk = MaterialTheme.colorScheme.onBackground

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // 动态雕塑徽章舞台：旋转极细光环 + 呼吸微倾背景卡影 + 悬浮黑白小猫
        Box(
            modifier = Modifier.size(178.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val c = Offset(size.width * 0.5f, size.height * 0.5f)
                val rOuter = size.minDimension * 0.46f
                val rInner = size.minDimension * 0.36f
                drawCircle(
                    color = ringInk.copy(alpha = if (isDark) 0.08f else 0.06f),
                    radius = rOuter,
                    center = c,
                    style = Stroke(width = 0.8.dp.toPx())
                )
                rotate(degrees = orbitDeg, pivot = c) {
                    drawCircle(
                        brush = Brush.sweepGradient(
                            colorStops = arrayOf(
                                0.00f to Color.Transparent,
                                0.25f to ringInk.copy(alpha = if (isDark) 0.38f else 0.26f),
                                0.42f to Color.Transparent,
                                0.72f to ringInk.copy(alpha = if (isDark) 0.18f else 0.14f),
                                1.00f to Color.Transparent
                            ),
                            center = c
                        ),
                        radius = rOuter,
                        center = c,
                        style = Stroke(width = 1.4.dp.toPx())
                    )
                }
                rotate(degrees = -orbitDeg * 0.65f, pivot = c) {
                    drawCircle(
                        brush = Brush.sweepGradient(
                            colorStops = arrayOf(
                                0.00f to Color.Transparent,
                                0.35f to ringInk.copy(alpha = if (isDark) 0.22f else 0.14f),
                                0.55f to Color.Transparent,
                                1.00f to Color.Transparent
                            ),
                            center = c
                        ),
                        radius = rInner,
                        center = c,
                        style = Stroke(width = 1.dp.toPx())
                    )
                }
            }

            // 左右两张呼吸轻摆的半透明单色影卡，呼应相册卡堆物理意象
            val ghostShape = remember { RoundedCornerShape(18.dp) }
            Box(
                modifier = Modifier
                    .size(width = 68.dp, height = 84.dp)
                    .graphicsLayer {
                        translationX = -24.dp.toPx() - 2.dp.toPx() * floatWave
                        translationY = 4.dp.toPx() + 2.dp.toPx() * floatWave
                        rotationZ = -11f - 1.8f * floatWave
                    }
                    .clip(ghostShape)
                    .background(if (isDark) Color(0xFF1A1B22) else Color(0xFFECECE9))
                    .border(0.7.dp, ringInk.copy(alpha = 0.10f), ghostShape)
            )
            Box(
                modifier = Modifier
                    .size(width = 68.dp, height = 84.dp)
                    .graphicsLayer {
                        translationX = 24.dp.toPx() + 2.dp.toPx() * floatWave
                        translationY = 4.dp.toPx() - 2.dp.toPx() * floatWave
                        rotationZ = 11f + 1.8f * floatWave
                    }
                    .clip(ghostShape)
                    .background(if (isDark) Color(0xFF21222A) else Color(0xFFF3F3F0))
                    .border(0.7.dp, ringInk.copy(alpha = 0.10f), ghostShape)
            )

            val emblemShape = remember { RoundedCornerShape(24.dp) }
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .graphicsLayer {
                        translationY = -3.5.dp.toPx() * floatWave
                    }
                    .shadow(
                        elevation = if (isDark) 0.dp else 14.dp,
                        shape = emblemShape,
                        clip = false,
                        ambientColor = Color.Black.copy(alpha = 0.08f),
                        spotColor = Color.Black.copy(alpha = 0.14f)
                    )
                    .clip(emblemShape)
                    .background(if (isDark) Color(0xFF1A1B21) else Color.White)
                    .border(
                        width = 0.85.dp,
                        color = if (isDark) Color.White.copy(alpha = 0.16f) else Color.Black.copy(alpha = 0.08f),
                        shape = emblemShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_mundru_logo),
                    contentDescription = "Mundru 徽标",
                    tint = if (isDark) Color(0xFFF4F4F6) else Color(0xFF141518),
                    modifier = Modifier.size(50.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(22.dp))

        Text(
            text = "轻扫之间\n重拾相册秩序",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 36.sp,
            letterSpacing = (-0.4).sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Mundru 源自格陵兰语「擦除与沉淀」。\n无广告、不联网，只留纯粹的整理手感。",
            fontSize = 13.sp,
            lineHeight = 21.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(22.dp))

        HorizontalDivider(
            modifier = Modifier.width(36.dp),
            thickness = 0.8.dp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
        )

        Spacer(modifier = Modifier.height(18.dp))

        Surface(
            shape = CircleShape,
            color = if (isDark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.04f),
            border = BorderStroke(
                width = 0.6.dp,
                color = if (isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.07f)
            )
        ) {
            Text(
                text = "100% 纯本地离线 · 0 网络权限",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.3.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
            )
        }
    }
}

/**
 * 第 2 幕：自由顺序无限循环演练场
 */
@Composable
private fun GesturePlaygroundPage(
    isDark: Boolean,
    doneLeft: Boolean,
    doneUp: Boolean,
    doneRight: Boolean,
    onDoneLeft: () -> Unit,
    onDoneUp: () -> Unit,
    onDoneRight: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "三种手势，随心试滑",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.2).sp,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "在卡片上随意左右或上滑，滑动标题区可随时翻回上一页",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(12.dp))

        OnboardingPlayground(
            isDark = isDark,
            doneLeft = doneLeft,
            doneUp = doneUp,
            doneRight = doneRight,
            onDoneLeft = onDoneLeft,
            onDoneUp = onDoneUp,
            onDoneRight = onDoneRight
        )
    }
}

/**
 * 第 3 幕：纯离线脉冲防护盾 + 错峰上浮隐私契约
 */
@Composable
private fun OfflinePledgePage(
    isDark: Boolean,
    isReplayMode: Boolean,
    isVisible: Boolean
) {
    val pulse = rememberInfiniteTransition(label = "pledge_pulse")
    val wave1 by pulse.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing)),
        label = "pledge_ring_1"
    )
    val wave2 = (wave1 + 0.5f) % 1f

    val row0 = remember { Animatable(0f) }
    val row1 = remember { Animatable(0f) }
    val row2 = remember { Animatable(0f) }

    LaunchedEffect(isVisible) {
        if (isVisible) {
            row0.snapTo(0f); row1.snapTo(0f); row2.snapTo(0f)
            launch { row0.animateTo(1f, spring(dampingRatio = 0.84f, stiffness = 420f)) }
            launch { delay(65L); row1.animateTo(1f, spring(dampingRatio = 0.84f, stiffness = 420f)) }
            launch { delay(130L); row2.animateTo(1f, spring(dampingRatio = 0.84f, stiffness = 420f)) }
        }
    }

    val ringInk = MaterialTheme.colorScheme.onBackground

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // 极简脉冲离线防护盾徽章
        Box(
            modifier = Modifier.size(96.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val c = Offset(size.width * 0.5f, size.height * 0.5f)
                val baseR = size.minDimension * 0.28f
                val maxR = size.minDimension * 0.48f
                listOf(wave1, wave2).forEach { w ->
                    val r = baseR + (maxR - baseR) * w
                    val a = (1f - w) * (if (isDark) 0.24f else 0.16f)
                    drawCircle(
                        color = ringInk.copy(alpha = a),
                        radius = r,
                        center = c,
                        style = Stroke(width = 1.dp.toPx())
                    )
                }
            }
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (isDark) Color(0xFF1A1B21) else Color.White)
                    .border(
                        width = 0.8.dp,
                        color = if (isDark) Color.White.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(16.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Shield,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(26.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = "你的回忆\n永远只留在本地",
            fontSize = 25.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 33.sp,
            letterSpacing = (-0.3).sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = if (isReplayMode) "每一张照片与操作记录均严格保存在本机，安全可控"
            else "下一步只需授予本地相册读取权限，即可直接开始整理",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(20.dp))

        SettingsGlassCard(isDark = isDark) {
            PledgeRow(
                icon = Icons.Rounded.Shield,
                title = "完全离线",
                desc = "不申请联网权限，照片只在手机本地整理。",
                isDark = isDark,
                revealProgress = { row0.value }
            )
            SettingsInsetDivider(isDark = isDark)
            PledgeRow(
                icon = Icons.Rounded.DeleteOutline,
                title = "回收站暂存",
                desc = "上滑仅移入待删清单，彻底删除前会再次确认。",
                isDark = isDark,
                revealProgress = { row1.value }
            )
            SettingsInsetDivider(isDark = isDark)
            PledgeRow(
                icon = Icons.Rounded.AutoAwesome,
                title = "轻点看大图",
                desc = "轻触查看原图，长按播放实况，点底部信息栏看拍摄详情。",
                isDark = isDark,
                revealProgress = { row2.value }
            )
        }
    }
}

@Composable
private fun PledgeRow(
    icon: ImageVector,
    title: String,
    desc: String,
    isDark: Boolean,
    revealProgress: () -> Float
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                val p = revealProgress().coerceIn(0f, 1f)
                alpha = p
                translationY = (1f - p) * 14.dp.toPx()
            }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    if (isDark) Color.White.copy(alpha = 0.08f)
                    else Color.Black.copy(alpha = 0.055f)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = desc,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
