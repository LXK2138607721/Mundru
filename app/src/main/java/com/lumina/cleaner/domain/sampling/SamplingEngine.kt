package com.lumina.cleaner.domain.sampling

import com.lumina.cleaner.core.model.MonthBucket
import com.lumina.cleaner.core.model.PhotoItem
import com.lumina.cleaner.core.model.SamplingMode
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Mundru 智能抽样引擎 v1.6 (SamplingEngine)
 * 品牌释义：Mundru 源自格陵兰语（Greenlandic），意为「擦掉 / 擦除」。
 *
 * v1.6 纯本地算法深度升级（0 第三方依赖、0 联网、全量向后兼容）：
 * 1. 自适应阶梯冷却 (Adaptive Tiered Cooldown)：
 *    根据单张照片累计保留审阅次数 (viewCount) 动态拉伸冷却期：
 *    viewCount <= 1 -> 24 小时；2 -> 7 天；3 -> 30 天；>= 4 -> 90 天，高频保留珍藏照不再反复打扰；
 * 2. 未审阅优先 (Unreviewed-First)：
 *    时间轴每个分层内优先推送 viewCount == 0 的全新照片，再按低审阅次数递增兜底；
 * 3. 高清理价值雷达 (Junk-Value Radar)：
 *    纯元数据 O(1) 识别 >30 天旧截图、>14 天社交/下载缓存目录 (QQ/WeChat/Tieba/Download 等) 及连拍成员，
 *    在随机漫步中温和混入约 28% 高清理价值候选，既保留回忆惊喜感又大幅提升单批腾空收益；
 * 4. 连拍 (<=5s 同图集) 与 64-bit dHash (汉明距离 <=8) 相邻成组：
 *    同分层内自动吸纳少量连拍/相似同伴（单组上限 4 张防刷屏），并将同组照片严格相邻排布方便对比秒删。
 */
