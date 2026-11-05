package com.lumina.cleaner.ui.deck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DeckReturnModelTest {
    @Test
    fun `return starts at displayed position including diagonal drag and residual depth`() {
        for (x in listOf(-45f, 0f, 180f, 1200f)) {
            val start = DeckReturnStart(x, -63f, 0.42f)
            val frame = DeckReturnModel.currentCard(0f, start, 3f)
            assertEquals(1f, frame.scale, 0.0001f)
            assertEquals(x, frame.x, 0.0001f)
            assertEquals(start.y, frame.y, 0.0001f)
            assertEquals(if (x < 0f) x * 0.035f else x * 0.012f, frame.rotation, 0.0001f)
            assertEquals(0f, frame.blurDp, 0.0001f)
            assertEquals(0f, frame.scrimAlpha, 0.0001f)
            assertEquals(DeckDepthModel.interpolateSecondLayer(start.depth), DeckReturnModel.nextLayer(0f, start.depth))
            assertEquals(1f, DeckReturnModel.thirdAlpha(0f), 0.0001f)
            assertEquals(-960f + x, DeckReturnModel.previousX(0f, -960f + x), 0.0001f)
            assertEquals(if (x > 0f) 1f else 0.001f, DeckReturnModel.previousAlpha(0f, start), 0.0001f)
        }
    }

    @Test
    fun `current card shrinks then tucks then expands without leaving the deck`() {
        val start = DeckReturnStart(180f, -40f, 0f)
        val samples = (0..100).map { DeckReturnModel.currentCard(it / 100f, start, 2f) }
        for (i in 1..100) {
            assertTrue("Position must keep moving at sample $i", samples[i].x < samples[i - 1].x)
            if (i <= 20 || i > 60) assertTrue(samples[i].scale < samples[i - 1].scale)
            else assertTrue(samples[i].scale > samples[i - 1].scale)
            assertEquals(samples[i].blurDp, DeckReturnModel.blurDp(i / 100f), 0.0001f)
            assertEquals(samples[i].scrimAlpha, DeckReturnModel.scrimAlpha(i / 100f), 0.0001f)
        }
        assertEquals(DeckReturnModel.MIN_SCALE, samples[20].scale, 0.0001f)
        assertEquals(DeckReturnModel.PEAK_SCALE, samples[60].scale, 0.0001f)
    }

    @Test
    fun `drag shrinks with rightward travel and restores as the finger pulls back`() {
        val forward = (0..160).map { DeckReturnModel.dragScale(it.toFloat(), 0f, 100f) }
        assertEquals(1f, forward.first(), 0f)
        assertEquals(0.66f, forward.last(), 0.0001f)
        assertTrue(forward.zipWithNext().all { (a,b) -> a > b })
        assertEquals(forward.reversed(), (160 downTo 0).map { DeckReturnModel.dragScale(it.toFloat(), 0f, 100f) })
        assertEquals(1f, DeckReturnModel.dragScale(-60f, 0f, 100f), 0f)
        assertEquals(1f, DeckReturnModel.dragScale(20f, -160f, 100f), 0f)
    }

    @Test
    fun `quick releases inherit displayed scale and stay within a single bounded overshoot`() {
        for (x in listOf(1f, 32f, 84f, 248f)) for (vx in listOf(-500f, 200f, 4000f)) {
            val start = DeckReturnModel.capture(x, -11f, 0.25f, vx, -40f, 100f)
            val scales = (0..100).map { DeckReturnModel.currentCard(it/100f, start, 2f).scale }
            assertEquals(DeckReturnModel.dragScale(x, -11f, 100f), scales.first(), 0.0001f)
            assertEquals(0.60f, scales.min(), 0.0001f)
            assertEquals(1.035f, scales.max(), 0.0001f)
            assertEquals(0.95f, scales.last(), 0.0001f)
        }
    }

    @Test
    fun `release carries measured velocity and incoming card never overshoots`() {
        val start = DeckReturnStart(60f, -10f, 0f, 220f, -80f)
        val dt = 0.0001f
        val seconds = dt * DeckReturnModel.DURATION_MILLIS / 1000f
        val frame = DeckReturnModel.currentCard(dt, start, 2f)
        assertEquals(start.vx, (frame.x - start.x) / seconds, 1f)
        assertEquals(start.vy, (frame.y - start.y) / seconds, 1f)
        assertEquals(start.vx, (DeckReturnModel.previousX(dt, -300f, start.vx) + 300f) / seconds, 2f)
        for (x in listOf(-300f, -1f, 0f, 10f)) for (vx in listOf(-2000f, 0f, 5000f)) {
            val path = (0..100).map { DeckReturnModel.previousX(it / 100f, x, vx) }
            assertTrue(path.zipWithNext().all { (a, b) -> abs(b) <= abs(a) + 0.0001f && b * x >= 0f })
            assertEquals(0f, path.last(), 0f)
        }
    }

    @Test
    fun `all layer endpoints match their roles after index handoff`() {
        val second = DeckDepthModel.interpolateSecondLayer(0f)
        val third = DeckDepthModel.interpolateThirdLayer(0f)
        for (density in listOf(1f, 2.625f, 3f)) {
            for (depth in listOf(0f, 0.45f, 1f)) {
                val start = DeckReturnStart(230f, -67f, depth)
                val frame = DeckReturnModel.currentCard(1f, start, density)
                assertEquals(second.scale, frame.scale, 0.0001f)
                assertEquals(0f, frame.x, 0.0001f)
                assertEquals(second.offsetYDp * density, frame.y, 0.0001f)
                assertEquals(0f, frame.rotation, 0.0001f)
                assertEquals(second.blurDp, frame.blurDp, 0.0001f)
                assertEquals(second.scrimAlpha, frame.scrimAlpha, 0.0001f)
                assertEquals(third, DeckReturnModel.nextLayer(1f, depth))
                assertEquals(0f, DeckReturnModel.previousX(1f, -650f), 0.0001f)
                assertEquals(1f, DeckReturnModel.previousAlpha(1f, start), 0.0001f)
                assertEquals(0f, DeckReturnModel.previousShadowDp(1f, start), 0.0001f)
                assertEquals(0f, DeckReturnModel.thirdAlpha(1f), 0.0001f)
            }
        }
    }

    @Test
    fun `phase boundaries are continuous and progress is bounded`() {
        val start = DeckReturnStart(180f, -40f, 0.5f)
        for (boundary in listOf(DeckReturnModel.SMALLEST_AT, DeckReturnModel.PEAK_AT)) {
            val before = DeckReturnModel.currentCard(boundary - 0.00001f, start, 3f)
            val after = DeckReturnModel.currentCard(boundary + 0.00001f, start, 3f)
            assertTrue(abs(after.scale - before.scale) < 0.0001f)
            assertTrue(abs(after.x - before.x) < 0.01f)
            assertTrue(abs(after.y - before.y) < 0.01f)
            assertTrue(abs(after.blurDp - before.blurDp) < 0.01f)
        }
        assertEquals(DeckReturnModel.currentCard(0f, start, 3f), DeckReturnModel.currentCard(-1f, start, 3f))
        assertEquals(DeckReturnModel.currentCard(1f, start, 3f), DeckReturnModel.currentCard(2f, start, 3f))
    }

    @Test
    fun `underlying cards retreat and incoming card arrives monotonically`() {
        val start = DeckReturnStart(180f, 0f, 0.5f)
        for (i in 1..100) {
            val p = i / 100f
            val previousP = (i - 1) / 100f
            val next = DeckReturnModel.nextLayer(p, start.depth)
            val previous = DeckReturnModel.nextLayer(previousP, start.depth)
            assertTrue(next.scale <= previous.scale + 0.0001f)
            assertTrue(next.offsetYDp >= previous.offsetYDp - 0.0001f)
            assertTrue(next.blurDp >= previous.blurDp - 0.0001f)
            assertTrue(DeckReturnModel.thirdAlpha(p) <= DeckReturnModel.thirdAlpha(previousP) + 0.0001f)
            assertTrue(DeckReturnModel.previousX(p, -780f) >= DeckReturnModel.previousX(previousP, -780f) - 0.0001f)
            assertTrue(DeckReturnModel.previousShadowDp(p, start) <= DeckReturnModel.previousShadowDp(previousP, start) + 0.0001f)
        }
    }
}
