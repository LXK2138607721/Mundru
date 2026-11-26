package com.lumina.cleaner.ui.deck

import android.content.Context
import android.graphics.drawable.Animatable
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.Parameters
import coil.size.Precision
import coil.size.Scale
import com.lumina.cleaner.core.model.PhotoDisplayAspectCache
import com.lumina.cleaner.core.model.PhotoItem
import com.lumina.cleaner.core.util.HapticFeedback
import com.lumina.cleaner.core.util.formatFileSize
import com.lumina.cleaner.ui.glass.LiquidGlassSurface
import com.lumina.cleaner.ui.glass.liquidBackdropSource
import com.lumina.cleaner.ui.glass.rememberLiquidBackdropState
import com.lumina.cleaner.ui.motion.MotionPhotoPlayer
import com.lumina.cleaner.ui.motion.MotionScaleType
import com.lumina.cleaner.ui.viewer.PhotoPreviewCache
import com.lumina.cleaner.ui.viewer.PhotoTransform
import kotlin.math.abs
import kotlin.math.roundToInt

private val PhotoCornerRadius = 24.dp
private val PhotoShape = RoundedCornerShape(PhotoCornerRadius)
private val TrashVermilion = Color(0xFFEA4335)
private const val MIN_DECK_PORTRAIT_RATIO = 9f / 16f
private const val MAX_DECK_LANDSCAPE_RATIO = 3.2f

/**
 * 卡片显示画幅计算：
 * - 保留 9:16 至 3.2:1 区间内所有竖图、方图、横图与横向长图（含 16:9、2.1:1、20:9、21:9）的真实显示比例，
 *   做到主界面不裁切、不拉伸、不旋转，并与全屏大图查看器 1:1 比例无缝衔接；
 * - 仅对极端超长竖图（< 9:16）与极端全景长条（> 3.2:1）施加安全上下限，保障纵图卡堆高度与触控热区舒适稳定。
 */
internal fun deckCardAspectRatio(aspectRatio: Float): Float {
    if (!aspectRatio.isFinite() || aspectRatio <= 0f) return 3f / 4f
    return aspectRatio.coerceIn(MIN_DECK_PORTRAIT_RATIO, MAX_DECK_LANDSCAPE_RATIO)
}

internal fun deckCardMemoryCacheKey(photoId: Long): String = "deck_card_$photoId"

/**
 * 统一的主舞台卡片解码请求：
 * 预解码阶段与 DeckCard 渲染阶段共享完全一致的目标像素与 memoryCacheKey，
 * 避免对「大图专清」中的 50MP 巨图按原尺寸并发解码，确保切换模式首帧 100% 命中显存零发白。
 */
internal fun buildDeckCardImageRequest(
    context: Context,
    photo: PhotoItem,
    targetWidthPx: Int
): ImageRequest {
    val ratio = deckCardAspectRatio(PhotoDisplayAspectCache.resolve(photo))
    val safeW = targetWidthPx.coerceIn(360, 1280)
    val safeH = (safeW / ratio).roundToInt().coerceIn(240, 2100)
    val params = Parameters.Builder()
        .enableFastThumbIfHeavy(
            photoId = photo.id,
            dateModified = photo.dateModified,
            sizeBytes = photo.size,
            displayName = photo.displayName,
            widthPx = safeW,
            heightPx = safeH
        )
        .build()
    val cacheKey = deckCardMemoryCacheKey(photo.id)
    return ImageRequest.Builder(context)
        .data(photo.uri)
        .size(safeW, safeH)
        .scale(Scale.FILL)
        .precision(Precision.INEXACT)
        .parameters(params)
        .memoryCacheKey(cacheKey)
        .placeholderMemoryCacheKey(cacheKey)
        .crossfade(false)
        .build()
}

// Cache the moving face *and its shadow* in local coordinates. The two opposite insets
// leave the measured size and hit box unchanged and give the shadow room in the texture.
private val MovingCardLayer = Modifier.layout { measurable, constraints ->
    val gutter = 48.dp.roundToPx()
    val layer = measurable.measure(constraints.offset(gutter * 2, gutter * 2))
    layout(layer.width - gutter * 2, layer.height - gutter * 2) { layer.place(-gutter, -gutter) }
}.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.padding(48.dp)

