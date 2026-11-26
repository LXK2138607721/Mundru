package com.lumina.cleaner.domain.motion

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionPhotoParserTest {

    @Test
    fun `skips motion tail scan for png gif webp bmp and allows jpeg heic`() {
        assertFalse(MotionPhotoParser.canContainMotionPhotoTail("screenshot_2026.png"))
        assertFalse(MotionPhotoParser.canContainMotionPhotoTail("BIG_GAME.PNG"))
        assertFalse(MotionPhotoParser.canContainMotionPhotoTail("meme.gif"))
        assertFalse(MotionPhotoParser.canContainMotionPhotoTail("sticker.webp"))
        assertFalse(MotionPhotoParser.canContainMotionPhotoTail("raw.bmp"))
        assertTrue(MotionPhotoParser.canContainMotionPhotoTail("IMG_20260101.jpg"))
        assertTrue(MotionPhotoParser.canContainMotionPhotoTail("IMG_20260101.HEIC"))
        assertTrue(MotionPhotoParser.canContainMotionPhotoTail(null))
    }

    @Test
    fun `chunked 64kb tail scanner finds ftyp straddling exact chunk boundary and reuses scratch`() {
        val totalSize = 200_000
        val payload = ByteArray(totalSize) { 0x11 }
        // Place box header (4 bytes) + "ftyp" right across the 64KB chunk boundary inside the tail scan
        val boundaryBoxOffset = MotionPhotoParser.TAIL_CHUNK_BYTES - 5
        payload[boundaryBoxOffset + 4] = 'f'.code.toByte()
        payload[boundaryBoxOffset + 5] = 't'.code.toByte()
        payload[boundaryBoxOffset + 6] = 'y'.code.toByte()
        payload[boundaryBoxOffset + 7] = 'p'.code.toByte()

        val scratch = MotionPhotoParser.allocateScratchBuffer()
        val found = MotionPhotoParser.findMp4FtypInTailStream(
            input = ByteArrayInputStream(payload),
            fileLength = totalSize.toLong(),
            scratchBuffer = scratch
        )
        assertNotNull(found)
        assertTrue(found!!.isMotionPhoto)
        assertEquals(boundaryBoxOffset.toLong(), found.videoOffset)
        assertEquals((totalSize - boundaryBoxOffset).toLong(), found.videoLength)

        // Reusing the same scratch buffer on a clean JPEG must not leak stale "ftyp" bytes
        val plainJpeg = ByteArray(180_000) { 0x22 }
        val notFound = MotionPhotoParser.findMp4FtypInTailStream(
            input = ByteArrayInputStream(plainJpeg),
            fileLength = plainJpeg.size.toLong(),
            scratchBuffer = scratch
        )
        assertNull(notFound)
    }

    @Test
    fun `tail scanner skips files larger than 512kb accurately and detects embedded mp4 near end`() {
        val totalSize = 700_000
        val payload = ByteArray(totalSize) { 0x05 }
        val mp4Start = totalSize - 24_000
        payload[mp4Start + 4] = 'f'.code.toByte()
        payload[mp4Start + 5] = 't'.code.toByte()
        payload[mp4Start + 6] = 'y'.code.toByte()
        payload[mp4Start + 7] = 'p'.code.toByte()

        val found = MotionPhotoParser.findMp4FtypInTailStream(
            input = ByteArrayInputStream(payload),
            fileLength = totalSize.toLong(),
            scratchBuffer = MotionPhotoParser.allocateScratchBuffer()
        )
        assertNotNull(found)
        assertEquals(mp4Start.toLong(), found!!.videoOffset)
        assertEquals(24_000L, found.videoLength)
    }
}
