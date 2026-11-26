package com.lumina.cleaner.core.model

import android.net.Uri
import android.util.LruCache
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Mundru 核心领域展示图片模型 (Models)
 *
 * 品牌释义：Mundru 源自格陵兰语（Greenlandic），意为「擦掉 / 擦除」。
 * 封装单张相片的 URI、物理尺寸、宽高比及实况/HDR 动态扩展参数。
 */
data class PhotoItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val dateModified: Long,
    val dateTaken: Long,
    val size: Long,
    val width: Int,
    val height: Int,
    val bucketName: String?,
    val isLivePhoto: Boolean = false,
    val motionVideoOffset: Long = 0L,
    val motionVideoLength: Long = 0L,
    val isHdr: Boolean = false
) {
    /**
     * 计算图片宽高比（兜底 3:4 经典比例，保障卡片布局稳定）
     */
    val aspectRatio: Float
        get() = if (width > 0 && height > 0) width.toFloat() / height.toFloat() else 3f / 4f
}

/**
 * 将 MediaStore 原始像素宽高与 EXIF 旋转角（0/90/180/270）规整为真实显示宽高。
 * 当旋转角为 90° 或 270°（含负角或超 360° 归一）时对调宽高，确保竖向缓冲存储的横拍/横屏截图
 * 从首帧起即按真实横向尺寸布局与计算画幅。
 */
internal fun resolveDisplayDimensions(
    rawWidth: Int,
    rawHeight: Int,
    orientationDegrees: Int
): Pair<Int, Int> {
    if (rawWidth <= 0 || rawHeight <= 0) return rawWidth to rawHeight
    val normalized = ((orientationDegrees % 360) + 360) % 360
    return if (normalized == 90 || normalized == 270) {
        rawHeight to rawWidth
    } else {
        rawWidth to rawHeight
    }
}

/**
 * 轻量级实测显示宽高比纠偏缓存（仅在 MediaStore 缺失或记录错误且 Coil 解码出不同有效比例时写入），
 * 保障主舞台卡片与全屏大图查看器在任何异常元数据文件上都严格共享同一真实显示比例。
 */
internal object PhotoDisplayAspectCache {
    private val cache = LruCache<Long, Float>(64)

    fun put(photoId: Long, aspectRatio: Float) {
        if (aspectRatio.isFinite() && aspectRatio > 0.05f && aspectRatio < 20f) {
            cache.put(photoId, aspectRatio)
        }
    }

    fun get(photoId: Long): Float? = cache.get(photoId)

    fun resolve(photo: PhotoItem): Float = get(photo.id) ?: photo.aspectRatio
}

/**
 * Mundru 整理模式枚举 (使用 Material Rounded 官方矢量图标，消除全机型 OEM Emoji 渲染差异)
 *
 * 相册整理模式，与模式菜单显示顺序一致。
 */
enum class SamplingMode(val title: String, val icon: ImageVector, val description: String) {
    RANDOM_WALK("随机漫步", Icons.Rounded.Casino, "随机浏览全库"),
    TIME_CAPSULE("时光考古", Icons.Rounded.History, "重温往日旧照"),
    ALBUM_CLEANUP("图集专清", Icons.Rounded.Folder, "按相册清理"),
    MONTH_CLEANUP("月份专清", Icons.Rounded.CalendarMonth, "按月份清理"),
    LARGE_PHOTO_CLEANUP("大图专清", Icons.Rounded.Storage, "优先清理大图")
}

/**
 * Mundru 月份归档时间分桶模型
 */
data class MonthBucket(
    val year: Int,
    val month: Int,
    val displayName: String,
    val count: Int,
    val startMillis: Long,
    val endMillis: Long
)