class SamplingEngine(
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default
) {

    companion object {
        const val COOLDOWN_WINDOW_MS = 24 * 3600 * 1000L                 // 1 次审阅：24 小时严格冷却
        const val COOLDOWN_TIER_2_MS = 7L * COOLDOWN_WINDOW_MS           // 2 次审阅：7 天冷却
        const val COOLDOWN_TIER_3_MS = 30L * COOLDOWN_WINDOW_MS          // 3 次审阅：30 天冷却
        const val COOLDOWN_TIER_4_MS = 90L * COOLDOWN_WINDOW_MS          // >=4 次审阅：90 天长效冷却
        const val TIME_CAPSULE_MIN_AGE_MS = 180L * 24 * 3600 * 1000L     // 时光考古门槛：至少 180 天前
        const val BURST_WINDOW_MS = 5_000L                               // 连拍判定时间窗：5 秒内同图集
        const val MAX_BURST_CLUSTER_IN_BATCH = 4                         // 单组连拍/相似在单批中的最大占比上限
        private const val OLD_SCREENSHOT_AGE_MS = 30L * COOLDOWN_WINDOW_MS
        private const val OLD_SOCIAL_CACHE_AGE_MS = 14L * COOLDOWN_WINDOW_MS
        private const val JUNK_MIX_RATIO = 0.28f

        private val SCREENSHOT_KEYWORDS = listOf(
            "screenshot", "screen_shot", "screenrecord", "截图", "截屏"
        )
        private val SOCIAL_CACHE_KEYWORDS = listOf(
            "wechat", "weixin", "qq", "tim", "tieba", "weibo",
            "xiaohongshu", "download", "cache", "temp", "saved pictures", "图片缓存"
        )

        /** 根据累计审阅保留次数计算自适应阶梯冷却时长 */
        fun cooldownWindowMs(viewCount: Int): Long = when {
            viewCount <= 1 -> COOLDOWN_WINDOW_MS
            viewCount == 2 -> COOLDOWN_TIER_2_MS
            viewCount == 3 -> COOLDOWN_TIER_3_MS
            else -> COOLDOWN_TIER_4_MS
        }
    }

    /**
     * 主分发入口（全模式接入 viewHistoryMap + viewCountMap + dHashMap 统一智能抽样与阶梯冷却）
     */
    suspend fun generateBatch(
        mode: SamplingMode,
        allPhotos: List<PhotoItem>,
        batchSize: Int,
        viewHistoryMap: Map<Long, Long> = emptyMap(),
        targetAlbumBucket: String? = null,
        targetMonthBucket: MonthBucket? = null,
        viewCountMap: Map<Long, Int> = emptyMap(),
        dHashMap: Map<Long, Long> = emptyMap()
    ): List<PhotoItem> = withContext(Dispatchers.Default) {
        when (mode) {
            SamplingMode.RANDOM_WALK -> getStratifiedRandomBatch(
                allPhotos, batchSize, viewHistoryMap, viewCountMap, dHashMap
            )
            SamplingMode.TIME_CAPSULE -> getTimeCapsuleBatch(
                allPhotos, batchSize, viewHistoryMap, viewCountMap
            )
            SamplingMode.ALBUM_CLEANUP -> getAlbumCleanupBatch(
                allPhotos, batchSize, targetAlbumBucket, viewHistoryMap, viewCountMap
            )
            SamplingMode.MONTH_CLEANUP -> getMonthCleanupBatch(
                allPhotos, batchSize, targetMonthBucket, viewHistoryMap, viewCountMap
            )
            SamplingMode.LARGE_PHOTO_CLEANUP -> getLargePhotoCleanupBatch(
                allPhotos, batchSize, viewHistoryMap, viewCountMap
            )
        }
    }

    /**
     * 核心阶梯冷却分流器：
     * 全模式严格排除尚在对应阶梯冷却期内的已审阅项，同一批次按 MediaStore ID 去重。
     */
    private fun filterWithCooldown(
        candidates: List<PhotoItem>,
        viewHistory: Map<Long, Long>,
        viewCountMap: Map<Long, Int> = emptyMap(),
        now: Long = clock()
    ): List<PhotoItem> {
        if (candidates.isEmpty()) return emptyList()

        val freshPhotos = mutableListOf<PhotoItem>()
        val ids = mutableSetOf<Long>()

        for (photo in candidates) {
            val lastViewed = viewHistory[photo.id] ?: 0L
            val viewCount = viewCountMap[photo.id] ?: if (lastViewed > 0L) 1 else 0
            val requiredCooldown = cooldownWindowMs(viewCount)
            val elapsed = now - lastViewed
            if ((lastViewed <= 0L || elapsed >= requiredCooldown) && ids.add(photo.id)) {
                freshPhotos.add(photo)
            }
        }

        return freshPhotos
    }

    /** 判定单张照片是否属于高清理价值候选（旧截图 / 社交缓存 / 连拍成员） */
    internal fun isJunkCandidate(
        photo: PhotoItem,
        burstMemberIds: Set<Long>,
        now: Long = clock()
    ): Boolean {
        if (photo.id in burstMemberIds) return true
        val photoTime = photo.effectiveTime()
        val ageMs = if (photoTime > 0L) (now - photoTime).coerceAtLeast(0L) else 0L
        val bucketLower = photo.bucketName.orEmpty().lowercase(Locale.ROOT)
        val nameLower = photo.displayName.lowercase(Locale.ROOT)

        if (ageMs >= OLD_SCREENSHOT_AGE_MS &&
            SCREENSHOT_KEYWORDS.any { kw -> bucketLower.contains(kw) || nameLower.contains(kw) }
        ) {
            return true
        }
        if (ageMs >= OLD_SOCIAL_CACHE_AGE_MS &&
            SOCIAL_CACHE_KEYWORDS.any { kw -> bucketLower.contains(kw) }
        ) {
            return true
        }
        return false
    }

    /** 找出候选池中属于 5 秒内同图集连拍的所有照片 ID */
    private fun findBurstMemberIds(photos: List<PhotoItem>): Set<Long> {
        if (photos.size < 2) return emptySet()
        val burstIds = HashSet<Long>()
        val byBucket = photos.filter { !it.bucketName.isNullOrBlank() }.groupBy { it.bucketName!! }
        for ((_, group) in byBucket) {
            if (group.size < 2) continue
            val sorted = group.sortedBy { it.effectiveTime() }
            for (i in 0 until sorted.lastIndex) {
                val ta = sorted[i].effectiveTime()
                val tb = sorted[i + 1].effectiveTime()
                if (ta > 0L && tb > 0L && abs(tb - ta) <= BURST_WINDOW_MS) {
                    burstIds.add(sorted[i].id)
                    burstIds.add(sorted[i + 1].id)
                }
            }
        }
        return burstIds
    }

    private fun arePairRelated(a: PhotoItem, b: PhotoItem, dHashMap: Map<Long, Long>): Boolean {
        if (!a.bucketName.isNullOrBlank() && a.bucketName == b.bucketName) {
            val ta = a.effectiveTime()
            val tb = b.effectiveTime()
            if (ta > 0L && tb > 0L && abs(ta - tb) <= BURST_WINDOW_MS) {
                return true
            }
        }
        val ha = dHashMap[a.id]
        val hb = dHashMap[b.id]
        if (ha != null && hb != null && PerceptualHash.isVisuallySimilar(ha, hb)) {
            return true
        }
        return false
    }

    /**
     * 在单层内优先选未审阅照片 + 约 28% 高清理价值雷达 + 同层连拍/相似同伴拉拢（单组<=4张）。
     */
    private fun sampleWithinStratum(
        stratum: List<PhotoItem>,
        quota: Int,
        viewCountMap: Map<Long, Int>,
        burstMemberIds: Set<Long>,
        dHashMap: Map<Long, Long>,
        now: Long
    ): List<PhotoItem> {
        if (quota <= 0 || stratum.isEmpty()) return emptyList()
        if (stratum.size <= quota) return stratum

        val unreviewed = stratum.filter { (viewCountMap[it.id] ?: 0) == 0 }
        val reviewed = stratum.filter { (viewCountMap[it.id] ?: 0) > 0 }

        val picked = ArrayList<PhotoItem>(quota)
        val pickedIds = HashSet<Long>(quota * 2)

        fun pickBalancedPool(pool: List<PhotoItem>, needed: Int) {
            if (needed <= 0 || pool.isEmpty()) return
            if (pool.size <= needed) {
                for (p in pool.shuffled(random)) {
                    if (pickedIds.add(p.id)) picked.add(p)
                }
                return
            }
            val junkPool = pool.filter { isJunkCandidate(it, burstMemberIds, now) }.shuffled(random)
            val normalPool = pool.filterNot { isJunkCandidate(it, burstMemberIds, now) }.shuffled(random)

            if (junkPool.isEmpty() || normalPool.isEmpty()) {
                for (p in pool.shuffled(random)) {
                    if (picked.size >= quota) break
                    if (pickedIds.add(p.id)) picked.add(p)
                }
                return
            }

            // 期望严格等于 needed * 0.28，兼顾回忆与清理效率
            val targetJunk = floor(needed * JUNK_MIX_RATIO + random.nextDouble()).toInt()
                .coerceIn(0, minOf(needed, junkPool.size))
            val targetNormal = (needed - targetJunk).coerceAtMost(normalPool.size)

            for (i in 0 until targetJunk) {
                val p = junkPool[i]
                if (pickedIds.add(p.id)) picked.add(p)
            }
            for (i in 0 until targetNormal) {
                val p = normalPool[i]
                if (pickedIds.add(p.id)) picked.add(p)
            }
            // 若任一池不足，从剩余项补齐
            if (picked.size < quota) {
                val rest = (junkPool.drop(targetJunk) + normalPool.drop(targetNormal)).shuffled(random)
                for (p in rest) {
                    if (picked.size >= quota) break
                    if (pickedIds.add(p.id)) picked.add(p)
                }
            }
        }

        // 1. 未审阅照片优先
        pickBalancedPool(unreviewed, quota)

        // 2. 若未审阅不足，按审阅次数从低到高补足
        if (picked.size < quota && reviewed.isNotEmpty()) {
            val byCount = reviewed.groupBy { viewCountMap[it.id] ?: 1 }.toSortedMap()
            for ((_, group) in byCount) {
                if (picked.size >= quota) break
                pickBalancedPool(group, quota - picked.size)
            }
        }

        // 3. 同层连拍 / 相似同伴拉拢：若已选种子存在未入选的同层连拍或 dHash 相似同伴，
        //    替换为同层普通孤立项（单组上限 MAX_BURST_CLUSTER_IN_BATCH），让用户一次对比清理
        if (picked.size >= 2 && stratum.size > picked.size) {
            val remaining = stratum.filter { it.id !in pickedIds }
            val hasPotentialRelations = burstMemberIds.isNotEmpty() || dHashMap.isNotEmpty()
            if (hasPotentialRelations && remaining.isNotEmpty()) {
                val seed = picked.firstOrNull { candidate ->
                    remaining.any { other -> arePairRelated(candidate, other, dHashMap) }
                }
                if (seed != null) {
                    val alreadyInGroup = picked.count { it.id == seed.id || arePairRelated(seed, it, dHashMap) }
                    val canAdd = (MAX_BURST_CLUSTER_IN_BATCH - alreadyInGroup).coerceAtLeast(0)
                    if (canAdd > 0) {
                        val companions = remaining
                            .filter { arePairRelated(seed, it, dHashMap) }
                            .sortedBy { abs(it.effectiveTime() - seed.effectiveTime()) }
                            .take(canAdd)
                        // 找出可被温和替换的孤立非种子项
                        val replaceableIndices = picked.indices.filter { idx ->
                            val item = picked[idx]
                            item.id != seed.id && !arePairRelated(seed, item, dHashMap)
                        }.reversed()

                        val swapCount = minOf(companions.size, replaceableIndices.size)
                        for (i in 0 until swapCount) {
                            val replaceAt = replaceableIndices[i]
                            pickedIds.remove(picked[replaceAt].id)
                            picked[replaceAt] = companions[i]
                            pickedIds.add(companions[i].id)
                        }
                    }
                }
            }
        }

        return picked
    }

    /**
     * 将最终批次中的连拍 (<=5s 同图集) 与 dHash 高度相似 (汉明距离 <=8) 照片严格相邻排布：
     * - 同组内部按拍摄时间正序排列，方便像翻连环画一样瞬间比对挑选最佳帧；
     * - 不同分组之间随机打乱，保持随机漫步的探索惊喜感。
     */
    internal fun arrangeAdjacentBurstsAndSimilar(
        selected: List<PhotoItem>,
        dHashMap: Map<Long, Long>
    ): List<PhotoItem> {
        val n = selected.size
        if (n <= 1) return selected

        val parent = IntArray(n) { it }
        fun find(x: Int): Int {
            var curr = x
            while (parent[curr] != curr) {
                parent[curr] = parent[parent[curr]]
                curr = parent[curr]
            }
            return curr
        }
        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[rb] = ra
        }

        for (i in 0 until n) {
            for (j in i + 1 until n) {
                if (arePairRelated(selected[i], selected[j], dHashMap)) {
                    union(i, j)
                }
            }
        }

        val clusters = (0 until n)
            .groupBy { find(it) }
            .values
            .map { indices ->
                indices.map { selected[it] }
                    .sortedWith(compareBy<PhotoItem> { it.effectiveTime() }.thenBy { it.id })
            }
            .shuffled(random)

        val ordered = ArrayList<PhotoItem>(n)
        for (cluster in clusters) {
            ordered.addAll(cluster)
        }
        return ordered
    }

    /**
     * 1. 智能分层随机漫步算法（Stratified Random Sampling v1.6）
     * 时间轴分层 + 自适应阶梯冷却 + 未审阅优先 + 高清理价值雷达 + 连拍/dHash 相邻成组
     */
    fun getStratifiedRandomBatch(
        allPhotos: List<PhotoItem>,
        batchSize: Int,
        viewHistory: Map<Long, Long>,
        viewCountMap: Map<Long, Int> = emptyMap(),
        dHashMap: Map<Long, Long> = emptyMap()
    ): List<PhotoItem> {
        if (batchSize <= 0) return emptyList()
        val now = clock()
        val eligiblePhotos = filterWithCooldown(allPhotos, viewHistory, viewCountMap, now)
        if (eligiblePhotos.size <= batchSize) {
            return arrangeAdjacentBurstsAndSimilar(eligiblePhotos, dHashMap)
        }

        val burstMemberIds = findBurstMemberIds(eligiblePhotos)
        val sorted = eligiblePhotos.sortedWith(compareBy<PhotoItem> { it.effectiveTime() }.thenBy { it.id })

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
            selected.addAll(
                sampleWithinStratum(
                    stratum = sorted.subList(start, end),
                    quota = quota,
                    viewCountMap = viewCountMap,
                    burstMemberIds = burstMemberIds,
                    dHashMap = dHashMap,
                    now = now
                )
            )
        }
        return arrangeAdjacentBurstsAndSimilar(selected, dHashMap)
    }

    /**
     * 2. 时光考古：专注至少 180 天前的历史老照片 + 那年今日
     * 无半年老照片时使用至少 30 天的老照片，全分支严格应用阶梯冷却与未审阅优先。
     */
    fun getTimeCapsuleBatch(
        allPhotos: List<PhotoItem>,
        batchSize: Int,
        viewHistory: Map<Long, Long> = emptyMap(),
        viewCountMap: Map<Long, Int> = emptyMap()
    ): List<PhotoItem> {
        if (batchSize <= 0) return emptyList()
        val now = clock()
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()

        val historicalPhotos = allPhotos.filter { photo ->
            val photoTime = photo.effectiveTime()
            val ageMs = now - photoTime
            photoTime > 0L && ageMs >= TIME_CAPSULE_MIN_AGE_MS
        }

        if (historicalPhotos.isEmpty()) {
            val olderFallback = allPhotos
                .filter { photo ->
                    val photoTime = photo.effectiveTime()
                    photoTime > 0L && (now - photoTime) >= 30L * COOLDOWN_WINDOW_MS
                }
                .sortedWith(compareBy<PhotoItem> { viewCountMap[it.id] ?: 0 }.thenBy { it.effectiveTime() })
            return filterWithCooldown(olderFallback, viewHistory, viewCountMap, now).take(batchSize)
        }

        val cooledHistorical = filterWithCooldown(historicalPhotos, viewHistory, viewCountMap, now)

        val onThisDayPhotos = cooledHistorical.filter { photo ->
            val photoTime = photo.effectiveTime()
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

        val oldestHistorical = cooledHistorical
            .filter { it.id !in pickedIds }
            .sortedWith(compareBy<PhotoItem> { viewCountMap[it.id] ?: 0 }.thenBy { it.effectiveTime() })
            .take(batchSize - result.size)

        result.addAll(oldestHistorical)
        return result
    }

    /**
     * 3. 图集专清：定向清理指定相册，接入阶梯冷却防重
     */
    fun getAlbumCleanupBatch(
        allPhotos: List<PhotoItem>,
        batchSize: Int,
        targetAlbumBucket: String?,
        viewHistory: Map<Long, Long> = emptyMap(),
        viewCountMap: Map<Long, Int> = emptyMap()
    ): List<PhotoItem> {
        if (batchSize <= 0) return emptyList()
        val filtered = if (targetAlbumBucket != null) {
            allPhotos.filter { it.bucketName == targetAlbumBucket }
        } else {
            allPhotos
        }
        if (filtered.isEmpty()) return emptyList()

        val cooled = filterWithCooldown(filtered, viewHistory, viewCountMap)
        return cooled.sortedByDescending { it.effectiveTime() }.take(batchSize)
    }

    /**
     * 4. 月份专清：定向清理指定月份区间，接入阶梯冷却防重
     */
    fun getMonthCleanupBatch(
        allPhotos: List<PhotoItem>,
        batchSize: Int,
        targetMonthBucket: MonthBucket?,
        viewHistory: Map<Long, Long> = emptyMap(),
        viewCountMap: Map<Long, Int> = emptyMap()
    ): List<PhotoItem> {
        if (batchSize <= 0) return emptyList()
        val filtered = if (targetMonthBucket != null) {
            allPhotos.filter {
                val time = it.effectiveTime()
                time in targetMonthBucket.startMillis until targetMonthBucket.endMillis
            }
        } else {
            allPhotos
        }
        if (filtered.isEmpty()) return emptyList()

        val cooled = filterWithCooldown(filtered, viewHistory, viewCountMap)
        return cooled.sortedByDescending { it.effectiveTime() }.take(batchSize)
    }

    /**
     * 5. 大图专清：仅使用已索引的文件字节数，接入阶梯冷却防重
     */
    internal fun getLargePhotoCleanupBatch(
        allPhotos: List<PhotoItem>,
        batchSize: Int,
        viewHistory: Map<Long, Long> = emptyMap(),
        viewCountMap: Map<Long, Int> = emptyMap()
    ): List<PhotoItem> {
        if (batchSize <= 0) return emptyList()
        return filterWithCooldown(allPhotos.filter { it.hasKnownFileSize }, viewHistory, viewCountMap)
            .sortedWith(compareByDescending<PhotoItem> { it.size }.thenBy { it.id })
            .take(batchSize)
    }

    /**
     * 提取相册内所有可用的历史月份归档列表（整型键零字符串分配，3000+ 照片 < 0.5ms）
     */
    fun extractMonthBuckets(allPhotos: List<PhotoItem>): List<MonthBucket> {
        if (allPhotos.isEmpty()) return emptyList()
        val calendar = Calendar.getInstance()
        val counts = HashMap<Int, Int>(48)

        for (photo in allPhotos) {
            val time = photo.effectiveTime()
            calendar.timeInMillis = time
            val key = calendar.get(Calendar.YEAR) * 100 + (calendar.get(Calendar.MONTH) + 1)
            counts[key] = (counts[key] ?: 0) + 1
        }

        val cal = Calendar.getInstance()
        return counts.entries.map { (key, count) ->
            val year = key / 100
            val month = key % 100
            cal.clear()
            cal.set(year, month - 1, 1, 0, 0, 0)
            val startMillis = cal.timeInMillis
            cal.add(Calendar.MONTH, 1)
            val endMillis = cal.timeInMillis

            MonthBucket(
                year = year,
                month = month,
                displayName = "${year}年${month}月",
                count = count,
                startMillis = startMillis,
                endMillis = endMillis
            )
        }.sortedByDescending { it.startMillis }
    }

    private fun PhotoItem.effectiveTime(): Long =
        dateTaken.takeIf { it > 0L } ?: dateModified
}

internal val PhotoItem.hasKnownFileSize: Boolean get() = size > 0L
