package com.lumina.cleaner.ui.deck

import android.net.Uri
import com.lumina.cleaner.core.model.PhotoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

class DeckSessionStateTest {

    private val uri = mock(Uri::class.java)

    private fun photo(id: Long, size: Long = 1024L * id) = PhotoItem(
        id = id,
        uri = uri,
        displayName = "IMG_$id.jpg",
        dateModified = id * 1000L,
        dateTaken = id * 1000L,
        size = size,
        width = 1200,
        height = 1600,
        bucketName = "Camera"
    )

    @Test
    fun `right swipe returns strictly to previous kept photo and never resurrects trashed photo`() {
        val a = photo(1)
        val b = photo(2)
        val c = photo(3)
        val d = photo(4)

        var state = DeckSessionState.startBatch(listOf(a, b, c, d))
        assertEquals(a, state.currentPhoto)
        assertFalse(state.hasPrevious)

        // 左滑保留 A -> 来到 B
        state = state.keepCurrent()
        assertEquals(b, state.currentPhoto)
        assertEquals(a, state.previousPhoto)

        // 上滑回收 B -> B 立即从可见牌堆移除，当前来到 C，上一张为保留照片 A
        state = state.trashPhoto(b)
        assertEquals(c, state.currentPhoto)
        assertEquals(a, state.previousPhoto)
        assertEquals(listOf(a, c, d), state.visibleDeck)
        assertEquals(1, state.trashedInBatchCount)
        assertEquals(b.size, state.trashedBytesInBatch)

        // 右滑返回上一张 -> 精准回到 A，B 绝对不会复活
        state = state.returnToPrevious()
        assertEquals(a, state.currentPhoto)
        assertFalse(state.visibleDeck.any { it.id == b.id })
        assertFalse(state.hasPrevious)
    }

    @Test
    fun `trashing first photo leaves no previous photo and advances cleanly`() {
        val a = photo(1)
        val b = photo(2)

        var state = DeckSessionState.startBatch(listOf(a, b))
        state = state.trashPhoto(a)

        assertEquals(b, state.currentPhoto)
        assertNull(state.previousPhoto)
        assertFalse(state.hasPrevious)
        assertEquals(1, state.reviewedCount)
        assertFalse(state.isBatchComplete)
    }

    @Test
    fun `trashing last remaining photo completes batch cleanly`() {
        val a = photo(1)
        val b = photo(2)

        var state = DeckSessionState.startBatch(listOf(a, b))
        state = state.keepCurrent() // 保留 A，来到 B
        state = state.trashPhoto(b) // 回收 B

        assertTrue(state.isBatchComplete)
        assertNull(state.currentPhoto)
        assertEquals(2, state.reviewedCount)
    }

    @Test
    fun `trashing every photo in batch reaches batch complete without index out of bounds`() {
        val batch = (1L..3L).map { photo(it) }
        var state = DeckSessionState.startBatch(batch)

        for (item in batch) {
            state = state.trashPhoto(item)
        }

        assertTrue(state.visibleDeck.isEmpty())
        assertTrue(state.isBatchComplete)
        assertEquals(3, state.trashedInBatchCount)
        assertEquals(3, state.reviewedCount)
        // 再次调用右滑与保留保持幂等安全
        assertEquals(state, state.returnToPrevious())
        assertEquals(state, state.keepCurrent())
    }

    @Test
    fun `updatePhotoMetadata enriches Live and HDR flags in place without shifting currentIndex`() {
        val a = photo(1)
        val b = photo(2)
        val c = photo(3)

        var state = DeckSessionState.startBatch(listOf(a, b, c)).keepCurrent() // 当前停在 B
        val enrichedB = b.copy(isLivePhoto = true, motionVideoOffset = 100L, motionVideoLength = 2048L, isHdr = true)
        state = state.updatePhotoMetadata(enrichedB)

        assertEquals(1, state.currentIndex)
        assertEquals(enrichedB, state.currentPhoto)
        assertTrue(state.currentPhoto!!.isLivePhoto)
        assertTrue(state.currentPhoto!!.isHdr)

        // 对不在队列中的照片保持幂等无副作用
        val unrelated = photo(999).copy(isHdr = true)
        assertEquals(state, state.updatePhotoMetadata(unrelated))
    }
}
