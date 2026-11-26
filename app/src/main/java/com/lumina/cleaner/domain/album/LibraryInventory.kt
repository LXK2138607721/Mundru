package com.lumina.cleaner.domain.album

import android.content.ContentResolver
import android.database.ContentObserver
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull

// Room is a review history, not an inventory. Missing or inaccessible IDs stay in Room.
internal fun activeLibraryCount(visible: Set<Long>, trash: Collection<Long>): Int =
    visible.size - trash.count { it in visible } // local_trash.photoId is the primary key

/**
 * 常驻设置页统计可见 ID 会话解析器：
 * - 每次进入/离开设置页都会清除上一次访问的陈旧 [liveIds] 并递增 [visitToken]，拒绝过期协程回写；
 * - 本次访问的 MediaStore 查询返回前，严格以当前图库快照的可见 ID 集合参与交集扣减，
 *   绝不误扣授权范围外或已被外部删除的回收站记录。
 */
internal data class SettingsVisibleIdsState(
    val visitToken: Int = 0,
    val liveIds: Set<Long>? = null,
    val liveIdsVisitToken: Int = -1
) {
    fun onEnterSettings(): SettingsVisibleIdsState = copy(
        visitToken = visitToken + 1,
        liveIds = null,
        liveIdsVisitToken = -1
    )

    fun onExitSettings(): SettingsVisibleIdsState = copy(
        liveIds = null,
        liveIdsVisitToken = -1
    )

    fun onLiveIdsLoaded(token: Int, ids: Set<Long>): SettingsVisibleIdsState =
        if (token == visitToken) {
            copy(liveIds = ids, liveIdsVisitToken = token)
        } else {
            this
        }

    fun effectiveVisibleIds(snapshotVisibleIds: Set<Long>): Set<Long> =
        if (liveIdsVisitToken == visitToken) liveIds ?: snapshotVisibleIds else snapshotVisibleIds

    fun activeCount(snapshotVisibleIds: Set<Long>, trashIds: Collection<Long>): Int =
        activeLibraryCount(effectiveVisibleIds(snapshotVisibleIds), trashIds)
}

/**
 * 按真实输入变更惰性重算交集计数（避免无关重组重复遍历回收站 ID，同时不拖慢转场）。
 */
internal class ActiveLibraryCountCache {
    private var lastVisible: Set<Long>? = null
    private var lastTrash: Collection<Long>? = null
    private var lastCount: Int = 0
    internal var computeCalls: Int = 0
        private set

    fun getOrCompute(visibleIds: Set<Long>, trashIds: Collection<Long>): Int {
        if (lastVisible === visibleIds && lastTrash === trashIds) {
            return lastCount
        }
        lastVisible = visibleIds
        lastTrash = trashIds
        computeCalls++
        lastCount = activeLibraryCount(visibleIds, trashIds)
        return lastCount
    }
}

/** Observe only while the statistics are on screen. Refresh once on each subscription. */
internal fun ContentResolver.visibleImageIds() = callbackFlow<Unit> {
    val observer = object : ContentObserver(null) {
        override fun onChange(selfChange: Boolean) { trySend(Unit) }
    }
    val images = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    registerContentObserver(images, true, observer)
    trySend(Unit)
    awaitClose { unregisterContentObserver(observer) }
}.conflate().mapNotNull {
    try {
        query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID),
            null, null, null)?.use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getLong(0)) }
        }
    } catch (_: SecurityException) {
        emptySet<Long>() // permission changed between resume and the query
    }
}.flowOn(Dispatchers.IO)
