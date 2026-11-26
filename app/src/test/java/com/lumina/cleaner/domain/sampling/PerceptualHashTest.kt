package com.lumina.cleaner.domain.sampling

import android.net.Uri
import com.lumina.cleaner.core.model.PhotoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

class PerceptualHashTest {

    private val uri = mock(Uri::class.java)

    private fun photo(id: Long, taken: Long, bucket: String = "Camera") = PhotoItem(
        id = id,
        uri = uri,
        displayName = "IMG_$id.jpg",
        dateModified = taken,
        dateTaken = taken,
        size = 2048,
        width = 1000,
        height = 1000,
        bucketName = bucket
    )

    @Test
    fun `identical horizontal gradient produces zero hamming distance and inverted gradient produces 64`() {
        val leftToRightBright = IntArray(PerceptualHash.GRID_WIDTH * PerceptualHash.GRID_HEIGHT) { idx ->
            val x = idx % PerceptualHash.GRID_WIDTH
            250 - x * 25
        }
        val rightToLeftBright = IntArray(PerceptualHash.GRID_WIDTH * PerceptualHash.GRID_HEIGHT) { idx ->
            val x = idx % PerceptualHash.GRID_WIDTH
            x * 25
        }

        val h1 = PerceptualHash.computeFromLumaGrid(leftToRightBright)
        val h2 = PerceptualHash.computeFromLumaGrid(leftToRightBright.copyOf())
        val hInv = PerceptualHash.computeFromLumaGrid(rightToLeftBright)

        assertEquals(0, PerceptualHash.hammingDistance(h1, h2))
        assertTrue(PerceptualHash.isVisuallySimilar(h1, h2))
        assertEquals(64, PerceptualHash.hammingDistance(h1, hInv))
        assertFalse(PerceptualHash.isVisuallySimilar(h1, hInv))
    }

    @Test
    fun `slight brightness shift preserves perceptual hash similarity`() {
        val base = IntArray(PerceptualHash.GRID_WIDTH * PerceptualHash.GRID_HEIGHT) { idx ->
            val x = idx % PerceptualHash.GRID_WIDTH
            val y = idx / PerceptualHash.GRID_WIDTH
            ((x * 19 + y * 13) % 200) + 20
        }
        val shifted = IntArray(base.size) { i -> (base[i] + 15).coerceAtMost(255) }

        val hBase = PerceptualHash.computeFromLumaGrid(base)
        val hShifted = PerceptualHash.computeFromLumaGrid(shifted)

        assertEquals(0, PerceptualHash.hammingDistance(hBase, hShifted))
        assertTrue(PerceptualHash.isVisuallySimilar(hBase, hShifted))
    }

    @Test
    fun `findTimeNeighborCandidates selects only unhashed photos with 30s same-bucket neighbors`() {
        val baseTime = 1_700_000_000_000L
        val photos = listOf(
            photo(1, baseTime, "Camera"),
            photo(2, baseTime + 4_000L, "Camera"),      // 4s neighbor of 1
            photo(3, baseTime + 600_000L, "Camera"),    // isolated (10 min later)
            photo(4, baseTime + 5_000L, "Screenshots"), // different bucket, isolated
            photo(5, baseTime + 20_000L, "Camera")      // 16s neighbor of 2 (already cached below)
        )
        val existing = mapOf(5L to 0x1234L)

        val candidates = PerceptualHash.findTimeNeighborCandidates(photos, existing, maxCandidates = 10)
        assertEquals(setOf(1L, 2L), candidates.map { it.id }.toSet())
    }
}
