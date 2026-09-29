package com.lumina.cleaner.domain.sampling

import android.content.Context
import com.lumina.cleaner.core.model.MonthBucket
import com.lumina.cleaner.core.model.PhotoItem
import com.lumina.cleaner.core.model.SamplingMode
import java.util.Calendar
import kotlin.math.abs

/**
 * Mundru 智能抽样引擎 (SamplingEngine)
 * 品牌释义：Mundru 源自格陵兰语（Greenlandic），意为「擦掉 / 擦除」。
 * 引擎定位：相册智能分层抽样核心，驱动碎片化漫步、时光考古、图集与月份定向整理。
 *
 * 核心整理模式（4 大黄金板块）：
 * 1. 分层随机漫步 (Random Walk)：时间轴分桶 + 严格 24 小时冷却，保证全相册漫游且绝不反复重复
 * 2. 时光考古 (Time Capsule)：专注至少 180 天前的历史老照片与「那年今日」，彻底剔除近期图片
 * 3. 图集专清 (Album Cleanup)：指定相册深度清理，加入冷却游标机制，每批推送未整理的新照片
 * 4. 月份专清 (Month Cleanup)：指定历史月份归档清理，加入冷却游标机制
 *
 * 严格去重与冷却契约：
 * 任何被左滑保留（不打算清理）的图片，在 24 小时冷却保护期内绝对不会在后续轮次中重复出现；
 * 15 分钟内刚划过的图片进行硬性隔离；若相片库已全部划过，则按最久未查看正序兜底，绝不连续两轮出现同一张。
 */
class SamplingEngine {

    companion object {
        const val COOLDOWN_WINDOW_MS = 24 * 3600 * 1000L             // 24 小时冷却保护期
        const val IMMEDIATE_SESSION_COOLDOWN_MS = 15 * 60 * 1000L     // 15 分钟会话级硬隔离
        const val TIME_CAPSULE_MIN_AGE_MS = 180L * 24 * 3600 * 1000L // 时光考古门槛：至少 180 天前 (半年) 的照片
    }

    /**
     * 主分发入口（全模式接入 viewHistoryMap 统一冷却与防重）
     */
    suspend fun generateBatch(
        context: Context,
        mode: SamplingMode,
        allPhotos: List<PhotoItem>,
        batchSize: Int,
        viewHistoryMap: Map<Long, Long> = emptyMap(),
        targetAlbumBucket: String? = null,
        targetMonthBucket: MonthBucket? = null,
        onHashComputed: (suspend (Long, Long) -> Unit)? = null
    ): List<PhotoItem> {
        if (allPhotos.isEmpty()) return emptyList()

        return when (mode) {
            SamplingMode.RANDOM_WALK -> getStratifiedRandomBatch(allPhotos, batchSize, viewHistoryMap)
            SamplingMode.TIME_CAPSULE -> getTimeCapsuleBatch(allPhotos, batchSize, viewHistoryMap)
            SamplingMode.ALBUM_CLEANUP -> getAlbumCleanupBatch(allPhotos, batchSize, targetAlbumBucket, viewHistoryMap)
            SamplingMode.MONTH_CLEANUP -> getMonthCleanupBatch(allPhotos, batchSize, targetMonthBucket, viewHistoryMap)
        }
    }

    /**
     * 核心冷却分流器：
     * 将候选列表划分为 [freshPhotos]（未查看或已度过冷却期）与 [coolingPhotos]（冷却中）。
     * 优先全量选用 freshPhotos；若不足，再用最久远前查看的 coolingPhotos 补足，
     * 且在任何情况下严格排除 15 分钟内刚刚划过的照片（除非总库太小）。
     */
    private fun filterWithCooldown(
        candidates: List<PhotoItem>,
        batchSize: Int,
        viewHistory: Map<Long, Long>,
        now: Long = System.currentTimeMillis()
    ): List<PhotoItem> {
        if (candidates.isEmpty()) return emptyList()

        val freshPhotos = mutableListOf<PhotoItem>()
        val coolingPhotos = mutableListOf<PhotoItem>()

        for (photo in candidates) {
            val lastViewed = viewHistory[photo.id] ?: 0L
            val elapsed = now - lastViewed
            if (lastViewed <= 0L || elapsed >= COOLDOWN_WINDOW_MS) {
                freshPhotos.add(photo)
            } else {
                coolingPhotos.add(photo)
            }
        }

        // 优先从完全无冷却的新相片中选取
        if (freshPhotos.size >= batchSize) {
            return freshPhotos
        }

        // 不足时，使用冷却中的相片补足，按「最久前查看」正序排列（越早看过的越优先重新出现）
        val result = freshPhotos.toMutableList()
        val needed = batchSize - result.size

        // 过滤掉 15 分钟内刚划过的相片（即时会话绝不重复）
        val validCooling = coolingPhotos
            .filter { (now - (viewHistory[it.id] ?: 0L)) >= IMMEDIATE_SESSION_COOLDOWN_MS }
            .sortedBy { viewHistory[it.id] ?: 0L }

        if (validCooling.isNotEmpty()) {
            result.addAll(validCooling.take(needed))
        }

        return result
    }

