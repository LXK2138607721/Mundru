package com.lumina.cleaner.ui.viewer

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.lumina.cleaner.core.model.PhotoItem
import com.lumina.cleaner.core.util.HapticFeedback
import com.lumina.cleaner.ui.motion.MotionPhotoPlayer
import com.lumina.cleaner.ui.motion.MotionScaleType
import kotlinx.coroutines.Job
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
    modifier: Modifier = Modifier
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

    // 高保真平滑缩放与平移动效控制器 (解决双击瞬间跳变，呈现丝滑变大中间态)
    val scaleAnim = remember(photo.id) { Animatable(1f) }
    val offsetXAnim = remember(photo.id) { Animatable(0f) }
    val offsetYAnim = remember(photo.id) { Animatable(0f) }
    val scale = scaleAnim.value
    val offset = Offset(offsetXAnim.value, offsetYAnim.value)

    // 平滑缩小并退出查看器
    fun dismissWithAnimation() {
        if (isDismissing) return
        isDismissing = true
        HapticFeedback.click(context)
        coroutineScope.launch {
            if (scaleAnim.value > 1.05f) {
                val resetSpec = tween<Float>(durationMillis = 240, easing = FastOutSlowInEasing)
                launch { scaleAnim.animateTo(1f, resetSpec) }
                launch { offsetXAnim.animateTo(0f, resetSpec) }
                launch { offsetYAnim.animateTo(0f, resetSpec) }
            }
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

    val currentProgress = animProgress.value
    // 背景深色暗幕：平滑淡入与淡出
    val bgAlpha = (currentProgress * 0.98f).coerceIn(0f, 0.98f)
    // 缩放尺寸因子：从 0.50f（主界面居中卡片比例）平滑放大到 1.0f；退出时从 1.0f 一直平滑缩小至 0.50f
    val baseScale = 0.50f + 0.50f * currentProgress
    val contentScaleFactor = baseScale * scale
    // 内容透明度：退出末端无缝隐化交接
    val contentAlpha = if (isDismissing) {
        (currentProgress / 0.35f).coerceIn(0f, 1f)
    } else {
        currentProgress.coerceIn(0f, 1f)
    }
    // 圆角：全屏 0dp，缩回卡片形态时恢复 24dp
    val cornerRadius = (24.dp * (1f - currentProgress)).coerceAtLeast(0.dp)
    // 顶部操作控件透明度：退出时立即淡隐
    val controlsAlpha = if (isDismissing) {
        ((currentProgress - 0.70f) / 0.30f).coerceIn(0f, 1f)
    } else {
        ((currentProgress - 0.45f) / 0.55f).coerceIn(0f, 1f)
    }

    var lastTapTime by remember { mutableLongStateOf(0L) }
    var lastTapOffset by remember { mutableStateOf(Offset.Zero) }
    var tapJob by remember { mutableStateOf<Job?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = bgAlpha))
            .pointerInput(photo.id) {
                detectTransformGestures { _, pan, zoom, _ ->
                    if (currentProgress < 0.95f || isDismissing) return@detectTransformGestures
                    if (zoom != 1f || pan != Offset.Zero) {
                        tapJob?.cancel()
                    }
                    val currentScale = scaleAnim.value
                    val newScale = (currentScale * zoom).coerceIn(1f, 5f)

                    coroutineScope.launch {
                        scaleAnim.snapTo(newScale)
                        if (newScale > 1f) {
                            val maxPanX = (size.width * (newScale - 1f)) / 2f
                            val maxPanY = (size.height * (newScale - 1f)) / 2f
                            offsetXAnim.snapTo((offsetXAnim.value + pan.x).coerceIn(-maxPanX, maxPanX))
                            offsetYAnim.snapTo((offsetYAnim.value + pan.y).coerceIn(-maxPanY, maxPanY))
                        } else {
                            offsetXAnim.snapTo(0f)
                            offsetYAnim.snapTo(0f)
                        }
                    }
                }
            }
            .pointerInput(photo.id) {
                detectTapGestures(
                    onTap = { tapOffset ->
                        if (currentProgress < 0.90f || isDismissing) return@detectTapGestures
                        val now = System.currentTimeMillis()
                        val isDoubleTap = (now - lastTapTime < 350L) &&
                                (tapOffset - lastTapOffset).getDistance() < 160f

                        if (isDoubleTap) {
                            // 收到第二击确认双击：立即取消单击退出的延时任务，彻底杜绝误退回主界面！
                            tapJob?.cancel()
                            tapJob = null
                            lastTapTime = 0L
                            HapticFeedback.click(context)

                            coroutineScope.launch {
                                // 280ms FastOutSlow 动力学插值动画，呈现清晰细腻的放大过渡中间态
                                val animSpec = tween<Float>(durationMillis = 280, easing = FastOutSlowInEasing)
                                if (scaleAnim.value > 1.2f) {
                                    // 已放大状态下双击：平滑缩小归位为 1x 经典比例
                                    launch { scaleAnim.animateTo(1f, animSpec) }
                                    launch { offsetXAnim.animateTo(0f, animSpec) }
                                    launch { offsetYAnim.animateTo(0f, animSpec) }
                                } else {
                                    // 未放大状态下双击：从 1x 丝滑舒展放大至 2.5x 并平滑聚焦对齐点击中心
                                    val targetScale = 2.5f
                                    val centerX = size.width / 2f
                                    val centerY = size.height / 2f
                                    val maxPanX = (size.width * (targetScale - 1f)) / 2f
                                    val maxPanY = (size.height * (targetScale - 1f)) / 2f
                                    val targetX = ((centerX - tapOffset.x) * 1.5f).coerceIn(-maxPanX, maxPanX)
                                    val targetY = ((centerY - tapOffset.y) * 1.5f).coerceIn(-maxPanY, maxPanY)

                                    launch { scaleAnim.animateTo(targetScale, animSpec) }
                                    launch { offsetXAnim.animateTo(targetX, animSpec) }
                                    launch { offsetYAnim.animateTo(targetY, animSpec) }
                                }
                            }
                        } else {
                            lastTapTime = now
                            lastTapOffset = tapOffset
                            tapJob?.cancel()
                            tapJob = coroutineScope.launch {
                                delay(320L) // 充足且舒适的 320ms 双击消歧窗口，确保双击放大操作极其从容
                                if (!isDismissing) {
                                    if (scaleAnim.value > 1.05f) {
                                        // 已放大状态下单指轻击：平滑动画还原为 1x 正常比例
                                        val animSpec = tween<Float>(durationMillis = 240, easing = FastOutSlowInEasing)
                                        launch { scaleAnim.animateTo(1f, animSpec) }
                                        launch { offsetXAnim.animateTo(0f, animSpec) }
                                        launch { offsetYAnim.animateTo(0f, animSpec) }
                                    } else {
                                        // 未放大状态下轻击：确认非双击后平滑缩小退出大图，返回主界面
                                        dismissWithAnimation()
                                    }
                                }
                            }
                        }
                    },
                    onPress = {
                        if (currentProgress < 0.95f || isDismissing) return@detectTapGestures
                        if (photo.isLivePhoto && photo.motionVideoLength > 0 && !isLivePlaying) {
                            HapticFeedback.tick(context)
                            isLivePressing = true
                            tryAwaitRelease()
                            isLivePressing = false
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
                    scaleX = contentScaleFactor
                    scaleY = contentScaleFactor
                    translationX = offset.x
                    translationY = offset.y
                    alpha = contentAlpha
                    clip = true
                    shape = RoundedCornerShape(cornerRadius)
                },
            contentAlignment = Alignment.Center
        ) {
            // 底层高清原图（FIT 居中包含，等比显示）
            AsyncImage(
                model = photo.uri,
                contentDescription = photo.displayName,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
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
                    .graphicsLayer { alpha = controlsAlpha },
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
                .graphicsLayer { alpha = controlsAlpha }
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
