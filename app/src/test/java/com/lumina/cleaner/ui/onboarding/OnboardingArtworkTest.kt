package com.lumina.cleaner.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class OnboardingArtworkTest {

    @Test
    fun demoCardAt_wrapsInfinitelyInBothDirections() {
        val zero = demoCardAt(0)
        val prev = demoCardAt(-1)
        val next = demoCardAt(1)

        assertEquals(0, zero.serial)
        assertEquals(-1, prev.serial)
        assertEquals(1, next.serial)
        assertNotEquals(zero.title, prev.title)
        assertNotEquals(zero.title, next.title)

        // 每 6 张一个完整循环周期，负向与正向严格无缝衔接
        assertEquals(zero.title, demoCardAt(6).title)
        assertEquals(zero.title, demoCardAt(-6).title)
        assertEquals(prev.title, demoCardAt(-7).title)
        assertEquals(prev.title, demoCardAt(5).title)
    }
}
