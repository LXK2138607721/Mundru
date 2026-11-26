package com.lumina.cleaner.ui.viewer

import kotlin.math.max
import kotlin.math.min

internal data class ImageFocus(val x: Float, val y: Float)

internal data class PhotoViewerStartGeometry(
    val fitWidth: Float,
    val fitHeight: Float,
    val startX: Float,
    val startY: Float,
    val startScale: Float,
    val startLocalClipWidth: Float,
    val startLocalClipHeight: Float,
    val hasSourceBounds: Boolean
)

/**
 * 大图查看器与主舞台卡片之间的精确共享几何计算器：
 * - 统一以真实显示宽高比计算全屏居中图框 (fitWidth × fitHeight) 与源卡覆盖缩放比；
 * - 对 9:16 ~ 3.2:1 内所有纵图与横向长图实现双轴等比零裁切衔接，对超长纵图/超宽全景同步插值局部裁切框与圆角。
 */
internal object PhotoViewerTransitionGeometry {
    fun compute(
        viewportWidth: Float,
        viewportHeight: Float,
        imageAspectRatio: Float,
        sourceLeft: Float,
        sourceTop: Float,
        sourceWidth: Float,
        sourceHeight: Float,
        hasSourceBounds: Boolean
    ): PhotoViewerStartGeometry {
        val vw = viewportWidth.coerceAtLeast(1f)
        val vh = viewportHeight.coerceAtLeast(1f)
        val safeRatio = imageAspectRatio.takeIf { it.isFinite() && it > 0f }?.coerceIn(0.15f, 6f) ?: (3f / 4f)
        val fitW = if (vw / vh > safeRatio) vh * safeRatio else vw
        val fitH = fitW / safeRatio

        if (!hasSourceBounds || sourceWidth <= 1f || sourceHeight <= 1f) {
            return PhotoViewerStartGeometry(
                fitWidth = fitW,
                fitHeight = fitH,
                startX = 0f,
                startY = 0f,
                startScale = 0.72f,
                startLocalClipWidth = fitW,
                startLocalClipHeight = fitH,
                hasSourceBounds = false
            )
        }

        val scaleW = sourceWidth / fitW
        val scaleH = sourceHeight / fitH
        val startScale = max(scaleW, scaleH).coerceIn(0.15f, 1.25f)
        val startX = (sourceLeft + sourceWidth * 0.5f) - vw * 0.5f
        val startY = (sourceTop + sourceHeight * 0.5f) - vh * 0.5f
        val localClipW = (sourceWidth / startScale).coerceIn(1f, fitW)
        val localClipH = (sourceHeight / startScale).coerceIn(1f, fitH)

        return PhotoViewerStartGeometry(
            fitWidth = fitW,
            fitHeight = fitH,
            startX = startX,
            startY = startY,
            startScale = startScale,
            startLocalClipWidth = localClipW,
            startLocalClipHeight = localClipH,
            hasSourceBounds = true
        )
    }

    fun localClipFrame(
        geometry: PhotoViewerStartGeometry,
        clampedProgress: Float,
        cornerProgress: Float,
        currentScale: Float,
        cardCornerRadiusPx: Float
    ): Triple<Float, Float, Float> {
        val p = clampedProgress.coerceIn(0f, 1f)
        val clipW = geometry.startLocalClipWidth + (geometry.fitWidth - geometry.startLocalClipWidth) * p
        val clipH = geometry.startLocalClipHeight + (geometry.fitHeight - geometry.startLocalClipHeight) * p
        val safeScale = currentScale.coerceAtLeast(0.1f)
        val localCorner = (cardCornerRadiusPx * cornerProgress.coerceIn(0f, 1f)) / safeScale
        return Triple(clipW, clipH, localCorner)
    }
}

/** One atomic viewport value, shared by gesture updates and the single zoom animation. */
internal data class PhotoTransform(
    val scale: Float = 1f,
    val x: Float = 0f,
    val y: Float = 0f
) {
    fun transformed(zoom: Float, panX: Float, panY: Float, width: Int, height: Int): PhotoTransform {
        val newScale = (scale * zoom).coerceIn(1f, 5f)
        val maxX = width * (newScale - 1f) / 2f
        val maxY = height * (newScale - 1f) / 2f
        return PhotoTransform(newScale, (x + panX).coerceIn(-maxX, maxX), (y + panY).coerceIn(-maxY, maxY))
    }

    fun interpolate(target: PhotoTransform, fraction: Float) = PhotoTransform(
        scale + (target.scale - scale) * fraction,
        x + (target.x - x) * fraction,
        y + (target.y - y) * fraction
    )

    companion object {
        fun imageFocusFromCrop(x: Float, y: Float, aspectRatio: Float, width: Int, height: Int): ImageFocus {
            val imageWidth = max(width.toFloat(), height * aspectRatio)
            val imageHeight = imageWidth / aspectRatio
            return ImageFocus(
                ((x + (imageWidth - width) / 2f) / imageWidth).coerceIn(0f, 1f),
                ((y + (imageHeight - height) / 2f) / imageHeight).coerceIn(0f, 1f)
            )
        }

        fun zoomedAtImageFocus(focusX: Float, focusY: Float, aspectRatio: Float, width: Int, height: Int): PhotoTransform {
            val imageWidth = min(width.toFloat(), height * aspectRatio)
            val imageHeight = imageWidth / aspectRatio
            return PhotoTransform(
                scale = 2.5f,
                x = (0.5f - focusX.coerceIn(0f, 1f)) * imageWidth * 1.5f,
                y = (0.5f - focusY.coerceIn(0f, 1f)) * imageHeight * 1.5f
            )
        }

        fun doubleTapTarget(requestedScale: Float, focusX: Float, focusY: Float, width: Int, height: Int): PhotoTransform =
            if (requestedScale > 1.2f) PhotoTransform() else zoomedAt(focusX, focusY, width, height)

        fun zoomedAt(focusX: Float, focusY: Float, width: Int, height: Int): PhotoTransform = PhotoTransform(
            scale = 2.5f,
            x = (0.5f - focusX.coerceIn(0f, 1f)) * width * 1.5f,
            y = (0.5f - focusY.coerceIn(0f, 1f)) * height * 1.5f
        )
    }
}
