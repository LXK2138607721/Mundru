package com.lumina.cleaner.core.model

import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
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
 * Mundru 整理模式枚举 (使用 Material Rounded 官方矢量图标，消除全机型 OEM Emoji 渲染差异)
 *
 * 提供四维高效整理维度，专注重塑相册秩序。
 */
enum class SamplingMode(val title: String, val icon: ImageVector, val description: String) {
    RANDOM_WALK("随机漫步", Icons.Rounded.Casino, "时间轴分层抽样，纵览回忆且不重复"),
    TIME_CAPSULE("时光考古", Icons.Rounded.History, "那年今日与远古积存，深水区拓荒"),
    ALBUM_CLEANUP("图集专清", Icons.Rounded.Folder, "挑选特定图集，专注定向清理"),
    MONTH_CLEANUP("月份专清", Icons.Rounded.CalendarMonth, "按年月时间轴归档，精准清理历史月度")
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
