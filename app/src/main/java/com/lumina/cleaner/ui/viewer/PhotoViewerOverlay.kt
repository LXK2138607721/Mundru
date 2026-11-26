package com.lumina.cleaner.ui.viewer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.Coil
import coil.compose.AsyncImage
import coil.memory.MemoryCache
import coil.request.ImageRequest
import coil.size.Precision
import com.lumina.cleaner.core.model.PhotoDisplayAspectCache
import com.lumina.cleaner.core.model.PhotoItem
import com.lumina.cleaner.core.util.HapticFeedback
import com.lumina.cleaner.ui.deck.detectSnappyTapAndLivePress
import com.lumina.cleaner.ui.glass.LiquidGlassSurface
import com.lumina.cleaner.ui.glass.liquidBackdropSource
import com.lumina.cleaner.ui.glass.rememberLiquidBackdropState
import com.lumina.cleaner.ui.motion.MotionPhotoPlayer
import com.lumina.cleaner.ui.motion.MotionScaleType
import com.lumina.cleaner.ui.theme.stableStatusBarsPadding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 卡片已解码内存缓存键轻量索引：
 * 供大图查看器在 220ms 展开弹簧期间零 IO、零解码直接复用卡面已驻留显存的高清位图，
 * 落定后再静默升级至全屏原图分辨率，面向骁龙 680 等中低端机型也能保证进出全程满帧。
 */
internal object PhotoPreviewCache {
    private val cache = LruCache<Long, MemoryCache.Key>(24)

    fun put(photoId: Long, key: MemoryCache.Key) {
        cache.put(photoId, key)
    }

    fun get(photoId: Long): MemoryCache.Key? = cache.get(photoId)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun lerpFloat(start: Float, end: Float, fraction: Float): Float =
    start + (end - start) * fraction

/**
 * 居中实际图像框的硬件圆角矩形裁切形状（基于 Outline.Rounded 走 HWUI 原生圆角裁切，零 Path 软绘开销）。
 * 用于将打开/关闭/下拉过渡期的圆角与比例裁切精确贴合在图片真实矩形上，而非全屏黑底容器四角。
 */
private data class CenteredRoundRectShape(
    val clipWidth: Float,
    val clipHeight: Float,
    val cornerRadiusPx: Float
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val w = clipWidth.coerceIn(1f, size.width.coerceAtLeast(1f))
        val h = clipHeight.coerceIn(1f, size.height.coerceAtLeast(1f))
        val left = (size.width - w) * 0.5f
        val top = (size.height - h) * 0.5f
        val r = cornerRadiusPx.coerceIn(0f, minOf(w, h) * 0.5f)
        return Outline.Rounded(
            RoundRect(
                left = left,
                top = top,
                right = left + w,
                bottom = top + h,
                cornerRadius = CornerRadius(r, r)
            )
        )
    }
}

/**
 * Mundru 系统相册级沉浸式高保真原图查看器 (PhotoViewerOverlay)
 * 品牌释义：Mundru 源自格陵兰语（Greenlandic），意为「擦掉 / 擦除」。
 *
 * v1.6.5 升级亮点：
 * 1. 全暗房一体式真·液态玻璃 HUD：彻底取消割裂白底弹窗与独立 Dialog 窗口；底部烟熏液态玻璃胶囊
 *    轻点后原地 Q 弹舒展为悬浮于原图上的 AGSL 实时折射暗房详情浮卡（纯白/银灰徕卡排版，下滑或点外即收起）；
 * 2. 零窗口切换彻底杜绝系统防窥遮罩误触发，零花哨色块、零重复缩略图；
 * 3. 零窗口重排抖动 + Frame-0 瞬时直显 + 卡片精确共享几何弹簧进出 + 1x 跟手下拉/上拉甩回。
 */
