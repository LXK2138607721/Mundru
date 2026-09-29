package com.lumina.cleaner.core.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Mundru 数据库持久化实体定义 (Room Entities)
 *
 * 品牌释义：Mundru 源自格陵兰语（Greenlandic），意为「擦掉 / 擦除」。
 * 架构定位：构建纯本地离线照片与回收站索引，支持数万张相片毫秒级状态查询与 24h 冷却衰减。
 */
@Entity(
    tableName = "photos",
    indices = [
        Index(value = ["dateModified"]),
        Index(value = ["bucketName"]),
        Index(value = ["lastViewedAt"])
    ]
)
data class PhotoEntity(
    @PrimaryKey val id: Long,
    val uriString: String,
    val displayName: String,
    val dateModified: Long,
    val dateTaken: Long,
    val size: Long,
    val width: Int,
    val height: Int,
    val bucketName: String?,
    val relativePath: String?,
    val isLivePhoto: Boolean = false,
    val motionVideoOffset: Long = 0L,
    val motionVideoLength: Long = 0L,
    val isHdr: Boolean = false,
    val viewCount: Int = 0,
    val lastViewedAt: Long = 0L
)

/**
 * Mundru 本地回收站暂存缓冲实体 (Local Trash)
 *
 * 擦除设计：在发起物理删除前作为中间缓冲池，避免频繁弹出系统授权弹窗，
 * 支持用户在应用内多选撤销与单次聚合批量彻底销毁。
 */
@Entity(tableName = "local_trash")
data class LocalTrashEntity(
    @PrimaryKey val photoId: Long,
    val uriString: String,
    val size: Long,
    val deletedAt: Long
)
