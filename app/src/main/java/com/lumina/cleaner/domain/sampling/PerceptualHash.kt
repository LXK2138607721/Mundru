package com.lumina.cleaner.domain.sampling

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.lumina.cleaner.core.model.PhotoItem
import kotlin.math.abs

/**
 * 纯本地零依赖 64-bit 差异感知哈希引擎 (PerceptualHash / dHash)
 *
 * 1. 降采样缩至 9x8 灰度矩阵，比较相邻横向亮度梯度生成 64-bit 视觉指纹；
 * 2. 单张内存 < 8KB，纯 Kotlin 实现，零第三方库依赖；
 * 3. 采用懒索引策略：仅对同图集 30 秒内存在时间邻居且无缓存的照片在后台 IO 线程分批计算。
 */
object PerceptualHash {

    const val GRID_WIDTH = 9
    const val GRID_HEIGHT = 8
    const val SIMILAR_HAMMING_THRESHOLD = 8
    const val NEIGHBOR_CANDIDATE_WINDOW_MS = 30_000L

    /** 计算两个 64-bit dHash 间的汉明距离 ([0..64]) */
    fun hammingDistance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    /** 判断两枚 dHash 是否视觉高度相似（默认汉明距离 <= 8） */
    fun isVisuallySimilar(a: Long, b: Long, maxDistance: Int = SIMILAR_HAMMING_THRESHOLD): Boolean =
        hammingDistance(a, b) <= maxDistance

    /** 纯 JVM 可单测核心：从 9x8 (72 像素) 灰度数组计算 64-bit dHash */
    fun computeFromLumaGrid(luma9x8: IntArray): Long {
        require(luma9x8.size == GRID_WIDTH * GRID_HEIGHT) {
            "Expected ${GRID_WIDTH * GRID_HEIGHT} luma samples, got ${luma9x8.size}"
        }
        var hash = 0L
        var bit = 0
        for (y in 0 until GRID_HEIGHT) {
            val rowOffset = y * GRID_WIDTH
            for (x in 0 until GRID_WIDTH - 1) {
                if (luma9x8[rowOffset + x] > luma9x8[rowOffset + x + 1]) {
                    hash = hash or (1L shl bit)
                }
                bit++
            }
        }
        return hash
    }

    /** 从 Android Bitmap 计算 64-bit dHash（自动回收临时缩放图） */
    fun computeFromBitmap(bitmap: Bitmap): Long {
        val scaled = Bitmap.createScaledBitmap(bitmap, GRID_WIDTH, GRID_HEIGHT, true)
        return try {
            val pixels = IntArray(GRID_WIDTH * GRID_HEIGHT)
            scaled.getPixels(pixels, 0, GRID_WIDTH, 0, 0, GRID_WIDTH, GRID_HEIGHT)
            val luma = IntArray(pixels.size) { idx ->
                val c = pixels[idx]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                (77 * r + 150 * g + 29 * b) shr 8
            }
            computeFromLumaGrid(luma)
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    /** 低内存降采样解码并计算 64-bit dHash，失败时安全返回 null */
    fun computeFromUri(contentResolver: ContentResolver, uri: Uri): Long? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            }
            val srcW = bounds.outWidth
            val srcH = bounds.outHeight
            if (srcW <= 0 || srcH <= 0) return null

            var sampleSize = 1
            val targetMin = 48
            while ((srcW / (sampleSize * 2)) >= targetMin && (srcH / (sampleSize * 2)) >= targetMin) {
                sampleSize *= 2
            }

            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val decoded = contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOpts)
            } ?: return null

            try {
                computeFromBitmap(decoded)
            } finally {
                decoded.recycle()
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** 懒索引候选筛选器：仅挑选无缓存且同图集 30 秒内有邻居的照片，最多返回 [maxCandidates] 张 */
    fun findTimeNeighborCandidates(
        photos: List<PhotoItem>,
        existingDHashes: Map<Long, Long>,
        maxCandidates: Int = 24
    ): List<PhotoItem> {
        if (photos.size < 2 || maxCandidates <= 0) return emptyList()
        val result = ArrayList<PhotoItem>(maxCandidates)
        val added = HashSet<Long>(maxCandidates * 2)

        val byBucket = photos.groupBy { it.bucketName.orEmpty() }
        for ((_, group) in byBucket) {
            if (group.size < 2) continue
            val sorted = group.sortedByDescending { it.effectiveTimestamp() }
            for (i in 0 until sorted.lastIndex) {
                val a = sorted[i]
                val b = sorted[i + 1]
                val ta = a.effectiveTimestamp()
                val tb = b.effectiveTimestamp()
                if (ta > 0L && tb > 0L && abs(ta - tb) <= NEIGHBOR_CANDIDATE_WINDOW_MS) {
                    if (a.id !in existingDHashes && added.add(a.id)) {
                        result.add(a)
                        if (result.size >= maxCandidates) return result
                    }
                    if (b.id !in existingDHashes && added.add(b.id)) {
                        result.add(b)
                        if (result.size >= maxCandidates) return result
                    }
                }
            }
        }
        return result
    }

    private fun PhotoItem.effectiveTimestamp(): Long =
        if (dateTaken > 0L) dateTaken else dateModified
}
