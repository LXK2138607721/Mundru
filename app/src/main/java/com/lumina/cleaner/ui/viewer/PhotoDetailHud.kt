package com.lumina.cleaner.ui.viewer

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import com.lumina.cleaner.core.model.PhotoItem
import com.lumina.cleaner.core.util.HapticFeedback
import com.lumina.cleaner.ui.glass.LiquidBackdropState
import com.lumina.cleaner.ui.glass.LiquidGlassSurface
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private class LayoutGeometryHolder {
    var origin: Offset = Offset.Zero
    var size: IntSize = IntSize.Zero
}

/**
 * 硬件加速圆角矩形动态裁切轮廓：
 * 在 RenderThread 中直接命中 Android Outline.setRoundRect 快速路径，
 * 实现从底部胶囊实际像素框到完整详情浮卡的真正形态演变（0 字体压扁、0 布局重排）。
 */
private data class MorphRoundRectShape(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val cornerRadiusPx: Float
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val safeLeft = left.coerceIn(0f, size.width)
        val safeRight = right.coerceIn(safeLeft + 1f, size.width.coerceAtLeast(safeLeft + 1f))
        val safeTop = top.coerceIn(0f, size.height)
        val safeBottom = bottom.coerceIn(safeTop + 1f, size.height.coerceAtLeast(safeTop + 1f))
        val maxR = minOf(safeRight - safeLeft, safeBottom - safeTop) * 0.5f
        val r = cornerRadiusPx.coerceIn(0f, maxR)
        return Outline.Rounded(
            RoundRect(
                left = safeLeft,
                top = safeTop,
                right = safeRight,
                bottom = safeBottom,
                cornerRadius = CornerRadius(r, r)
            )
        )
    }
}

private fun smoothStep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun lerpFloat(a: Float, b: Float, t: Float): Float = a + (b - a) * t

/**
 * 120fps 纯 GPU 零重排暗房液态玻璃舒展详情浮卡 (PhotoDetailHud)
 *
 * 性能与物理架构：
 * - 彻底废弃逐帧触发布局重排与 Skia RenderEffect 重建的 `animateContentSize` / `AnimatedContent`；
 * - 胶囊与展开浮卡各自维持固定尺寸，AGSL 液态玻璃着色器与模糊纹理仅编译上传 1 次；
 * - 展开与折叠全程仅在 RenderThread `graphicsLayer` 中完成底部锚点缩放、平移与透明度混合，
 *   0 重组、0 布局抖动、0 逐帧 Shader 重建，支持跟手下拉阻尼收起。
 */
