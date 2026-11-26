package com.lumina.cleaner.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

data class MotionHdrUpdate(
    val id: Long,
    val isLivePhoto: Boolean,
    val offset: Long,
    val length: Long,
    val isHdr: Boolean
)

/**
 * Mundru 数据库访问对象 (PhotoDao)
 *
 * 品牌释义：Mundru 源自格陵兰语（Greenlandic），意为「擦掉 / 擦除」。
 * 核心职责：
 * 1. 提供高效的本地照片检索、浏览计数、实况/HDR 与 64-bit dHash 感知指纹异步缓存；
 * 2. 维护本地回收站暂存状态，提供精准的 PhotoID + URI 双重安全清除机制，防止幽灵残留。
 */
@Dao
interface PhotoDao {

    // ========== 照片元数据查询与同步 ==========

    /**
     * 查询所有未被丢入回收站的有效照片列表（按修改时间倒序排列）
     */
    @Query("SELECT * FROM photos WHERE id NOT IN (SELECT photoId FROM local_trash) ORDER BY dateModified DESC")
    suspend fun getAllActivePhotosSync(): List<PhotoEntity>

    /**
     * 查询相册全量缓存照片列表
     */
    @Query("SELECT * FROM photos")
    suspend fun getAllPhotosSync(): List<PhotoEntity>

    /**
     * 获取累计审阅浏览相片总次数
     */
    @Query("SELECT SUM(viewCount) FROM photos")
    suspend fun getTotalReviewedCount(): Long?

    /**
     * 批量插入或增量更新相片元数据
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateAll(photos: List<PhotoEntity>)

    /**
     * 标记照片已被审阅，更新浏览次数与最近查看时间戳（用于自适应阶梯冷却衰减）
     */
    @Query("UPDATE photos SET viewCount = viewCount + 1, lastViewedAt = :viewedAt WHERE id = :id")
    suspend fun markViewed(id: Long, viewedAt: Long)

    /**
     * 异步持久化解析得到的实况照片（Motion Photo）与高动态范围（HDR）元数据
     */
    @Query("UPDATE photos SET isLivePhoto = :isLivePhoto, motionVideoOffset = :offset, motionVideoLength = :length, isHdr = :isHdr WHERE id = :id")
    suspend fun updateMotionAndHdrMetadata(id: Long, isLivePhoto: Boolean, offset: Long, length: Long, isHdr: Boolean)

    /**
     * 异步持久化纯本地计算的 64-bit 差异感知哈希 (dHash)
     */
    @Query("UPDATE photos SET dHash = :dHash WHERE id = :id")
    suspend fun updateDHash(id: Long, dHash: Long)

    /**
     * 单事务批量标记快滑审阅事件，避免连续快滑逐张提交触发 SQLite WAL checkpoint 尖峰
     */
    @Transaction
    suspend fun markViewedEvents(events: List<Pair<Long, Long>>) {
        for ((id, viewedAt) in events) {
            markViewed(id, viewedAt)
        }
    }

    /**
     * 单事务批量持久化实况照片与 HDR 元数据
     */
    @Transaction
    suspend fun updateMotionAndHdrBatch(updates: List<MotionHdrUpdate>) {
        for (u in updates) {
            updateMotionAndHdrMetadata(
                id = u.id,
                isLivePhoto = u.isLivePhoto,
                offset = u.offset,
                length = u.length,
                isHdr = u.isHdr
            )
        }
    }

    /**
     * 单事务批量持久化 dHash 感知指纹
     */
    @Transaction
    suspend fun updateDHashBatch(entries: List<Pair<Long, Long>>) {
        for ((id, hash) in entries) {
            updateDHash(id, hash)
        }
    }

    // ========== Mundru 回收站暂存与物理擦除双重索引 ==========

    /**
     * 响应式监听本地回收站照片列表（按删除时间倒序流式推送）
     */
    @Query("SELECT * FROM local_trash ORDER BY deletedAt DESC")
    fun getTrashItems(): Flow<List<LocalTrashEntity>>

    /**
     * 将照片放入本地回收站暂存区
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun moveToTrash(item: LocalTrashEntity)

    /**
     * 从回收站单张恢复相片（撤回暂存）
     */
    @Query("DELETE FROM local_trash WHERE photoId = :photoId")
    suspend fun restoreFromTrash(photoId: Long)

    /**
     * 批量恢复相片（从回收站移除暂存）
     */
    @Query("DELETE FROM local_trash WHERE photoId IN (:photoIds)")
    suspend fun restoreFromTrashBatch(photoIds: List<Long>)

    /**
     * 批量标记相片已被审阅（重置冷却保护时间戳）
     */
    @Query("UPDATE photos SET viewCount = viewCount + 1, lastViewedAt = :viewedAt WHERE id IN (:ids)")
    suspend fun markViewedBatch(ids: List<Long>, viewedAt: Long)

    /**
     * 根据 PhotoId 查询单张相片元数据
     */
    @Query("SELECT * FROM photos WHERE id = :id LIMIT 1")
    suspend fun getPhotoById(id: Long): PhotoEntity?

    /** 仅移除与本次系统操作相符的 URI。 */
    @Query("DELETE FROM local_trash WHERE uriString IN (:uriStrings)")
    suspend fun deleteFromTrashByUris(uriStrings: List<String>)
}
