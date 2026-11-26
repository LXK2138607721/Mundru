package com.lumina.cleaner.ui.trash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecycleBinReflowPlannerTest {

    @Test
    fun scatteredRow1Row3Row5Removal_computesExactScreenSpaceDeltasAndStaggerWave() {
        // 24 张照片（8 行 × 3 列），删除第 1 行全部 3 张(1,2,3) + 第 3 行第 2 张(8) + 第 5 行第 2 张(14)
        val oldIds = (1L..24L).toList()
        val removed = setOf(1L, 2L, 3L, 8L, 14L)
        val removedBeforeMap = RecycleBinReflowPlanner.countRemovedBeforeEachSurvivor(oldIds, removed)

        assertEquals(3, removedBeforeMap[4L])   // 原第 2 行第 1 张，前方删了 3 张
        assertEquals(3, removedBeforeMap[7L])   // 原第 3 行第 1 张，前方删了 3 张
        assertEquals(4, removedBeforeMap[9L])   // 原第 3 行第 3 张，前方删了 4 张
        assertEquals(5, removedBeforeMap[15L])  // 原第 5 行第 3 张，前方删了 5 张
        assertEquals(5, removedBeforeMap[19L])  // 原屏外第 7 行第 1 张，前方删了 5 张

        val colPitch = 390f
        val rowPitch = 390f

        // 校验原第 2 行第 1 张 (id=4)：从 Row 2 Col 0 (0, 390) 向上滑至 Row 1 Col 0 (0, 0)
        val p4 = RecycleBinReflowPlanner.planForTile(
            newIndex = 0,
            oldPosPx = 0f to rowPitch,
            newPosPx = 0f to 0f,
            rowPitchPx = rowPitch,
            removedBeforeNewCount = removedBeforeMap[4L] ?: 0,
            shiftRank = 0
        )
        assertTrue(p4.hasMotion)
        assertEquals(0f, p4.deltaX, 0.01f)
        assertEquals(rowPitch, p4.deltaY, 0.01f)
        assertEquals(0L, p4.staggerDelayMs)
        assertFalse(p4.isEnteringFromBottom)

        // 校验原第 3 行第 3 张 (id=9)：从 Row 3 Col 2 (780, 780) 跨列斜移到 Row 2 Col 1 (390, 390)
        val p9 = RecycleBinReflowPlanner.planForTile(
            newIndex = 4,
            oldPosPx = (2 * colPitch) to (2 * rowPitch),
            newPosPx = colPitch to rowPitch,
            rowPitchPx = rowPitch,
            removedBeforeNewCount = removedBeforeMap[9L] ?: 0,
            shiftRank = 4
        )
        assertTrue(p9.hasMotion)
        assertEquals(colPitch, p9.deltaX, 0.01f)
        assertEquals(rowPitch, p9.deltaY, 0.01f)
        assertEquals(4 * RecycleBinReflowPlanner.STAGGER_STEP_MS, p9.staggerDelayMs)
        // 前排卡片层级高于后排，防止空中无序穿透遮挡
        assertTrue(p4.zOrder > p9.zOrder)

        // 校验原屏外底部新进入视口的卡片 (id=19)：自动获得从底部柔和上滑入场偏移
        val p19 = RecycleBinReflowPlanner.planForTile(
            newIndex = 13,
            oldPosPx = null,
            newPosPx = colPitch to (4 * rowPitch),
            rowPitchPx = rowPitch,
            removedBeforeNewCount = removedBeforeMap[19L] ?: 0,
            shiftRank = 13
        )
        assertTrue(p19.hasMotion)
        assertTrue(p19.isEnteringFromBottom)
        assertEquals(0f, p19.deltaX, 0.01f)
        assertTrue(p19.deltaY > rowPitch) // 2 行步进的柔和入场距离
        assertEquals(RecycleBinReflowPlanner.MAX_STAGGER_MS, p19.staggerDelayMs)
    }

    @Test
    fun scrolledDownClampScenario_preservesTrueScreenSpaceGlideDelta() {
        // 模拟用户向下滚动后删除第 1 行 + 第 3 行 + 第 5 行，LazyVerticalGrid 夹紧 scrollOffset 归零：
        // 原本露出一半在顶部 (y = -120) 的第 2 行卡片落到 (y = 0)，屏幕空间差值应精准为 -120px 而非丢动画
        val clampedPlan = RecycleBinReflowPlanner.planForTile(
            newIndex = 0,
            oldPosPx = 39f to -120f,
            newPosPx = 39f to 0f,
            rowPitchPx = 390f,
            removedBeforeNewCount = 3,
            shiftRank = 0
        )
        assertTrue(clampedPlan.hasMotion)
        assertEquals(0f, clampedPlan.deltaX, 0.01f)
        assertEquals(-120f, clampedPlan.deltaY, 0.01f)
    }

    @Test
    fun tilesBeforeFirstRemovedItem_doNotAnimate() {
        val oldIds = listOf(10L, 11L, 12L, 13L, 14L)
        val removed = setOf(13L)
        val map = RecycleBinReflowPlanner.countRemovedBeforeEachSurvivor(oldIds, removed)
        assertEquals(null, map[10L])
        assertEquals(null, map[12L])
        assertEquals(1, map[14L])

        val staticPlan = RecycleBinReflowPlanner.planForTile(
            newIndex = 0,
            oldPosPx = 39f to 350f,
            newPosPx = 39f to 350f,
            rowPitchPx = 390f,
            removedBeforeNewCount = 0,
            shiftRank = 0
        )
        assertFalse(staticPlan.hasMotion)
        assertEquals(0L, staticPlan.staggerDelayMs)
    }
}
