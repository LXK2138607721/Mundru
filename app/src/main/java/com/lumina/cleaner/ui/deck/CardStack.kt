package com.lumina.cleaner.ui.deck

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.lumina.cleaner.core.model.PhotoItem
import com.lumina.cleaner.core.util.HapticFeedback
import kotlinx.coroutines.launch
import kotlin.math.abs

enum class CardSwipeAction {
    KEEP,
    DELETE
}

/**
 * Mundru 卡片主舞台 (CardStack)
 * 品牌释义：Mundru 源自格陵兰语（Greenlandic），意为「擦掉 / 擦除」。
 * 舞台架构：极致手感的核心卡片流舞台，承载保留、擦除与无缝回溯。
 *
 * 核心交互与手感升级：
 * 1. 实时跟手右滑：当用户按住屏幕右滑不松手时，上一张照片根据拖拽距离 1:1 从屏幕左边缘跟手拉出；
 * 2. 预加载零延迟：上一张照片常驻挂载预加载 Bitmap 纹理，彻底根除右滑时的卡顿感与延迟显示；
 * 3. 丝滑物理衔接：右滑松手后弹簧动画无缝推进上一张照片入驻正中央，状态切换零跳变；
 * 4. 左滑与上滑手感保持轻快利落，首张照片右滑阻尼保护。
 */
