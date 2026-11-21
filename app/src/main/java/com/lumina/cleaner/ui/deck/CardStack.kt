package com.lumina.cleaner.ui.deck

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.lumina.cleaner.core.model.PhotoItem
import com.lumina.cleaner.core.util.HapticFeedback
import kotlin.math.abs

private enum class DeckRole { THIRD, NEXT, FRONT, PREVIOUS }

enum class CardSwipeAction {
    KEEP,
    DELETE
}

// 稳定对象复用（解决 G5：避免在每帧 graphicsLayer 中重复分配 Shape）
private val CardCornerShape = RoundedCornerShape(24.dp)

/**
 * Mundru 卡片主舞台 (CardStack)
 * 品牌释义：Mundru 源自格陵兰语（Greenlandic），意为「擦掉 / 擦除」。
 * 舞台架构：极致手感的核心卡片流舞台，承载保留、擦除与无缝回溯。
 *
 * 核心交互与手感升级：
 * 1. 实时跟手右滑：当用户按住屏幕右滑不松手时，上一张照片根据拖拽距离 1:1 从屏幕左边缘跟手拉出；
 * 2. 预加载零延迟：上一张照片常驻挂载预加载 Bitmap 纹理，彻底根除右滑时的卡顿感与延迟显示；
 * 3. 右滑交接：当前照片收拢、滑入上一张下方，再展开至第二层大小后交接索引；
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
    onDoubleClickPhoto: (PhotoItem, Offset) -> Unit = { photo, _ -> onClickPhoto(photo) },
    showFileSize: Boolean = false
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
    val animDepthProgress = remember(currentPhoto.id) { Animatable(0f) }
    val animReturnProgress = remember(currentPhoto.id) { Animatable(0f) }
    var inputLayout by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var returnStart by remember(currentPhoto.id) { mutableStateOf<DeckReturnStart?>(null) }
    var hasTriggeredThresholdHaptic by remember(currentPhoto.id) { mutableStateOf(false) }

    // 生产环境手势协同器：持有真实生命周期，管理双动画协调、照片身份守卫、逻辑坐标同步与景深连续过渡
    val coordinator = remember { DeckGestureCoordinator(coroutineScope) }

    // 照片身份变更守卫：当当前照片变化时，立即取消旧照片的所有活跃任务并重置代次 (Fix F1)
    LaunchedEffect(currentPhoto.id) {
        coordinator.bindPhoto(currentPhoto.id)
        hasTriggeredThresholdHaptic = false
    }

    fun nextLayerMetrics(): DeckDepthMetrics = returnStart?.let {
        DeckReturnModel.nextLayer(animReturnProgress.value, it.depth)
    } ?: DeckDepthModel.interpolateSecondLayer(animDepthProgress.value)

    val frontModifier = Modifier
        .graphicsLayer {
            val returning = returnStart
            if (returning != null) {
                val frame = DeckReturnModel.currentCard(animReturnProgress.value, returning, density.density)
                scaleX = frame.scale
                scaleY = frame.scale
                translationX = frame.x
                translationY = frame.y
                rotationZ = frame.rotation
            } else {
                scaleX = 1f
                scaleY = 1f
                translationX = animOffsetX.value
                translationY = animOffsetY.value
                // 左滑轻快倾斜，右滑保持稳定水平推移
                rotationZ = if (animOffsetX.value < 0) animOffsetX.value * 0.035f else animOffsetX.value * 0.012f
            }
        }
        .onPlaced { inputLayout = it }
        .pointerInput(currentPhoto.id, horizontalThreshold, verticalThreshold) {
            val velocityTracker = VelocityTracker()
            detectDragGestures(
                onDragStart = {
                    if (coordinator.isCommittingSwipe) return@detectDragGestures
                    velocityTracker.resetTracking()
                    coordinator.onDragStart(
                        initialX = animOffsetX.value,
                        initialY = animOffsetY.value,
                        hThreshold = horizontalThreshold,
                        vThreshold = verticalThreshold,
                        initialDepth = animDepthProgress.value
                    )
                },
                onDragEnd = {
                    if (coordinator.isCommittingSwipe) return@detectDragGestures
                    coordinator.onDragEnd()

                    val dx = coordinator.dragLogicalX
                    val dy = coordinator.dragLogicalY
                    val velocity = velocityTracker.calculateVelocity()
                    val vx = velocity.x
                    val vy = velocity.y

                    // 解决 G1：裁决绝对采用最新的手势逻辑位移与速度，不依赖排队中的协程
                    val action = coordinator.resolveReleaseAction(
                        vx = vx,
                        vy = vy,
                        hThreshold = horizontalThreshold,
                        vThreshold = verticalThreshold,
                        hasPreviousPhoto = currentIndex > 0 && prevPhoto != null
                    )

                    val commitSpring = spring<Float>(
                        dampingRatio = 0.86f,
                        stiffness = Spring.StiffnessMedium
                    )
                    val softSpring = spring<Float>(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = 260f
                    )

                    when (action) {
                        // 从当前显示帧开始收拢和归位，不再将本张向右移出后瞬移回底层。
                        ReleaseAction.COMMIT_RIGHT -> {
                            HapticFeedback.click(context)
                            returnStart = DeckReturnModel.capture(animOffsetX.value, animOffsetY.value,
                                animDepthProgress.value, vx, vy, horizontalThreshold)
                            coordinator.commitReturn(
                                progress = animReturnProgress,
                                onCommitSuccess = onSwipeRight
                            )
                        }
                        // 首张无上一张照片，物理回弹与轻微触觉提示
                        ReleaseAction.BOUNCE_RIGHT_FIRST -> {
                            hasTriggeredThresholdHaptic = false
                            HapticFeedback.tick(context)
                            coordinator.bounceBack(
                                softSpring = softSpring,
                                animDepthProgress = animDepthProgress,
                                animOffsetX = animOffsetX,
                                animOffsetY = animOffsetY,
                                startOffsetX = dx,
                                startOffsetY = dy
                            )
                        }
                        // 左滑：保留并进入下一张照片（并发协同等待景深推进至 1f 终点）
                        ReleaseAction.COMMIT_LEFT -> {
                            HapticFeedback.click(context)
                            coordinator.commitSwipe(
                                commitSpring = commitSpring,
                                animDepthProgress = animDepthProgress,
                                animOffset = animOffsetX,
                                targetOffset = -screenWidthPx * 1.5f,
                                targetDepthP = 1f,
                                startOffset = dx,
                                onCommitSuccess = { onSwipeLeft(currentPhoto) }
                            )
                        }
                        // 上滑：移入回收站（并发协同等待景深推进至 1f 终点）
                        ReleaseAction.COMMIT_UP -> {
                            HapticFeedback.heavy(context)
                            coordinator.commitSwipe(
                                commitSpring = commitSpring,
                                animDepthProgress = animDepthProgress,
                                animOffset = animOffsetY,
                                targetOffset = -screenHeightPx,
                                targetDepthP = 1f,
                                startOffset = dy,
                                onCommitSuccess = { onSwipeUp(currentPhoto) }
                            )
                        }
                        // 未达阈值回弹：景深与位移平滑协同退回 0f
                        ReleaseAction.BOUNCE -> {
                            hasTriggeredThresholdHaptic = false
                            coordinator.bounceBack(
                                softSpring = softSpring,
                                animDepthProgress = animDepthProgress,
                                animOffsetX = animOffsetX,
                                animOffsetY = animOffsetY,
                                startOffsetX = dx,
                                startOffsetY = dy
                            )
                        }
                    }
                },
                onDragCancel = {
                    if (coordinator.isCommittingSwipe) return@detectDragGestures
                    hasTriggeredThresholdHaptic = false
                    val dx = coordinator.dragLogicalX
                    val dy = coordinator.dragLogicalY
                    coordinator.onDragEnd()
                    val softSpring = spring<Float>(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = 260f
                    )
                    coordinator.bounceBack(
                        softSpring = softSpring,
                        animDepthProgress = animDepthProgress,
                        animOffsetX = animOffsetX,
                        animOffsetY = animOffsetY,
                        startOffsetX = dx,
                        startOffsetY = dy
                    )
                },
                onDrag = { change, dragAmount ->
                    if (coordinator.isCommittingSwipe) return@detectDragGestures
                    val layout = inputLayout ?: return@detectDragGestures
                    val sample = deckDragSample(change.position, dragAmount, layout::localToRoot)
                    change.consume()
                    velocityTracker.addPosition(change.uptimeMillis, sample.position)

                    val currentLogicalX = coordinator.dragLogicalX
                    // 首张照片向右滑施加阻尼保护
                    val effectiveDragX = if (currentIndex == 0 && (currentLogicalX + sample.delta.x) > 0f) {
                        sample.delta.x * 0.28f
                    } else {
                        sample.delta.x
                    }

                    val newX = currentLogicalX + effectiveDragX
                    val newY = coordinator.dragLogicalY + sample.delta.y

                    // 1. 同步记录手势逻辑位移并更新景深连续过渡（解决 G1 与 G2）
                    coordinator.onDragUpdate(
                        newX = newX,
                        newY = newY,
                        hThreshold = horizontalThreshold,
                        vThreshold = verticalThreshold,
                        animDepthProgress = animDepthProgress,
                        updateJobScope = coroutineScope
                    )

                    // 2. 异步更新展示位移（带代次与拖拽状态校验保护）
                    coordinator.updateVisualOffset(
                        targetX = newX,
                        targetY = newY,
                        animOffsetX = animOffsetX,
                        animOffsetY = animOffsetY,
                        updateJobScope = coroutineScope
                    )

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
            )
        }
    val layers = listOfNotNull(
        thirdPhoto?.let { DeckRole.THIRD to it },
        nextPhoto?.let { DeckRole.NEXT to it },
        DeckRole.FRONT to currentPhoto,
        prevPhoto?.let { DeckRole.PREVIOUS to it }
    )
    Box(
        modifier = modifier.fillMaxSize().padding(horizontal = horizontalPaddingDp),
        contentAlignment = Alignment.Center
    ) {
        for ((role, photo) in layers) key(photo.id) {
            // The same card moves between layers; its loaded painter stays alive at handoff.
            val isFront = role == DeckRole.FRONT
            val layerModifier = when (role) {
                DeckRole.THIRD -> Modifier.graphicsLayer {
                    val metrics = DeckDepthModel.interpolateThirdLayer(animDepthProgress.value)
                    scaleX = metrics.scale
                    scaleY = metrics.scale
                    translationY = with(density) { metrics.offsetYDp.dp.toPx() }
                    alpha = if (returnStart != null) DeckReturnModel.thirdAlpha(animReturnProgress.value) else 1f
                }
                DeckRole.NEXT -> Modifier.graphicsLayer {
                    val metrics = nextLayerMetrics()
                    scaleX = metrics.scale
                    scaleY = metrics.scale
                    translationY = with(density) { metrics.offsetYDp.dp.toPx() }
                }
                DeckRole.FRONT -> frontModifier
                DeckRole.PREVIOUS -> Modifier.graphicsLayer {
                    val returning = returnStart
                    if (returning != null) {
                        val progress = animReturnProgress.value
                        translationX = DeckReturnModel.previousX(progress, hiddenBaseOffset + returning.x, returning.vx)
                        alpha = DeckReturnModel.previousAlpha(progress, returning)
                        shadowElevation = with(density) { DeckReturnModel.previousShadowDp(progress, returning).dp.toPx() }
                    } else {
                        translationX = hiddenBaseOffset + animOffsetX.value
                        val isSwipingRight = animOffsetX.value > 0f
                        alpha = if (isSwipingRight) 1f else 0.001f
                        shadowElevation = if (isSwipingRight) with(density) { 16.dp.toPx() } else 0f
                    }
                    ambientShadowColor = Color.Black.copy(alpha = 0.08f)
                    spotShadowColor = Color.Black.copy(alpha = 0.18f)
                    shape = CardCornerShape
                    clip = false
                }
            }
            Box(Modifier.fillMaxWidth().then(layerModifier)) {
                DeckCard(
                    photo = photo,
                    showFileSize = showFileSize,
                    isFront = isFront,
                    smoothOutline = isFront,
                    cacheMovingCard = role == DeckRole.PREVIOUS || (isFront && returnStart == null),
                    // Apply scale inside the drag detector so the hit target stays full-sized.
                    modifier = if (isFront) Modifier.graphicsLayer {
                        val scale = if (prevPhoto != null && returnStart == null)
                            DeckReturnModel.dragScale(animOffsetX.value, animOffsetY.value, horizontalThreshold)
                        else 1f
                        scaleX = scale
                        scaleY = scale
                    } else Modifier,
                    enableGestures = isFront && returnStart == null,
                    onClick = { if (isFront && !coordinator.isCommittingSwipe) onClickPhoto(photo) },
                    onDoubleClick = if (isFront) { focus ->
                        if (!coordinator.isCommittingSwipe) onDoubleClickPhoto(photo, focus)
                    } else null,
                    depthBlurRadiusDp = {
                        when (role) {
                            DeckRole.THIRD -> DeckDepthModel.interpolateThirdLayer(animDepthProgress.value).blurDp
                            DeckRole.NEXT -> nextLayerMetrics().blurDp
                            DeckRole.FRONT -> if (returnStart == null) 0f else DeckReturnModel.blurDp(animReturnProgress.value)
                            DeckRole.PREVIOUS -> 0f
                        }
                    },
                    depthScrimAlpha = {
                        when (role) {
                            DeckRole.THIRD -> DeckDepthModel.interpolateThirdLayer(animDepthProgress.value).scrimAlpha
                            DeckRole.NEXT -> nextLayerMetrics().scrimAlpha
                            DeckRole.FRONT -> if (returnStart == null) 0f else DeckReturnModel.scrimAlpha(animReturnProgress.value)
                            DeckRole.PREVIOUS -> 0f
                        }
                    }
                )
            }
        }
    }
}
