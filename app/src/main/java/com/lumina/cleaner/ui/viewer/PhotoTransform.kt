package com.lumina.cleaner.ui.viewer

import kotlin.math.max
import kotlin.math.min

internal data class ImageFocus(val x: Float, val y: Float)

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
