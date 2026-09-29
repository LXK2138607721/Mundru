package com.lumina.cleaner.ui.deck

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
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

/**
 * Mundru 核心相册卡片组件 (DeckCard)
 * 品牌释义：Mundru 源自格陵兰语（Greenlandic），意为「擦掉 / 擦除」。
 * 单体设计：高保真原图呈现、LIVE 与 HDR 双徽标隔离、平滑连续圆角与微秒手势联动。
 *
 * 核心交互与逻辑规范：
 * 1. 24dp 平滑连续圆角（Squircle）+ 柔和弥散阴影；
 * 2. 自适应宽高比（横图与竖图黄金适配）；
 * 3. HDR 与 LIVE 徽标严格物理隔离：
 *    - 点击 HDR 徽标仅触发轻触触觉反馈，绝对不触发 Live 播放，杜绝事件穿透；
 *    - LIVE 徽标：轻点只播放一次实况音画，播放完毕自动复位；长按卡片或徽标依然支持按住预览、松手即停；
 * 4. 实况照片视频画面采用等比裁剪 (CROP) 适配，绝不拉伸变形。
 */
@Composable
fun DeckCard(
    photo: PhotoItem,
    onClick: () -> Unit = {},
    enableGestures: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // 点击播放单次标志
    var isLivePlaying by remember(photo.id) { mutableStateOf(false) }
    // 按住卡片主体临时预览标志
    var isLivePressing by remember(photo.id) { mutableStateOf(false) }
    // 合成播放状态：点击播放单次 或 按住临时播
    val isPlayingLive = isLivePlaying || isLivePressing

    // 自适应卡片比例：横屏自适应，竖屏适中
    val cardAspectRatio = remember(photo.aspectRatio) {
        when {
            photo.aspectRatio < 0.6f -> 9f / 16f
            photo.aspectRatio < 0.85f -> 3f / 4f
            photo.aspectRatio < 1.2f -> 1f
            photo.aspectRatio < 1.55f -> 4f / 3f
            else -> 16f / 9f
        }
    }

    val gestureModifier = if (enableGestures) {
        Modifier.pointerInput(photo.id) {
            detectTapGestures(
                onTap = { onClick() },
                onPress = {
                    if (photo.isLivePhoto && photo.motionVideoLength > 0 && !isLivePlaying) {
                        HapticFeedback.tick(context)
                        isLivePressing = true
                        tryAwaitRelease()
                        isLivePressing = false
                    }
                }
            )
        }
    } else {
        Modifier
    }

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 10.dp,
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(cardAspectRatio)
            .shadow(
                elevation = 20.dp,
                shape = RoundedCornerShape(24.dp),
                ambientColor = Color.Black.copy(alpha = 0.06f),
                spotColor = Color.Black.copy(alpha = 0.10f)
            )
            .clip(RoundedCornerShape(24.dp))
            .then(gestureModifier)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 底层静态图片
            AsyncImage(
                model = photo.uri,
                contentDescription = photo.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

            // 实况照片播放器层（播放激活时平滑浮现渲染，保持等比裁切，绝不拉伸变形）
            if (photo.isLivePhoto && photo.motionVideoLength > 0) {
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

            val isGif = photo.displayName.endsWith(".gif", ignoreCase = true)
            // HDR、实况照片（Live Photo）与 GIF 动图标签组（严格隔离事件）
            if (photo.isHdr || photo.isLivePhoto || isGif) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(16.dp),
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
                                    detectTapGestures(
                                        onTap = {
                                            HapticFeedback.tick(context)
                                        }
                                    )
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

                    // HDR 徽标：独立消费触摸，绝对不穿透给底层卡片，绝不误播 Live
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

                    // LIVE 徽标：支持「点击播放一次」与「长按临时播放」双模式
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
        }
    }
}
