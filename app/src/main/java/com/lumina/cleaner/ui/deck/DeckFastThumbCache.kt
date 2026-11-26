package com.lumina.cleaner.ui.deck

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import coil.decode.DataSource
import coil.intercept.Interceptor
import coil.request.ImageResult
import coil.request.Parameters
import coil.request.SuccessResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

internal const val FAST_THUMB_PARAM_KEY = "mundru_fast_thumb_key"

/**
 * 纯本地私有缓存目录下的大体积无损 PNG 极速缩略缓存 (DeckFastThumbCache)
 *
 * 色彩与性能双保障：
 * 1. 仅针对 >= 2.5MB 的标准 sRGB 无损 PNG 启用私有缩略缓存（解决 20MB+ 4K PNG 每次冷启全流 zlib 解压慢的问题）；
 * 2. 相机 JPG / Display P3 / Ultra-HDR 照片 100% 走原生色彩管理直出，绝不剥离广色域 ICC 或 Gainmap，杜绝任何照片变色；
 * 3. 文件探测、HARDWARE 硬解与首次落盘全部严格在 Dispatchers.IO 执行，主线程耗时严格 0ms。
 */
internal class DeckFastThumbInterceptor(context: Context) : Interceptor {
    private val appContext = context.applicationContext
    private val cacheDir = File(appContext.cacheDir, "deck_thumbs_v2").apply { mkdirs() }
    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var writesSinceTrim = 0

    init {
        // 清理旧版 v1 缓存目录，确保曾经缓存过的相机 JPG 立即回到原生广色域/HDR 解码管线
        writeScope.launch {
            runCatching {
                val legacyDir = File(appContext.cacheDir, "deck_thumbs_v1")
                if (legacyDir.exists()) legacyDir.deleteRecursively()
            }
        }
    }

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val thumbKey = request.parameters.value<String>(FAST_THUMB_PARAM_KEY)
            ?: return chain.proceed(request)
        val dataUri = request.data as? Uri
        if (dataUri == null || dataUri.scheme != "content") {
            return chain.proceed(request)
        }

        val safeName = sanitizeKey(thumbKey)
        val cachedBitmap = withContext(Dispatchers.IO) {
            val cachedFile = File(cacheDir, "$safeName.jpg")
            if (!cachedFile.isFile || cachedFile.length() <= 256L) {
                null
            } else {
                runCatching {
                    val hwOpts = BitmapFactory.Options().apply {
                        inPreferredConfig = if (request.allowHardware) {
                            Bitmap.Config.HARDWARE
                        } else {
                            Bitmap.Config.ARGB_8888
                        }
                    }
                    BitmapFactory.decodeFile(cachedFile.absolutePath, hwOpts)
                        ?: BitmapFactory.decodeFile(cachedFile.absolutePath)
                }.getOrNull()?.takeIf { it.width > 0 && it.height > 0 }
            }
        }

        if (cachedBitmap != null) {
            return SuccessResult(
                drawable = BitmapDrawable(appContext.resources, cachedBitmap),
                request = request,
                dataSource = DataSource.DISK,
                memoryCacheKey = request.memoryCacheKey
            )
        }

        val result = chain.proceed(request)
        if (result is SuccessResult && result.drawable !is Animatable) {
            val bmp = (result.drawable as? BitmapDrawable)?.bitmap
            val isStandardSrgb = bmp != null && (
                Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                    bmp.colorSpace == null ||
                    bmp.colorSpace == ColorSpace.get(ColorSpace.Named.SRGB)
                )
            val hasGainmap = bmp != null &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                bmp.hasGainmap()

            if (bmp != null && !bmp.isRecycled && isStandardSrgb && !hasGainmap &&
                bmp.width in 32..2400 && bmp.height in 32..2600
            ) {
                writeScope.launch {
                    var softwareCopy: Bitmap? = null
                    try {
                        if (bmp.isRecycled) return@launch
                        softwareCopy = bmp.copy(Bitmap.Config.ARGB_8888, false) ?: return@launch
                        cacheDir.mkdirs()
                        val cachedFile = File(cacheDir, "$safeName.jpg")
                        val tmpFile = File(cacheDir, "$safeName.tmp")
                        FileOutputStream(tmpFile).use { out ->
                            softwareCopy.compress(Bitmap.CompressFormat.JPEG, 90, out)
                        }
                        if (tmpFile.length() > 256L) {
                            if (cachedFile.exists()) cachedFile.delete()
                            tmpFile.renameTo(cachedFile)
                        } else {
                            tmpFile.delete()
                        }
                        if (++writesSinceTrim >= 12) {
                            writesSinceTrim = 0
                            trimCacheIfNeeded()
                        }
                    } catch (_: Exception) {
                    } finally {
                        softwareCopy?.recycle()
                    }
                }
            }
        }
        return result
    }

    private fun sanitizeKey(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            if (ch.isLetterOrDigit() || ch == '_' || ch == '-') sb.append(ch)
            else sb.append('_')
        }
        return sb.toString()
    }

    private fun trimCacheIfNeeded() {
        val files = cacheDir.listFiles()?.filter { it.isFile } ?: return
        val maxBytes = 64L * 1024L * 1024L
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        val sortedOldest = files.sortedBy { it.lastModified() }
        for (file in sortedOldest) {
            val len = file.length()
            if (file.delete()) {
                total -= len
                if (total <= maxBytes * 8 / 10) break
            }
        }
    }
}

internal fun fastThumbKeyIfHeavy(
    photoId: Long,
    dateModified: Long,
    sizeBytes: Long,
    displayName: String,
    widthPx: Int,
    heightPx: Int
): String? {
    // 仅对大体积无损 PNG 启用缩略缓存；JPG / HEIC / HDR 相机原图 100% 走原生色彩管理解码，绝不变色
    val isHeavyLosslessPng = sizeBytes >= 2_500_000L && displayName.endsWith(".png", ignoreCase = true)
    return if (isHeavyLosslessPng) "p_${photoId}_${dateModified}_${widthPx}x${heightPx}" else null
}

/** IO 线程快速探测该重型 PNG 是否已命中私有 2.5ms 极速缩略缓存 */
internal fun hasFastThumbOnDisk(
    context: Context,
    photoId: Long,
    dateModified: Long,
    sizeBytes: Long,
    displayName: String,
    widthPx: Int,
    heightPx: Int
): Boolean {
    val rawKey = fastThumbKeyIfHeavy(photoId, dateModified, sizeBytes, displayName, widthPx, heightPx)
        ?: return false
    val sb = StringBuilder(rawKey.length)
    for (ch in rawKey) {
        if (ch.isLetterOrDigit() || ch == '_' || ch == '-') sb.append(ch) else sb.append('_')
    }
    val file = File(File(context.applicationContext.cacheDir, "deck_thumbs_v2"), "${sb}.jpg")
    return file.isFile && file.length() > 256L
}

internal fun Parameters.Builder.enableFastThumbIfHeavy(
    photoId: Long,
    dateModified: Long,
    sizeBytes: Long,
    displayName: String,
    widthPx: Int,
    heightPx: Int
): Parameters.Builder {
    val key = fastThumbKeyIfHeavy(photoId, dateModified, sizeBytes, displayName, widthPx, heightPx)
    if (key != null) {
        set(FAST_THUMB_PARAM_KEY, key, memoryCacheKey = null)
    }
    return this
}
