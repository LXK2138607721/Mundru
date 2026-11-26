package com.lumina.cleaner.ui.viewer

import com.lumina.cleaner.core.model.resolveDisplayDimensions
import com.lumina.cleaner.ui.deck.deckCardAspectRatio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LandscapeCardAndViewerGeometryTest {

    @Test
    fun resolveDisplayDimensions_swapsWidthAndHeightFor90And270ExifRotations() {
        // 典型 EXIF 旋转后为横图的 JPEG：像素缓冲 1080x2280 + 旋转 90° / 270° -> 实际横向显示 2280x1080
        assertEquals(2280 to 1080, resolveDisplayDimensions(1080, 2280, 90))
        assertEquals(2280 to 1080, resolveDisplayDimensions(1080, 2280, 270))
        assertEquals(2280 to 1080, resolveDisplayDimensions(1080, 2280, -90))
        assertEquals(2280 to 1080, resolveDisplayDimensions(1080, 2280, 450))

        // 0° / 180° 保持原始方向
        assertEquals(2280 to 1080, resolveDisplayDimensions(2280, 1080, 0))
        assertEquals(2280 to 1080, resolveDisplayDimensions(2280, 1080, 180))
        assertEquals(1080 to 1920, resolveDisplayDimensions(1080, 1920, 0))

        // 非法或未记录尺寸安全透传
        assertEquals(0 to 0, resolveDisplayDimensions(0, 0, 90))
        assertEquals(-1 to 100, resolveDisplayDimensions(-1, 100, 90))
    }

    @Test
    fun deckCardAspectRatio_preservesWideLandscapeAndNormalPortraitWithoutCropping() {
        // 约 2.1:1 横向长图（如 2280x1080 游戏截图）必须原比例展示，不再强行截断成 16:9
        val wide21 = 2280f / 1080f
        assertEquals(wide21, deckCardAspectRatio(wide21), 0.0001f)
        assertEquals(2.1f, deckCardAspectRatio(2.1f), 0.0001f)
        assertEquals(20f / 9f, deckCardAspectRatio(20f / 9f), 0.0001f)
        assertEquals(21f / 9f, deckCardAspectRatio(21f / 9f), 0.0001f)

        // 常规横/方/纵图保持精确比例
        assertEquals(16f / 9f, deckCardAspectRatio(16f / 9f), 0.0001f)
        assertEquals(4f / 3f, deckCardAspectRatio(4f / 3f), 0.0001f)
        assertEquals(1f, deckCardAspectRatio(1f), 0.0001f)
        assertEquals(3f / 4f, deckCardAspectRatio(3f / 4f), 0.0001f)
        assertEquals(9f / 16f, deckCardAspectRatio(9f / 16f), 0.0001f)

        // 极端超长竖图与超宽全景条受安全上下限约束
        assertEquals(9f / 16f, deckCardAspectRatio(9f / 20f), 0.0001f)
        assertEquals(3.2f, deckCardAspectRatio(5.0f), 0.0001f)

        // 异常比例回退至 3:4
        assertEquals(3f / 4f, deckCardAspectRatio(0f), 0.0001f)
        assertEquals(3f / 4f, deckCardAspectRatio(-2f), 0.0001f)
        assertEquals(3f / 4f, deckCardAspectRatio(Float.NaN), 0.0001f)
        assertEquals(3f / 4f, deckCardAspectRatio(Float.POSITIVE_INFINITY), 0.0001f)
    }

    @Test
    fun viewerTransitionGeometry_wideLandscapeShrinksContinuouslyIntoExactCardBounds() {
        val vw = 1224f
        val vh = 2664f
        val wideRatio = 2280f / 1080f
        val cardW = 1080f
        val cardH = cardW / deckCardAspectRatio(wideRatio)
        val cardLeft = (vw - cardW) * 0.5f
        val cardTop = 1220f
        val cardCornerPx = 72f // 24dp @ 3x

        val geom = PhotoViewerTransitionGeometry.compute(
            viewportWidth = vw,
            viewportHeight = vh,
            imageAspectRatio = wideRatio,
            sourceLeft = cardLeft,
            sourceTop = cardTop,
            sourceWidth = cardW,
            sourceHeight = cardH,
            hasSourceBounds = true
        )

        assertTrue(geom.hasSourceBounds)
        assertEquals(vw, geom.fitWidth, 0.01f)
        assertEquals(vw / wideRatio, geom.fitHeight, 0.01f)
        assertEquals(cardW / vw, geom.startScale, 0.001f)
        assertEquals(0f, geom.startX, 0.01f)
        assertEquals((cardTop + cardH * 0.5f) - vh * 0.5f, geom.startY, 0.01f)

        // 退场终点 p=0：视觉缩放后裁切矩形与源卡宽高像素级重合，视觉圆角精确还原 24dp
        val (clipW0, clipH0, corner0) = PhotoViewerTransitionGeometry.localClipFrame(
            geometry = geom,
            clampedProgress = 0f,
            cornerProgress = 1f,
            currentScale = geom.startScale,
            cardCornerRadiusPx = cardCornerPx
        )
        assertEquals(cardW, clipW0 * geom.startScale, 0.5f)
        assertEquals(cardH, clipH0 * geom.startScale, 0.5f)
        assertEquals(cardCornerPx, corner0 * geom.startScale, 0.5f)

        // 退场中段 p=0.5：连续过渡，缩放与圆角位于卡片态与全屏态之间
        val midScale = geom.startScale + (1f - geom.startScale) * 0.5f
        val (clipWMid, clipHMid, cornerMid) = PhotoViewerTransitionGeometry.localClipFrame(
            geometry = geom,
            clampedProgress = 0.5f,
            cornerProgress = 0.5f,
            currentScale = midScale,
            cardCornerRadiusPx = cardCornerPx
        )
        assertTrue(clipWMid * midScale > cardW && clipWMid * midScale < geom.fitWidth)
        assertTrue(clipHMid * midScale > cardH && clipHMid * midScale < geom.fitHeight)
        assertEquals(cardCornerPx * 0.5f, cornerMid * midScale, 0.5f)

        // 全屏态 p=1：恢复无圆角全宽适配框
        val (clipW1, clipH1, corner1) = PhotoViewerTransitionGeometry.localClipFrame(
            geometry = geom,
            clampedProgress = 1f,
            cornerProgress = 0f,
            currentScale = 1f,
            cardCornerRadiusPx = cardCornerPx
        )
        assertEquals(geom.fitWidth, clipW1, 0.01f)
        assertEquals(geom.fitHeight, clipH1, 0.01f)
        assertEquals(0f, corner1, 0.01f)
    }

    @Test
    fun viewerTransitionGeometry_exifRotatedLandscapeAndPortraitMatchCardBounds() {
        val vw = 1224f
        val vh = 2664f

        // 1. EXIF 旋转后为横图的 JPEG（原始 1080x2280, orientation=90°）
        val (rotW, rotH) = resolveDisplayDimensions(1080, 2280, 90)
        val rotRatio = rotW.toFloat() / rotH.toFloat()
        val rotCardRatio = deckCardAspectRatio(rotRatio)
        assertEquals(rotRatio, rotCardRatio, 0.0001f)
        val rotGeom = PhotoViewerTransitionGeometry.compute(
            viewportWidth = vw,
            viewportHeight = vh,
            imageAspectRatio = rotRatio,
            sourceLeft = 72f,
            sourceTop = 1200f,
            sourceWidth = 1080f,
            sourceHeight = 1080f / rotCardRatio,
            hasSourceBounds = true
        )
        assertEquals(1080f, rotGeom.startLocalClipWidth * rotGeom.startScale, 0.5f)
        assertEquals(1080f / rotCardRatio, rotGeom.startLocalClipHeight * rotGeom.startScale, 0.5f)

        // 2. 普通 3:4 纵图：保留原有无裁切缩回行为
        val portraitRatio = 3f / 4f
        val portraitGeom = PhotoViewerTransitionGeometry.compute(
            viewportWidth = vw,
            viewportHeight = vh,
            imageAspectRatio = portraitRatio,
            sourceLeft = 72f,
            sourceTop = 640f,
            sourceWidth = 1080f,
            sourceHeight = 1440f,
            hasSourceBounds = true
        )
        assertEquals(1080f, portraitGeom.startLocalClipWidth * portraitGeom.startScale, 0.5f)
        assertEquals(1440f, portraitGeom.startLocalClipHeight * portraitGeom.startScale, 0.5f)

        // 3. 超长 9:20 纵图（卡片压限在 9:16）：p=0 裁切框严格收敛回 9:16 卡片尺寸
        val tallRatio = 9f / 20f
        val tallGeom = PhotoViewerTransitionGeometry.compute(
            viewportWidth = vw,
            viewportHeight = vh,
            imageAspectRatio = tallRatio,
            sourceLeft = 72f,
            sourceTop = 400f,
            sourceWidth = 1080f,
            sourceHeight = 1920f,
            hasSourceBounds = true
        )
        assertEquals(1080f, tallGeom.startLocalClipWidth * tallGeom.startScale, 0.5f)
        assertEquals(1920f, tallGeom.startLocalClipHeight * tallGeom.startScale, 0.5f)

        // 4. 无源卡坐标兜底（如回收站网格点开）
        val fallback = PhotoViewerTransitionGeometry.compute(vw, vh, rotRatio, 0f, 0f, 0f, 0f, false)
        assertFalse(fallback.hasSourceBounds)
        assertEquals(0.72f, fallback.startScale, 0.001f)
    }
}