@Composable
internal fun PhotoDetailHud(
    photo: PhotoItem,
    backdrop: LiquidBackdropState?,
    expanded: Boolean,
    isCardMounted: Boolean,
    backdropCacheKey: Any,
    expandProgressProvider: () -> Float,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
    val collapseDragThresholdPx = with(density) { 36.dp.toPx() }

    // 进入查看器即异步预读本地 EXIF，展开时零高度跳变
    var exifInfo by remember(photo.uri) { mutableStateOf(PhotoExifInfo()) }
    LaunchedEffect(photo.uri) {
        exifInfo = PhotoExifMetadata.read(context, photo.uri)
    }

    var filenameCopied by remember(photo.id) { mutableStateOf(false) }
    LaunchedEffect(filenameCopied) {
        if (filenameCopied) {
            delay(1800L)
            filenameCopied = false
        }
    }

    val dragOffsetY = remember(photo.id) { Animatable(0f) }
    LaunchedEffect(expanded) {
        if (expanded) {
            dragOffsetY.snapTo(0f)
        } else if (dragOffsetY.value != 0f) {
            // 下拉触发收起时与主形变弹簧同步回弹至底部胶囊原位，避免悬空折叠
            dragOffsetY.animateTo(
                targetValue = 0f,
                animationSpec = spring(dampingRatio = 0.96f, stiffness = 460f)
            )
        }
    }

    val captureTimestamp = remember(photo.dateTaken, photo.dateModified) {
        if (photo.dateTaken > 0) photo.dateTaken else photo.dateModified
    }
    val shortDate = remember(captureTimestamp) {
        SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(captureTimestamp))
    }
    val heroDateTime = remember(captureTimestamp) {
        SimpleDateFormat("yyyy年M月d日 · HH:mm", Locale.getDefault()).format(Date(captureTimestamp))
    }
    val weekdayText = remember(captureTimestamp) {
        SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(captureTimestamp))
    }
    val formatExt = remember(photo.displayName) {
        PhotoExifMetadata.formatExtension(photo.displayName)
    }
    val megaPixels = remember(photo.width, photo.height) {
        if (photo.width > 0 && photo.height > 0) {
            val mp = (photo.width.toLong() * photo.height) / 1_000_000.0
            if (mp >= 0.1) String.format(Locale.US, "%.1f MP", mp) else "<0.1 MP"
        } else null
    }
    val dimensionSub = remember(photo.width, photo.height) {
        if (photo.width > 0 && photo.height > 0) "${photo.width} × ${photo.height}" else "未记录"
    }
    val formattedSize = remember(photo.size) {
        when {
            photo.size < 1024 -> "${photo.size} B"
            photo.size < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", photo.size / 1024.0)
            else -> String.format(Locale.US, "%.2f MB", photo.size / (1024.0 * 1024.0))
        }
    }
    val aspectLabel = remember(photo.width, photo.height) {
        PhotoExifMetadata.formatAspectRatioLabel(photo.width, photo.height) ?: "标准"
    }
    val orientationSub = remember(photo.width, photo.height) {
        when {
            photo.width <= 0 || photo.height <= 0 -> "画幅"
            photo.width == photo.height -> "方形"
            photo.width > photo.height -> "横屏"
            else -> "竖屏"
        }
    }
    val summaryText = remember(shortDate, megaPixels, formattedSize) {
        listOfNotNull(shortDate, megaPixels, formattedSize).joinToString(" · ")
    }

    val pillGeo = remember { LayoutGeometryHolder() }
    val cardGeo = remember { LayoutGeometryHolder() }
    val expandedCardShape = remember { RoundedCornerShape(22.dp) }
    val morphRimStroke = remember(density) { Stroke(width = with(density) { 0.9.dp.toPx() }) }
    val currentExpanded = remember { mutableStateOf(expanded) }.apply { value = expanded }

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.BottomCenter
    ) {
        // 第 1 层：收起态固定尺寸烟熏液态玻璃胶囊（在完全重合的胶囊像素框内与形变浮卡无缝接力）
        Box(
            modifier = Modifier
                .padding(horizontal = 8.dp)
                .onGloballyPositioned { coords ->
                    pillGeo.origin = coords.positionInRoot()
                    pillGeo.size = coords.size
                }
        ) {
            LiquidGlassSurface(
                backdrop = backdrop,
                isDark = true,
                cornerRadius = 20.dp,
                blurRadius = 16.dp,
                refractionStrengthDp = 12.dp,
                tintColor = Color(0x54141620),
                elevation = 0.dp,
                originInRootOverride = { pillGeo.origin },
                staticBackdropCache = true,
                backdropCacheKey = backdropCacheKey,
                onClick = {
                    if (!currentExpanded.value) {
                        HapticFeedback.click(context)
                        onExpandedChange(true)
                    }
                },
                pressBounce = true,
                modifier = Modifier.graphicsLayer {
                    val p = expandProgressProvider().coerceIn(0f, 1f)
                    // 胶囊外框与形变玻璃壳在同一像素框内平滑接力（0 跳闪）
                    alpha = 1f - smoothStep(0.02f, 0.22f, p)
                }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .graphicsLayer {
                            val p = expandProgressProvider().coerceIn(0f, 1f)
                            alpha = 1f - smoothStep(0.0f, 0.18f, p)
                            translationY = -5.dp.toPx() * p
                        }
                        .padding(start = 16.dp, end = 12.dp, top = 9.dp, bottom = 9.dp)
                ) {
                    Text(
                        text = summaryText,
                        style = TextStyle(fontFeatureSettings = "tnum"),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White.copy(alpha = 0.92f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowUp,
                        contentDescription = if (!expanded) "查看照片详情" else null,
                        tint = Color.White.copy(alpha = 0.72f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        // 第 2 层：展开态固定尺寸烟熏暗晶 AGSL 液态玻璃浮卡（硬件圆角裁切从胶囊实测框无缝胀大/收拢，0 文字压扁）
        if (isCardMounted) {
            val cameraSpecs = remember(exifInfo) {
                buildList {
                    exifInfo.focalLength?.let { add("焦距" to it) }
                    exifInfo.aperture?.let { add("光圈" to it) }
                    exifInfo.shutterSpeed?.let { add("快门" to it) }
                    exifInfo.iso?.let { add("ISO" to it.removePrefix("ISO ")) }
                }
            }
            val mediaFeatures = remember(photo.isHdr, photo.isLivePhoto) {
                buildList {
                    if (photo.isHdr) add("高动态范围 HDR")
                    if (photo.isLivePhoto) add("Live Photo 实况")
                }.joinToString(" · ")
            }

            // 挂载后先以 1x1px 极微可视区触发 2 帧真实 HWUI 预烘焙（规避 alpha=0f 被 HWUI 跳过录制），之后完全隐去
            var prebakedOnce by remember(backdropCacheKey) { mutableStateOf(false) }
            LaunchedEffect(backdropCacheKey) {
                withFrameNanos { }
                withFrameNanos { }
                prebakedOnce = true
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { coords ->
                        cardGeo.origin = coords.positionInRoot()
                        cardGeo.size = coords.size
                    }
            ) {
                LiquidGlassSurface(
                    backdrop = backdrop,
                    isDark = true,
                    cornerRadius = 22.dp,
                    blurRadius = 16.dp,
                    refractionStrengthDp = 12.dp,
                    tintColor = Color(0x54141620),
                    elevation = 0.dp,
                    originInRootOverride = { cardGeo.origin },
                    staticBackdropCache = true,
                    backdropCacheKey = backdropCacheKey,
                    pressBounce = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            val p = expandProgressProvider().coerceIn(0f, 1f)
                            val w = size.width
                            val h = size.height
                            if (p <= 0.004f) {
                                if (!prebakedOnce && w > 2f && h > 2f) {
                                    alpha = 0.01f
                                    shape = MorphRoundRectShape(0f, h - 2f, 2f, h, 0f)
                                    clip = true
                                } else {
                                    alpha = 0f
                                    clip = false
                                }
                                scaleX = 1f
                                scaleY = 1f
                                translationY = 0f
                                return@graphicsLayer
                            }
                            alpha = 1f
                            transformOrigin = TransformOrigin(0.5f, 1f)
                            if (p >= 0.995f) {
                                shape = expandedCardShape
                                clip = true
                                scaleX = 1f
                                scaleY = 1f
                                translationY = dragOffsetY.value
                                return@graphicsLayer
                            }
                            if (w > 2f && h > 2f) {
                                val startW = if (pillGeo.size.width > 0) pillGeo.size.width.toFloat().coerceAtMost(w) else w * 0.62f
                                val startH = if (pillGeo.size.height > 0) pillGeo.size.height.toFloat().coerceAtMost(h) else 38.dp.toPx()
                                val curW = lerpFloat(startW, w, p)
                                val curH = lerpFloat(startH, h, p)
                                val left = (w - curW) * 0.5f
                                val top = h - curH
                                val curCorner = lerpFloat(startH * 0.5f, 22.dp.toPx(), p)
                                shape = MorphRoundRectShape(
                                    left = left,
                                    top = top,
                                    right = left + curW,
                                    bottom = h,
                                    cornerRadiusPx = curCorner
                                )
                                clip = true
                            }
                            val breathScale = 0.985f + 0.015f * p
                            scaleX = breathScale
                            scaleY = breathScale
                            translationY = dragOffsetY.value
                        }
                        .then(
                            if (expanded) {
                                Modifier.pointerInput(photo.id) {
                                    val touchSlop = viewConfiguration.touchSlop
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        val velocityTracker = VelocityTracker()
                                        var dragging = false
                                        var accumY = 0f
                                        var accumX = 0f

                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                            if (!change.pressed) {
                                                if (!dragging && !change.isConsumed) {
                                                    change.consume()
                                                }
                                                break
                                            }
                                            val delta = change.positionChange()
                                            if (!dragging) {
                                                accumX += delta.x
                                                accumY += delta.y
                                                if (abs(accumY) > touchSlop && abs(accumY) > abs(accumX)) {
                                                    dragging = true
                                                }
                                            }
                                            if (dragging) {
                                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                                                val current = dragOffsetY.value
                                                val step = if (current + delta.y < 0f) delta.y * 0.14f else delta.y * 0.78f
                                                coroutineScope.launch {
                                                    dragOffsetY.snapTo((current + step).coerceAtLeast(-18f))
                                                }
                                                change.consume()
                                            }
                                        }

                                        if (dragging) {
                                            val velY = velocityTracker.calculateVelocity().y
                                            if (dragOffsetY.value > collapseDragThresholdPx || velY > 650f) {
                                                HapticFeedback.tick(context)
                                                onExpandedChange(false)
                                            } else {
                                                coroutineScope.launch {
                                                    dragOffsetY.animateTo(
                                                        0f,
                                                        spring(dampingRatio = 0.78f, stiffness = 580f)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            } else Modifier
                        )
                ) {
                    // 烟熏暗晶渐变底幕（独立叶子层，0 子树重绘、随形变丝滑由 33% 胶囊通透度过渡至 75% 暗房高可读底色）
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .drawBehind {
                                val p = expandProgressProvider().coerceIn(0f, 1f)
                                val veilAlpha = 0.64f * smoothStep(0f, 0.72f, p)
                                if (veilAlpha > 0.005f) {
                                    drawRect(Color(0xFF0D0F16).copy(alpha = veilAlpha))
                                }
                            }
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                // 离屏预光栅化整套排版内容：展开/收拢全程 0 文字重排重绘，GPU 仅合成单张缓存纹理
                                compositingStrategy = CompositingStrategy.Offscreen
                                val p = expandProgressProvider().coerceIn(0f, 1f)
                                // 内容随液态玻璃舒展柔顺上浮显露，折叠时提前柔化淡入胶囊，0 压扁变形
                                alpha = smoothStep(0.18f, 0.76f, p)
                                translationY = (1f - p) * 20.dp.toPx()
                            }
                            .padding(horizontal = 20.dp, vertical = 18.dp)
                    ) {
                        // 1. 顶部时间标题 + 格式微标 + 收起圆钮
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = heroDateTime,
                                    style = TextStyle(fontFeatureSettings = "tnum"),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White.copy(alpha = 0.96f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(5.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = weekdayText,
                                        fontSize = 12.sp,
                                        color = Color.White.copy(alpha = 0.54f)
                                    )
                                    DarkroomMicroTag(formatExt)
                                    if (photo.isHdr) DarkroomMicroTag("HDR")
                                    if (photo.isLivePhoto) DarkroomMicroTag("LIVE")
                                }
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.08f))
                                    .border(0.6.dp, Color.White.copy(alpha = 0.14f), CircleShape)
                                    .then(
                                        if (expanded) {
                                            Modifier.clickable {
                                                HapticFeedback.tick(context)
                                                onExpandedChange(false)
                                            }
                                        } else Modifier
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.KeyboardArrowDown,
                                    contentDescription = "收起照片详情",
                                    tint = Color.White.copy(alpha = 0.80f),
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                        }

                        // 2. 光学 EXIF 参数区（仅相机原图展示，截图/网图自动隐去）
                        if (exifInfo.hasCameraSpecs) {
                            Spacer(modifier = Modifier.height(14.dp))
                            DarkroomHairline()
                            Spacer(modifier = Modifier.height(13.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = exifInfo.cameraLabel ?: "相机光学参数",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color.White.copy(alpha = 0.90f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "EXIF",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = 1.1.sp,
                                    color = Color.White.copy(alpha = 0.42f)
                                )
                            }

                            if (cameraSpecs.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(10.dp))
                                val trayShape = remember { RoundedCornerShape(12.dp) }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(trayShape)
                                        .background(Color.White.copy(alpha = 0.055f))
                                        .border(0.5.dp, Color.White.copy(alpha = 0.09f), trayShape)
                                        .padding(vertical = 9.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    cameraSpecs.forEachIndexed { index, (label, value) ->
                                        if (index > 0) DarkroomVerticalHairline(heightDp = 22)
                                        Column(
                                            modifier = Modifier.weight(1f),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Text(
                                                text = value,
                                                style = TextStyle(fontFeatureSettings = "tnum"),
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Color.White.copy(alpha = 0.94f),
                                                maxLines = 1
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = label,
                                                fontSize = 10.sp,
                                                color = Color.White.copy(alpha = 0.46f),
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // 3. 像素 / 体积 / 画幅 三栏等宽仪表
                        Spacer(modifier = Modifier.height(14.dp))
                        DarkroomHairline()
                        Spacer(modifier = Modifier.height(13.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            DarkroomSpecColumn(
                                caption = "像素",
                                value = megaPixels ?: "未知",
                                subLabel = dimensionSub,
                                modifier = Modifier.weight(1.15f)
                            )
                            DarkroomVerticalHairline(heightDp = 28)
                            DarkroomSpecColumn(
                                caption = "体积",
                                value = formattedSize,
                                subLabel = formatExt,
                                modifier = Modifier.weight(1f)
                            )
                            DarkroomVerticalHairline(heightDp = 28)
                            DarkroomSpecColumn(
                                caption = "画幅",
                                value = aspectLabel,
                                subLabel = orientationSub,
                                modifier = Modifier.weight(0.9f)
                            )
                        }

                        // 4. 极简文件信息底栏（纯排版，轻点文件名直接轻震复制）
                        Spacer(modifier = Modifier.height(13.dp))
                        DarkroomHairline()
                        Spacer(modifier = Modifier.height(11.dp))

                        DarkroomMetaLine(
                            label = "图集",
                            value = photo.bucketName ?: "根目录"
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        val mintConfirm = Color(0xFF6EE7B7)
                        val copyTint by animateColorAsState(
                            targetValue = if (filenameCopied) mintConfirm else Color.White.copy(alpha = 0.62f),
                            animationSpec = tween(150),
                            label = "darkroom_copy_tint"
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .then(
                                    if (expanded) {
                                        Modifier.clickable {
                                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                                            clipboard?.setPrimaryClip(
                                                ClipData.newPlainText("photo_filename", photo.displayName)
                                            )
                                            HapticFeedback.tick(context)
                                            filenameCopied = true
                                        }
                                    } else Modifier
                                )
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "文件",
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.44f),
                                modifier = Modifier.width(42.dp)
                            )
                            Text(
                                text = photo.displayName,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color.White.copy(alpha = 0.90f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(
                                        if (filenameCopied) mintConfirm.copy(alpha = 0.14f)
                                        else Color.White.copy(alpha = 0.08f)
                                    )
                                    .padding(
                                        horizontal = if (filenameCopied) 8.dp else 7.dp,
                                        vertical = 4.dp
                                    )
                            ) {
                                Icon(
                                    imageVector = if (filenameCopied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                                    contentDescription = if (filenameCopied) "已复制文件名" else "复制文件名",
                                    tint = copyTint,
                                    modifier = Modifier.size(13.dp)
                                )
                                if (filenameCopied) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "已复制",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = copyTint
                                    )
                                }
                            }
                        }

                        if (mediaFeatures.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            DarkroomMetaLine(
                                label = "特性",
                                value = mediaFeatures
                            )
                        }
                    }
                }

                // 独立兄弟高光描边层：彻底隔离形变动画帧，0 子树重录、0 逐帧对象分配
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .drawBehind {
                            val p = expandProgressProvider().coerceIn(0f, 1f)
                            if (p in 0.015f..0.99f && size.width > 2f && size.height > 2f) {
                                val rimFade = (1f - smoothStep(0.90f, 0.99f, p)) * smoothStep(0.015f, 0.12f, p)
                                if (rimFade > 0.01f) {
                                    val startW = if (pillGeo.size.width > 0) pillGeo.size.width.toFloat().coerceAtMost(size.width) else size.width * 0.62f
                                    val startH = if (pillGeo.size.height > 0) pillGeo.size.height.toFloat().coerceAtMost(size.height) else 38.dp.toPx()
                                    val curW = lerpFloat(startW, size.width, p)
                                    val curH = lerpFloat(startH, size.height, p)
                                    val left = (size.width - curW) * 0.5f
                                    val top = size.height - curH
                                    val curCorner = lerpFloat(startH * 0.5f, 22.dp.toPx(), p)
                                        .coerceAtMost(minOf(curW, curH) * 0.5f)
                                    translate(top = dragOffsetY.value) {
                                        drawRoundRect(
                                            color = Color.White.copy(alpha = 0.28f * rimFade),
                                            topLeft = Offset(left, top),
                                            size = Size(curW, curH),
                                            cornerRadius = CornerRadius(curCorner, curCorner),
                                            style = morphRimStroke
                                        )
                                    }
                                }
                            }
                        }
                )
            }
        }
    }
}

@Composable
private fun DarkroomMicroTag(text: String) {
    Surface(
        shape = CircleShape,
        color = Color.White.copy(alpha = 0.08f),
        border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.16f))
    ) {
        Text(
            text = text,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.4.sp,
            color = Color.White.copy(alpha = 0.84f),
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun DarkroomSpecColumn(
    caption: String,
    value: String,
    subLabel: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = caption,
            fontSize = 10.sp,
            color = Color.White.copy(alpha = 0.46f),
            maxLines = 1
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = TextStyle(fontFeatureSettings = "tnum"),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White.copy(alpha = 0.95f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = subLabel,
            style = TextStyle(fontFeatureSettings = "tnum"),
            fontSize = 10.sp,
            color = Color.White.copy(alpha = 0.48f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun DarkroomMetaLine(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = Color.White.copy(alpha = 0.44f),
            modifier = Modifier.width(42.dp)
        )
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White.copy(alpha = 0.90f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun DarkroomHairline() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .background(Color.White.copy(alpha = 0.09f))
    )
}

@Composable
private fun DarkroomVerticalHairline(heightDp: Int) {
    Box(
        modifier = Modifier
            .width(0.5.dp)
            .height(heightDp.dp)
            .background(Color.White.copy(alpha = 0.10f))
    )
}