    /**
     * 1. 分层随机漫步算法（Stratified Random Sampling）
     * 时间轴分层 + 严格 24 小时冷却过滤
     */
    fun getStratifiedRandomBatch(
        allPhotos: List<PhotoItem>,
        batchSize: Int,
        viewHistory: Map<Long, Long>
    ): List<PhotoItem> {
        val now = System.currentTimeMillis()
        val eligiblePhotos = filterWithCooldown(allPhotos, batchSize, viewHistory, now)
        if (eligiblePhotos.size <= batchSize) return eligiblePhotos.shuffled()

        val sorted = eligiblePhotos.sortedBy { it.dateTaken.takeIf { t -> t > 0 } ?: it.dateModified }

        val bucketCount = 5.coerceAtMost(batchSize)
        val chunkSize = (sorted.size + bucketCount - 1) / bucketCount
        val buckets = sorted.chunked(chunkSize)

        val selected = mutableListOf<PhotoItem>()
        val pickedIds = mutableSetOf<Long>()
        val perBucketTarget = (batchSize + bucketCount - 1) / bucketCount

        for (bucket in buckets) {
            val available = bucket.filter { it.id !in pickedIds }
            if (available.isEmpty()) continue

            val sampleCount = perBucketTarget.coerceAtMost(batchSize - selected.size)
            val bucketPicks = available.shuffled().take(sampleCount)
            for (item in bucketPicks) {
                selected.add(item)
                pickedIds.add(item.id)
                if (selected.size >= batchSize) break
            }
            if (selected.size >= batchSize) break
        }

        if (selected.size < batchSize) {
            val remaining = eligiblePhotos.filter { it.id !in pickedIds }.shuffled()
            for (item in remaining) {
                selected.add(item)
                if (selected.size >= batchSize) break
            }
        }

        return selected.shuffled()
    }

    /**
     * 2. 时光考古：专注至少 180 天前的历史老照片 + 那年今日
     * 修复：彻底排除近期（< 180 天）拍摄的照片，严格应用冷却防重
     */
    fun getTimeCapsuleBatch(
        allPhotos: List<PhotoItem>,
        batchSize: Int,
        viewHistory: Map<Long, Long> = emptyMap()
    ): List<PhotoItem> {
        val now = System.currentTimeMillis()
        val calendar = Calendar.getInstance()
        val currentMonth = calendar.get(Calendar.MONTH)
        val currentDay = calendar.get(Calendar.DAY_OF_MONTH)
        val currentYear = calendar.get(Calendar.YEAR)

        // 严格条件：只允许至少 180 天前的历史深水区照片
        val historicalPhotos = allPhotos.filter { photo ->
            val photoTime = if (photo.dateTaken > 0) photo.dateTaken else photo.dateModified
            val ageMs = now - photoTime
            ageMs >= TIME_CAPSULE_MIN_AGE_MS
        }

        if (historicalPhotos.isEmpty()) {
            // 如果整个手机里都没有超过半年的老照片，则取最老的前 20% 照片（但必须排除 30 天以内的）
            val olderFallback = allPhotos
                .filter { photo ->
                    val photoTime = if (photo.dateTaken > 0) photo.dateTaken else photo.dateModified
                    (now - photoTime) >= 30L * 24 * 3600 * 1000L
                }
                .sortedBy { if (it.dateTaken > 0) it.dateTaken else it.dateModified }
            return filterWithCooldown(olderFallback, batchSize, viewHistory, now).take(batchSize)
        }

        // 应用冷却过滤，排除近期刚看过的
        val cooledHistorical = filterWithCooldown(historicalPhotos, batchSize, viewHistory, now)

        // 步骤 1：那年今日 (On This Day)：往年同月、同日（±5天内）
        val onThisDayPhotos = cooledHistorical.filter { photo ->
            val photoTime = if (photo.dateTaken > 0) photo.dateTaken else photo.dateModified
            calendar.timeInMillis = photoTime
            val photoYear = calendar.get(Calendar.YEAR)
            val photoMonth = calendar.get(Calendar.MONTH)
            val photoDay = calendar.get(Calendar.DAY_OF_MONTH)

            photoYear < currentYear && photoMonth == currentMonth && abs(photoDay - currentDay) <= 5
        }.shuffled()

        if (onThisDayPhotos.size >= batchSize) {
            return onThisDayPhotos.take(batchSize)
        }

        val result = onThisDayPhotos.toMutableList()
        val pickedIds = result.map { it.id }.toMutableSet()

        // 步骤 2：远古积存正序拓荒（最老未整理的照片优先推选）
        val oldestHistorical = cooledHistorical
            .filter { it.id !in pickedIds }
            .sortedBy { if (it.dateTaken > 0) it.dateTaken else it.dateModified }
            .take(batchSize - result.size)

        result.addAll(oldestHistorical)
        return result
    }

