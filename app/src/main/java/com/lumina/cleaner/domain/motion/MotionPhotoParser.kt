package com.lumina.cleaner.domain.motion

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.InputStream
import java.util.Locale

data class ParsedMediaMetadata(
    val isMotionPhoto: Boolean,
    val videoOffset: Long = 0L,
    val videoLength: Long = 0L,
    val isHdr: Boolean = false
)

typealias MotionPhotoMetadata = ParsedMediaMetadata

/**
 * Mundru 实况照片与 HDR 动态范围元数据解析器 (MotionPhotoParser)
 * 品牌释义：Mundru 源自格陵兰语（Greenlandic），意为「擦掉 / 擦除」。
 * 零依赖架构：基于原生 XMP 与 MP4 局部切片嗅探，毫秒级解析主流厂商实况相片与 HDR 动态范围。
 *
 * 支持厂商：Google Pixel、华为/荣耀、小米、vivo/iQOO、OPPO/一加、三星
 * 特性：
 * - 非 JPEG/HEIF 格式（PNG/GIF/WebP/BMP）秒级跳过 EXIF 与尾部 MP4 扫描；
 * - 尾部扫描采用可复用 64KB 分块滑窗（带 8 字节跨块重叠），彻底消除 512KB LOS 大数组垃圾与 GC 尖峰。
 */
object MotionPhotoParser {

    internal const val TAIL_SCAN_LIMIT_BYTES = 512 * 1024L
    internal const val TAIL_CHUNK_BYTES = 64 * 1024
    private const val OVERLAP_BYTES = 8

    fun allocateScratchBuffer(): ByteArray = ByteArray(TAIL_CHUNK_BYTES)

    internal fun canContainMotionPhotoTail(displayName: String?): Boolean {
        if (displayName.isNullOrBlank()) return true
        val lower = displayName.lowercase(Locale.ROOT)
        return !(lower.endsWith(".png") ||
            lower.endsWith(".gif") ||
            lower.endsWith(".webp") ||
            lower.endsWith(".bmp") ||
            lower.endsWith(".svg"))
    }

    fun parse(
        context: Context,
        uri: Uri,
        displayName: String? = null,
        scratchBuffer: ByteArray? = null
    ): ParsedMediaMetadata {
        val resolver = context.contentResolver
        var isHdr = detectHdrFromMediaStore(resolver, uri)

        // PNG/GIF/WebP/BMP 不可能携带 JPEG/HEIF 尾部拼接 MP4 实况切片，直接跳过重型 IO 与尾扫
        if (!canContainMotionPhotoTail(displayName)) {
            return ParsedMediaMetadata(isMotionPhoto = false, isHdr = isHdr)
        }

        val fileLength = queryContentLength(resolver, uri) ?: return ParsedMediaMetadata(false, isHdr = isHdr)

        // Step 1: 读取 XMP 与 Exif 提示
        var xmp: String? = null
        try {
            resolver.openInputStream(uri)?.use { input ->
                val exif = ExifInterface(input)
                xmp = exif.getAttribute(ExifInterface.TAG_XMP)

                if (!isHdr) {
                    val software = exif.getAttribute(ExifInterface.TAG_SOFTWARE)?.lowercase() ?: ""
                    val desc = exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION)?.lowercase() ?: ""
                    val comment = exif.getAttribute(ExifInterface.TAG_USER_COMMENT)?.lowercase() ?: ""
                    val xmpLower = xmp?.lowercase() ?: ""

                    isHdr = software.contains("hdr") || software.contains("ultra hdr") || software.contains("pro xdr") || software.contains("proxdr") ||
                        desc.contains("hdr") || desc.contains("ultra hdr") ||
                        comment.contains("hdr") ||
                        xmpLower.contains("gainmap") || xmpLower.contains("ultrahdr") || xmpLower.contains("hdr_gm") || xmpLower.contains("hdr10")
                }
            }
        } catch (_: Exception) { }

        if (xmp != null) {
            val itemLength = extractMotionPhotoLength(xmp!!)
            if (itemLength != null && itemLength in 1 until fileLength) {
                return ParsedMediaMetadata(
                    isMotionPhoto = true,
                    videoOffset = fileLength - itemLength,
                    videoLength = itemLength,
                    isHdr = isHdr
                )
            }

            val microVideoOffset = extractMicroVideoOffset(xmp!!)
            if (microVideoOffset != null && microVideoOffset in 1 until fileLength) {
                return ParsedMediaMetadata(
                    isMotionPhoto = true,
                    videoOffset = fileLength - microVideoOffset,
                    videoLength = microVideoOffset,
                    isHdr = isHdr
                )
            }
        }

        // Step 2: 64KB 分块滑窗扫描文件末尾查找 MP4 "ftyp" 标记（零 512KB LOS 大对象分配）
        val tailResult = scanTailForMp4(resolver, uri, fileLength, scratchBuffer)
        if (tailResult != null) {
            return ParsedMediaMetadata(
                isMotionPhoto = true,
                videoOffset = tailResult.videoOffset,
                videoLength = tailResult.videoLength,
                isHdr = isHdr
            )
        }

