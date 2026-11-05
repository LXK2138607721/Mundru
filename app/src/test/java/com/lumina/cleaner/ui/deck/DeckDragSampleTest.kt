package com.lumina.cleaner.ui.deck

import androidx.compose.ui.geometry.Offset
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Test

class DeckDragSampleTest {
    private fun rotate(p: Offset, radians: Float) = Offset(
        p.x * cos(radians) - p.y * sin(radians),
        p.x * sin(radians) + p.y * cos(radians)
    )

    private fun checkPoint(expected: Offset, actual: Offset) {
        assertEquals(expected.x, actual.x, 0.0002f)
        assertEquals(expected.y, actual.y, 0.0002f)
    }

    @Test
    fun `steady finger motion stays steady as the card moves and rotates underneath it`() {
        val delta = Offset(2.5f, -0.75f)
        for (i in 1..60) {
            val root = Offset(50f, 80f) + delta * i.toFloat()
            val translation = Offset(i * 2.1f, i * -0.3f)
            val angle = i * 0.004f
            val local = rotate(root - translation, -angle)
            val sample = deckDragSample(local, rotate(delta, -angle)) { rotate(it, angle) + translation }
            checkPoint(root, sample.position)
            checkPoint(delta, sample.delta)
        }
    }

    @Test
    fun `the first event uses only the delta beyond slop and still fingers add no drift`() {
        val pos = Offset(90f, 60f)
        val angle = 0.23f
        val overSlop = Offset(0.25f, -0.125f)
        val toRoot = { p: Offset -> rotate(p, angle) + Offset(26f, -3f) }
        checkPoint(rotate(overSlop, angle), deckDragSample(pos, overSlop, toRoot).delta)
        checkPoint(Offset.Zero, deckDragSample(pos, Offset.Zero, toRoot).delta)
    }
}
