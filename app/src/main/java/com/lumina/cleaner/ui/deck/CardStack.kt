package com.lumina.cleaner.ui.deck

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.lumina.cleaner.core.model.PhotoDisplayAspectCache
import com.lumina.cleaner.core.model.PhotoItem
import com.lumina.cleaner.core.util.HapticFeedback
import com.lumina.cleaner.ui.glass.LiquidBackdropState
import com.lumina.cleaner.ui.viewer.PhotoTransform
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

private enum class DeckRole { FOURTH, THIRD, NEXT, FRONT, PREVIOUS }

enum class CardSwipeAction {
    KEEP,
    DELETE
}

// 稳定对象复用（解决 G5：避免在每帧 graphicsLayer 中重复分配 Shape）
private val CardCornerShape = RoundedCornerShape(24.dp)

@Stable
private class DeckCardMotionState(val photoId: Long) {
    val offsetX = Animatable(0f)
    val offsetY = Animatable(0f)
    val depthProgress = Animatable(0f)
    val returnProgress = Animatable(0f)
    val trashArcProgress = Animatable(0f)
    var returnStart by mutableStateOf<DeckReturnStart?>(null)
    var trashArcStart by mutableStateOf<DeckTrashArcStart?>(null)
    var hasTriggeredThresholdHaptic: Boolean = false
}

/**
 * Mundru 卡片主舞台 (CardStack)
 * 品牌释义：Mundru 源自格陵兰语（Greenlandic），意为「擦掉 / 擦除」。
 *
 * v1.6.1 零断触 & 零停顿手势架构重构：
 * 1. 全舞台稳定统一手势层 (`pointerInput(Unit)` 挂载于静态外层 `fillMaxSize()` 舞台)：
 *    - 绝不因 `currentPhoto.id` 或 `trashTargetInRoot` 变化而重启手势协程，彻底根除左右滑动断触；
 *    - 无论横图 (16:9) 还是竖图 (9:16)，手指落在整个主舞台任意位置都可顺畅拖拽，绝不漏触；
 * 2. 可打断毫秒级无缝交接 (`flushCommitIfInFlight` + `precreatedMotion`)：
 *    - 左滑切图采用继承甩出初速的紧致弹簧（~140-175ms，消除 350ms 隐形收尾死区）；
 *    - 即使在上一张刚刚滑出的瞬间立刻点击看大图或连续快滑下一张，触控瞬间同步落定上一张并无缝绑定到新卡片，
 *      实现「左滑后立刻点就能秒开大图、连续快滑零断触」的旗舰系统相册手感；
 * 3. 100% 保留原版三层可见模糊景深卡堆、Q 弹 `DeckReturnModel` 回看曲线与三段式神灯上滑回收联动。
 */