        return ParsedMediaMetadata(
            isMotionPhoto = false,
            videoOffset = 0L,
            videoLength = 0L,
            isHdr = isHdr
        )
    }

    private fun detectHdrFromMediaStore(resolver: ContentResolver, uri: Uri): Boolean {
        return try {
            val columns = arrayOf("color_transfer", "is_hdr")
            resolver.query(uri, columns, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val ctIdx = cursor.getColumnIndex("color_transfer")
                    if (ctIdx >= 0 && cursor.getInt(ctIdx) >= 6) return true // ST2084 or HLG
                    val hdrIdx = cursor.getColumnIndex("is_hdr")
                    if (hdrIdx >= 0 && cursor.getInt(hdrIdx) > 0) return true
                }
                false
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    private fun extractMotionPhotoLength(xmp: String): Long? {
        val tagPattern = Regex("<(?:rdf:li|Container:Item)[^>]*(?:>|/>)", RegexOption.IGNORE_CASE)
        for (match in tagPattern.findAll(xmp)) {
            val tag = match.value
            val semantic = extractAttribute(tag, "Item:Semantic")
            val length = extractAttribute(tag, "Item:Length")?.toLongOrNull()
            if (length != null && length > 0) {
                if (semantic.equals("MotionPhoto", ignoreCase = true) ||
                    semantic.equals("MotionPhotoVideo", ignoreCase = true)
                ) {
                    return length
                }
            }
        }
        return null
    }

    private fun extractMicroVideoOffset(xmp: String): Long? {
        val keys = listOf(
            "GCamera:MicroVideoOffset",
            "Camera:MicroVideoOffset",
            "HwMicroVideo:MicroVideoOffset",
            "Huawei:MicroVideoOffset",
            "vivo:MicroVideoOffset",
            "vivo:LivePhotoVideoOffset",
            "OPPO:MicroVideoOffset",
            "OnePlus:MicroVideoOffset",
            "Xiaomi:MicroVideoOffset",
            "Samsung:MicroVideoOffset",
            "MicroVideoOffset"
        )
        for (key in keys) {
            extractAttribute(xmp, key)?.toLongOrNull()?.let { return it }
        }
        return null
    }

    private fun extractAttribute(tag: String, name: String): String? {
        val pattern = Regex(
            "$name\\s*=\\s*\"([^\"]+)\"|$name\\s*=\\s*'([^']+)'",
            RegexOption.IGNORE_CASE
        )
        val match = pattern.find(tag) ?: return null
        return match.groups[1]?.value ?: match.groups[2]?.value
    }

    private fun scanTailForMp4(
        resolver: ContentResolver,
        uri: Uri,
        fileLength: Long,
        scratchBuffer: ByteArray?
    ): ParsedMediaMetadata? {
        if (fileLength < 1024L) return null
        return try {
            resolver.openInputStream(uri)?.use { input ->
                findMp4FtypInTailStream(input, fileLength, scratchBuffer)
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 纯 JVM 可单测的尾部滑窗 MP4 "ftyp" 探测器：
     * - 仅使用单块 64KB 缓冲（可跨多张照片复用），避免每次分配 512KB LOS 对象；
     * - 保留 8 字节滑窗重叠，确保跨块边界的 boxHeader + "ftyp" 100% 命中。
     */
    internal fun findMp4FtypInTailStream(
        input: InputStream,
        fileLength: Long,
        scratchBuffer: ByteArray? = null
    ): ParsedMediaMetadata? {
        if (fileLength < 1024L) return null
        val searchSize = minOf(fileLength, TAIL_SCAN_LIMIT_BYTES)
        val skipSize = fileLength - searchSize
        if (skipSize > 0L) {
            var remaining = skipSize
            while (remaining > 0L) {
                val skipped = input.skip(remaining)
                if (skipped <= 0L) {
                    if (input.read() < 0) return null
                    remaining--
                } else {
                    remaining -= skipped
                }
            }
        }

        val buffer = if (scratchBuffer != null && scratchBuffer.size >= OVERLAP_BYTES + 16) {
            scratchBuffer
        } else {
            ByteArray(minOf(searchSize, TAIL_CHUNK_BYTES.toLong()).toInt().coerceAtLeast(OVERLAP_BYTES + 16))
        }

        var carry = 0
        var windowFileOffset = skipSize
        var remainingToRead = searchSize
        val maxVideoLength = (fileLength * 0.9).toLong()

        while (remainingToRead > 0L) {
            val space = buffer.size - carry
            val toRead = minOf(space.toLong(), remainingToRead).toInt()
            val bytesRead = input.read(buffer, carry, toRead)
            if (bytesRead <= 0) break
            remainingToRead -= bytesRead

            val validLen = carry + bytesRead
            val limit = validLen - 8
            for (i in 0 until limit) {
                if (buffer[i + 4] == 0x66.toByte() &&
                    buffer[i + 5] == 0x74.toByte() &&
                    buffer[i + 6] == 0x79.toByte() &&
                    buffer[i + 7] == 0x70.toByte()
                ) {
                    val videoStart = windowFileOffset + i
                    val videoLength = fileLength - videoStart
                    if (videoLength in 1024L until maxVideoLength) {
                        return ParsedMediaMetadata(
                            isMotionPhoto = true,
                            videoOffset = videoStart,
                            videoLength = videoLength
                        )
                    }
                }
            }

            if (validLen > OVERLAP_BYTES) {
                val consumed = validLen - OVERLAP_BYTES
                System.arraycopy(buffer, consumed, buffer, 0, OVERLAP_BYTES)
                windowFileOffset += consumed
                carry = OVERLAP_BYTES
            } else {
                carry = validLen
            }
        }
        return null
    }

    private fun queryContentLength(resolver: ContentResolver, uri: Uri): Long? {
        try {
            resolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                if (afd.length >= 0) return afd.length
            }
        } catch (_: Exception) { }

        try {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)?.use { cursor ->
                val sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                if (sizeCol >= 0 && cursor.moveToFirst()) {
                    val size = cursor.getLong(sizeCol)
                    if (size > 0) return size
                }
            }
        } catch (_: SecurityException) {
            // The selected media grant may change between the scan and the read.
        }
        return null
    }
}