/** 先在照片本地坐标中生成透明边缘，再对整个轮廓旋转、缩放和平移。 */
private val SmoothPhotoOutline = Modifier
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithCache {
        // 一个透明纹素加一小段覆盖过渡。使用物理像素，不随屏幕密度加宽。
        val inset = minOf(1.25f, size.minDimension / 2f)
        val outside = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(-1f, -1f, size.width + 1f, size.height + 1f))
            addRoundRect(
                RoundRect(
                    Rect(inset, inset, size.width - inset, size.height - inset),
                    CornerRadius((PhotoCornerRadius.toPx() - inset).coerceAtLeast(0f))
                )
            )
        }
        onDrawWithContent {
            drawContent()
            drawPath(outside, Color.Black, blendMode = BlendMode.Clear)
        }
    }

/**
 * Mundru 核心相册卡片组件 (DeckCard)
 * 品牌释义：Mundru 源自格陵兰语（Greenlandic），意为「擦掉 / 擦除」。
 * 单体设计：高保真原图呈现、LIVE / HDR / GIF / 体积 真·液态玻璃折射徽标、平滑连续圆角与实时微光张力。
 */
@Composable
fun DeckCard(
    photo: PhotoItem,
    onClick: () -> Unit = {},
    enableGestures: Boolean = true,
    enableBadgeGestures: Boolean = enableGestures,
    modifier: Modifier = Modifier,
    onDoubleClick: ((Offset) -> Unit)? = null,
    depthBlurRadiusDp: () -> Float = { 0f },
    depthScrimAlpha: () -> Float = { 0f },
    trashGlowAlpha: () -> Float = { 0f },
    smoothOutline: Boolean = false,
    cacheMovingCard: Boolean = false,
    deferImageDuringVortex: Boolean = false,
    showFileSize: Boolean = false,
    isFront: Boolean = enableGestures,
    isLivePressingExternal: Boolean = false,
    isVortexActive: Boolean = false
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val cardTargetWidthPx = remember(configuration.screenWidthDp, density.density) {
        ((configuration.screenWidthDp - 48).coerceAtLeast(240) * density.density).roundToInt()
    }
    val cardImageRequest = remember(photo.id, photo.uri, cardTargetWidthPx) {
        buildDeckCardImageRequest(context, photo, cardTargetWidthPx)
    }
    val scrimColor = MaterialTheme.colorScheme.surfaceVariant
    val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val glassRimColors = remember(isDarkTheme) {
        if (isDarkTheme) {
            listOf(
                Color.White.copy(alpha = 0.46f),
                Color.White.copy(alpha = 0.20f),
                Color.White.copy(alpha = 0.07f),
                Color.White.copy(alpha = 0.13f),
                Color.White.copy(alpha = 0.28f)
            )
        } else {
            listOf(
                Color.White.copy(alpha = 0.78f),
                Color.White.copy(alpha = 0.36f),
                Color.White.copy(alpha = 0.12f),
                Color.White.copy(alpha = 0.22f),
                Color.White.copy(alpha = 0.50f)
            )
        }
    }
    val blurCache = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) remember {
        DeckBlurCache { px ->
            android.graphics.RenderEffect.createBlurEffect(
                px, px, android.graphics.Shader.TileMode.CLAMP
            ).asComposeRenderEffect()
        }
    } else null
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnDoubleClick by rememberUpdatedState(onDoubleClick)

    // An animated drawable redraws every frame; keep it and live/HDR content on their original path.
    // showFileSize 采用自绘烟熏微光斜角玻璃胶囊，不挂载嵌套 cardBackdrop，确保「大图专清」100% 保留 MovingCardLayer 120fps 缓存！
    var animatedFace by remember(photo.uri) { mutableStateOf(false) }
    val isGif = photo.displayName.endsWith(".gif", ignoreCase = true)
    val hasGlassBadges = isFront && (photo.isHdr || photo.isLivePhoto || isGif)
    val enableBadgeBackdrop = hasGlassBadges && !isVortexActive
    val reuseMovingLayer = cacheMovingCard && !photo.isLivePhoto && !photo.isHdr && !animatedFace && !hasGlassBadges

    // 点击播放单次标志
    var isLivePlaying by remember(photo.id, isFront) { mutableStateOf(false) }
    // 按住卡片主体/徽标临时预览标志
    var isLivePressingLocal by remember(photo.id, isFront) { mutableStateOf(false) }
    val isPlayingLive = isLivePlaying || isLivePressingLocal || isLivePressingExternal

    // 真实显示比例：优先使用已纠偏或 MediaStore 旋转规整后的比例；解码后若发现异常元数据差异 >4% 则自动原地自愈
    var decodedAspectRatio by remember(photo.id, photo.uri) {
        mutableStateOf(PhotoDisplayAspectCache.get(photo.id))
    }
    val effectiveAspectRatio = decodedAspectRatio ?: photo.aspectRatio
    val cardAspectRatio = remember(effectiveAspectRatio) {
        deckCardAspectRatio(effectiveAspectRatio)
    }

    val canLiveLongPress = photo.isLivePhoto && photo.motionVideoLength > 0

    val gestureModifier = if (enableGestures) {
        Modifier.pointerInput(photo.id, canLiveLongPress) {
            detectSnappyTapAndLivePress(
                doubleTapWindowMs = 135L,
                enableLongPress = canLiveLongPress,
                onLongPressStart = {
                    if (!isLivePlaying) {
                        HapticFeedback.tick(context)
                        isLivePressingLocal = true
                    }
                },
                onLongPressEnd = {
                    isLivePressingLocal = false
                },
                onTap = { currentOnClick() },
                onDoubleTap = onDoubleClick?.let {
                    { position ->
                        val focus = PhotoTransform.imageFocusFromCrop(
                            position.x, position.y, effectiveAspectRatio, size.width, size.height
                        )
                        currentOnDoubleClick?.invoke(Offset(focus.x, focus.y))
                    }
                }
            )
        }
    } else {
        Modifier
    }

    // 仅在卡片处于静止/交互态且需要渲染液态玻璃徽标时启用局部采样源；漩涡期间跳过嵌套离屏重录，消除双重 AGSL 尖峰
    val cardBackdrop = if (hasGlassBadges) rememberLiquidBackdropState() else null
    val activeBadgeBackdrop = if (enableBadgeBackdrop) cardBackdrop else null

    Surface(
        shape = PhotoShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        // The outer shadow supplies elevation. Surface clips the photo once in local coordinates.
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(cardAspectRatio)
            .then(if (reuseMovingLayer) MovingCardLayer else Modifier)
            .shadow(
                elevation = 20.dp,
                shape = PhotoShape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.06f),
                spotColor = Color.Black.copy(alpha = 0.10f)
            )
            .then(if (smoothOutline) SmoothPhotoOutline else Modifier)
            .then(gestureModifier)
    ) {
        val contentBoxModifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                if (blurCache != null) {
                    val blurDp = depthBlurRadiusDp()
                    val px = with(density) { blurDp.dp.toPx() }
                    renderEffect = blurCache.at(if (blurDp > 0.5f && px > 1f) px else 0f)
                }
            }
            .drawWithCache {
                val w = size.width.coerceAtLeast(1f)
                val h = size.height.coerceAtLeast(1f)
                val cornerPx = PhotoCornerRadius.toPx()
                val sealInset = 0.8f
                val sealCorner = CornerRadius((cornerPx - sealInset).coerceAtLeast(0f))
                val sealTopLeft = Offset(sealInset, sealInset)
                val sealSize = Size((w - sealInset * 2f).coerceAtLeast(1f), (h - sealInset * 2f).coerceAtLeast(1f))
                val sealColor = Color.Black.copy(alpha = if (isDarkTheme) 0.22f else 0.13f)
                val sealStroke = Stroke(width = 0.55.dp.toPx())

                val rimInset = 1.6f
                val rimCorner = CornerRadius((cornerPx - rimInset).coerceAtLeast(0f))
                val rimTopLeft = Offset(rimInset, rimInset)
                val rimSize = Size((w - rimInset * 2f).coerceAtLeast(1f), (h - rimInset * 2f).coerceAtLeast(1f))
                val rimBrush = Brush.linearGradient(
                    colors = glassRimColors,
                    start = Offset.Zero,
                    end = Offset(w, h)
                )
                val rimStroke = Stroke(width = 1.15.dp.toPx())
                val trashStroke = Stroke(width = 2.6.dp.toPx())
                val trashWashBrush = Brush.verticalGradient(
                    colors = listOf(
                        TrashVermilion.copy(alpha = 0.24f),
                        Color.Transparent
                    ),
                    startY = 0f,
                    endY = h * 0.36f
                )
                val trashRimBrush = Brush.verticalGradient(
                    colors = listOf(
                        TrashVermilion.copy(alpha = 0.92f),
                        TrashVermilion.copy(alpha = 0.42f)
                    )
                )
                val outerCorner = CornerRadius(cornerPx, cornerPx)

                onDrawWithContent {
                    drawContent()
                    // 上滑回收蓄力期：Draw-Phase 零重组零分配绘制精致朱红光学高光边与顶部柔和晕染；
                    // 常态下以单层半透微光斜角玻璃相框包边（深浅照片皆立体通透，绝无双圈感）
                    val glow = trashGlowAlpha().coerceIn(0f, 1f)
                    val glassAlpha = (1f - glow * 1.15f).coerceIn(0f, 1f)
                    if (glassAlpha > 0.01f) {
                        drawRoundRect(
                            color = sealColor.copy(alpha = sealColor.alpha * glassAlpha),
                            topLeft = sealTopLeft,
                            size = sealSize,
                            cornerRadius = sealCorner,
                            style = sealStroke
                        )
                        drawRoundRect(
                            brush = rimBrush,
                            topLeft = rimTopLeft,
                            size = rimSize,
                            cornerRadius = rimCorner,
                            alpha = glassAlpha,
                            style = rimStroke
                        )
                    }
                    if (glow > 0.01f) {
                        drawRect(
                            brush = trashWashBrush,
                            alpha = glow
                        )
                        drawRoundRect(
                            brush = trashRimBrush,
                            cornerRadius = outerCorner,
                            alpha = glow,
                            style = trashStroke
                        )
                    }
                }
            }

        Box(modifier = contentBoxModifier) {
            // 底层静态图片 + 实况层（作为角标液态玻璃的兄弟采样底层；漩涡期间暂停离屏复录）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (cardBackdrop != null) {
                            Modifier.liquidBackdropSource(cardBackdrop, enabled = enableBadgeBackdrop)
                        } else Modifier
                    )
            ) {
                // 仅对旋展期完全不可见的第 4 层地平线卡延后挂载解码；第 1/2/3 张可见景深卡始终立即挂载，
                // 配合换批并发预解码彻底消灭「先黑卡再慢变模糊」现象。
                val shouldMountImage = !(deferImageDuringVortex && isVortexActive)
                if (shouldMountImage) {
                    AsyncImage(
                        model = cardImageRequest,
                        onSuccess = { state ->
                            val drawable = state.result.drawable
                            animatedFace = drawable is Animatable
                            val dw = drawable.intrinsicWidth
                            val dh = drawable.intrinsicHeight
                            if (dw > 0 && dh > 0) {
                                val actualRatio = dw.toFloat() / dh.toFloat()
                                val expectedRatio = photo.aspectRatio
                                val needsCorrection = photo.width <= 0 || photo.height <= 0 ||
                                    abs(actualRatio - expectedRatio) / expectedRatio.coerceAtLeast(0.01f) > 0.04f
                                if (needsCorrection) {
                                    PhotoDisplayAspectCache.put(photo.id, actualRatio)
                                    if (decodedAspectRatio == null || abs(decodedAspectRatio!! - actualRatio) > 0.01f) {
                                        decodedAspectRatio = actualRatio
                                    }
                                }
                            }
                            state.result.memoryCacheKey?.let { key ->
                                PhotoPreviewCache.put(photo.id, key)
                            }
                        },
                        contentDescription = photo.displayName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // 实况照片播放器层（播放激活时平滑浮现渲染，保持等比裁切，绝不拉伸变形）
                if (photo.isLivePhoto && photo.motionVideoLength > 0) {
                    // A retained card keeps its image, but never retains an active player offstage.
                    key(isFront) {
                        MotionPhotoPlayer(
                            imageUri = photo.uri,
                            videoOffset = photo.motionVideoOffset,
                            videoLength = photo.motionVideoLength,
                            isPlaying = isPlayingLive,
                            scaleType = MotionScaleType.CROP,
                            photoWidth = photo.width,
                            photoHeight = photo.height,
                            onPlaybackComplete = {
                                isLivePlaying = false
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }

            // HDR、实况照片（Live Photo）与 GIF 动图真·液态玻璃折射徽标组（静态一次烘焙 + 漩涡零嵌套开销）
            if (isFront && (photo.isHdr || photo.isLivePhoto || isGif)) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isGif) {
                        LiquidGlassSurface(
                            backdrop = activeBadgeBackdrop,
                            isDark = true,
                            cornerRadius = 11.dp,
                            blurRadius = 12.dp,
                            refractionStrengthDp = 8.dp,
                            tintColor = if (activeBadgeBackdrop != null) Color(0x520E1016) else Color(0x88101218),
                            elevation = 4.dp,
                            pressBounce = false,
                            staticBackdropCache = true,
                            backdropCacheKey = photo.id,
                            modifier = Modifier.pointerInput(photo.id, enableBadgeGestures) {
                                if (!enableBadgeGestures) return@pointerInput
                                detectTapGestures(onTap = { HapticFeedback.tick(context) })
                            }
                        ) {
                            Text(
                                text = "GIF",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                            )
                        }
                    }

                    if (photo.isHdr) {
                        LiquidGlassSurface(
                            backdrop = activeBadgeBackdrop,
                            isDark = true,
                            cornerRadius = 11.dp,
                            blurRadius = 12.dp,
                            refractionStrengthDp = 8.dp,
                            tintColor = if (activeBadgeBackdrop != null) Color(0x520E1016) else Color(0x88101218),
                            elevation = 4.dp,
                            pressBounce = false,
                            staticBackdropCache = true,
                            backdropCacheKey = photo.id,
                            modifier = Modifier.pointerInput(photo.id, enableBadgeGestures) {
                                if (!enableBadgeGestures) return@pointerInput
                                detectTapGestures(onTap = { HapticFeedback.tick(context) })
                            }
                        ) {
                            Text(
                                text = "HDR",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                            )
                        }
                    }

                    if (photo.isLivePhoto) {
                        LiquidGlassSurface(
                            backdrop = activeBadgeBackdrop,
                            isDark = true,
                            cornerRadius = 11.dp,
                            blurRadius = 12.dp,
                            refractionStrengthDp = 8.dp,
                            tintColor = if (isPlayingLive) Color(0x991E824C) else if (activeBadgeBackdrop != null) Color(0x520E1016) else Color(0x88101218),
                            elevation = 4.dp,
                            pressBounce = false,
                            staticBackdropCache = !isPlayingLive,
                            backdropCacheKey = photo.id,
                            modifier = Modifier.pointerInput(photo.id, enableBadgeGestures) {
                                if (!enableBadgeGestures) return@pointerInput
                                detectTapGestures(
                                    onTap = {
                                        HapticFeedback.click(context)
                                        isLivePlaying = !isLivePlaying
                                    },
                                    onPress = {
                                        if (!isLivePlaying && photo.motionVideoLength > 0) {
                                            HapticFeedback.tick(context)
                                            isLivePressingLocal = true
                                            tryAwaitRelease()
                                            isLivePressingLocal = false
                                        }
                                    }
                                )
                            }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = if (isPlayingLive) "▶ 播放中" else "LIVE",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            }

            if (isFront && showFileSize) {
                val sizeLabel = remember(photo.size) { formatFileSize(photo.size) }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(16.dp)
                        .drawWithCache {
                            val r = 11.dp.toPx()
                            val corner = CornerRadius(r, r)
                            val bodyBrush = Brush.linearGradient(
                                colors = listOf(Color(0xBC181B24), Color(0xCC0D0F15)),
                                start = Offset.Zero,
                                end = Offset(size.width, size.height)
                            )
                            val rimBrush = Brush.linearGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.44f),
                                    Color.White.copy(alpha = 0.16f),
                                    Color.White.copy(alpha = 0.08f),
                                    Color.White.copy(alpha = 0.26f)
                                ),
                                start = Offset.Zero,
                                end = Offset(size.width, size.height)
                            )
                            val rimStroke = Stroke(width = 0.8.dp.toPx())
                            onDrawWithContent {
                                drawRoundRect(brush = bodyBrush, cornerRadius = corner)
                                drawContent()
                                drawRoundRect(brush = rimBrush, cornerRadius = corner, style = rimStroke)
                            }
                        }
                ) {
                    Text(
                        text = sizeLabel,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            // API 28~30 降级遮罩：仅在 Android 12 以下渲染，通过动态 alpha 降低对比度，不拦截事件
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = depthScrimAlpha().coerceIn(0f, 1f)
                        }
                        .background(scrimColor)
                )
            }
        }
    }
}
