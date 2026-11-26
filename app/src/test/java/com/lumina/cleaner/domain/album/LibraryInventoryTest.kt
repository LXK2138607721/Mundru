package com.lumina.cleaner.domain.album

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryInventoryTest {
    @Test fun externalDeleteAndPartialAccessCountOnlyTheVisibleIntersection() {
        val trash = listOf(2L, 99L)
        assertEquals(2, activeLibraryCount(setOf(1L, 2L, 3L), trash))
        assertEquals(1, activeLibraryCount(setOf(1L, 2L), trash))
        assertEquals(0, activeLibraryCount(emptySet(), trash))
        assertEquals(2, activeLibraryCount(setOf(1L, 2L, 3L), trash))
    }

    @Test fun trashUndoAndPermanentDeleteDoNotDoubleSubtract() {
        val visible = setOf(1L, 2L)
        assertEquals(1, activeLibraryCount(visible, listOf(2L)))
        assertEquals(2, activeLibraryCount(visible, emptyList()))
        assertEquals(1, activeLibraryCount(setOf(1L), emptyList()))
    }

    @Test fun settingsFirstFrameBeforeLiveQuerySubtractsOnlyVisibleTrashIntersection() {
        // 反例回归：当前可见 ID={1,2}，回收站含授权范围外记录 {1,99}，首包 liveIds 返回前必须显示 1 而非 0
        val state = SettingsVisibleIdsState().onEnterSettings()
        val snapshotVisibleIds = setOf(1L, 2L)
        val trashIds = listOf(1L, 99L)

        assertEquals(snapshotVisibleIds, state.effectiveVisibleIds(snapshotVisibleIds))
        assertEquals(1, state.activeCount(snapshotVisibleIds, trashIds))
    }

    @Test fun retainedSettingsReentryDiscardsStaleLiveIdsAndUsesCurrentSnapshotUntilRefresh() {
        val trashIds = listOf(1L, 99L)
        // 第 1 次进设置页并收到当时的 MediaStore 实时结果 {1,2,3}
        val visit1 = SettingsVisibleIdsState().onEnterSettings()
        val visit1Loaded = visit1.onLiveIdsLoaded(visit1.visitToken, setOf(1L, 2L, 3L))
        assertEquals(2, visit1Loaded.activeCount(setOf(1L, 2L, 3L), trashIds))

        // 退出设置页后图库快照缩窄为 {1,2}，第 2 次进入设置页在本次实时查询返回前不得沿用旧 liveIds
        val exited = visit1Loaded.onExitSettings()
        val visit2 = exited.onEnterSettings()
        val narrowedSnapshot = setOf(1L, 2L)
        assertEquals(1, visit2.activeCount(narrowedSnapshot, trashIds))

        // 过期协程携旧 visitToken 迟到回写必须被忽略
        val afterStaleEmission = visit2.onLiveIdsLoaded(visit1.visitToken, setOf(1L, 2L, 3L, 4L))
        assertEquals(1, afterStaleEmission.activeCount(narrowedSnapshot, trashIds))

        // 本次访问的新 MediaStore 查询返回后才切换到新结果
        val visit2Loaded = afterStaleEmission.onLiveIdsLoaded(visit2.visitToken, setOf(1L, 2L, 4L))
        assertEquals(2, visit2Loaded.activeCount(narrowedSnapshot, trashIds))
    }

    @Test fun activeLibraryCountCacheRecomputesOnlyWhenInputsChange() {
        val cache = ActiveLibraryCountCache()
        val snapshot = setOf(1L, 2L)
        val trash = listOf(1L, 99L)

        assertEquals(1, cache.getOrCompute(snapshot, trash))
        assertEquals(1, cache.getOrCompute(snapshot, trash))
        assertEquals(1, cache.computeCalls)

        val live = setOf(1L, 2L, 3L)
        assertEquals(2, cache.getOrCompute(live, trash))
        assertEquals(2, cache.computeCalls)

        val updatedTrash = listOf(1L, 2L, 99L)
        assertEquals(1, cache.getOrCompute(live, updatedTrash))
        assertEquals(3, cache.computeCalls)
    }
}
