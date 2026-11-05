package com.lumina.cleaner.ui.deck

import org.junit.Assert.*
import org.junit.Test

class DeckBlurCacheTest {
    @Test
    fun `exact repeats reuse the same effect but even a subpixel change takes effect`() {
        val created = mutableListOf<Float>()
        val cache = DeckBlurCache { px -> created.add(px); Any() }
        val original = cache.at(12f)
        repeat(119) { assertSame(original, cache.at(12f)) }
        assertNotSame(original, cache.at(12.001f))
        assertNull(cache.at(0f))
        assertNull(cache.at(0f))
        assertNotSame(original, cache.at(12f))
        assertEquals(listOf(12f, 12.001f, 12f), created)
    }
}
