package com.lumina.cleaner.ui.deck

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeckTrashArcModelTest {

    @Test
    fun `dragTrashPull responds only to upward-dominant drag and stays zero on horizontal or downward drag`() {
        val vThreshold = 150f
        assertEquals(0f, DeckTrashArcModel.dragTrashPull(0f, 0f, vThreshold), 0.0001f)
        assertEquals(0f, DeckTrashArcModel.dragTrashPull(-200f, 0f, vThreshold), 0.0001f)
        assertEquals(0f, DeckTrashArcModel.dragTrashPull(200f, 0f, vThreshold), 0.0001f)
        assertEquals(0f, DeckTrashArcModel.dragTrashPull(0f, 120f, vThreshold), 0.0001f)

        val midPull = DeckTrashArcModel.dragTrashPull(0f, -100f, vThreshold)
        val fullPull = DeckTrashArcModel.dragTrashPull(0f, -260f, vThreshold)
        assertTrue("midPull should be positive: $midPull", midPull in 0.2f..0.95f)
        assertTrue("fullPull should exceed midPull: $fullPull > $midPull", fullPull > midPull)
        assertTrue("fullPull should clamp at 1f: $fullPull", fullPull <= 1f)
    }

    @Test
    fun `lidOpenDegrees opens smoothly during drag and snaps to 44 degrees past threshold`() {
        assertEquals(0f, DeckTrashArcModel.lidOpenDegrees(0f, false), 0.0001f)
        val half = DeckTrashArcModel.lidOpenDegrees(0.5f, false)
        assertTrue("half pull lid angle should be mid-open: $half", half in 15f..30f)
        assertEquals(44f, DeckTrashArcModel.lidOpenDegrees(0.8f, true), 0.0001f)
    }

    @Test
    fun `durationForVelocity adapts smoothly between fast flick and gentle release`() {
        val density = 3f
        val gentle = DeckTrashArcModel.durationForVelocity(0f, density)
        val medium = DeckTrashArcModel.durationForVelocity(-1650f, density)
        val fast = DeckTrashArcModel.durationForVelocity(-5000f, density)

        assertEquals(DeckTrashArcModel.MAX_DURATION_MS, gentle)
        assertEquals(DeckTrashArcModel.MIN_DURATION_MS, fast)
        assertTrue("medium ($medium) should be strictly between fast ($fast) and gentle ($gentle)", medium in (fast + 1) until gentle)
    }

    @Test
    fun `frameAt begins at exact release coordinates and lands precisely at trash anchor with zero alpha`() {
        val density = 3f
        val start = DeckTrashArcModel.capture(
            startX = -24f,
            startY = -180f,
            targetX = 360f,
            targetY = -920f,
            vx = 180f,
            vy = -2100f,
            startScale = 0.88f,
            startRotationZ = 3.5f,
            density = density
        )

        val f0 = DeckTrashArcModel.frameAt(0f, start, density)
        assertEquals(start.startX, f0.x, 0.01f)
        assertEquals(start.startY, f0.y, 0.01f)
        assertEquals(start.startScale, f0.scaleX, 0.01f)
        assertEquals(start.startScale, f0.scaleY, 0.01f)
        assertEquals(1f, f0.alpha, 0.001f)

        val fMid = DeckTrashArcModel.frameAt(0.58f, start, density)
        // Genie 尾段挤压拉伸：scaleY > scaleX 呈现吸入拉伸张力
        assertTrue("Expected Genie vertical stretch at mid-arc: scaleY=${fMid.scaleY} > scaleX=${fMid.scaleX}", fMid.scaleY > fMid.scaleX)
        assertEquals(1f, fMid.alpha, 0.001f)

        val f1 = DeckTrashArcModel.frameAt(1f, start, density)
        assertEquals(start.targetX, f1.x, 0.01f)
        assertEquals(start.targetY, f1.y, 0.01f)
        assertEquals(0f, f1.alpha, 0.001f)
        assertTrue("End scale should shrink cleanly into bin mouth", f1.scaleX <= 0.16f && f1.scaleY <= 0.16f)
    }

    @Test
    fun `flushCommitIfInFlight settles outgoing swipe once and unlocks coordinator immediately`() = runBlocking {
        val coordinator = DeckGestureCoordinator(this)
        coordinator.bindPhoto(101L)
        val depth = Animatable(0.4f)
        val offset = Animatable(-220f)
        var commits = 0

        coordinator.commitSwipe(
            commitSpring = tween(300),
            animDepthProgress = depth,
            animOffset = offset,
            targetOffset = -1200f,
            targetDepthP = 1f,
            direction = ReleaseAction.COMMIT_LEFT,
            onCommitSuccess = { commits++ }
        )
        assertTrue(coordinator.isCommittingSwipe)

        // 模拟用户在切图动画中途立刻触控下一张照片
        val flushedDir = coordinator.flushCommitIfInFlight()
        assertEquals(ReleaseAction.COMMIT_LEFT, flushedDir)
        assertFalse(coordinator.isCommittingSwipe)
        assertEquals(1, commits)

        // 再次刷新或等待原动画窗口均不得重复回调
        assertNull(coordinator.flushCommitIfInFlight())
        delay(30)
        assertEquals(1, commits)
    }

    @Test
    fun `flushCommitIfInFlight settles trash arc impact and commit exactly once`() = runBlocking {
        val coordinator = DeckGestureCoordinator(this)
        coordinator.bindPhoto(202L)
        val arc = Animatable(0f)
        val depth = Animatable(0.5f)
        var impacts = 0
        var commits = 0

        coordinator.commitTrashArc(
            arcProgress = arc,
            animDepthProgress = depth,
            depthSpring = tween(250),
            durationMillis = 250,
            onArcImpact = { impacts++ },
            onCommitSuccess = { commits++ }
        )
        assertTrue(coordinator.isCommittingSwipe)

        val flushedDir = coordinator.flushCommitIfInFlight()
        assertEquals(ReleaseAction.COMMIT_UP, flushedDir)
        assertFalse(coordinator.isCommittingSwipe)
        assertEquals(1, impacts)
        assertEquals(1, commits)
        assertNull(coordinator.flushCommitIfInFlight())
    }

    @Test
    fun `fourth layer is invisible at rest, monotonic across drag, and matches third layer resting state at p=1`() {
        val rest = DeckDepthModel.interpolateFourthLayer(0f)
        val thirdRest = DeckDepthModel.interpolateThirdLayer(0f)
        val full = DeckDepthModel.interpolateFourthLayer(1f)

        // 静息态完全隐形，不干扰原版三层卡堆视觉
        assertEquals(0f, rest.alpha, 0.0001f)
        assertEquals(0f, DeckDepthModel.interpolateFourthLayer(0.08f).alpha, 0.0001f)

        // p = 1 交接瞬间与第三层 p = 0 像素级 100% 严丝合缝（零 1 帧突兀弹出）
        assertEquals(thirdRest.scale, full.scale, 0.0001f)
        assertEquals(thirdRest.offsetYDp, full.offsetYDp, 0.0001f)
        assertEquals(thirdRest.blurDp, full.blurDp, 0.0001f)
        assertEquals(thirdRest.scrimAlpha, full.scrimAlpha, 0.0001f)
        assertEquals(1f, full.alpha, 0.0001f)

        // 来回拖拽 0..1 严格单调无跳变
        var prev = rest
        for (i in 1..100) {
            val cur = DeckDepthModel.interpolateFourthLayer(i / 100f)
            assertTrue(cur.scale >= prev.scale - 0.0001f)
            assertTrue(cur.offsetYDp <= prev.offsetYDp + 0.0001f)
            assertTrue(cur.blurDp <= prev.blurDp + 0.0001f)
            assertTrue(cur.alpha >= prev.alpha - 0.0001f)
            prev = cur
        }
    }
}
