package com.lumina.cleaner.domain.sampling

import android.content.Context
import com.lumina.cleaner.core.model.MonthBucket
import com.lumina.cleaner.core.model.PhotoItem
import com.lumina.cleaner.core.model.SamplingMode
import java.util.Calendar
import java.util.Locale
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Mundru 智能抽样引擎 (SamplingEngine)
 * 品牌释义：Mundru 源自格陵兰语（Greenlandic），意为「擦掉 / 擦除」。
 * 引擎定位：相册智能分层抽样核心，驱动碎片化漫步、时光考古、图集与月份定向整理。
 *
 * 核心整理模式（4 大黄金板块）：
 * 1. 分层随机漫步 (Random Walk)：时间轴分桶 + 严格 24 小时冷却，保证全相册漫游且绝不反复重复
 * 2. 时光考古 (Time Capsule)：优先至少 180 天前的老照片；无半年老照片时保留至少 30 天的原有降级规则
 * 3. 图集专清 (Album Cleanup)：指定相册深度清理，加入冷却游标机制，每批推送未整理的新照片
 * 4. 月份专清 (Month Cleanup)：指定历史月份归档清理，加入冷却游标机制
 *
 * 严格去重与冷却契约：
 * 任何被左滑保留（不打算清理）的图片，在 24 小时冷却保护期内绝对不会在后续轮次中重复出现；
 * 候选不足时返回实际剩余张数，不用冷却中的照片补满。
 */
class SamplingEngine(
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default
) {

    companion object {
        const val COOLDOWN_WINDOW_MS = 24 * 3600 * 1000L             // 24 小时严格冷却保护期
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
    ): List<PhotoItem> = withContext(Dispatchers.Default) {
        when (mode) {
            SamplingMode.RANDOM_WALK -> getStratifiedRandomBatch(allPhotos, batchSize, viewHistoryMap)
            SamplingMode.TIME_CAPSULE -> getTimeCapsuleBatch(allPhotos, batchSize, viewHistoryMap)
            SamplingMode.ALBUM_CLEANUP -> getAlbumCleanupBatch(allPhotos, batchSize, targetAlbumBucket, viewHistoryMap)
            SamplingMode.MONTH_CLEANUP -> getMonthCleanupBatch(allPhotos, batchSize, targetMonthBucket, viewHistoryMap)
        }
    }

    /**
     * 核心冷却分流器：
     * 全模式严格排除 24 小时内已审阅项，同一批次按 MediaStore ID 去重。
     */
    private fun filterWithCooldown(
        candidates: List<PhotoItem>,
        viewHistory: Map<Long, Long>,
        now: Long = clock()
    ): List<PhotoItem> {
        if (candidates.isEmpty()) return emptyList()

        val freshPhotos = mutableListOf<PhotoItem>()
        val ids = mutableSetOf<Long>()

        for (photo in candidates) {
            val lastViewed = viewHistory[photo.id] ?: 0L
            val elapsed = now - lastViewed
            if ((lastViewed <= 0L || elapsed >= COOLDOWN_WINDOW_MS) && ids.add(photo.id)) {
                freshPhotos.add(photo)
            }
        }

        return freshPhotos
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
        if (batchSize <= 0) return emptyList()
        val eligiblePhotos = filterWithCooldown(allPhotos, viewHistory)
        if (eligiblePhotos.size <= batchSize) return eligiblePhotos.shuffled(random)

        val sorted = eligiblePhotos.sortedBy { it.dateTaken.takeIf { t -> t > 0 } ?: it.dateModified }

        val bucketCount = 5.coerceAtMost(batchSize)
        val selected = mutableListOf<PhotoItem>()
        val baseQuota = batchSize / bucketCount
        val extraBuckets = (0 until bucketCount).filter { index ->
            ((index + 1L) * sorted.size / bucketCount) - (index.toLong() * sorted.size / bucketCount) > baseQuota
        }.shuffled(random).take(batchSize % bucketCount).toSet()
        for (index in 0 until bucketCount) {
            // Exactly bucketCount non-empty strata, including both ends of the timeline.
            val start = (index.toLong() * sorted.size / bucketCount).toInt()
            val end = ((index + 1L) * sorted.size / bucketCount).toInt()
            val quota = baseQuota + if (index in extraBuckets) 1 else 0
            selected.addAll(sorted.subList(start, end).shuffled(random).take(quota))
        }
        return selected.shuffled(random)
    }

    /**
     * 2. 时光考古：专注至少 180 天前的历史老照片 + 那年今日
     * 无半年老照片时使用至少 30 天的老照片，全分支严格应用冷却防重。
     */
    fun getTimeCapsuleBatch(
        allPhotos: List<PhotoItem>,
        batchSize: Int,
        viewHistory: Map<Long, Long> = emptyMap()
    ): List<PhotoItem> {
        if (batchSize <= 0) return emptyList()
        val now = clock()
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()

        // 严格条件：只允许至少 180 天前的历史深水区照片
        val historicalPhotos = allPhotos.filter { photo ->
            val photoTime = if (photo.dateTaken > 0) photo.dateTaken else photo.dateModified
            val ageMs = now - photoTime
            photoTime > 0L && ageMs >= TIME_CAPSULE_MIN_AGE_MS
        }

        if (historicalPhotos.isEmpty()) {
            // 保留原有降级规则：没有半年老照片时，按最老优先取至少 30 天前的照片。
            val olderFallback = allPhotos
                .filter { photo ->
                    val photoTime = if (photo.dateTaken > 0) photo.dateTaken else photo.dateModified
                    photoTime > 0L && (now - photoTime) >= 30L * 24 * 3600 * 1000L
                }
                .sortedBy { if (it.dateTaken > 0) it.dateTaken else it.dateModified }
            return filterWithCooldown(olderFallback, viewHistory, now).take(batchSize)
        }

        // 应用冷却过滤，排除近期刚看过的
        val cooledHistorical = filterWithCooldown(historicalPhotos, viewHistory, now)

        // 步骤 1：那年今日（±5 天），窗口允许跨月、跨年，闰日映射到非闰年的 2 月 28 日。
        val onThisDayPhotos = cooledHistorical.filter { photo ->
            val photoTime = if (photo.dateTaken > 0) photo.dateTaken else photo.dateModified
            val date = Instant.ofEpochMilli(photoTime).atZone(zone).toLocalDate()
            val anniversary = date.withYear(today.year)
            date.year < today.year && (-1L..1L).any { yearOffset ->
                abs(ChronoUnit.DAYS.between(today, anniversary.plusYears(yearOffset))) <= 5L
            }
        }.shuffled(random)

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
        if (batchSize <= 0) return emptyList()
        val filtered = if (targetAlbumBucket != null) {
            allPhotos.filter { it.bucketName == targetAlbumBucket }
        } else {
            allPhotos
        }
        if (filtered.isEmpty()) return emptyList()

        val cooled = filterWithCooldown(filtered, viewHistory)
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
        if (batchSize <= 0) return emptyList()
        val filtered = if (targetMonthBucket != null) {
            allPhotos.filter {
                val time = if (it.dateTaken > 0) it.dateTaken else it.dateModified
                time in targetMonthBucket.startMillis until targetMonthBucket.endMillis
            }
        } else {
            allPhotos
        }
        if (filtered.isEmpty()) return emptyList()

        val cooled = filterWithCooldown(filtered, viewHistory)
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
            val key = String.format(Locale.ROOT, "%04d-%02d", year, month)
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
