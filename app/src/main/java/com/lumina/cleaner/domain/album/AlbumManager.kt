package com.lumina.cleaner.domain.album

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AlbumInfo(
    val name: String,
    val count: Int,
    val coverUri: Uri?,
    val relativePath: String?
)

/**
 * Mundru 图集管理中心 (AlbumManager)
 * 品牌释义：Mundru 源自格陵兰语（Greenlandic），意为「擦掉 / 擦除」。
 * 核心定位：本地图集聚合与容量分析，服务「图集专清」模式快速定向擦除。
 *
 * 核心特性：
 * 1. 自动聚合系统相册与自定义图集；
 * 2. 统计各图集照片数量并提取最新封面；
 * 3. 按照片数量降序精准排序，专注服务「图集专清」模式。
 */
class AlbumManager(private val context: Context) {

    private val contentResolver: ContentResolver = context.contentResolver

    /**
     * 获取所有图集（合并系统相册，按照片容量降序返回）
     */
    suspend fun getAvailableAlbums(): List<AlbumInfo> = withContext(Dispatchers.IO) {
        val albumMap = mutableMapOf<String, Pair<Int, Uri?>>()
        val pathMap = mutableMapOf<String, String>()

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Images.Media.RELATIVE_PATH else MediaStore.Images.Media.DATA
        )

        try {
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(MediaStore.Images.Media._ID)
                val bucketCol = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                val pathCol = cursor.getColumnIndex(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Images.Media.RELATIVE_PATH else MediaStore.Images.Media.DATA
                )

                while (cursor.moveToNext()) {
                    val bucketName = cursor.getString(bucketCol) ?: continue
                    val id = cursor.getLong(idCol)
                    val uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
                    val path = if (pathCol >= 0) cursor.getString(pathCol) else null

                    val current = albumMap[bucketName]
                    if (current == null) {
                        albumMap[bucketName] = 1 to uri
                        if (path != null) pathMap[bucketName] = path
                    } else {
                        albumMap[bucketName] = (current.first + 1) to current.second
                    }
                }
            }
        } catch (_: Exception) { }

        val albumList = mutableListOf<AlbumInfo>()
        albumMap.forEach { (name, pair) ->
            albumList.add(
                AlbumInfo(
                    name = name,
                    count = pair.first,
                    coverUri = pair.second,
                    relativePath = pathMap[name]
                )
            )
        }

        // 直接按照片数量降序排序，大图集优先呈现
        return@withContext albumList.sortedByDescending { it.count }
    }
}
