package com.lumina.cleaner.domain.motion

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface

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
 * 特性：一次 IO 即可同时检测出实况切片（Live Photo）与高动态范围（HDR / Ultra HDR / Gainmap）
 */
object MotionPhotoParser {

    fun parse(context: Context, uri: Uri): ParsedMediaMetadata {
        val resolver = context.contentResolver
        val fileLength = queryContentLength(resolver, uri) ?: return ParsedMediaMetadata(false)

        var isHdr = detectHdrFromMediaStore(resolver, uri)

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

        // Step 2: 扫描文件末尾查找 MP4 "ftyp" 标记（兼容 vivo/旧机型）
        val tailResult = scanTailForMp4(resolver, uri, fileLength)
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

    private fun scanTailForMp4(resolver: ContentResolver, uri: Uri, fileLength: Long): ParsedMediaMetadata? {
        if (fileLength < 1024) return null

        return try {
            resolver.openInputStream(uri)?.use { input ->
                val searchSize = minOf(fileLength, 512 * 1024L).toInt()
                val skipSize = fileLength - searchSize
                if (skipSize > 0) input.skip(skipSize)

                val buffer = ByteArray(searchSize)
                val bytesRead = input.read(buffer)
                if (bytesRead < 8) return null

                val ftyp = byteArrayOf(0x66, 0x74, 0x79, 0x70) // "ftyp"
                for (i in 0 until (bytesRead - 8)) {
                    if (buffer[i + 4] == ftyp[0] &&
                        buffer[i + 5] == ftyp[1] &&
                        buffer[i + 6] == ftyp[2] &&
                        buffer[i + 7] == ftyp[3]
                    ) {
                        val videoStart = skipSize + i
                        val videoLength = fileLength - videoStart
                        if (videoLength in 1024 until (fileLength * 0.9).toLong()) {
                            return ParsedMediaMetadata(
                                isMotionPhoto = true,
                                videoOffset = videoStart,
                                videoLength = videoLength
                            )
                        }
                    }
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun queryContentLength(resolver: ContentResolver, uri: Uri): Long? {
        try {
            resolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                if (afd.length >= 0) return afd.length
            }
        } catch (_: Exception) { }

        resolver.query(uri, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)?.use { cursor ->
            val sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
            if (sizeCol >= 0 && cursor.moveToFirst()) {
                val size = cursor.getLong(sizeCol)
                if (size > 0) return size
            }
        }
        return null
    }
}
