package com.lumina.cleaner.ui.motion

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import java.io.FileInputStream
import java.nio.ByteBuffer
import kotlin.math.min

/**
 * 实况照片画面缩放适配模式
 */
enum class MotionScaleType {
    /**
     * 等比居中容纳 (留黑边)，与 ContentScale.Fit 保持完全一致，绝不拉伸变形
     */
    FIT,

    /**
     * 撑满容器并居中裁切，与 ContentScale.Crop 保持完全一致
     */
    CROP
}

/**
 * Mundru 原生极轻量实况照片（Motion Photo / Live Photo）播放器
 * 品牌释义：Mundru 源自格陵兰语（Greenlandic），意为「擦掉 / 擦除」。
 * 核心引擎：100% 原生轻量实况照片播放器，动态矩阵等比渲染与单次播映。
 *
 * 100% 原生 Android SDK（MediaDataSource + MediaPlayer + TextureView），零第三方依赖，零 APK 体积增加。
 * 特性：
 * 1. 动态矩阵适配：基于真实视频宽高比执行 Matrix 缩放居中，绝不拉伸变形铺满全屏；
 * 2. 状态隔离：未激活播放时完全透明，无缝呈现底层高保真原图，播放时平滑淡入；
 * 3. 单次播放支持：isLooping 设为 false，播放完毕自动回调并复位。
 */
@Composable
fun MotionPhotoPlayer(
    imageUri: Uri,
    videoOffset: Long,
    videoLength: Long,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = false,
    playAudio: Boolean = true,
    scaleType: MotionScaleType = MotionScaleType.FIT,
    photoWidth: Int = 0,
    photoHeight: Int = 0,
    onPlaybackComplete: () -> Unit = {}
) {
    // Stacked cards and an unopened LIVE photo must not allocate decoders or read media files.
    key(imageUri, videoOffset, videoLength) {
        var activated by remember { mutableStateOf(false) }
        LaunchedEffect(isPlaying) {
            if (isPlaying) activated = true
        }
        if (isPlaying || activated) {
            ActiveMotionPhotoPlayer(imageUri, videoOffset, videoLength, modifier, isPlaying,
                playAudio, scaleType, photoWidth, photoHeight, onPlaybackComplete)
        }
    }
}