@Composable
fun CardStack(
    photos: List<PhotoItem>,
    currentIndex: Int,
    onSwipeLeft: (PhotoItem) -> Unit,
    onSwipeRight: () -> Unit,
    onSwipeUp: (PhotoItem) -> Unit,
    onClickPhoto: (PhotoItem, Rect?) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
    onDoubleClickPhoto: (PhotoItem, Offset, Rect?) -> Unit = { photo, _, bounds -> onClickPhoto(photo, bounds) },
    showFileSize: Boolean = false,
    trashTargetInRootProvider: () -> Offset? = { null },
    trashPullState: MutableFloatState? = null,
    trashThresholdState: MutableState<Boolean>? = null,
    onTrashArcImpact: () -> Unit = {},
    backdropState: LiquidBackdropState? = null,
    horizontalOnlySwipeState: MutableState<Boolean>? = null,
    ambientMotionSignal: (() -> Float)? = null,
    vortexBloomProgress: () -> Float = { 1f },
    isVortexActive: Boolean = false
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
    val fourthPhoto = photos.getOrNull(currentIndex + 3)

    // 预创建动效承载器：当用户在上一张收尾瞬间抢先触控时提前绑定到新照片，重组后零闪烁无缝接棒
    var precreatedMotion by remember { mutableStateOf<DeckCardMotionState?>(null) }
    var flushedPhotoId by remember { mutableStateOf<Long?>(null) }
    var livePressingPhotoId by remember { mutableStateOf<Long?>(null) }

    val motion = remember(currentPhoto.id) {
        val pre = precreatedMotion
        if (pre != null && pre.photoId == currentPhoto.id) {
            precreatedMotion = null
            pre
        } else {
            DeckCardMotionState(currentPhoto.id)
        }
    }

    val stageLayoutRef = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val frontRestingBoundsRef = remember(currentPhoto.id) { arrayOfNulls<Rect>(1) }

    // 生产环境手势协同器：持有真实生命周期，管理双动画协调、照片身份守卫、无缝打断与景深连续过渡
    val coordinator = remember { DeckGestureCoordinator(coroutineScope) }

    // 通过 rememberUpdatedState 提供最新状态给永不重启的 pointerInput(Unit)
    val latestPhotos by rememberUpdatedState(photos)
    val latestIndex by rememberUpdatedState(currentIndex)
    val latestMotion by rememberUpdatedState(motion)
    val latestFrontBoundsRef by rememberUpdatedState(frontRestingBoundsRef)
    val latestTrashTargetProvider by rememberUpdatedState(trashTargetInRootProvider)
    val latestOnSwipeLeft by rememberUpdatedState(onSwipeLeft)
    val latestOnSwipeRight by rememberUpdatedState(onSwipeRight)
    val latestOnSwipeUp by rememberUpdatedState(onSwipeUp)
    val latestOnClickPhoto by rememberUpdatedState(onClickPhoto)
    val latestOnDoubleClickPhoto by rememberUpdatedState(onDoubleClickPhoto)
    val latestOnTrashArcImpact by rememberUpdatedState(onTrashArcImpact)
    val latestHThreshold by rememberUpdatedState(horizontalThreshold)
    val latestVThreshold by rememberUpdatedState(verticalThreshold)
    val latestScreenW by rememberUpdatedState(screenWidthPx)
    val latestScreenH by rememberUpdatedState(screenHeightPx)
    val latestPadPx by rememberUpdatedState(padPx)
    val latestDensity by rememberUpdatedState(density.density)
    val latestVortexActive by rememberUpdatedState(isVortexActive)

    // 仅在卡片真正逼近顶栏（上滑拖拽 / 神灯吸入弧线）或顶部氛围流光渐变时驱动液态玻璃 120fps 实时折射；
    // 纯水平左/右滑卡时卡片始终位于顶栏下方，零无效顶栏 AGSL+Blur 重绘与 getImageFilter 洪峰。
    val horizontalPauseSettleJobRef = remember { arrayOfNulls<Job>(1) }
    fun setHorizontalSwipePause(active: Boolean, immediate: Boolean = false) {
        val state = horizontalOnlySwipeState ?: return
        horizontalPauseSettleJobRef[0]?.cancel()
        horizontalPauseSettleJobRef[0] = null
        if (active) {
            if (!state.value) state.value = true
        } else if (immediate) {
            if (state.value) state.value = false
        } else if (state.value) {
            // 连续极速左滑期间（间隔 <260ms）保持状态稳定不翻动，彻底消除每张切卡帧切换 drawLayer/drawContent 造成的 2.7ms Draw 尖峰；
            // 停手 260ms 后静默恢复一次底图录制即归零静息。
            horizontalPauseSettleJobRef[0] = coroutineScope.launch {
                delay(260L)
                if (!coordinator.isDragging && !coordinator.isCommittingSwipe) {
                    state.value = false
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            horizontalPauseSettleJobRef[0]?.cancel()
            horizontalPauseSettleJobRef[0] = null
            horizontalOnlySwipeState?.value = false
        }
    }

    DisposableEffect(backdropState, motion, ambientMotionSignal) {
        backdropState?.bindMotionSignal {
            val upwardY = motion.offsetY.value.let { if (it < -6f) it else 0f }
            val arcP = motion.trashArcProgress.value * 100f
            val ambientP = ambientMotionSignal?.invoke() ?: 0f
            upwardY + arcP + ambientP
        }
        onDispose {
            backdropState?.bindMotionSignal(null)
        }
    }

    // 照片身份变更守卫：仅当 coordinator 尚未提前绑定至本照片时重置，绝不打断已抢先启动的新手势
    LaunchedEffect(currentPhoto.id) {
        if (flushedPhotoId != null && flushedPhotoId != currentPhoto.id) {
            flushedPhotoId = null
        }
        if (coordinator.currentPhotoId != currentPhoto.id) {
            coordinator.bindPhoto(currentPhoto.id)
            motion.hasTriggeredThresholdHaptic = false
            trashPullState?.floatValue = 0f
            trashThresholdState?.value = false
            setHorizontalSwipePause(active = false, immediate = false)
        }
    }

    fun nextLayerMetrics(): DeckDepthMetrics {
        if (flushedPhotoId == currentPhoto.id) {
            // 上一张已被同步打断提交但重组尚未落帧，保持第二层处于 1.0 清晰就位态，杜绝 1 帧闪回模糊
            return DeckDepthModel.interpolateSecondLayer(1f)
        }
        return motion.returnStart?.let {
            DeckReturnModel.nextLayer(motion.returnProgress.value, it.depth)
        } ?: DeckDepthModel.interpolateSecondLayer(motion.depthProgress.value)
    }

    fun currentDragTrashPull(): Float {
        if (motion.returnStart != null || motion.trashArcStart != null) return 0f
        return DeckTrashArcModel.dragTrashPull(motion.offsetX.value, motion.offsetY.value, verticalThreshold)
    }

    val frontModifier = Modifier.graphicsLayer {
        if (flushedPhotoId == currentPhoto.id) {
            alpha = 0f
            return@graphicsLayer
        }
        val returning = motion.returnStart
        val trashing = motion.trashArcStart
        when {
            returning != null -> {
                val frame = DeckReturnModel.currentCard(motion.returnProgress.value, returning, density.density)
                scaleX = frame.scale
                scaleY = frame.scale
                translationX = frame.x
                translationY = frame.y
                rotationZ = frame.rotation
                rotationX = 0f
                alpha = 1f
            }
            trashing != null -> {
                val frame = DeckTrashArcModel.frameAt(motion.trashArcProgress.value, trashing, density.density)
                scaleX = frame.scaleX
                scaleY = frame.scaleY
                translationX = frame.x
                translationY = frame.y
                rotationZ = frame.rotationZ
                rotationX = 0f
                alpha = frame.alpha
            }
            else -> {
                val pull = DeckTrashArcModel.dragTrashPull(motion.offsetX.value, motion.offsetY.value, verticalThreshold)
                val bloom = vortexBloomProgress().coerceIn(0f, 1f)
                if (bloom < 1f) {
                    // 严格单调舒展（0.86x -> 1.000x，绝不过冲变大再回缩抖动）
                    val s = 0.86f + 0.14f * bloom
                    scaleX = s
                    scaleY = s
                    translationX = motion.offsetX.value
                    translationY = motion.offsetY.value
                    rotationZ = -4f * (1f - bloom)
                    rotationX = 0f
                    alpha = (bloom / 0.28f).coerceIn(0f, 1f)
                } else {
                    scaleX = 1f
                    scaleY = 1f
                    translationX = motion.offsetX.value
                    translationY = motion.offsetY.value
                    cameraDistance = 14f * density.density
                    rotationX = DeckTrashArcModel.dragTiltXDegrees(pull)
                    rotationZ = when {
                        pull > 0.05f -> (motion.offsetX.value * 0.02f) + pull * 4.5f
                        motion.offsetX.value < 0f -> motion.offsetX.value * 0.035f
                        else -> motion.offsetX.value * 0.012f
                    }
                    alpha = 1f
                }
            }
        }
    }

    val layers = listOfNotNull(
        fourthPhoto?.let { DeckRole.FOURTH to it },
        thirdPhoto?.let { DeckRole.THIRD to it },
        nextPhoto?.let { DeckRole.NEXT to it },
        DeckRole.FRONT to currentPhoto,
        prevPhoto?.let { DeckRole.PREVIOUS to it }
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { stageLayoutRef[0] = it }
            // 永不重启的静态外层统一手势层：彻底消除切图/徽标更新引起的断触与左滑后点击顿感
            .pointerInput(Unit) {
                val touchSlop = viewConfiguration.touchSlop
                val doubleTapSlopPx = 96.dp.toPx()
                val doubleTapWindowMs = 135L

                var pendingTapJob: Job? = null
                var lastTapPhotoId: Long = -1L
                var lastTapTimeMs: Long = 0L
                var lastTapStagePos: Offset = Offset.Zero

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = true)
                    if (latestVortexActive) return@awaitEachGesture
                    val downTimeMs = down.uptimeMillis
                    val downPos = down.position

                    // 1. 解析当前触控应绑定的目标照片；若上一张尚在飞出收尾，触控瞬间同步落定并无缝接管新卡片
                    val photosNow = latestPhotos
                    val idxNow = latestIndex
                    if (photosNow.isEmpty() || idxNow >= photosNow.size) return@awaitEachGesture

                    var activePhoto: PhotoItem
                    var activeMotion: DeckCardMotionState
                    var activeHasPrev: Boolean
                    var wasFlushed = false

                    if (coordinator.isCommittingSwipe) {
                        val dir = coordinator.committingDirection
                        val oldPhoto = photosNow.getOrNull(idxNow)
                        val expectedPhoto = when (dir) {
                            ReleaseAction.COMMIT_RIGHT -> photosNow.getOrNull(idxNow - 1)
                            ReleaseAction.COMMIT_LEFT, ReleaseAction.COMMIT_UP -> photosNow.getOrNull(idxNow + 1)
                            else -> oldPhoto
                        }
                        val expectedHasPrev = when (dir) {
                            ReleaseAction.COMMIT_RIGHT -> (idxNow - 1) > 0
                            ReleaseAction.COMMIT_LEFT -> true
                            ReleaseAction.COMMIT_UP -> idxNow > 0
                            else -> idxNow > 0
                        }
                        coordinator.flushCommitIfInFlight()
                        if (expectedPhoto == null) return@awaitEachGesture

                        val freshMotion = DeckCardMotionState(expectedPhoto.id)
                        precreatedMotion = freshMotion
                        flushedPhotoId = oldPhoto?.id
                        coordinator.bindPhoto(expectedPhoto.id)
                        trashPullState?.floatValue = 0f
                        trashThresholdState?.value = false
                        setHorizontalSwipePause(active = dir != ReleaseAction.COMMIT_UP, immediate = dir == ReleaseAction.COMMIT_UP)

                        activePhoto = expectedPhoto
                        activeMotion = freshMotion
                        activeHasPrev = expectedHasPrev
                        wasFlushed = true
                    } else {
                        activePhoto = photosNow[idxNow]
                        activeMotion = latestMotion
                        activeHasPrev = idxNow > 0 && photosNow.getOrNull(idxNow - 1) != null
                        if (coordinator.currentPhotoId != activePhoto.id) {
                            coordinator.bindPhoto(activePhoto.id)
                        }
                    }

                    // 2. 启动可选实况照片长按预览计时器（不阻塞单击与滑动）
                    val canLiveLongPress = activePhoto.isLivePhoto && activePhoto.motionVideoLength > 0L
                    var longPressed = false
                    var longPressJob: Job? = null
                    if (canLiveLongPress) {
                        val targetId = activePhoto.id
                        longPressJob = coroutineScope.launch {
                            delay(viewConfiguration.longPressTimeoutMillis)
                            longPressed = true
                            HapticFeedback.tick(context)
                            livePressingPhotoId = targetId
                        }
                    }

                    var isDragActive = false
                    var totalDx = 0f
                    var totalDy = 0f
                    var lastChangePos = downPos
                    var upConsumedByChild = false
                    var gestureCanceled = false
                    val velocityTracker = VelocityTracker()

                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null) {
                                gestureCanceled = true
                                break
                            }

                            if (!change.pressed) {
                                lastChangePos = change.position
                                upConsumedByChild = change.isConsumed
                                break
                            }

                            if (change.isConsumed && !isDragActive) {
                                // 子级徽标（如 LIVE/HDR）已接管事件，立即放手绝不干扰
                                upConsumedByChild = true
                                longPressJob?.cancel()
                                break
                            }

                            if (change.positionChanged()) {
                                val delta = change.positionChange()
                                if (!isDragActive) {
                                    totalDx += delta.x
                                    totalDy += delta.y
                                    if (Offset(totalDx, totalDy).getDistance() > touchSlop) {
                                        // 越过防抖阈值：立刻取消待定单击与长按，1:1 丝滑进入拖拽模式
                                        pendingTapJob?.cancel()
                                        pendingTapJob = null
                                        lastTapPhotoId = -1L
                                        longPressJob?.cancel()
                                        if (longPressed) {
                                            longPressed = false
                                            livePressingPhotoId = null
                                        }

                                        isDragActive = true
                                        velocityTracker.resetTracking()
                                        velocityTracker.addPosition(downTimeMs, downPos)
                                        velocityTracker.addPosition(change.uptimeMillis, change.position)

                                        val hThresh = latestHThreshold
                                        val vThresh = latestVThreshold
                                        coordinator.onDragStart(
                                            initialX = activeMotion.offsetX.value,
                                            initialY = activeMotion.offsetY.value,
                                            hThreshold = hThresh,
                                            vThreshold = vThresh,
                                            initialDepth = activeMotion.depthProgress.value
                                        )

                                        val curX = coordinator.dragLogicalX
                                        val effDx = if (!activeHasPrev && (curX + totalDx) > 0f) totalDx * 0.28f else totalDx
                                        val newX = curX + effDx
                                        val newY = coordinator.dragLogicalY + totalDy

                                        coordinator.onDragUpdate(
                                            newX = newX,
                                            newY = newY,
                                            hThreshold = hThresh,
                                            vThreshold = vThresh,
                                            animDepthProgress = activeMotion.depthProgress,
                                            updateJobScope = coroutineScope
                                        )
                                        coordinator.updateVisualOffset(
                                            targetX = newX,
                                            targetY = newY,
                                            animOffsetX = activeMotion.offsetX,
                                            animOffsetY = activeMotion.offsetY,
                                            updateJobScope = coroutineScope
                                        )
                                        setHorizontalSwipePause(
                                            active = newY >= -6f && abs(newX) > 1f,
                                            immediate = newY < -6f
                                        )
                                        change.consume()
                                    }
                                } else {
                                    change.consume()
                                    velocityTracker.addPosition(change.uptimeMillis, change.position)

                                    val hThresh = latestHThreshold
                                    val vThresh = latestVThreshold
                                    val curX = coordinator.dragLogicalX
                                    val effDx = if (!activeHasPrev && (curX + delta.x) > 0f) delta.x * 0.28f else delta.x
                                    val newX = curX + effDx
                                    val newY = coordinator.dragLogicalY + delta.y

                                    coordinator.onDragUpdate(
                                        newX = newX,
                                        newY = newY,
                                        hThreshold = hThresh,
                                        vThreshold = vThresh,
                                        animDepthProgress = activeMotion.depthProgress,
                                        updateJobScope = coroutineScope
                                    )
                                    coordinator.updateVisualOffset(
                                        targetX = newX,
                                        targetY = newY,
                                        animOffsetX = activeMotion.offsetX,
                                        animOffsetY = activeMotion.offsetY,
                                        updateJobScope = coroutineScope
                                    )

                                    val pull = DeckTrashArcModel.dragTrashPull(newX, newY, vThresh)
                                    val trashThresholdReached = newY < -vThresh && abs(newY) >= abs(newX) * 0.65f
                                    trashPullState?.floatValue = pull
                                    trashThresholdState?.value = trashThresholdReached
                                    val isHorizontalOnly = newY >= -6f && pull <= 0.005f
                                    setHorizontalSwipePause(active = isHorizontalOnly, immediate = !isHorizontalOnly)

                                    val reachedThreshold = (newX > hThresh && activeHasPrev) ||
                                        (newX < -hThresh) ||
                                        trashThresholdReached
                                    if (reachedThreshold && !activeMotion.hasTriggeredThresholdHaptic) {
                                        activeMotion.hasTriggeredThresholdHaptic = true
                                        HapticFeedback.tick(context)
                                    } else if (!reachedThreshold && activeMotion.hasTriggeredThresholdHaptic) {
                                        activeMotion.hasTriggeredThresholdHaptic = false
                                    }
                                }
                            }
                        }
                    } finally {
                        longPressJob?.cancel()
                        if (longPressed) {
                            livePressingPhotoId = null
                        }
                    }

                    // 3A. 拖拽抬手/取消裁决
                    if (isDragActive) {
                        val hThresh = latestHThreshold
                        val vThresh = latestVThreshold
                        val softSpring = spring<Float>(dampingRatio = 0.68f, stiffness = 380f)

                        if (gestureCanceled) {
                            activeMotion.hasTriggeredThresholdHaptic = false
                            trashPullState?.floatValue = 0f
                            trashThresholdState?.value = false
                            val dx = coordinator.dragLogicalX
                            val dy = coordinator.dragLogicalY
                            coordinator.onDragEnd()
                            coordinator.bounceBack(
                                softSpring = softSpring,
                                animDepthProgress = activeMotion.depthProgress,
                                animOffsetX = activeMotion.offsetX,
                                animOffsetY = activeMotion.offsetY,
                                startOffsetX = dx,
                                startOffsetY = dy,
                                onBounceComplete = { setHorizontalSwipePause(active = false, immediate = false) }
                            )
                            return@awaitEachGesture
                        }

                        coordinator.onDragEnd()
                        val dx = coordinator.dragLogicalX
                        val dy = coordinator.dragLogicalY
                        val velocity = velocityTracker.calculateVelocity()
                        val vx = velocity.x
                        val vy = velocity.y

                        val action = coordinator.resolveReleaseAction(
                            vx = vx,
                            vy = vy,
                            hThreshold = hThresh,
                            vThreshold = vThresh,
                            hasPreviousPhoto = activeHasPrev
                        )

                        // 紧致无死区切图弹簧：visibilityThreshold 设为 12px / 0.012f，彻底消灭 350ms 隐形收尾锁死
                        val commitSpring = spring<Float>(
                            dampingRatio = 0.98f,
                            stiffness = 620f,
                            visibilityThreshold = 12f
                        )
                        val depthRiseSpring = spring<Float>(
                            dampingRatio = 0.96f,
                            stiffness = 560f,
                            visibilityThreshold = 0.012f
                        )

                        when (action) {
                            ReleaseAction.COMMIT_RIGHT -> {
                                trashPullState?.floatValue = 0f
                                trashThresholdState?.value = false
                                setHorizontalSwipePause(active = true)
                                HapticFeedback.click(context)
                                activeMotion.returnStart = DeckReturnModel.capture(
                                    activeMotion.offsetX.value,
                                    activeMotion.offsetY.value,
                                    activeMotion.depthProgress.value,
                                    vx,
                                    vy,
                                    hThresh
                                )
                                coordinator.commitReturn(
                                    progress = activeMotion.returnProgress,
                                    onCommitSuccess = {
                                        setHorizontalSwipePause(active = false, immediate = false)
                                        latestOnSwipeRight()
                                    }
                                )
                            }
                            ReleaseAction.BOUNCE_RIGHT_FIRST -> {
                                activeMotion.hasTriggeredThresholdHaptic = false
                                trashPullState?.floatValue = 0f
                                trashThresholdState?.value = false
                                HapticFeedback.tick(context)
                                coordinator.bounceBack(
                                    softSpring = softSpring,
                                    animDepthProgress = activeMotion.depthProgress,
                                    animOffsetX = activeMotion.offsetX,
                                    animOffsetY = activeMotion.offsetY,
                                    startOffsetX = dx,
                                    startOffsetY = dy,
                                    onBounceComplete = { setHorizontalSwipePause(active = false, immediate = false) }
                                )
                            }
                            ReleaseAction.COMMIT_LEFT -> {
                                trashPullState?.floatValue = 0f
                                trashThresholdState?.value = false
                                setHorizontalSwipePause(active = true)
                                HapticFeedback.click(context)
                                coordinator.commitSwipe(
                                    commitSpring = commitSpring,
                                    animDepthProgress = activeMotion.depthProgress,
                                    animOffset = activeMotion.offsetX,
                                    targetOffset = -(latestScreenW + latestPadPx + 24f),
                                    targetDepthP = 1f,
                                    startOffset = dx,
                                    depthSpring = depthRiseSpring,
                                    initialVelocity = vx.coerceAtMost(0f),
                                    direction = ReleaseAction.COMMIT_LEFT,
                                    onCommitSuccess = {
                                        setHorizontalSwipePause(active = false, immediate = false)
                                        latestOnSwipeLeft(activePhoto)
                                    }
                                )
                            }
                            ReleaseAction.COMMIT_UP -> {
                                HapticFeedback.tick(context)
                                trashPullState?.floatValue = 1f
                                trashThresholdState?.value = true
                                setHorizontalSwipePause(active = false, immediate = true)

                                val cardCenter = latestFrontBoundsRef[0]?.center
                                    ?: Offset(latestScreenW * 0.5f, latestScreenH * 0.5f)
                                val targetCenter = latestTrashTargetProvider()
                                    ?: Offset(latestScreenW * 0.88f, latestScreenH * 0.08f)

                                val pull = DeckTrashArcModel.dragTrashPull(dx, dy, vThresh)
                                val startScale = DeckTrashArcModel.dragScale(pull)
                                val startRot = (dx * 0.02f) + pull * 4.5f

                                val arc = DeckTrashArcModel.capture(
                                    startX = dx,
                                    startY = dy,
                                    targetX = targetCenter.x - cardCenter.x,
                                    targetY = targetCenter.y - cardCenter.y,
                                    vx = vx,
                                    vy = vy,
                                    startScale = startScale,
                                    startRotationZ = startRot,
                                    density = latestDensity
                                )
                                activeMotion.trashArcStart = arc
                                coordinator.commitTrashArc(
                                    arcProgress = activeMotion.trashArcProgress,
                                    animDepthProgress = activeMotion.depthProgress,
                                    depthSpring = depthRiseSpring,
                                    durationMillis = arc.durationMillis,
                                    onArcImpact = {
                                        HapticFeedback.heavy(context)
                                        trashPullState?.floatValue = 0f
                                        trashThresholdState?.value = false
                                        latestOnTrashArcImpact()
                                    },
                                    onCommitSuccess = { latestOnSwipeUp(activePhoto) }
                                )
                            }
                            ReleaseAction.BOUNCE -> {
                                activeMotion.hasTriggeredThresholdHaptic = false
                                trashPullState?.floatValue = 0f
                                trashThresholdState?.value = false
                                coordinator.bounceBack(
                                    softSpring = softSpring,
                                    animDepthProgress = activeMotion.depthProgress,
                                    animOffsetX = activeMotion.offsetX,
                                    animOffsetY = activeMotion.offsetY,
                                    startOffsetX = dx,
                                    startOffsetY = dy,
                                    onBounceComplete = { setHorizontalSwipePause(active = false, immediate = false) }
                                )
                            }
                        }
                        return@awaitEachGesture
                    }

                    // 3B. 非拖拽纯点击（支持左滑后毫秒级秒开大图 + 135ms 极速双击放大，且绝不阻塞下一次立即拖拽）
                    setHorizontalSwipePause(active = false, immediate = true)
                    if (longPressed || upConsumedByChild || gestureCanceled) return@awaitEachGesture

                    val layout = stageLayoutRef[0]
                    val rootTap = layout?.localToRoot(lastChangePos)
                    val cardBounds = if (wasFlushed) null else latestFrontBoundsRef[0]
                    val hitCard = when {
                        cardBounds != null && rootTap != null -> cardBounds.inflate(16f).contains(rootTap)
                        else -> true // 刚抢先落定新图时允许直接打开，不因旧图 bounds 误拦截
                    }
                    if (!hitCard) return@awaitEachGesture

                    val nowMs = System.currentTimeMillis()
                    val isDoubleTap = pendingTapJob?.isActive == true &&
                        lastTapPhotoId == activePhoto.id &&
                        (nowMs - lastTapTimeMs) <= doubleTapWindowMs &&
                        (lastChangePos - lastTapStagePos).getDistance() <= doubleTapSlopPx

                    if (isDoubleTap) {
                        pendingTapJob?.cancel()
                        pendingTapJob = null
                        lastTapPhotoId = -1L

                        val focus = if (cardBounds != null && rootTap != null && cardBounds.width > 1f && cardBounds.height > 1f) {
                            val localX = (rootTap.x - cardBounds.left).coerceIn(0f, cardBounds.width)
                            val localY = (rootTap.y - cardBounds.top).coerceIn(0f, cardBounds.height)
                            val f = PhotoTransform.imageFocusFromCrop(
                                localX, localY, PhotoDisplayAspectCache.resolve(activePhoto),
                                cardBounds.width.toInt(), cardBounds.height.toInt()
                            )
                            Offset(f.x, f.y)
                        } else {
                            Offset(0.5f, 0.5f)
                        }
                        latestOnDoubleClickPhoto(activePhoto, focus, cardBounds)
                    } else {
                        pendingTapJob?.cancel()
                        lastTapPhotoId = activePhoto.id
                        lastTapTimeMs = nowMs
                        lastTapStagePos = lastChangePos

                        val tappedPhoto = activePhoto
                        val tappedBounds = cardBounds
                        pendingTapJob = coroutineScope.launch {
                            delay(doubleTapWindowMs)
                            lastTapPhotoId = -1L
                            latestOnClickPhoto(tappedPhoto, tappedBounds ?: latestFrontBoundsRef[0])
                        }
                    }
                }
            }
            .padding(horizontal = horizontalPaddingDp),
        contentAlignment = Alignment.Center
    ) {
        for ((role, photo) in layers) key(photo.id) {
            // The same card moves between layers; its loaded painter stays alive at handoff.
            val isFront = role == DeckRole.FRONT
            val layerModifier = when (role) {
                DeckRole.FOURTH -> Modifier.graphicsLayer {
                    // 第四张地平线层：静息时 100% 隐形，左滑/上滑拖拽期间 1:1 随 depthProgress 丝滑显露或隐退，
                    // 在 p=1 时像素级衔接第三层静息态，彻底消灭高竖大图位于第 4 张时的突兀 1 帧弹出
                    if (motion.returnStart != null || vortexBloomProgress() < 0.99f) {
                        alpha = 0f
                        return@graphicsLayer
                    }
                    val p = if (flushedPhotoId == currentPhoto.id) 1f else motion.depthProgress.value
                    val metrics = DeckDepthModel.interpolateFourthLayer(p)
                    scaleX = metrics.scale
                    scaleY = metrics.scale
                    translationY = with(density) { metrics.offsetYDp.dp.toPx() }
                    alpha = metrics.alpha
                }
                DeckRole.THIRD -> Modifier.graphicsLayer {
                    val p = if (flushedPhotoId == currentPhoto.id) 1f else motion.depthProgress.value
                    val metrics = DeckDepthModel.interpolateThirdLayer(p)
                    val bloom = vortexBloomProgress().coerceIn(0f, 1f)
                    if (bloom < 1f) {
                        val localP = ((bloom - 0.16f) / 0.84f).coerceIn(0f, 1f)
                        val eased = 1f - (1f - localP) * (1f - localP)
                        val waveScale = metrics.scale * (0.88f + 0.12f * eased)
                        scaleX = waveScale
                        scaleY = waveScale
                        translationY = with(density) { (metrics.offsetYDp * (0.45f + 0.55f * eased)).dp.toPx() }
                        alpha = (localP / 0.40f).coerceIn(0f, 1f)
                    } else {
                        scaleX = metrics.scale
                        scaleY = metrics.scale
                        translationY = with(density) { metrics.offsetYDp.dp.toPx() }
                        alpha = if (motion.returnStart != null) DeckReturnModel.thirdAlpha(motion.returnProgress.value) else 1f
                    }
                }
                DeckRole.NEXT -> Modifier.graphicsLayer {
                    val metrics = nextLayerMetrics()
                    val bloom = vortexBloomProgress().coerceIn(0f, 1f)
                    if (bloom < 1f) {
                        val localP = ((bloom - 0.08f) / 0.92f).coerceIn(0f, 1f)
                        val eased = 1f - (1f - localP) * (1f - localP)
                        val waveScale = metrics.scale * (0.86f + 0.14f * eased)
                        scaleX = waveScale
                        scaleY = waveScale
                        translationY = with(density) { (metrics.offsetYDp * (0.45f + 0.55f * eased)).dp.toPx() }
                        alpha = (localP / 0.35f).coerceIn(0f, 1f)
                    } else {
                        scaleX = metrics.scale
                        scaleY = metrics.scale
                        translationY = with(density) { metrics.offsetYDp.dp.toPx() }
                        alpha = 1f
                    }
                }
                DeckRole.FRONT -> frontModifier
                DeckRole.PREVIOUS -> Modifier.graphicsLayer {
                    val returning = motion.returnStart
                    if (returning != null) {
                        val progress = motion.returnProgress.value
                        translationX = DeckReturnModel.previousX(progress, hiddenBaseOffset + returning.x, returning.vx)
                        alpha = DeckReturnModel.previousAlpha(progress, returning)
                        shadowElevation = with(density) { DeckReturnModel.previousShadowDp(progress, returning).dp.toPx() }
                    } else {
                        translationX = hiddenBaseOffset + motion.offsetX.value
                        val isSwipingRight = motion.offsetX.value > 0f
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
                    isLivePressingExternal = livePressingPhotoId == photo.id,
                    isVortexActive = isVortexActive,
                    smoothOutline = isFront && !isVortexActive,
                    cacheMovingCard = !isVortexActive && (isFront && motion.returnStart == null && motion.trashArcStart == null),
                    deferImageDuringVortex = role == DeckRole.FOURTH,
                    modifier = if (isFront) {
                        Modifier
                            .onGloballyPositioned { coords ->
                                if (motion.returnStart == null &&
                                    motion.trashArcStart == null &&
                                    abs(motion.offsetX.value) < 0.5f &&
                                    abs(motion.offsetY.value) < 0.5f
                                ) {
                                    frontRestingBoundsRef[0] = coords.boundsInRoot()
                                }
                            }
                            .graphicsLayer {
                                val rightScale = if (prevPhoto != null && motion.returnStart == null && motion.trashArcStart == null) {
                                    DeckReturnModel.dragScale(motion.offsetX.value, motion.offsetY.value, horizontalThreshold)
                                } else 1f
                                val upScale = if (motion.returnStart == null && motion.trashArcStart == null) {
                                    DeckTrashArcModel.dragScale(currentDragTrashPull())
                                } else 1f
                                val combined = rightScale * upScale
                                scaleX = combined
                                scaleY = combined
                            }
                    } else Modifier,
                    // 卡片主体单击/双击/长按已由外层稳定统一手势层零延迟接管；徽标继续独立隔离交互
                    enableGestures = false,
                    enableBadgeGestures = isFront && motion.returnStart == null && motion.trashArcStart == null,
                    trashGlowAlpha = {
                        if (!isFront) {
                            0f
                        } else {
                            val trashing = motion.trashArcStart
                            if (trashing != null) {
                                DeckTrashArcModel.frameAt(motion.trashArcProgress.value, trashing, density.density).glowAlpha
                            } else {
                                val pull = currentDragTrashPull()
                                val reached = motion.offsetY.value < -verticalThreshold &&
                                    abs(motion.offsetY.value) >= abs(motion.offsetX.value) * 0.65f
                                DeckTrashArcModel.dragGlowAlpha(pull, reached)
                            }
                        }
                    },
                    depthBlurRadiusDp = {
                        when (role) {
                            DeckRole.FOURTH -> {
                                if (motion.returnStart != null) 0f
                                else {
                                    val p = if (flushedPhotoId == currentPhoto.id) 1f else motion.depthProgress.value
                                    // 第四层仅在顶部露出一道窄边，使用与第三层静息态完全一致的恒定 16dp 柔焦，
                                    // 交接零跳变且 HWUI 单次缓存复用，避免每 8.3ms 重算整卡 66px 高斯模糊
                                    if (p <= 0.08f) 0f else DeckDepthModel.interpolateThirdLayer(0f).blurDp
                                }
                            }
                            // 第三层同样保持恒定 16dp 柔焦（静息与运动零每帧重算 48px 全卡模糊），
                            // 将动态连续去模糊 GPU 预算 100% 留给视觉主角第二层 NEXT (12dp -> 0dp)
                            DeckRole.THIRD -> DeckDepthModel.interpolateThirdLayer(0f).blurDp
                            DeckRole.NEXT -> nextLayerMetrics().blurDp
                            DeckRole.FRONT -> if (motion.returnStart == null) 0f else DeckReturnModel.blurDp(motion.returnProgress.value)
                            DeckRole.PREVIOUS -> 0f
                        }
                    },
                    depthScrimAlpha = {
                        when (role) {
                            DeckRole.FOURTH -> {
                                val p = if (flushedPhotoId == currentPhoto.id) 1f else motion.depthProgress.value
                                DeckDepthModel.interpolateFourthLayer(p).scrimAlpha
                            }
                            DeckRole.THIRD -> DeckDepthModel.interpolateThirdLayer(
                                if (flushedPhotoId == currentPhoto.id) 1f else motion.depthProgress.value
                            ).scrimAlpha
                            DeckRole.NEXT -> nextLayerMetrics().scrimAlpha
                            DeckRole.FRONT -> if (motion.returnStart == null) 0f else DeckReturnModel.scrimAlpha(motion.returnProgress.value)
                            DeckRole.PREVIOUS -> 0f
                        }
                    }
                )
            }
        }
    }
}