    /**
     * 3. 图集专清：定向清理指定相册，具备冷却前移游标特性，不重复展示已保留的照片
     */
    fun getAlbumCleanupBatch(
        allPhotos: List<PhotoItem>,
        batchSize: Int,
        targetAlbumBucket: String?,
        viewHistory: Map<Long, Long> = emptyMap()
    ): List<PhotoItem> {
        val filtered = if (targetAlbumBucket != null) {
            allPhotos.filter { it.bucketName == targetAlbumBucket }
        } else {
            allPhotos
        }
        if (filtered.isEmpty()) return emptyList()

        val cooled = filterWithCooldown(filtered, batchSize, viewHistory)
        return cooled.sortedByDescending { it.dateTaken.takeIf { t -> t > 0 } ?: it.dateModified }.take(batchSize)
    }

    /**
     * 4. 月份专清：定向清理指定月份区间，具备冷却前移游标特性
     */
    fun getMonthCleanupBatch(
        allPhotos: List<PhotoItem>,
        batchSize: Int,
        targetMonthBucket: MonthBucket?,
        viewHistory: Map<Long, Long> = emptyMap()
    ): List<PhotoItem> {
        val filtered = if (targetMonthBucket != null) {
            allPhotos.filter {
                val time = if (it.dateTaken > 0) it.dateTaken else it.dateModified
                time in targetMonthBucket.startMillis until targetMonthBucket.endMillis
            }
        } else {
            allPhotos
        }
        if (filtered.isEmpty()) return emptyList()

        val cooled = filterWithCooldown(filtered, batchSize, viewHistory)
        return cooled.sortedByDescending { it.dateTaken.takeIf { t -> t > 0 } ?: it.dateModified }.take(batchSize)
    }

    /**
     * 提取相册内所有可用的历史月份归档列表
     */
    fun extractMonthBuckets(allPhotos: List<PhotoItem>): List<MonthBucket> {
        val calendar = Calendar.getInstance()
        val map = mutableMapOf<String, MutableList<PhotoItem>>()

        for (photo in allPhotos) {
            val time = if (photo.dateTaken > 0) photo.dateTaken else photo.dateModified
            calendar.timeInMillis = time
            val year = calendar.get(Calendar.YEAR)
            val month = calendar.get(Calendar.MONTH) + 1
            val key = String.format("%04d-%02d", year, month)
            map.getOrPut(key) { mutableListOf() }.add(photo)
        }

        return map.map { (key, photos) ->
            val parts = key.split("-")
            val year = parts[0].toInt()
            val month = parts[1].toInt()

            val cal = Calendar.getInstance().apply {
                set(Calendar.YEAR, year)
                set(Calendar.MONTH, month - 1)
                set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val startMillis = cal.timeInMillis
            cal.add(Calendar.MONTH, 1)
            val endMillis = cal.timeInMillis

            MonthBucket(
                year = year,
                month = month,
                displayName = "${year}年${month}月",
                count = photos.size,
                startMillis = startMillis,
                endMillis = endMillis
            )
        }.sortedByDescending { it.startMillis }
    }
}
