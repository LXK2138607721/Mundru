package com.lumina.cleaner.ui.viewer

import org.junit.Assert.*
import org.junit.Test

class PhotoTransformTest {
    @Test fun `double tap focuses center or tapped corner at 2 point 5 times`() {
        assertEquals(PhotoTransform(2.5f), PhotoTransform.zoomedAt(0.5f, 0.5f, 1000, 2000))
        assertEquals(PhotoTransform(2.5f, 750f, 1500f), PhotoTransform.zoomedAt(0f, 0f, 1000, 2000))
        assertEquals(PhotoTransform(2.5f, -750f, -1500f), PhotoTransform.zoomedAt(1f, 1f, 1000, 2000))
    }

    @Test fun `pinch clamps scale and pan and fully resets at 1x`() {
        assertEquals(PhotoTransform(5f, 2000f, -4000f), PhotoTransform(2f).transformed(10f, 9999f, -9999f, 1000, 2000))
        assertEquals(PhotoTransform(), PhotoTransform(2.5f, 100f, 100f).transformed(0.1f, 30f, 40f, 1000, 2000))
    }

    @Test fun `animation interpolates scale and both axes together`() {
        val start = PhotoTransform()
        val end = PhotoTransform(2.5f, 300f, -600f)
        assertEquals(start, start.interpolate(end, 0f))
        assertEquals(PhotoTransform(1.75f, 150f, -300f), start.interpolate(end, 0.5f))
        assertEquals(end, start.interpolate(end, 1f))
    }

    @Test fun `replacement animation starts from current in flight transform`() {
        val halfway = PhotoTransform().interpolate(PhotoTransform(2.5f, 300f, 600f), 0.5f)
        assertEquals(halfway, halfway.interpolate(PhotoTransform(), 0f))
        assertEquals(PhotoTransform(), halfway.interpolate(PhotoTransform(), 1f))
    }

    @Test fun `rapid double taps toggle requested destination not a near 1x animation frame`() {
        val zoomIn = PhotoTransform.doubleTapTarget(1f, 0.5f, 0.5f, 1000, 2000)
        val earlyFrame = PhotoTransform().interpolate(zoomIn, 0.01f)
        assertTrue(earlyFrame.scale < 1.2f)
        val zoomOut = PhotoTransform.doubleTapTarget(zoomIn.scale, 0.5f, 0.5f, 1000, 2000)
        assertEquals(PhotoTransform(), zoomOut)
        assertEquals(zoomIn, PhotoTransform.doubleTapTarget(zoomOut.scale, 0.5f, 0.5f, 1000, 2000))
    }

    @Test fun `square card focus maps through Fit letterboxing without doubling translation`() {
        val focus = PhotoTransform.imageFocusFromCrop(500f, 100f, 1f, 1000, 1000)
        val target = PhotoTransform.zoomedAtImageFocus(focus.x, focus.y, 1f, 1000, 2000)
        assertEquals(0.5f, focus.x, 0.0001f)
        assertEquals(0.1f, focus.y, 0.0001f)
        assertEquals(600f, target.y, 0.001f)
        assertEquals(600f, (600f - 1000f) * target.scale + target.y + 1000f, 0.001f)
    }

    @Test fun `landscape Crop edges are converted to original image coordinates`() {
        val focus = PhotoTransform.imageFocusFromCrop(0f, 300f, 2f, 1000, 600)
        assertEquals(1f / 12f, focus.x, 0.0001f)
        assertEquals(0.5f, focus.y, 0.0001f)
        val target = PhotoTransform.zoomedAtImageFocus(focus.x, focus.y, 2f, 1000, 2000)
        assertEquals(625f, target.x, 0.001f)
        assertEquals(0f, target.y, 0.001f)
    }

    @Test fun `tall photo Crop top does not mean original image top`() {
        val focus = PhotoTransform.imageFocusFromCrop(500f, 0f, 0.2f, 1000, 2000)
        assertEquals(0.5f, focus.x, 0.0001f)
        assertEquals(0.3f, focus.y, 0.0001f)
        val target = PhotoTransform.zoomedAtImageFocus(focus.x, focus.y, 0.2f, 1000, 2000)
        assertEquals(0f, target.x, 0.001f)
        assertEquals(600f, target.y, 0.001f)
    }

    @Test fun `mapped pixel stays anchored for portrait landscape square and extreme ratios`() {
        for (ratio in listOf(0.2f, 0.6f, 1f, 1.3f, 2f, 3f)) {
            for ((x, y) in listOf(0f to 0f, 1f to 1f, 0.5f to 0.5f, 0.1f to 0.8f)) {
                val focus = PhotoTransform.imageFocusFromCrop(x * 900f, y * 1200f, ratio, 900, 1200)
                val target = PhotoTransform.zoomedAtImageFocus(focus.x, focus.y, ratio, 1000, 2000)
                val fitWidth = minOf(1000f, 2000f * ratio)
                val fitHeight = fitWidth / ratio
                val pixelX = (1000f - fitWidth) / 2f + focus.x * fitWidth
                val pixelY = (2000f - fitHeight) / 2f + focus.y * fitHeight
                assertEquals(pixelX, (pixelX - 500f) * target.scale + target.x + 500f, 0.002f)
                assertEquals(pixelY, (pixelY - 1000f) * target.scale + target.y + 1000f, 0.002f)
            }
        }
    }
}