@Composable
fun PhotoViewerOverlay(
    photo: PhotoItem,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    initialZoomFocus: Offset? = null,
    sourceBounds: Rect? = null
) {
    val context = LocalContext.current
    val view = LocalView.current
    val coroutineScope = rememberCoroutineScope()
    val viewerBackdrop = rememberLiquidBackdropState()

    // 沉浸式隐藏顶部系统状态栏（兼容全面屏等比截图与深浅双主题），退出时精准恢复原外观且底层零布局跳动
    val originalBarState = remember(view) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        Triple(
            controller?.systemBarsBehavior ?: WindowInsetsControllerCompat.BEHAVIOR_DEFAULT,
            controller?.isAppearanceLightStatusBars ?: false,
            controller?.isAppearanceLightNavigationBars ?: false
        )
    }
    fun restoreSystemBars() {
        val window = view.context.findActivity()?.window ?: return
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior = originalBarState.first
        controller.isAppearanceLightStatusBars = originalBarState.second
        controller.isAppearanceLightNavigationBars = originalBarState.third
        controller.show(WindowInsetsCompat.Type.statusBars())
    }
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
            controller.hide(WindowInsetsCompat.Type.statusBars())
        }
        onDispose {
            restoreSystemBars()
        }
    }

    val animProgress = remember(photo.id) { Animatable(0f) }
    val hudAlpha = remember(photo.id) { Animatable(0f) }
    var isDismissing by remember(photo.id) { mutableStateOf(false) }

    val pullOffsetX = remember(photo.id) { Animatable(0f) }
    val pullOffsetY = remember(photo.id) { Animatable(0f) }
    var isPulling by remember(photo.id) { mutableStateOf(false) }

    var dismissOriginTx by remember(photo.id) { mutableFloatStateOf(0f) }
    var dismissOriginTy by remember(photo.id) { mutableFloatStateOf(0f) }
    var dismissOriginScale by remember(photo.id) { mutableFloatStateOf(1f) }
    var dismissOriginCorner by remember(photo.id) { mutableFloatStateOf(0f) }
    var dismissFromPull by remember(photo.id) { mutableStateOf(false) }

    var hasLoadError by remember(photo.uri) { mutableStateOf(false) }
    var isViewerSettled by remember(photo.id) { mutableStateOf(false) }
    var isDetailPrewarmed by remember(photo.id) { mutableStateOf(false) }

    val isGif = remember(photo.displayName) { photo.displayName.endsWith(".gif", ignoreCase = true) }
    val previewCacheKey = remember(photo.id) { PhotoPreviewCache.get(photo.id) }
    val previewBitmap = remember(photo.id, previewCacheKey, isGif) {
        if (!isGif && previewCacheKey != null) {
            Coil.imageLoader(context).memoryCache?.get(previewCacheKey)?.bitmap
                ?.takeIf { !it.isRecycled && maxOf(it.width, it.height) >= 720 }
        } else null
    }
    val previewImageBitmap = remember(previewBitmap) { previewBitmap?.asImageBitmap() }
    var decodedAspectRatio by remember(photo.id, previewBitmap) {
        val cached = PhotoDisplayAspectCache.get(photo.id)
        val fromPreview = previewBitmap?.let { bmp ->
            if (bmp.width > 0 && bmp.height > 0) bmp.width.toFloat() / bmp.height.toFloat() else null
        }
        val initial = cached ?: fromPreview?.takeIf { ratio ->
            photo.width <= 0 || photo.height <= 0 ||
                abs(ratio - photo.aspectRatio) / photo.aspectRatio.coerceAtLeast(0.01f) > 0.04f
        }
        mutableStateOf(initial)
    }
    val effectiveAspectRatio = decodedAspectRatio ?: photo.aspectRatio

    LaunchedEffect(photo.id) {
        animProgress.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = 0.84f,
                stiffness = 480f,
                visibilityThreshold = 0.002f
            )
        )
        if (isDismissing) return@LaunchedEffect
        isViewerSettled = true
        hudAlpha.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing)
        )
        delay(60L)
        if (!isDismissing) {
            isDetailPrewarmed = true
        }
    }

    var transform by remember(photo.id) { mutableStateOf(PhotoTransform()) }
    var requestedScale by remember(photo.id) { mutableFloatStateOf(1f) }
    var transformJob by remember(photo.id) { mutableStateOf<Job?>(null) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var showDetailSheet by remember(photo.id) { mutableStateOf(false) }

    val detailExpandAnim = remember(photo.id) { Animatable(0f) }
    LaunchedEffect(showDetailSheet) {
        if (showDetailSheet) {
            detailExpandAnim.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = 0.92f,
                    stiffness = 420f,
                    visibilityThreshold = 0.0015f
                )
            )
        } else {
            detailExpandAnim.animateTo(
                targetValue = 0f,
                animationSpec = spring(
                    dampingRatio = 0.96f,
                    stiffness = 460f,
                    visibilityThreshold = 0.0015f
                )
            )
        }
    }
    val isDetailOverlayMounted by remember(photo.id) {
        derivedStateOf { showDetailSheet || detailExpandAnim.value > 0.002f }
    }
    val isDetailCardPrewarmed by remember(photo.id) {
        derivedStateOf { isDetailPrewarmed || showDetailSheet || detailExpandAnim.value > 0.002f }
    }
    val isBackdropActive = isViewerSettled && !isDismissing && !isPulling && transform.scale <= 1.05f
    val activeGlassBackdrop = if (isViewerSettled) viewerBackdrop else null

    fun animateTransform(target: PhotoTransform, durationMillis: Int = 240) {
        transformJob?.cancel()
        requestedScale = target.scale
        val start = transform
        transformJob = coroutineScope.launch {
            animate(0f, 1f, animationSpec = tween(durationMillis, easing = FastOutSlowInEasing)) { fraction, _ ->
                transform = start.interpolate(target, fraction)
            }
        }
    }

    LaunchedEffect(photo.id, initialZoomFocus, viewportSize, effectiveAspectRatio) {
        if (initialZoomFocus != null && viewportSize != IntSize.Zero) {
            animateTransform(
                PhotoTransform.zoomedAtImageFocus(
                    initialZoomFocus.x, initialZoomFocus.y, effectiveAspectRatio, viewportSize.width, viewportSize.height
                )
            )
        }
    }

    fun computeStartGeometry(): PhotoViewerStartGeometry {
        val bounds = sourceBounds
        return PhotoViewerTransitionGeometry.compute(
            viewportWidth = viewportSize.width.toFloat(),
            viewportHeight = viewportSize.height.toFloat(),
            imageAspectRatio = effectiveAspectRatio,
            sourceLeft = bounds?.left ?: 0f,
            sourceTop = bounds?.top ?: 0f,
            sourceWidth = bounds?.width ?: 0f,
            sourceHeight = bounds?.height ?: 0f,
            hasSourceBounds = bounds != null && viewportSize.width > 0 && viewportSize.height > 0
        )
    }

    fun currentPullFraction(): Float {
        val vh = viewportSize.height.toFloat().coerceAtLeast(1f)
        return (abs(pullOffsetY.value) / (vh * 0.38f)).coerceIn(0f, 1f)
    }

    fun dismissWithAnimation(fromPull: Boolean = false) {
        if (isDismissing) return
        isDismissing = true
        isPulling = false
        restoreSystemBars()
        HapticFeedback.click(context)

        if (fromPull) {
            val pullFrac = currentPullFraction()
            dismissOriginTx = pullOffsetX.value
            dismissOriginTy = pullOffsetY.value
            dismissOriginScale = (1f - 0.30f * pullFrac).coerceIn(0.68f, 1f)
            dismissOriginCorner = pullFrac
            dismissFromPull = true
        } else {
            dismissOriginCorner = 0f
            dismissFromPull = false
        }

        coroutineScope.launch {
            if (!fromPull && requestedScale > 1.02f) {
                animateTransform(PhotoTransform(), 180)
            }
            animProgress.animateTo(
                targetValue = 0f,
                animationSpec = spring(
                    dampingRatio = 0.88f,
                    stiffness = 560f,
                    visibilityThreshold = 0.005f
                )
            )
            onDismiss()
        }
    }
    val currentDismiss by rememberUpdatedState(::dismissWithAnimation)

    BackHandler(onBack = {
        if (showDetailSheet) {
            showDetailSheet = false
        } else {
            dismissWithAnimation()
        }
    })

    var isLivePlaying by remember(photo.id) { mutableStateOf(false) }
    var isLivePressing by remember(photo.id) { mutableStateOf(false) }
    val isPlayingLive = isLivePlaying || isLivePressing

    fun controlsAlpha(): Float {
        if (isDismissing) {
            return (((animProgress.value - 0.78f) / 0.22f).coerceIn(0f, 1f) * hudAlpha.value).coerceIn(0f, 1f)
        }
        val pullFade = (1f - currentPullFraction() * 2.6f).coerceIn(0f, 1f)
        val zoomFade = if (transform.scale > 1.15f) 0f else 1f
        return (hudAlpha.value * pullFade * zoomFade).coerceIn(0f, 1f)
    }

    val canLiveLongPress = photo.isLivePhoto && photo.motionVideoLength > 0

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { viewportSize = it }
            .pointerInput(photo.id) {
                val touchSlop = viewConfiguration.touchSlop
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var pastTouchSlop = false
                    var pullActive = false
                    var totalZoom = 1f
                    var totalPan = Offset.Zero
                    val velocityTracker = VelocityTracker()

                    while (true) {
                        val event = awaitPointerEvent()
                        val changes = event.changes
                        val pressedChanges = changes.filter { it.pressed }
                        if (pressedChanges.isEmpty()) break
                        if (isDismissing || showDetailSheet) break

                        val multiTouch = pressedChanges.size >= 2
                        val zoomedIn = transform.scale > 1.02f

                        val zoomChange = event.calculateZoom()
                        val panChange = event.calculatePan()

                        if (!pastTouchSlop) {
                            totalZoom *= zoomChange
                            totalPan += panChange
                            val centroidSize = event.calculateCentroidSize(useCurrent = false)
                            val zoomMotion = abs(1f - totalZoom) * centroidSize
                            val panMotion = totalPan.getDistance()
                            if (multiTouch || zoomMotion > touchSlop || panMotion > touchSlop) {
                                pastTouchSlop = true
                                pullActive = !multiTouch && !zoomedIn && abs(totalPan.y) >= abs(totalPan.x) * 0.8f
                                if (pullActive) isPulling = true
                            }
                        }

                        if (pastTouchSlop) {
                            if (multiTouch && pullActive) {
                                pullActive = false
                                isPulling = false
                                coroutineScope.launch {
                                    pullOffsetX.snapTo(0f)
                                    pullOffsetY.snapTo(0f)
                                }
                            }

                            if (pullActive) {
                                val first = pressedChanges.first()
                                velocityTracker.addPosition(first.uptimeMillis, first.position)
                                coroutineScope.launch {
                                    pullOffsetX.snapTo(pullOffsetX.value + panChange.x * 0.65f)
                                    pullOffsetY.snapTo(pullOffsetY.value + panChange.y)
                                }
                                changes.forEach { if (it.positionChanged()) it.consume() }
                            } else if (multiTouch || zoomedIn) {
                                if (zoomChange != 1f || panChange != Offset.Zero) {
                                    transformJob?.cancel()
                                    transformJob = null
                                    transform = transform.transformed(
                                        zoomChange, panChange.x, panChange.y,
                                        viewportSize.width, viewportSize.height
                                    )
                                    requestedScale = transform.scale
                                    changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                            } else {
                                changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                    }

                    if (pullActive && !isDismissing) {
                        val velY = velocityTracker.calculateVelocity().y
                        val dismissThreshold = (viewportSize.height * 0.14f).coerceAtLeast(140f)
                        if (abs(pullOffsetY.value) > dismissThreshold || abs(velY) > 950f) {
                            currentDismiss(true)
                        } else {
                            isPulling = false
                            val bounceSpring = spring<Float>(dampingRatio = 0.70f, stiffness = 440f)
                            coroutineScope.launch { pullOffsetX.animateTo(0f, bounceSpring) }
                            coroutineScope.launch { pullOffsetY.animateTo(0f, bounceSpring) }
                        }
                    } else {
                        isPulling = false
                    }
                }
            }
            .pointerInput(photo.id, canLiveLongPress) {
                detectSnappyTapAndLivePress(
                    doubleTapWindowMs = 180L,
                    enableLongPress = canLiveLongPress,
                    onLongPressStart = {
                        if (!isDismissing && !isLivePlaying) {
                            HapticFeedback.tick(context)
                            isLivePressing = true
                        }
                    },
                    onLongPressEnd = { isLivePressing = false },
                    onTap = {
                        if (!isDismissing && !isPulling && !showDetailSheet) {
                            if (requestedScale > 1.05f) animateTransform(PhotoTransform(), 210)
                            else currentDismiss(false)
                        }
                    },
                    onDoubleTap = { position ->
                        if (!isDismissing && !isPulling && !showDetailSheet) {
                            HapticFeedback.click(context)
                            val target = PhotoTransform.doubleTapTarget(
                                requestedScale,
                                position.x / size.width,
                                position.y / size.height,
                                size.width,
                                size.height
                            )
                            animateTransform(target, 230)
                        }
                    }
                )
            }
    ) {
        // 第 1 层：全屏背景与主照片内容（仅落定静止时开启一次背景采样，进出场与拖拽期间 0 离屏录制）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .liquidBackdropSource(viewerBackdrop, enabled = isBackdropActive)
                .drawBehind {
                    val pullFade = if (dismissFromPull) 1f else (1f - currentPullFraction() * 0.76f).coerceIn(0.22f, 1f)
                    val bgAlpha = (animProgress.value * pullFade).coerceIn(0f, 1f)
                    drawRect(Color.Black.copy(alpha = bgAlpha))
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val progress = animProgress.value.coerceIn(0f, 1.08f)
                        val clampedP = progress.coerceIn(0f, 1f)
                        val geom = computeStartGeometry()
                        val contentScale: Float

                        if (isDismissing && dismissFromPull) {
                            contentScale = lerpFloat(geom.startScale, dismissOriginScale, clampedP)
                            scaleX = contentScale
                            scaleY = contentScale
                            translationX = lerpFloat(geom.startX, dismissOriginTx, clampedP)
                            translationY = lerpFloat(geom.startY, dismissOriginTy, clampedP)
                        } else {
                            val pullFrac = currentPullFraction()
                            val pullScale = (1f - 0.30f * pullFrac).coerceIn(0.68f, 1f)
                            val geomScale = lerpFloat(geom.startScale, 1f, progress)
                            contentScale = geomScale * pullScale * transform.scale
                            scaleX = contentScale
                            scaleY = contentScale
                            translationX = lerpFloat(geom.startX, 0f, progress) + pullOffsetX.value + transform.x
                            translationY = lerpFloat(geom.startY, 0f, progress) + pullOffsetY.value + transform.y
                        }

                        alpha = if (isDismissing) {
                            val fadeTail = if (geom.hasSourceBounds) 0.05f else 0.20f
                            (clampedP / fadeTail).coerceIn(0f, 1f)
                        } else {
                            1f
                        }

                        val cornerProgress = when {
                            isDismissing && dismissFromPull -> lerpFloat(1f, dismissOriginCorner, clampedP)
                            isPulling -> maxOf(1f - clampedP, currentPullFraction())
                            else -> 1f - clampedP
                        }
                        val inTransition = isDismissing || isPulling || clampedP < 0.995f
                        if (inTransition && viewportSize.width > 0 && viewportSize.height > 0) {
                            val (clipW, clipH, localCornerPx) = PhotoViewerTransitionGeometry.localClipFrame(
                                geometry = geom,
                                clampedProgress = clampedP,
                                cornerProgress = cornerProgress,
                                currentScale = contentScale,
                                cardCornerRadiusPx = 24.dp.toPx()
                            )
                            val needsClip = localCornerPx > 0.35f ||
                                abs(clipW - geom.fitWidth) > 0.5f ||
                                abs(clipH - geom.fitHeight) > 0.5f
                            clip = needsClip
                            shape = if (needsClip) {
                                CenteredRoundRectShape(clipW, clipH, localCornerPx)
                            } else {
                                RectangleShape
                            }
                        } else {
                            clip = false
                            shape = RectangleShape
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                if (!hasLoadError) {
                    val fastPreview = if (!isViewerSettled && requestedScale <= 1.05f) previewImageBitmap else null
                    if (fastPreview != null) {
                        Image(
                            bitmap = fastPreview,
                            contentDescription = photo.displayName,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        val needsUltraRes = requestedScale > 1.25f
                        val fullResRequest = remember(photo.uri, previewCacheKey, needsUltraRes) {
                            ImageRequest.Builder(context)
                                .data(photo.uri)
                                .apply {
                                    if (!needsUltraRes && !isGif) {
                                        size(2560, 2560)
                                        precision(Precision.INEXACT)
                                    }
                                    if (previewCacheKey != null) {
                                        placeholderMemoryCacheKey(previewCacheKey)
                                    }
                                }
                                .crossfade(false)
                                .build()
                        }
                        AsyncImage(
                            model = fullResRequest,
                            contentDescription = photo.displayName,
                            contentScale = ContentScale.Fit,
                            onSuccess = { state ->
                                val dw = state.result.drawable.intrinsicWidth
                                val dh = state.result.drawable.intrinsicHeight
                                if (dw > 0 && dh > 0) {
                                    val actualRatio = dw.toFloat() / dh.toFloat()
                                    val expectedRatio = photo.aspectRatio
                                    if (photo.width <= 0 || photo.height <= 0 ||
                                        abs(actualRatio - expectedRatio) / expectedRatio.coerceAtLeast(0.01f) > 0.04f
                                    ) {
                                        PhotoDisplayAspectCache.put(photo.id, actualRatio)
                                        if (decodedAspectRatio == null || abs(decodedAspectRatio!! - actualRatio) > 0.01f) {
                                            decodedAspectRatio = actualRatio
                                        }
                                    }
                                }
                            },
                            onError = { hasLoadError = true },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.BrokenImage,
                                contentDescription = "文件已失效",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(52.dp)
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = "该照片在外部存储中已被移除或同步，文件已失效",
                                fontSize = 14.sp,
                                color = Color.White.copy(alpha = 0.88f),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                if (!hasLoadError && photo.isLivePhoto && photo.motionVideoLength > 0) {
                    MotionPhotoPlayer(
                        imageUri = photo.uri,
                        videoOffset = photo.motionVideoOffset,
                        videoLength = photo.motionVideoLength,
                        isPlaying = isPlayingLive,
                        scaleType = MotionScaleType.FIT,
                        photoWidth = photo.width,
                        photoHeight = photo.height,
                        onPlaybackComplete = { isLivePlaying = false },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        // 第 2 层：左上角真·液态玻璃 HDR / LIVE / GIF 徽标组（延迟至展开落定后挂载，首帧 0 着色器编译与测量开销）
        if (isViewerSettled && (photo.isHdr || photo.isLivePhoto || isGif)) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .stableStatusBarsPadding()
                    .padding(top = 16.dp, start = 16.dp)
                    .graphicsLayer { alpha = controlsAlpha() },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isGif) {
                    LiquidGlassSurface(
                        backdrop = activeGlassBackdrop,
                        isDark = true,
                        cornerRadius = 11.dp,
                        blurRadius = 14.dp,
                        refractionStrengthDp = 9.dp,
                        tintColor = Color(0x5212141B),
                        elevation = 6.dp,
                        staticBackdropCache = true,
                        backdropCacheKey = "${photo.id}_badge",
                        pressBounce = false,
                        modifier = Modifier.pointerInput(Unit) {
                            detectTapGestures(onTap = { HapticFeedback.tick(context) })
                        }
                    ) {
                        Text(
                            text = "GIF",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                        )
                    }
                }

                if (photo.isHdr) {
                    LiquidGlassSurface(
                        backdrop = activeGlassBackdrop,
                        isDark = true,
                        cornerRadius = 11.dp,
                        blurRadius = 14.dp,
                        refractionStrengthDp = 9.dp,
                        tintColor = Color(0x5212141B),
                        elevation = 6.dp,
                        staticBackdropCache = true,
                        backdropCacheKey = "${photo.id}_badge",
                        pressBounce = false,
                        modifier = Modifier.pointerInput(Unit) {
                            detectTapGestures(onTap = { HapticFeedback.tick(context) })
                        }
                    ) {
                        Text(
                            text = "HDR",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                        )
                    }
                }

                if (photo.isLivePhoto) {
                    LiquidGlassSurface(
                        backdrop = activeGlassBackdrop,
                        isDark = true,
                        cornerRadius = 11.dp,
                        blurRadius = 14.dp,
                        refractionStrengthDp = 9.dp,
                        tintColor = if (isPlayingLive) Color(0x991E824C) else Color(0x5212141B),
                        elevation = 6.dp,
                        staticBackdropCache = true,
                        backdropCacheKey = "${photo.id}_badge",
                        pressBounce = false,
                        modifier = Modifier.pointerInput(photo.id) {
                            detectTapGestures(
                                onTap = {
                                    HapticFeedback.click(context)
                                    isLivePlaying = !isLivePlaying
                                },
                                onPress = {
                                    if (!isLivePlaying && photo.motionVideoLength > 0) {
                                        HapticFeedback.tick(context)
                                        isLivePressing = true
                                        tryAwaitRelease()
                                        isLivePressing = false
                                    }
                                }
                            )
                        }
                    ) {
                        Text(
                            text = if (isPlayingLive) "▶ 播放中" else "LIVE",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                        )
                    }
                }
            }
        }

        // 详情浮卡展开时的极轻暗房点击收起层（仅消耗外部点击回缩浮卡，不触发退出查看器；零逐帧重组）
        if (isDetailOverlayMounted) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawBehind {
                        drawRect(Color.Black.copy(alpha = 0.28f * detailExpandAnim.value.coerceIn(0f, 1f)))
                    }
                    .then(
                        if (showDetailSheet) {
                            Modifier.pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = {
                                        HapticFeedback.tick(context)
                                        showDetailSheet = false
                                    }
                                )
                            }
                        } else {
                            Modifier
                        }
                    )
            )
        }

        // 底部暗房一体式 120fps 纯 GPU 预烘焙液态玻璃胶囊 / 舒展浮卡（落定后再挂载，首帧 0 着色器/EXIF/排版开销）
        if (isViewerSettled) {
            PhotoDetailHud(
                photo = photo,
                backdrop = activeGlassBackdrop,
                expanded = showDetailSheet,
                isCardMounted = isDetailCardPrewarmed,
                backdropCacheKey = "${photo.id}_s",
                expandProgressProvider = { detailExpandAnim.value },
                onExpandedChange = { target ->
                    if (!isDismissing) {
                        showDetailSheet = target
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 20.dp, start = 16.dp, end = 16.dp)
                    .graphicsLayer { alpha = controlsAlpha() }
            )
        }
    }
}
