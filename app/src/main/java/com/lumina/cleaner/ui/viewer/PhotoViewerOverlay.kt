package com.lumina.cleaner.ui.viewer

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.PhotoSizeSelectActual
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntSize
import com.lumina.cleaner.core.model.PhotoItem
import com.lumina.cleaner.core.util.HapticFeedback
import com.lumina.cleaner.ui.motion.MotionPhotoPlayer
import com.lumina.cleaner.ui.motion.MotionScaleType
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Mundru 沉浸式高保真原图查看器 (PhotoViewerOverlay)
 * 品牌释义：Mundru 源自格陵兰语（Greenlandic），意为「擦掉 / 擦除」。
 * 核心体验：高保真原图双指缩放、EXIF 详情浮层与实况照片原比例播放。
 *
 * 核心特性：
 * 1. 丝滑进出转场：展开时从卡片形态平滑放大至全屏，退出时播放平滑缩小动画并无缝回到主界面卡片位置；
 * 2. 彻底消除实况照片拉伸铺满全屏硬伤：采用 FIT 等比居中容纳与矩阵变换，像素级与原图对齐，绝不拉伸变形；
 * 3. 实况播放规范化：点击 LIVE 按钮只播放一次，播放完自动复位；长按画面临时预览、松手即停；
 * 4. 严格隔离 HDR 与 LIVE 徽标逻辑：点击 HDR 绝不触发实况播放；
 * 5. 双击智能放大/还原 (1x <-> 2.5x)，双指捏合自由缩放 (1x ~ 5x)；
 * 6. 右上角精致详情入口唤起 ModalBottomSheet。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoViewerOverlay(
    photo: PhotoItem,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    initialZoomFocus: Offset? = null // Normalized original-image coordinates, not card/viewport coordinates.
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // 展开与退出平滑物理动效控制器 (0f: 卡片收纳态, 1f: 全屏展开态)
    val animProgress = remember(photo.id) { Animatable(0f) }
    var isDismissing by remember(photo.id) { mutableStateOf(false) }

    LaunchedEffect(photo.id) {
        animProgress.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = 0.82f,
                stiffness = 320f
            )
        )
    }

    var transform by remember(photo.id) { mutableStateOf(PhotoTransform()) }
    var requestedScale by remember(photo.id) { mutableFloatStateOf(1f) }
    var transformJob by remember(photo.id) { mutableStateOf<Job?>(null) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }

    fun animateTransform(target: PhotoTransform, durationMillis: Int = 280) {
        transformJob?.cancel()
        requestedScale = target.scale
        val start = transform
        transformJob = coroutineScope.launch {
            animate(0f, 1f, animationSpec = tween(durationMillis, easing = FastOutSlowInEasing)) { fraction, _ ->
                transform = start.interpolate(target, fraction)
            }
        }
    }

    // Card double taps are resolved before opening the overlay; no second tap is lost in the transition.
    LaunchedEffect(photo.id, initialZoomFocus, viewportSize, photo.aspectRatio) {
        if (initialZoomFocus != null && viewportSize != IntSize.Zero) {
            animateTransform(PhotoTransform.zoomedAtImageFocus(
                initialZoomFocus.x, initialZoomFocus.y, photo.aspectRatio, viewportSize.width, viewportSize.height
            ))
        }
    }

    val transformState = rememberTransformableState { zoom, pan, _ ->
        if (!isDismissing) {
            transformJob?.cancel()
            transformJob = null
            transform = transform.transformed(zoom, pan.x, pan.y, viewportSize.width, viewportSize.height)
            requestedScale = transform.scale
        }
    }

    // 平滑缩小并退出查看器
    fun dismissWithAnimation() {
        if (isDismissing) return
        isDismissing = true
        HapticFeedback.click(context)
        coroutineScope.launch {
            animateTransform(PhotoTransform(), 240)
            animProgress.animateTo(
                targetValue = 0f,
                animationSpec = tween(
                    durationMillis = 260,
                    easing = FastOutSlowInEasing
                )
            )
            onDismiss()
        }
    }
    val currentDismiss by rememberUpdatedState(::dismissWithAnimation)

    BackHandler(onBack = { dismissWithAnimation() })
    var showDetailSheet by remember { mutableStateOf(false) }

    // 实况照片：点击单次播放标志 与 按住临时预览标志
    var isLivePlaying by remember(photo.id) { mutableStateOf(false) }
    var isLivePressing by remember(photo.id) { mutableStateOf(false) }
    val isPlayingLive = isLivePlaying || isLivePressing

    val formattedDate = remember(photo.dateTaken, photo.dateModified) {
        val time = if (photo.dateTaken > 0) photo.dateTaken else photo.dateModified
        SimpleDateFormat("yyyy年MM月dd日 HH:mm", Locale.getDefault()).format(Date(time))
    }

    val megaPixels = remember(photo.width, photo.height) {
        if (photo.width > 0 && photo.height > 0) {
            val mp = (photo.width.toLong() * photo.height) / 1_000_000.0
            String.format(Locale.getDefault(), "%.1f MP", mp)
        } else null
    }

    val formattedSize = remember(photo.size) {
        when {
            photo.size < 1024 -> "${photo.size} B"
            photo.size < 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f KB", photo.size / 1024.0)
            else -> String.format(Locale.getDefault(), "%.2f MB", photo.size / (1024.0 * 1024.0))
        }
    }

    fun controlsAlpha(): Float {
        val progress = animProgress.value
        return if (isDismissing) ((progress - 0.70f) / 0.30f).coerceIn(0f, 1f)
        else ((progress - 0.45f) / 0.55f).coerceIn(0f, 1f)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind { drawRect(Color.Black.copy(alpha = (animProgress.value * 0.98f).coerceIn(0f, 0.98f))) }
            .onSizeChanged { viewportSize = it }
            .transformable(
                state = transformState,
                // Consume genuine drags after platform touch slop, even at 1x; taps stay intact.
                lockRotationOnZoomPan = true,
                enabled = !isDismissing
            )
            .pointerInput(photo.id) {
                detectTapGestures(
                    onTap = {
                        if (!isDismissing) {
                            if (requestedScale > 1.05f) animateTransform(PhotoTransform(), 240)
                            else currentDismiss()
                        }
                    },
                    onDoubleTap = { position ->
                        if (!isDismissing) {
                            HapticFeedback.click(context)
                            val target = PhotoTransform.doubleTapTarget(
                                requestedScale, position.x / size.width, position.y / size.height, size.width, size.height
                            )
                            animateTransform(target)
                        }
                    },
                    onLongPress = if (photo.isLivePhoto && photo.motionVideoLength > 0) ({ _ -> }) else null,
                    onPress = {
                        if (!isDismissing && photo.isLivePhoto && photo.motionVideoLength > 0 && !isLivePlaying) {
                            coroutineScope {
                                val preview = launch {
                                    delay(viewConfiguration.longPressTimeoutMillis)
                                    HapticFeedback.tick(context)
                                    isLivePressing = true
                                }
                                try {
                                    tryAwaitRelease()
                                } finally {
                                    preview.cancel()
                                    isLivePressing = false
                                }
                            }
                        }
                    }
                )
            }
    ) {
        // 主照片内容（带平滑展开与缩小退出物理过渡）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // Read per-frame state in the layer, not in the whole viewer composition.
                    val progress = animProgress.value
                    val contentScale = (0.50f + 0.50f * progress) * transform.scale
                    scaleX = contentScale
                    scaleY = contentScale
                    translationX = transform.x
                    translationY = transform.y
                    alpha = if (isDismissing) (progress / 0.35f).coerceIn(0f, 1f) else progress.coerceIn(0f, 1f)
                    clip = true
                    shape = RoundedCornerShape((24.dp * (1f - progress)).coerceAtLeast(0.dp))
                },
            contentAlignment = Alignment.Center
        ) {
            // 底层高清原图（FIT 居中包含，等比显示，带外部文件失效安全防御）
            coil.compose.SubcomposeAsyncImage(
                model = photo.uri,
                contentDescription = photo.displayName,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
                error = {
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
            )

            // 实况照片播放器层（精准 FIT 等比居中缩放，未播放时透明，绝不拉伸变形铺满全屏）
            if (photo.isLivePhoto && photo.motionVideoLength > 0) {
                MotionPhotoPlayer(
                    imageUri = photo.uri,
                    videoOffset = photo.motionVideoOffset,
                    videoLength = photo.motionVideoLength,
                    isPlaying = isPlayingLive,
                    scaleType = MotionScaleType.FIT,
                    photoWidth = photo.width,
                    photoHeight = photo.height,
                    onPlaybackComplete = {
                        isLivePlaying = false
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        val isGif = photo.displayName.endsWith(".gif", ignoreCase = true)
        // 左上角 HDR、LIVE 与 GIF 动图徽标（严格隔离手势事件）
        if (photo.isHdr || photo.isLivePhoto || isGif) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(top = 16.dp, start = 16.dp)
                    .graphicsLayer { alpha = controlsAlpha() },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // GIF 动图徽标
                if (isGif) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF6C5CE7).copy(alpha = 0.88f),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .pointerInput(Unit) {
                                detectTapGestures(onTap = { HapticFeedback.tick(context) })
                            }
                    ) {
                        Text(
                            text = "GIF",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                // HDR 徽标：独立消费，绝不触发 Live 播放或退出大图
                if (photo.isHdr) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color.Black.copy(alpha = 0.65f),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = {
                                        HapticFeedback.tick(context)
                                    }
                                )
                            }
                    ) {
                        Text(
                            text = "HDR",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                // LIVE 徽标：点击一下只播放一次，播放完自动复位；长按可临时播放
                if (photo.isLivePhoto) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isPlayingLive) {
                            Color(0xFF1E824C).copy(alpha = 0.88f)
                        } else {
                            Color.Black.copy(alpha = 0.65f)
                        },
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .pointerInput(photo.id) {
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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
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

        // 右上角详细信息入口
        Surface(
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.5f),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 16.dp, end = 16.dp)
                .size(40.dp)
                .graphicsLayer { alpha = controlsAlpha() }
        ) {
            IconButton(onClick = {
                if (isDismissing) return@IconButton
                HapticFeedback.click(context)
                showDetailSheet = true
            }) {
                Icon(
                    imageVector = Icons.Rounded.Info,
                    contentDescription = "详细信息",
                    tint = Color.White
                )
            }
        }
    }

    // 详细信息底部弹层 (ModalBottomSheet)
    if (showDetailSheet) {
        ModalBottomSheet(
            onDismissRequest = { showDetailSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "照片详情",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                DetailRow(
                    icon = Icons.Rounded.Photo,
                    label = "文件名",
                    value = photo.displayName
                )
                Spacer(modifier = Modifier.height(10.dp))
                DetailRow(
                    icon = Icons.Rounded.CalendarToday,
                    label = "拍摄时间",
                    value = formattedDate
                )
                Spacer(modifier = Modifier.height(10.dp))
                DetailRow(
                    icon = Icons.Rounded.PhotoSizeSelectActual,
                    label = "分辨率",
                    value = if (photo.width > 0 && photo.height > 0)
                        "${photo.width} × ${photo.height} (${megaPixels ?: ""})"
                    else "未知"
                )
                Spacer(modifier = Modifier.height(10.dp))
                DetailRow(
                    icon = Icons.Rounded.Storage,
                    label = "文件大小",
                    value = formattedSize
                )
                Spacer(modifier = Modifier.height(10.dp))
                DetailRow(
                    icon = Icons.Rounded.Folder,
                    label = "所属图集",
                    value = photo.bucketName ?: "根目录"
                )
                if (photo.isHdr) {
                    Spacer(modifier = Modifier.height(10.dp))
                    DetailRow(
                        icon = Icons.Rounded.AutoAwesome,
                        label = "色彩范围",
                        value = "高动态范围 · HDR"
                    )
                }
                if (photo.isLivePhoto) {
                    Spacer(modifier = Modifier.height(10.dp))
                    DetailRow(
                        icon = Icons.Rounded.PlayCircle,
                        label = "实况属性",
                        value = "Live Photo · 包含视频音轨切片"
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun DetailRow(
    icon: ImageVector,
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.size(36.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
