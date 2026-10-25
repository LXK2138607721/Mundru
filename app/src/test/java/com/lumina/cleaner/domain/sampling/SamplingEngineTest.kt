package com.lumina.cleaner.domain.sampling

import android.net.Uri
import com.lumina.cleaner.core.model.MonthBucket
import com.lumina.cleaner.core.model.PhotoItem
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock

class SamplingEngineTest {
    private val day = 86_400_000L
    private val now = time("2026-01-02")
    private val engine = SamplingEngine({ now }, Random(23))
    private val uri = mock(Uri::class.java)

    private fun time(date: String) = LocalDate.parse(date).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private fun photo(id: Long, taken: Long = now - 200 * day, modified: Long = taken, album: String = "Camera") =
        PhotoItem(id, uri, "IMG_$id.jpg", modified, taken, 1000, 100, 100, album)

    @Test fun `24 hour cooldown is strict and expires exactly at the boundary`() {
        val photos = (1L..4L).map { photo(it) }
        val history = mapOf(1L to now - 1, 2L to now - SamplingEngine.COOLDOWN_WINDOW_MS + 1,
            3L to now - SamplingEngine.COOLDOWN_WINDOW_MS)
        assertEquals(setOf(3L, 4L), engine.getStratifiedRandomBatch(photos, 15, history).map { it.id }.toSet())
    }

    @Test fun `cooling items never fill a short batch`() {
        val photos = (1L..20L).map { photo(it) }
        val history = (1L..19L).associateWith { now - 1000 }
        assertEquals(listOf(20L), engine.getStratifiedRandomBatch(photos, 15, history).map { it.id })
        assertTrue(engine.getStratifiedRandomBatch(photos, 15, photos.associate { it.id to now }).isEmpty())
    }

    @Test fun `future review timestamps remain cooling`() {
        assertTrue(engine.getStratifiedRandomBatch(listOf(photo(1)), 5, mapOf(1L to now + day)).isEmpty())
    }

    @Test fun `all modes reject nonpositive sizes and deduplicate ids`() {
        val photos = listOf(photo(1), photo(1), photo(2))
        for (size in listOf(-1, 0)) {
            assertTrue(engine.getStratifiedRandomBatch(photos, size, emptyMap()).isEmpty())
            assertTrue(engine.getTimeCapsuleBatch(photos, size).isEmpty())
            assertTrue(engine.getAlbumCleanupBatch(photos, size, "Camera").isEmpty())
            assertTrue(engine.getMonthCleanupBatch(photos, size, null).isEmpty())
        }
        assertEquals(2, engine.getStratifiedRandomBatch(photos, 15, emptyMap()).size)
        assertEquals(2, engine.getTimeCapsuleBatch(photos, 15).size)
        assertEquals(2, engine.getAlbumCleanupBatch(photos, 15, "Camera").size)
        assertEquals(2, engine.getMonthCleanupBatch(photos, 15, null).size)
    }

    @Test fun `strata cover the whole timeline even when pool barely exceeds batch`() {
        val photos = (0L..15L).map { photo(it, now - (1000 - it) * day) }
        repeat(20) {
            val ids = engine.getStratifiedRandomBatch(photos, 15, emptyMap()).map { it.id }
            assertEquals(15, ids.size)
            assertEquals(3, ids.count { it in 0L..2L })
            assertEquals(3, ids.count { it in 3L..5L })
            assertEquals(3, ids.count { it in 6L..8L })
            assertEquals(3, ids.count { it in 9L..11L })
            assertEquals(3, ids.count { it in 12L..15L })
        }
    }

    @Test fun `all pool and batch sizes return exactly min of unique candidates and requested size`() {
        val photos = (1L..100L).map { photo(it, now - it * day) }
        for (poolSize in 0..100) for (batchSize in 1..30) {
            val batch = engine.getStratifiedRandomBatch(photos.take(poolSize), batchSize, emptyMap())
            assertEquals("pool=$poolSize batch=$batchSize", minOf(poolSize, batchSize), batch.size)
            assertEquals(batch.size, batch.distinctBy { it.id }.size)
        }
    }

    @Test fun `album mode respects target cooldown and newest first including missing date taken`() {
        val photos = listOf(photo(1, now - 3 * day), photo(2, 0, now - day),
            photo(3, now, album = "Screenshots"), photo(4, now))
        assertEquals(listOf(2L, 1L), engine.getAlbumCleanupBatch(photos, 15, "Camera", mapOf(4L to now)).map { it.id })
    }

    @Test fun `month interval includes start but excludes next month midnight`() {
        val start = time("2025-11-01")
        val end = time("2025-12-01")
        val bucket = MonthBucket(2025, 11, "2025年11月", 2, start, end)
        val photos = listOf(photo(1, start - 1), photo(2, start), photo(3, end - 1), photo(4, end))
        assertEquals(listOf(3L, 2L), engine.getMonthCleanupBatch(photos, 15, bucket).map { it.id })
        assertEquals(listOf(2L), engine.getMonthCleanupBatch(photos, 15, bucket, mapOf(3L to now)).map { it.id })
    }

    @Test fun `anniversary selection crosses month and year boundaries`() {
        val photos = listOf(photo(1, time("2020-06-01")), photo(2, time("2024-12-30")), photo(3, time("2024-01-07")))
        assertEquals(setOf(2L, 3L), engine.getTimeCapsuleBatch(photos, 2).map { it.id }.toSet())
    }

    @Test fun `leap anniversary works in non leap years`() {
        val leapEngine = SamplingEngine({ time("2026-02-28") }, Random(1))
        val photos = listOf(photo(1, time("2020-01-01")), photo(2, time("2024-02-29")))
        assertEquals(2L, leapEngine.getTimeCapsuleBatch(photos, 1).single().id)
    }

    @Test fun `time capsule keeps historical cutoff and does not bypass cooldown`() {
        val photos = listOf(photo(1, now - 180 * day), photo(2, now - 179 * day), photo(3, now - 400 * day))
        assertEquals(listOf(1L), engine.getTimeCapsuleBatch(photos, 15, mapOf(3L to now)).map { it.id })
        assertTrue(engine.getTimeCapsuleBatch(photos, 15, mapOf(1L to now, 3L to now)).isEmpty())
    }

    @Test fun `original thirty day fallback is retained and excludes unknown timestamps`() {
        val photos = listOf(photo(1, now - 29 * day), photo(2, now - 30 * day), photo(3, now - 90 * day), photo(4, 0, 0))
        assertEquals(listOf(3L, 2L), engine.getTimeCapsuleBatch(photos, 15).map { it.id })
    }

    @Test fun `month buckets use real calendar boundaries including leap February`() {
        val photos = listOf(photo(1, time("2024-02-29")), photo(2, time("2024-03-01")), photo(3, time("2024-02-01")))
        val buckets = engine.extractMonthBuckets(photos)
        assertEquals(listOf(3, 2), buckets.map { it.month })
        assertEquals(2, buckets[1].count)
        assertEquals(time("2024-02-01"), buckets[1].startMillis)
        assertEquals(time("2024-03-01"), buckets[1].endMillis)
    }
}