@Composable
private fun ActiveMotionPhotoPlayer(
    imageUri: Uri,
    videoOffset: Long,
    videoLength: Long,
    modifier: Modifier,
    isPlaying: Boolean,
    playAudio: Boolean,
    scaleType: MotionScaleType,
    photoWidth: Int,
    photoHeight: Int,
    onPlaybackComplete: () -> Unit
) {
    val context = LocalContext.current
    val currentOnComplete by rememberUpdatedState(onPlaybackComplete)
    var surface by remember { mutableStateOf<Surface?>(null) }
    var textureViewRef by remember { mutableStateOf<TextureView?>(null) }
    val mediaPlayer = remember { MediaPlayer() }
    var dataSource by remember { mutableStateOf<MotionPhotoDataSource?>(null) }
    var isPrepared by remember { mutableStateOf(false) }

    var actualVideoWidth by remember { mutableIntStateOf(if (photoWidth > 0) photoWidth else 0) }
    var actualVideoHeight by remember { mutableIntStateOf(if (photoHeight > 0) photoHeight else 0) }

    // 矩阵变换：按视频原始比例居中缩放，杜绝拉伸铺满全屏
    fun updateTextureTransform(tv: TextureView, vWidth: Int, vHeight: Int) {
        val viewWidth = tv.width
        val viewHeight = tv.height
        if (viewWidth <= 0 || viewHeight <= 0 || vWidth <= 0 || vHeight <= 0) return

        val viewRatio = viewWidth.toFloat() / viewHeight.toFloat()
        val videoRatio = vWidth.toFloat() / vHeight.toFloat()

        val sx: Float
        val sy: Float

        if (scaleType == MotionScaleType.FIT) {
            // FIT: 完整包含在视图内，居中，等比缩放
            if (viewRatio > videoRatio) {
                sx = (viewHeight * videoRatio) / viewWidth
                sy = 1.0f
            } else {
                sx = 1.0f
                sy = (viewWidth / videoRatio) / viewHeight
            }
        } else {
            // CROP: 撑满视图并居中裁剪超出部分
            if (viewRatio > videoRatio) {
                sx = 1.0f
                sy = (viewWidth / videoRatio) / viewHeight
            } else {
                sx = (viewHeight * videoRatio) / viewWidth
                sy = 1.0f
            }
        }

        val matrix = Matrix()
        matrix.setScale(sx, sy, viewWidth / 2f, viewHeight / 2f)
        tv.setTransform(matrix)
    }

    LaunchedEffect(surface, imageUri, videoOffset, videoLength) {
        val targetSurface = surface ?: return@LaunchedEffect
        if (videoOffset <= 0 || videoLength <= 0) return@LaunchedEffect

        mediaPlayer.reset()
        isPrepared = false
        dataSource?.close()

        val source = MotionPhotoDataSource(
            context = context,
            uri = imageUri,
            videoStart = videoOffset,
            videoLength = videoLength
        )
        dataSource = source

        if (!source.isValid) return@LaunchedEffect

        try {
            mediaPlayer.setDataSource(source)
            mediaPlayer.setSurface(targetSurface)
            // 绝不循环播放：点击一次只播放一遍
            mediaPlayer.isLooping = false
            val vol = if (playAudio) 1.0f else 0f
            mediaPlayer.setVolume(vol, vol)

            mediaPlayer.setOnVideoSizeChangedListener { _, w, h ->
                if (w > 0 && h > 0) {
                    actualVideoWidth = w
                    actualVideoHeight = h
                    textureViewRef?.let { updateTextureTransform(it, w, h) }
                }
            }

            mediaPlayer.setOnCompletionListener {
                try {
                    mediaPlayer.seekTo(0)
                } catch (_: Exception) { }
                currentOnComplete()
            }

            mediaPlayer.setOnPreparedListener { player ->
                isPrepared = true
                val vw = if (player.videoWidth > 0) player.videoWidth else actualVideoWidth
                val vh = if (player.videoHeight > 0) player.videoHeight else actualVideoHeight
                if (vw > 0 && vh > 0) {
                    actualVideoWidth = vw
                    actualVideoHeight = vh
                    textureViewRef?.let { updateTextureTransform(it, vw, vh) }
                }
                // LaunchedEffect(isPlaying, isPrepared) is the sole playback owner.
            }

            mediaPlayer.setOnErrorListener { _, _, _ -> true }
            mediaPlayer.prepareAsync()
        } catch (_: Exception) { }
    }

    LaunchedEffect(isPlaying, isPrepared) {
        if (!isPrepared) return@LaunchedEffect
        try {
            if (isPlaying) {
                textureViewRef?.let { tv ->
                    val vw = if (actualVideoWidth > 0) actualVideoWidth else photoWidth
                    val vh = if (actualVideoHeight > 0) actualVideoHeight else photoHeight
                    if (vw > 0 && vh > 0) {
                        updateTextureTransform(tv, vw, vh)
                    }
                }
                mediaPlayer.seekTo(0)
                mediaPlayer.start()
            } else {
                if (mediaPlayer.isPlaying) {
                    mediaPlayer.pause()
                }
                mediaPlayer.seekTo(0)
            }
        } catch (_: Exception) { }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                mediaPlayer.reset()
                mediaPlayer.release()
                dataSource?.close()
                dataSource = null
                surface?.release()
                surface = null
                textureViewRef = null
            } catch (_: Exception) { }
        }
    }

    // 播放激活时平滑淡入，未播放时完全透明以展现高保真原图
    val playerAlpha by animateFloatAsState(
        targetValue = if (isPlaying && isPrepared) 1f else 0f,
        animationSpec = tween(durationMillis = 150),
        label = "motion_player_alpha"
    )

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = playerAlpha },
            factory = { ctx ->
                TextureView(ctx).apply {
                    isOpaque = false
                    textureViewRef = this
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                            surface = Surface(st)
                            val vw = if (actualVideoWidth > 0) actualVideoWidth else photoWidth
                            val vh = if (actualVideoHeight > 0) actualVideoHeight else photoHeight
                            if (vw > 0 && vh > 0) {
                                updateTextureTransform(this@apply, vw, vh)
                            }
                        }

                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                            val vw = if (actualVideoWidth > 0) actualVideoWidth else photoWidth
                            val vh = if (actualVideoHeight > 0) actualVideoHeight else photoHeight
                            if (vw > 0 && vh > 0) {
                                updateTextureTransform(this@apply, vw, vh)
                            }
                        }

                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                            surface?.release()
                            surface = null
                            textureViewRef = null
                            return true
                        }

                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                    }
                }
            }
        )
    }
}

private class MotionPhotoDataSource(
    context: Context,
    uri: Uri,
    private val videoStart: Long,
    private val videoLength: Long
) : MediaDataSource() {
    private val parcelFd = try {
        context.contentResolver.openFileDescriptor(uri, "r")
    } catch (_: Exception) { null }

    private val channel = parcelFd?.let { FileInputStream(it.fileDescriptor).channel }
    val isValid: Boolean = channel != null

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= videoLength) return -1
        val available = min(size.toLong(), videoLength - position).toInt()
        val byteBuffer = ByteBuffer.wrap(buffer, offset, available)
        val actualPos = videoStart + position
        return channel?.read(byteBuffer, actualPos) ?: -1
    }

    override fun getSize(): Long = videoLength

    override fun close() {
        try {
            channel?.close()
            parcelFd?.close()
        } catch (_: Exception) { }
    }
}
