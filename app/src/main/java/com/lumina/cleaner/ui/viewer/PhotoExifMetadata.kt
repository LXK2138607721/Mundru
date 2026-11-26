package com.lumina.cleaner.ui.viewer

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 纯本地解析的照片 EXIF 拍摄规格模型 (PhotoExifInfo)
 */
@Immutable
data class PhotoExifInfo(
    val cameraLabel: String? = null,
    val focalLength: String? = null,
    val aperture: String? = null,
    val shutterSpeed: String? = null,
    val iso: String? = null
) {
    val hasCameraSpecs: Boolean
        get() = cameraLabel != null ||
            focalLength != null ||
            aperture != null ||
            shutterSpeed != null ||
            iso != null
}

/**
 * 纯本地零联网 EXIF 摄影参数读取与专业格式化器 (PhotoExifMetadata)
 * 基于项目已内置的 androidx.exifinterface，零新增依赖、安全兜底任意无 EXIF 截图/网图。
 */
object PhotoExifMetadata {

    suspend fun read(context: Context, uri: Uri): PhotoExifInfo = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                val make = exif.getAttribute(ExifInterface.TAG_MAKE)
                val model = exif.getAttribute(ExifInterface.TAG_MODEL)

                val focal35 = exif.getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0)
                val focalRaw = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, -1.0)
                val fNumber = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, -1.0)
                val exposureTime = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, -1.0)
                val isoValue = parseIsoValue(
                    exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY),
                    exif.getAttribute(ExifInterface.TAG_ISO_SPEED),
                    exif.getAttribute(ExifInterface.TAG_RECOMMENDED_EXPOSURE_INDEX)
                )

                PhotoExifInfo(
                    cameraLabel = formatCameraLabel(make, model),
                    focalLength = formatFocalLength(focal35, focalRaw),
                    aperture = formatAperture(fNumber),
                    shutterSpeed = formatShutterSpeed(exposureTime),
                    iso = isoValue?.let { "ISO $it" }
                )
            } ?: PhotoExifInfo()
        }.getOrDefault(PhotoExifInfo())
    }

    /**
     * 安全解析多厂商 ISO 字符串：兼容荣耀/华为/索尼常见的多值数组格式（如 "250,0" 或 "400 0"）
     */
    internal fun parseIsoValue(vararg rawValues: String?): Int? {
        for (raw in rawValues) {
            if (raw.isNullOrBlank()) continue
            val token = raw.trim()
                .split(',', ' ', ';', '/')
                .firstOrNull { it.isNotBlank() }
                ?.trim()
                ?: continue
            val parsed = token.toIntOrNull()
            if (parsed != null && parsed in 1..10_000_000) {
                return parsed
            }
        }
        return null
    }

    /** 合并品牌与机型，自动消除品牌名重复（如 Apple + iPhone -> iPhone；HONOR + HONOR Magic -> HONOR Magic） */
    internal fun formatCameraLabel(make: String?, model: String?): String? {
        val cleanMake = make?.trim().takeUnless { it.isNullOrBlank() }
        val cleanModel = model?.trim().takeUnless { it.isNullOrBlank() }
        if (cleanMake == null && cleanModel == null) return null
        if (cleanMake == null) return cleanModel
        if (cleanModel == null) return cleanMake
        if (cleanModel.startsWith(cleanMake, ignoreCase = true)) return cleanModel
        if (cleanMake.equals("Apple", ignoreCase = true) && cleanModel.startsWith("iPhone", ignoreCase = true)) {
            return cleanModel
        }
        return "$cleanMake $cleanModel"
    }

    /** 优先展示等效 35mm 焦距（如 24mm），其次展示物理焦距（如 5.6mm） */
    internal fun formatFocalLength(focal35mm: Int, physicalFocalMm: Double): String? {
        if (focal35mm in 1..2000) return "${focal35mm}mm"
        if (physicalFocalMm > 0.1 && physicalFocalMm < 2000.0) {
            val rounded = (physicalFocalMm * 10.0).roundToInt() / 10.0
            val text = if (abs(rounded - rounded.roundToInt()) < 0.05) {
                "${rounded.roundToInt()}"
            } else {
                String.format(Locale.US, "%.1f", rounded)
            }
            return "${text}mm"
        }
        return null
    }

    /** 格式化光圈（如 f/1.9、f/2） */
    internal fun formatAperture(fNumber: Double): String? {
        if (fNumber <= 0.5 || fNumber > 64.0) return null
        val rounded = (fNumber * 10.0).roundToInt() / 10.0
        return if (abs(rounded - rounded.roundToInt()) < 0.05) {
            "f/${rounded.roundToInt()}"
        } else {
            String.format(Locale.US, "f/%.1f", rounded)
        }
    }

    /** 格式化专业快门速度（如 1/120s、1/1000s、0.5s、2s） */
    internal fun formatShutterSpeed(exposureSeconds: Double): String? {
        if (exposureSeconds <= 0.0 || exposureSeconds > 3600.0) return null
        return if (exposureSeconds < 0.5) {
            val denom = (1.0 / exposureSeconds).roundToInt().coerceAtLeast(2)
            "1/${denom}s"
        } else if (abs(exposureSeconds - exposureSeconds.roundToInt()) < 0.05) {
            "${exposureSeconds.roundToInt()}s"
        } else {
            String.format(Locale.US, "%.1fs", exposureSeconds)
        }
    }

    /** 识别优雅常见画幅比例（如 4:3、3:4、16:9、9:16、1:1、3:2、20:9） */
    internal fun formatAspectRatioLabel(width: Int, height: Int): String? {
        if (width <= 0 || height <= 0) return null
        val ratio = width.toFloat() / height.toFloat()
        val known = listOf(
            (1f / 1f) to "1:1",
            (4f / 3f) to "4:3",
            (3f / 4f) to "3:4",
            (3f / 2f) to "3:2",
            (2f / 3f) to "2:3",
            (16f / 9f) to "16:9",
            (9f / 16f) to "9:16",
            (20f / 9f) to "20:9",
            (9f / 20f) to "9:20",
            (21f / 9f) to "21:9",
            (9f / 21f) to "9:21",
            (5f / 4f) to "5:4",
            (4f / 5f) to "4:5"
        )
        for ((target, label) in known) {
            if (abs(ratio - target) / target < 0.045f) return label
        }
        return if (ratio >= 1f) {
            String.format(Locale.US, "%.1f:1", ratio)
        } else {
            String.format(Locale.US, "1:%.1f", 1f / ratio)
        }
    }

    /** 提取简洁大写文件格式标签（JPG / PNG / HEIC / WEBP / GIF） */
    internal fun formatExtension(displayName: String): String {
        val ext = displayName.substringAfterLast('.', "").uppercase(Locale.ROOT)
        return when (ext) {
            "JPEG" -> "JPG"
            "" -> "IMG"
            else -> ext.take(5)
        }
    }
}