@Composable
fun CardStack(
    photos: List<PhotoItem>,
    currentIndex: Int,
    onSwipeLeft: (PhotoItem) -> Unit,
    onSwipeRight: () -> Unit,
    onSwipeUp: (PhotoItem) -> Unit,
    onClickPhoto: (PhotoItem) -> Unit = {},
    modifier: Modifier = Modifier,
    onDoubleClickPhoto: (PhotoItem, Offset) -> Unit = { photo, _ -> onClickPhoto(photo) }
) {
    if (photos.isEmpty() || currentIndex >= photos.size) {
        return
    }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current

    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }

    val horizontalPaddingDp = 24.dp
    val padPx = with(density) { horizontalPaddingDp.toPx() }
    val cardWidthPx = screenWidthPx - padPx * 2

    // 隐藏基准距离：当 translationX 为该值时，上一张卡片的右边缘刚好紧贴屏幕左边缘 (x = 0)
    val hiddenBaseOffset = -(cardWidthPx + padPx)

    val horizontalThreshold = screenWidthPx * 0.13f
    val verticalThreshold = screenHeightPx * 0.065f

    val prevPhoto = if (currentIndex > 0) photos.getOrNull(currentIndex - 1) else null
    val currentPhoto = photos[currentIndex]
    val nextPhoto = photos.getOrNull(currentIndex + 1)
    val thirdPhoto = photos.getOrNull(currentIndex + 2)

    val animOffsetX = remember(currentPhoto.id) { Animatable(0f) }
    val animOffsetY = remember(currentPhoto.id) { Animatable(0f) }
    var hasTriggeredThresholdHaptic by remember(currentPhoto.id) { mutableStateOf(false) }
    var isCommittingSwipe by remember(currentPhoto.id) { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = horizontalPaddingDp),
        contentAlignment = Alignment.Center
    ) {
        // 底层第三张卡片
        if (thirdPhoto != null) {
            DeckCard(
                photo = thirdPhoto,
                enableGestures = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(y = 28.dp)
                    .scale(0.90f)
            )
        }

        // 底层第二张候选卡片
        if (nextPhoto != null) {
            DeckCard(
                photo = nextPhoto,
                enableGestures = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(y = 14.dp)
                    .scale(0.95f)
            )
        }

        // 当前居中卡片（接收主手势操作）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    translationX = animOffsetX.value
                    translationY = animOffsetY.value
                    // 左滑轻快倾斜，右滑保持稳定水平推移
                    rotationZ = if (animOffsetX.value < 0) animOffsetX.value * 0.035f else animOffsetX.value * 0.012f
                }
                .pointerInput(currentPhoto.id) {
                    val velocityTracker = VelocityTracker()
                    detectDragGestures(
                        onDragStart = {
                            if (isCommittingSwipe) return@detectDragGestures
                            velocityTracker.resetTracking()
                        },
                        onDragEnd = {
                            if (isCommittingSwipe) return@detectDragGestures
                            coroutineScope.launch {
                                val dx = animOffsetX.value
                                val dy = animOffsetY.value
                                val velocity = velocityTracker.calculateVelocity()
                                val vx = velocity.x
                                val vy = velocity.y

                                val isFastUp = vy < -350f && dy < -18f
                                val isFastRight = vx > 450f && dx > 25f
                                val isFastLeft = vx < -450f && dx < -25f

                                val commitSpring = spring<Float>(
                                    dampingRatio = 0.86f,
                                    stiffness = Spring.StiffnessMedium
                                )

                                when {
                                    // 右滑：查看上一张照片（丝滑拉满衔接）
                                    (dx > horizontalThreshold || isFastRight) && abs(dx) >= abs(dy) * 0.6f -> {
                                        if (currentIndex > 0 && prevPhoto != null) {
                                            isCommittingSwipe = true
                                            HapticFeedback.click(context)
                                            // 目标位移让上一张卡片完美推进至屏幕正中心 (translationX = 0)
                                            val targetRightExit = -hiddenBaseOffset
                                            animOffsetX.animateTo(targetRightExit, commitSpring)
                                            onSwipeRight()
                                        } else {
                                            // 首张无上一张照片，物理回弹与轻微触觉提示
                                            hasTriggeredThresholdHaptic = false
                                            HapticFeedback.tick(context)
                                            val softSpring = spring<Float>(
                                                dampingRatio = Spring.DampingRatioNoBouncy,
                                                stiffness = 260f
                                            )
                                            launch { animOffsetX.animateTo(0f, softSpring) }
                                            launch { animOffsetY.animateTo(0f, softSpring) }
                                        }
                                    }
                                    // 左滑：保留并进入下一张照片
                                    (dx < -horizontalThreshold || isFastLeft) && abs(dx) >= abs(dy) * 0.6f -> {
                                        isCommittingSwipe = true
                                        HapticFeedback.click(context)
                                        animOffsetX.animateTo(-screenWidthPx * 1.5f, commitSpring)
                                        onSwipeLeft(currentPhoto)
                                    }
                                    // 上滑：移入回收站（包容单手斜推生理角度）
                                    (dy < -verticalThreshold || isFastUp) && abs(dy) >= abs(dx) * 0.65f -> {
                                        isCommittingSwipe = true
                                        HapticFeedback.heavy(context)
                                        animOffsetY.animateTo(-screenHeightPx, commitSpring)
                                        onSwipeUp(currentPhoto)
                                    }
                                    else -> {
                                        hasTriggeredThresholdHaptic = false
                                        val softSpring = spring<Float>(
                                            dampingRatio = Spring.DampingRatioNoBouncy,
                                            stiffness = 260f
                                        )
                                        launch { animOffsetX.animateTo(0f, softSpring) }
                                        launch { animOffsetY.animateTo(0f, softSpring) }
                                    }
                                }
                            }
                        },
                        onDragCancel = {
                            if (isCommittingSwipe) return@detectDragGestures
                            coroutineScope.launch {
                                hasTriggeredThresholdHaptic = false
                                val softSpring = spring<Float>(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = 260f
                                )
                                launch { animOffsetX.animateTo(0f, softSpring) }
                                launch { animOffsetY.animateTo(0f, softSpring) }
                            }
                        },
                        onDrag = { change, dragAmount ->
                            if (isCommittingSwipe) return@detectDragGestures
                            change.consume()
                            velocityTracker.addPosition(change.uptimeMillis, change.position)
                            coroutineScope.launch {
                                val currentX = animOffsetX.value
                                // 首张照片向右滑施加阻尼保护
                                val effectiveDragX = if (currentIndex == 0 && (currentX + dragAmount.x) > 0f) {
                                    dragAmount.x * 0.28f
                                } else {
                                    dragAmount.x
                                }

                                val newX = currentX + effectiveDragX
                                val newY = animOffsetY.value + dragAmount.y
                                animOffsetX.snapTo(newX)
                                animOffsetY.snapTo(newY)

                                val reachedThreshold = (newX > horizontalThreshold && currentIndex > 0) ||
                                        (newX < -horizontalThreshold) ||
                                        (newY < -verticalThreshold && abs(newY) >= abs(newX) * 0.65f)
                                if (reachedThreshold && !hasTriggeredThresholdHaptic) {
                                    hasTriggeredThresholdHaptic = true
                                    HapticFeedback.tick(context)
                                } else if (!reachedThreshold && hasTriggeredThresholdHaptic) {
                                    hasTriggeredThresholdHaptic = false
                                }
                            }
                        }
                    )
                }
        ) {
            DeckCard(
                photo = currentPhoto,
                onClick = { if (!isCommittingSwipe) onClickPhoto(currentPhoto) },
                onDoubleClick = { focus -> if (!isCommittingSwipe) onDoubleClickPhoto(currentPhoto, focus) }
            )
        }

        // 上一张卡片：常驻挂载以预加载图片 Bitmap，并在右滑时根据手指位移从屏幕左侧 1:1 跟手拉出
        if (prevPhoto != null) {
            val isSwipingRight = animOffsetX.value > 0f
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        val offsetX = hiddenBaseOffset + animOffsetX.value
                        translationX = offsetX
                        // 右滑时完全不透明呈现，非右滑时微量透明度保留组合以维持 Coil Bitmap 预热
                        alpha = if (isSwipingRight) 1f else 0.001f
                    }
                    .shadow(
                        elevation = if (isSwipingRight) 16.dp else 0.dp,
                        ambientColor = Color.Black.copy(alpha = 0.08f),
                        spotColor = Color.Black.copy(alpha = 0.18f)
                    )
            ) {
                DeckCard(
                    photo = prevPhoto,
                    enableGestures = false,
                    onClick = {}
                )
            }
        }
    }
}
