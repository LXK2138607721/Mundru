package com.lumina.cleaner.ui.deck

import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.MonotonicFrameClock
import com.lumina.cleaner.core.model.PhotoItem
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import kotlin.coroutines.resume
import kotlin.math.abs

/**
 * 显式受控帧时钟 (ManualFrameClock)
 *
 * 解决 G3：一次推进 (advanceFrame) 赋予当帧所有等待者完全相同的时间戳，
 * 配合调度器排空 (drainDispatchers)，彻底消除多协程时间漂移与任意 delay 猜测。
 */
private class ManualFrameClock : MonotonicFrameClock {
    private val waiters = mutableListOf<CancellableContinuation<Long>>()
    var timeNanos: Long = 0L
        private set

    override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
        val t = suspendCancellableCoroutine<Long> { waiters.add(it) }
        return onFrame(t)
    }

    fun advanceFrame() {
        timeNanos += 16_000_000L
        val pending = waiters.toList()
        waiters.clear()
        pending.forEach { if (it.isActive) it.resume(timeNanos) }
    }
}

private suspend fun drainDispatchers() {
    repeat(12) { yield() }
}

private suspend fun ManualFrameClock.advanceFrames(count: Int) {
    repeat(count) {
        advanceFrame()
        drainDispatchers()
    }
}

class DeckDepthModelTest {

    private val hThreshold = 140f
    private val vThreshold = 150f

    private fun createTestPhoto(id: Long) = PhotoItem(
        id = id,
        uri = mock(Uri::class.java),
        displayName = "IMG_$id.jpg",
        dateModified = 1700000000000L,
        dateTaken = 1700000000000L,
        size = 1024L * 1024L,
        width = 1080,
        height = 1920,
        bucketName = "Camera"
    )

    @Test
    fun `endpoints at p equals 0 and 1 match layer continuity contract`() {
        // p = 0
        val secondP0 = DeckDepthModel.interpolateSecondLayer(0f)
        assertEquals(0.95f, secondP0.scale, 0.001f)
        assertEquals(14f, secondP0.offsetYDp, 0.001f)
        assertEquals(12f, secondP0.blurDp, 0.001f)
        assertEquals(0.18f, secondP0.scrimAlpha, 0.001f)

        val thirdP0 = DeckDepthModel.interpolateThirdLayer(0f)
        assertEquals(0.90f, thirdP0.scale, 0.001f)
        assertEquals(28f, thirdP0.offsetYDp, 0.001f)
        assertEquals(16f, thirdP0.blurDp, 0.001f)
        assertEquals(0.26f, thirdP0.scrimAlpha, 0.001f)

        // p = 1
        val secondP1 = DeckDepthModel.interpolateSecondLayer(1f)
        assertEquals(1.00f, secondP1.scale, 0.001f)
        assertEquals(0f, secondP1.offsetYDp, 0.001f)
        assertEquals(0f, secondP1.blurDp, 0.001f)
        assertEquals(0f, secondP1.scrimAlpha, 0.001f)

        val thirdP1 = DeckDepthModel.interpolateThirdLayer(1f)
        assertEquals(0.95f, thirdP1.scale, 0.001f)
        assertEquals(14f, thirdP1.offsetYDp, 0.001f)
        assertEquals(12f, thirdP1.blurDp, 0.001f)
        assertEquals(0.18f, thirdP1.scrimAlpha, 0.001f)

        // 验证端点契约：旧第三张终点必须完全等于旧第二张起点！
        assertEquals(secondP0.scale, thirdP1.scale, 0.001f)
        assertEquals(secondP0.offsetYDp, thirdP1.offsetYDp, 0.001f)
        assertEquals(secondP0.blurDp, thirdP1.blurDp, 0.001f)
        assertEquals(secondP0.scrimAlpha, thirdP1.scrimAlpha, 0.001f)
    }

    @Test
    fun `interpolation clamps out of bounds progress`() {
        val belowZero = DeckDepthModel.interpolateSecondLayer(-0.5f)
        val p0 = DeckDepthModel.interpolateSecondLayer(0f)
        assertEquals(p0, belowZero)

        val aboveOne = DeckDepthModel.interpolateSecondLayer(1.8f)
        val p1 = DeckDepthModel.interpolateSecondLayer(1f)
        assertEquals(p1, aboveOne)
    }

    @Test
    fun `drag left and up produce monotonic progress towards target`() {
        // 左滑缓慢拖拽
        val pLeft1 = DeckDepthModel.computeTargetProgress(-35f, 0f, hThreshold, vThreshold)
        val pLeft2 = DeckDepthModel.computeTargetProgress(-70f, 0f, hThreshold, vThreshold)
        val pLeft3 = DeckDepthModel.computeTargetProgress(-140f, 0f, hThreshold, vThreshold)
        val pLeft4 = DeckDepthModel.computeTargetProgress(-200f, 0f, hThreshold, vThreshold)

        assertEquals(0.25f, pLeft1, 0.001f)
        assertEquals(0.50f, pLeft2, 0.001f)
        assertEquals(1.00f, pLeft3, 0.001f)
        assertEquals(1.00f, pLeft4, 0.001f)
        assertTrue(pLeft1 < pLeft2 && pLeft2 < pLeft3)

        // 上滑缓慢拖拽
        val pUp1 = DeckDepthModel.computeTargetProgress(0f, -37.5f, hThreshold, vThreshold)
        val pUp2 = DeckDepthModel.computeTargetProgress(0f, -75f, hThreshold, vThreshold)
        val pUp3 = DeckDepthModel.computeTargetProgress(0f, -150f, hThreshold, vThreshold)

        assertEquals(0.25f, pUp1, 0.001f)
        assertEquals(0.50f, pUp2, 0.001f)
        assertEquals(1.00f, pUp3, 0.001f)
    }

    @Test
    fun `right drag produces zero progress and never advances next card`() {
        // 纯右滑
        val pRight = DeckDepthModel.computeTargetProgress(100f, 0f, hThreshold, vThreshold)
        assertEquals(0f, pRight, 0.001f)

        // 右上微斜但满足右滑约束
        val pRightUp = DeckDepthModel.computeTargetProgress(100f, -30f, hThreshold, vThreshold)
        assertEquals(0f, pRightUp, 0.001f)

        // 下滑
        val pDown = DeckDepthModel.computeTargetProgress(0f, 100f, hThreshold, vThreshold)
        assertEquals(0f, pDown, 0.001f)
    }

    @Test
    fun `fix R2 counterexample northeast diagonal swipe resolves consistently between preview and release action`() {
        // 审查报告 R2 精确复现反例输入：
        // hThreshold = 140, vThreshold = 150, dx = 100, dy = -160, vx = 0, vy = 0, hasPreviousPhoto = true
        val dx = 100f
        val dy = -160f
        val vx = 0f
        val vy = 0f

        val dragIntent = DeckDepthModel.resolveDragIntent(dx, dy, hThreshold, vThreshold)
        val targetProgress = DeckDepthModel.computeTargetProgress(dx, dy, hThreshold, vThreshold)
        val releaseAction = DeckDepthModel.resolveReleaseAction(
            dx = dx,
            dy = dy,
            vx = vx,
            vy = vy,
            hThreshold = hThreshold,
            vThreshold = vThreshold,
            hasPreviousPhoto = true
        )

        // 验证 R2 已修复：上滑已超阈值（-160 < -150），预览意图必须是 UP
        assertEquals(SwipeIntent.UP, dragIntent)
        assertEquals(1.0f, targetProgress, 0.001f)
        assertEquals(ReleaseAction.COMMIT_UP, releaseAction)

        // 验证真正判为回看的右上斜滑（水平位移与比例双达标）：dx = 150, dy = -100
        val rightDragIntent = DeckDepthModel.resolveDragIntent(150f, -100f, hThreshold, vThreshold)
        val rightTargetP = DeckDepthModel.computeTargetProgress(150f, -100f, hThreshold, vThreshold)
        val rightAction = DeckDepthModel.resolveReleaseAction(
            dx = 150f, dy = -100f, vx = 0f, vy = 0f,
            hThreshold = hThreshold, vThreshold = vThreshold,
            hasPreviousPhoto = true
        )
        assertEquals(SwipeIntent.RIGHT, rightDragIntent)
        assertEquals(0.0f, rightTargetP, 0.001f)
        assertEquals(ReleaseAction.COMMIT_RIGHT, rightAction)
    }

    @Test
    fun `production end-of-deck transition and layer presence for two cards and last card`() = runBlocking {
        val clock = ManualFrameClock()
        withContext(clock) {
            val coordinator = DeckGestureCoordinator(this)
            val animDepth = Animatable(0f)
            val animOffset = Animatable(0f)

            // 构造生产环境的照片列表与索引演变
            val photos = listOf(
                createTestPhoto(101L),
                createTestPhoto(102L)
            )
            var currentIndex = 0
            val swipedPhotos = mutableListOf<PhotoItem>()

            // ----------------------------------------------------
            // 阶段 1：只剩 2 张卡片 (currentIndex = 0)
            // ----------------------------------------------------
            var currentPhoto = photos.getOrNull(currentIndex)
            var prevPhoto = if (currentIndex > 0) photos.getOrNull(currentIndex - 1) else null
            var nextPhoto = photos.getOrNull(currentIndex + 1)
            var thirdPhoto = photos.getOrNull(currentIndex + 2)

            // 断言图层存在性契约：第二张存在，第三张不存在（null）
            assertEquals(101L, currentPhoto?.id)
            assertNull("首张无上一张", prevPhoto)
            assertEquals(102L, nextPhoto?.id)
            assertNull("只剩 2 张时底层第三张严禁存在", thirdPhoto)

            // 绑定第一张并提交
            coordinator.bindPhoto(currentPhoto!!.id)
            coordinator.commitSwipe(
                commitSpring = tween(80),
                animDepthProgress = animDepth,
                animOffset = animOffset,
                targetOffset = -1000f,
                targetDepthP = 1f,
                onCommitSuccess = {
                    swipedPhotos.add(currentPhoto!!)
                    currentIndex++
                }
            )
            clock.advanceFrames(10)

            // 断言第一张成功提交并切入下一张
            assertEquals(1, currentIndex)
            assertEquals(listOf(101L), swipedPhotos.map { it.id })

            // ----------------------------------------------------
            // 阶段 2：只剩最后 1 张卡片 (currentIndex = 1)
            // ----------------------------------------------------
            currentPhoto = photos.getOrNull(currentIndex)
            prevPhoto = if (currentIndex > 0) photos.getOrNull(currentIndex - 1) else null
            nextPhoto = photos.getOrNull(currentIndex + 1)
            thirdPhoto = photos.getOrNull(currentIndex + 2)

            // 断言图层存在性契约：第二张和第三张均不存在（null），上一张存在
            assertEquals(102L, currentPhoto?.id)
            assertEquals(101L, prevPhoto?.id)
            assertNull("只剩最后 1 张时底层第二张严禁存在", nextPhoto)
            assertNull("只剩最后 1 张时底层第三张严禁存在", thirdPhoto)

            // 重新绑定最后一张卡片（重置代次并终结旧任务）
            coordinator.bindPhoto(currentPhoto!!.id)
            assertEquals(102L, coordinator.currentPhotoId)

            coordinator.commitSwipe(
                commitSpring = tween(80),
                animDepthProgress = animDepth,
                animOffset = animOffset,
                targetOffset = -1000f,
                targetDepthP = 1f,
                onCommitSuccess = {
                    swipedPhotos.add(currentPhoto!!)
                    currentIndex++
                }
            )
            clock.advanceFrames(10)

            // ----------------------------------------------------
            // 阶段 3：批次全部完成 (currentIndex = 2 >= photos.size)
            // ----------------------------------------------------
            assertEquals(2, currentIndex)
            assertEquals(listOf(101L, 102L), swipedPhotos.map { it.id })

            val isDeckFinished = photos.isEmpty() || currentIndex >= photos.size
            assertTrue("最后一张提交后卡片舞台必须进入结算结束状态", isDeckFinished)
            assertNull(photos.getOrNull(currentIndex))
        }
    }

    @Test
    fun `fix R1 production coordinator boundary transition triggers smooth progression instead of snapTo zero`() = runBlocking {
        val clock = ManualFrameClock()
        withContext(clock) {
            val coordinator = DeckGestureCoordinator(this)
            val animDepth = Animatable(0f)
            coordinator.bindPhoto(1001L)

            // Step 1: dx = 59, dy = -100 (UP 意图，目标 = 100/150 = 0.667)
            coordinator.onDragStart()
            coordinator.onDragUpdate(59f, -100f, hThreshold, vThreshold, animDepth)
            drainDispatchers()
            // 推进 8 帧到达 0.667
            clock.advanceFrames(8)
            assertEquals(100f / 150f, animDepth.value, 0.01f)

            // Step 2: 横向轻移 2px 跨过边界 (61, -100)，转为 RIGHT 意图，目标为 0.0f
            coordinator.onDragUpdate(61f, -100f, hThreshold, vThreshold, animDepth)
            drainDispatchers()

            // 验证 R1 修复：当前进度未被瞬间置为 0，第 1 帧仍保持高位平滑过渡
            clock.advanceFrames(1)
            assertTrue("Progress should smoothly transition, not snap immediately to 0", animDepth.value > 0.50f)

            // 推进 12 帧后平滑归零
            clock.advanceFrames(12)
            assertEquals(0f, animDepth.value, 0.005f)
        }
    }

    @Test
    fun `fix G1 production coordinator synchronizes drag position and prevents stale release decision`() = runBlocking {
        val clock = ManualFrameClock()
        withContext(clock) {
            val coordinator = DeckGestureCoordinator(this)
            val animDepth = Animatable(0f)
            val animOffset = Animatable(0f)
            var commitInvoked = false

            coordinator.bindPhoto(2002L)
            coordinator.onDragStart()

            // 1. 倒数第二笔位移：x = -120f（阈值 140f 以内）
            coordinator.onDragUpdate(-120f, 0f, hThreshold, vThreshold, animDepth)
            drainDispatchers()
            assertEquals(-120f, coordinator.dragLogicalX, 0.001f)

            // 2. 最后一笔位移：x = -160f（已超越阈值），模拟展示动画排队延迟尚未执行 snapTo
            coordinator.onDragUpdate(-160f, 0f, hThreshold, vThreshold, animDepth)
            assertEquals(-160f, coordinator.dragLogicalX, 0.001f)

            // 3. 模拟松手事件立即到达（展示位移 animOffset 仍为 0f 或旧值）
            coordinator.onDragEnd()
            val action = coordinator.resolveReleaseAction(
                vx = 0f, vy = 0f,
                hThreshold = hThreshold, vThreshold = vThreshold,
                hasPreviousPhoto = true
            )

            // 验证 G1 修复：裁决必须立即采用最新的逻辑坐标 -160f，绝对判定为 COMMIT_LEFT（绝非 BOUNCE！）
            assertEquals(ReleaseAction.COMMIT_LEFT, action)

            // 4. 提交切图，验证从真实逻辑位移 -160f 起飞
            coordinator.commitSwipe(
                commitSpring = tween(80),
                animDepthProgress = animDepth,
                animOffset = animOffset,
                targetOffset = -1000f,
                targetDepthP = 1f,
                startOffset = coordinator.dragLogicalX,
                onCommitSuccess = { commitInvoked = true }
            )

            clock.advanceFrames(10)
            assertTrue(commitInvoked)
            assertEquals(-1000f, animOffset.value, 0.001f)
            assertEquals(1f, animDepth.value, 0.001f)
        }
    }

    @Test
    fun `fix G1 production coordinator handles retraction inside threshold before release`() = runBlocking {
        val clock = ManualFrameClock()
        withContext(clock) {
            val coordinator = DeckGestureCoordinator(this)
            val animDepth = Animatable(0f)
            val animOffset = Animatable(0f)
            var bounceCompleted = false

            coordinator.bindPhoto(2003L)
            coordinator.onDragStart()

            // 曾拖到 -160f
            coordinator.onDragUpdate(-160f, 0f, hThreshold, vThreshold, animDepth)
            drainDispatchers()

            // 松手前最后一刻收回到 -120f（阈值内且无速度）
            coordinator.onDragUpdate(-120f, 0f, hThreshold, vThreshold, animDepth)
            coordinator.onDragEnd()

            val action = coordinator.resolveReleaseAction(
                vx = 0f, vy = 0f,
                hThreshold = hThreshold, vThreshold = vThreshold,
                hasPreviousPhoto = true
            )

            // 验证 G1 修复：准确判定为 BOUNCE，绝不因历史位置错误提交
            assertEquals(ReleaseAction.BOUNCE, action)

            coordinator.bounceBack(
                softSpring = tween(80),
                animDepthProgress = animDepth,
                animOffsetX = animOffset,
                animOffsetY = Animatable(0f),
                startOffsetX = coordinator.dragLogicalX,
                startOffsetY = coordinator.dragLogicalY,
                onBounceComplete = { bounceCompleted = true }
            )

            clock.advanceFrames(10)
            assertTrue(bounceCompleted)
            assertEquals(0f, animOffset.value, 0.001f)
            assertEquals(0f, animDepth.value, 0.001f)
        }
    }

    @Test
    fun `fix G2 production coordinator immediate reversal without overshoot or lag`() = runBlocking {
        val clock = ManualFrameClock()
        withContext(clock) {
            val coordinator = DeckGestureCoordinator(this)
            val animDepth = Animatable(0f)

            coordinator.bindPhoto(3001L)
            coordinator.onDragStart()

            // 步骤 1：先在 RIGHT 区域建立位移，进度为 0
            coordinator.onDragUpdate(100f, 0f, hThreshold, vThreshold, animDepth)
            drainDispatchers()
            assertEquals(SwipeIntent.RIGHT, coordinator.lastDragIntent)

            // 步骤 2：转入 (0, -120)，UP 意图，目标进度 120/150 = 0.8
            coordinator.onDragUpdate(0f, -120f, hThreshold, vThreshold, animDepth)
            drainDispatchers()

            // 推进 5 帧到达约 0.6515
            clock.advanceFrames(5)
            val beforeReverse = animDepth.value
            assertTrue("Depth should have progressed towards 0.8", beforeReverse > 0.5f && beforeReverse < 0.75f)

            // 步骤 3：手指收回到 (0, -30)，最新目标为 30/150 = 0.2（仍在 UP 意图内部反向）
            coordinator.onDragUpdate(0f, -30f, hThreshold, vThreshold, animDepth)
            drainDispatchers()

            // 采样接下来的 8 帧
            val samples = mutableListOf<Float>()
            repeat(8) {
                clock.advanceFrames(1)
                samples.add(animDepth.value)
            }

            // 推进后续 20 帧以达最终收敛
            clock.advanceFrames(20)

            // 验证 G2 修复：
            // 1. 中间帧绝不逆向上冲旧目标 0.8！所有采样帧必须小于或等于反向前的高点！
            for (i in samples.indices) {
                assertTrue(
                    "Sample $i (${samples[i]}) must not overshoot beforeReverse ($beforeReverse)",
                    samples[i] <= beforeReverse + 0.001f
                )
            }
            // 2. 采样序列必须单调向下收敛至 0.2
            for (i in 0 until samples.size - 1) {
                assertTrue(
                    "Samples must monotonically decrease: ${samples[i]} >= ${samples[i + 1]}",
                    samples[i] >= samples[i + 1] - 0.001f
                )
            }
            // 3. 最终值必须精准收敛至最新目标 0.2
            assertEquals(0.2f, animDepth.value, 0.005f)
        }
    }

    @Test
    fun `fix G2 production coordinator accelerates towards further target without pause`() = runBlocking {
        val clock = ManualFrameClock()
        withContext(clock) {
            val coordinator = DeckGestureCoordinator(this)
            val animDepth = Animatable(0f)

            coordinator.bindPhoto(3002L)
            coordinator.onDragStart()

            // 步骤 1：先设定目标 0.2
            coordinator.onDragUpdate(0f, -30f, hThreshold, vThreshold, animDepth)
            drainDispatchers()
            clock.advanceFrames(2)

            // 步骤 2：突然深滑至 (0, -135)，目标剧烈变更为 135/150 = 0.9
            coordinator.onDragUpdate(0f, -135f, hThreshold, vThreshold, animDepth)
            drainDispatchers()

            // 推进并采样
            clock.advanceFrames(15)
            // 验证直接加速冲向 0.9，绝不停滞在 0.2
            assertEquals(0.9f, animDepth.value, 0.005f)
            assertTrue("Progress should surpass 0.2", animDepth.value > 0.8f)
        }
    }

    @Test
    fun `fix G2 production coordinator handles continuous rapid direction changes`() = runBlocking {
        val clock = ManualFrameClock()
        withContext(clock) {
            val coordinator = DeckGestureCoordinator(this)
            val animDepth = Animatable(0f)

            coordinator.bindPhoto(3003L)
            coordinator.onDragStart()

            // 连续快速更新：0.8 -> 0.3 -> 0.7 -> 0.0
            coordinator.onDragUpdate(0f, -120f, hThreshold, vThreshold, animDepth)
            clock.advanceFrames(2)

            coordinator.onDragUpdate(0f, -45f, hThreshold, vThreshold, animDepth)
            clock.advanceFrames(2)

            coordinator.onDragUpdate(0f, -105f, hThreshold, vThreshold, animDepth)
            clock.advanceFrames(2)

            coordinator.onDragUpdate(100f, 0f, hThreshold, vThreshold, animDepth) // 转为 RIGHT (target 0)
            clock.advanceFrames(15)

            // 验证最终精准平滑收敛至 0
            assertEquals(0f, animDepth.value, 0.005f)
        }
    }

    @Test
    fun `fix F1 production coordinator cancels old photo task on identity change and prevents callback`() = runBlocking {
        val clock = ManualFrameClock()
        withContext(clock) {
            val coordinator = DeckGestureCoordinator(this)
            val animDepth = Animatable(0f)
            val animOffset = Animatable(0f)

            var callbackInvokedOnPhotoA = false
            var callbackInvokedOnPhotoB = false

            // 1. 绑定照片 A
            coordinator.bindPhoto(1001L)
            assertEquals(1001L, coordinator.currentPhotoId)

            // 2. 照片 A 触发提交，动画进行中
            coordinator.commitSwipe(
                commitSpring = tween(160),
                animDepthProgress = animDepth,
                animOffset = animOffset,
                targetOffset = -1000f,
                targetDepthP = 1f,
                onCommitSuccess = { callbackInvokedOnPhotoA = true }
            )
            assertTrue(coordinator.isCommittingSwipe)

            // 3. 动画尚未完成中途（仅 2 帧），外部切换为照片 B
            clock.advanceFrames(2)
            coordinator.bindPhoto(2002L)
            assertEquals(2002L, coordinator.currentPhotoId)
            assertFalse(coordinator.isCommittingSwipe)

            // 4. 照片 B 触发正常提交操作
            coordinator.commitSwipe(
                commitSpring = tween(80),
                animDepthProgress = animDepth,
                animOffset = animOffset,
                targetOffset = -1000f,
                targetDepthP = 1f,
                onCommitSuccess = { callbackInvokedOnPhotoB = true }
            )

            clock.advanceFrames(16)

            // 验证 F1 修复：照片 A 的任务被成功取消且代次不匹配，A 的回调绝不触发；B 正常操作并成功触发回调！
            assertFalse(callbackInvokedOnPhotoA)
            assertTrue(callbackInvokedOnPhotoB)
        }
    }

    @Test
    fun `fix F2 production coordinator converges to latest target instead of stale value`() = runBlocking {
        val clock = ManualFrameClock()
        withContext(clock) {
            val coordinator = DeckGestureCoordinator(this)
            val animDepth = Animatable(0f)

            coordinator.bindPhoto(3003L)

            // 步骤 1：先在 RIGHT 区域产生位移
            coordinator.onDragUpdate(100f, 0f, hThreshold, vThreshold, animDepth)
            drainDispatchers()
            assertEquals(SwipeIntent.RIGHT, coordinator.lastDragIntent)

            // 步骤 2：从 RIGHT 转入 UP (59, -100)，产生意图突变，启动过渡动画朝 0.6666667f 推进
            coordinator.onDragUpdate(59f, -100f, hThreshold, vThreshold, animDepth)
            drainDispatchers()
            clock.advanceFrames(3)
            assertEquals(SwipeIntent.UP, coordinator.lastDragIntent)

            // 步骤 3：在过渡动画尚未结束前，手指继续拖到 (0, -140)，新目标为 140/150 = 0.93333334f！
            coordinator.onDragUpdate(0f, -140f, hThreshold, vThreshold, animDepth)
            drainDispatchers()
            assertEquals(SwipeIntent.UP, coordinator.lastDragIntent)
            assertEquals(140f / 150f, coordinator.pendingTargetDepthP, 0.001f)

            // 步骤 4：推进时钟等待动画收敛完成
            clock.advanceFrames(24)

            // 验证 F2 修复：最终值精确收敛到最新位移目标 140/150 = 0.93333334f，绝不卡在旧的 0.6666667f！
            assertEquals(140f / 150f, animDepth.value, 0.005f)
        }
    }

    @Test
    fun `production coordinator dual animation synchronization with controlled duration specs`() = runBlocking {
        val clock = ManualFrameClock()
        withContext(clock) {
            val coordinator = DeckGestureCoordinator(this)

            // ----------------------------------------------------
            // 顺序 A：位移先完成 (80ms)，景深后完成 (200ms)
            // ----------------------------------------------------
            val animDepthA = Animatable(0f)
            val animOffsetA = Animatable(0f)
            var callbackInvokedA = false

            coordinator.bindPhoto(4001L)
            coordinator.commitSwipe(
                commitSpring = tween(80),   // 位移 80ms 完成
                depthSpring = tween(200),   // 景深 200ms 完成
                animDepthProgress = animDepthA,
                animOffset = animOffsetA,
                targetOffset = -500f,
                targetDepthP = 1.0f,
                onCommitSuccess = { callbackInvokedA = true }
            )

            // 推进 7 帧 (112ms)：位移已经到达 -500f，但景深仍在过渡中 (~0.6-0.7)
            clock.advanceFrames(7)
            assertEquals(-500f, animOffsetA.value, 0.001f)
            assertTrue("Depth must still be in progress", animDepthA.value < 0.95f)
            assertFalse("Callback must NOT be invoked while depth is incomplete", callbackInvokedA)

            // 继续推进至 17 帧 (272ms)：景深亦到达 1.0f，回调此时触发且仅触发一次
            clock.advanceFrames(10)
            assertEquals(1.0f, animDepthA.value, 0.001f)
            assertTrue("Callback must be invoked after both complete", callbackInvokedA)

            // ----------------------------------------------------
            // 顺序 B：景深先完成 (80ms)，位移后完成 (200ms)
            // ----------------------------------------------------
            val animDepthB = Animatable(0f)
            val animOffsetB = Animatable(0f)
            var callbackInvokedB = false

            coordinator.bindPhoto(4002L)
            coordinator.commitSwipe(
                commitSpring = tween(200),  // 位移 200ms 完成
                depthSpring = tween(80),    // 景深 80ms 完成
                animDepthProgress = animDepthB,
                animOffset = animOffsetB,
                targetOffset = -500f,
                targetDepthP = 1.0f,
                onCommitSuccess = { callbackInvokedB = true }
            )

            // 推进 7 帧 (112ms)：景深已经到达 1.0f，但位移仍在运动中
            clock.advanceFrames(7)
            assertEquals(1.0f, animDepthB.value, 0.001f)
            assertTrue("Offset must still be in progress", animOffsetB.value > -480f)
            assertFalse("Callback must NOT be invoked while offset is incomplete", callbackInvokedB)

            // 继续推进至 17 帧 (272ms)：位移亦到达 -500f，回调此时触发
            clock.advanceFrames(10)
            assertEquals(-500f, animOffsetB.value, 0.001f)
            assertTrue("Callback must be invoked after both complete", callbackInvokedB)
        }
    }

    @Test
    fun `production coordinator dual animation synchronization with identical production spring`() = runBlocking {
        val clock = ManualFrameClock()
        withContext(clock) {
            val coordinator = DeckGestureCoordinator(this)
            val prodSpring = spring<Float>(
                dampingRatio = 0.86f,
                stiffness = Spring.StiffnessMedium
            )

            // 物理沉降顺序 A：景深距离终点极近 (0.95f -> 1.0f，8 帧内沉降)，位移距离终点极远 (0f -> -1000f)
            val animDepthA = Animatable(0.95f)
            val animOffsetA = Animatable(0f)
            var callbackInvokedA = false

            coordinator.bindPhoto(4003L)
            coordinator.commitSwipe(
                commitSpring = prodSpring,
                depthSpring = prodSpring,
                animDepthProgress = animDepthA,
                animOffset = animOffsetA,
                targetOffset = -1000f,
                targetDepthP = 1.0f,
                onCommitSuccess = { callbackInvokedA = true }
            )

            // 推进 7 帧 (112ms)：景深已完全沉降到 1.0f，位移仍在 -700f 附近未完成
            clock.advanceFrames(7)
            assertEquals(1.0f, animDepthA.value, 0.001f)
            assertTrue("Offset must still be in progress", animOffsetA.value > -900f)
            assertFalse("Callback must not fire while offset is settling", callbackInvokedA)

            // 推进至 30 帧：位移彻底沉降，回调触发
            clock.advanceFrames(23)
            assertEquals(-1000f, animOffsetA.value, 0.01f)
            assertTrue(callbackInvokedA)

            // 物理沉降顺序 B：位移距离终点极近 (-950f -> -1000f，7 帧内沉降)，景深距离终点极远 (0f -> 1.0f)
            val animDepthB = Animatable(0f)
            val animOffsetB = Animatable(-950f)
            var callbackInvokedB = false

            coordinator.bindPhoto(4004L)
            coordinator.commitSwipe(
                commitSpring = prodSpring,
                depthSpring = prodSpring,
                animDepthProgress = animDepthB,
                animOffset = animOffsetB,
                targetOffset = -1000f,
                targetDepthP = 1.0f,
                onCommitSuccess = { callbackInvokedB = true }
            )

            // 推进 10 帧 (160ms)：位移已基本沉降至 -1000f (误差 < 0.2f)，景深仍在低位爬升
            clock.advanceFrames(10)
            assertEquals(-1000f, animOffsetB.value, 0.2f)
            assertTrue("Depth must still be in progress", animDepthB.value < 1.0f)
            assertFalse("Callback must not fire while depth is settling", callbackInvokedB)

            // 推进至 30 帧：景深彻底沉降，回调触发
            clock.advanceFrames(20)
            assertEquals(1.0f, animDepthB.value, 0.001f)
            assertTrue(callbackInvokedB)
        }
    }

    @Test
    fun `coordinator commit action invoked exactly once even under repeated calls`() = runBlocking {
        val clock = ManualFrameClock()
        withContext(clock) {
            val coordinator = DeckGestureCoordinator(this)
            val animDepth = Animatable(0f)
            val animOffset = Animatable(0f)
            var callbackCount = 0

            coordinator.bindPhoto(5005L)
            coordinator.commitSwipe(
                commitSpring = tween(80),
                animDepthProgress = animDepth,
                animOffset = animOffset,
                targetOffset = -500f,
                targetDepthP = 1.0f,
                onCommitSuccess = { callbackCount++ }
            )

            // 在提交飞行中重复调用 commitSwipe，应被 isCommittingSwipe 拦截
            coordinator.commitSwipe(
                commitSpring = tween(80),
                animDepthProgress = animDepth,
                animOffset = animOffset,
                targetOffset = -500f,
                targetDepthP = 1.0f,
                onCommitSuccess = { callbackCount++ }
            )

            clock.advanceFrames(12)
            assertEquals(1, callbackCount)
        }
    }

    @Test
    fun `fix H1 production coordinator regrab during left bounce maintains visual continuity and depth`() = runBlocking {
        val clock = ManualFrameClock()
        withContext(clock) {
            val coordinator = DeckGestureCoordinator(this)
            val animDepth = Animatable(0f)
            val animOffsetX = Animatable(0f)
            val animOffsetY = Animatable(0f)
            var bounceCompleted = false

            coordinator.bindPhoto(6001L)
            coordinator.onDragStart()
            coordinator.onDragUpdate(-80f, 0f, hThreshold, vThreshold, animDepth)
            coordinator.updateVisualOffset(-80f, 0f, animOffsetX, animOffsetY)
            drainDispatchers()
            coordinator.onDragEnd()

            // 触发回弹
            val softSpring = spring<Float>(dampingRatio = 1f, stiffness = 260f)
            coordinator.bounceBack(
                softSpring = softSpring,
                animDepthProgress = animDepth,
                animOffsetX = animOffsetX,
                animOffsetY = animOffsetY,
                startOffsetX = coordinator.dragLogicalX,
                startOffsetY = coordinator.dragLogicalY,
                onBounceComplete = { bounceCompleted = true }
            )

            // 推进 3 帧：处于回弹中途
            clock.advanceFrames(3)
            val grabX = animOffsetX.value
            val grabDepth = animDepth.value
            assertTrue("Card should be in mid-bounce (x between -80 and -60)", grabX < -65f && grabX > -80f)
            assertTrue("Depth should be in mid-bounce (~0.5): got $grabDepth", grabDepth > 0.45f && grabDepth < 0.60f)

            // 用户在回弹中途重新抓取卡片，并向左拖拽 5px
            coordinator.onDragStart(
                initialX = animOffsetX.value,
                initialY = animOffsetY.value,
                hThreshold = hThreshold,
                vThreshold = vThreshold
            )
            val deltaX = -5f
            val newX = coordinator.dragLogicalX + deltaX
            coordinator.onDragUpdate(newX, coordinator.dragLogicalY, hThreshold, vThreshold, animDepth)
            coordinator.updateVisualOffset(newX, coordinator.dragLogicalY, animOffsetX, animOffsetY)
            drainDispatchers()

            // 验证 H1 修复：
            // 1. 卡片绝对不跳跃到原点附近（-5px），而是精确等于 grabX + deltaX！
            assertEquals(grabX + deltaX, animOffsetX.value, 0.001f)
            assertTrue("Position must be near -77px, not -5px!", animOffsetX.value < -70f)

            // 2. 景深进度绝对不骤降到 0.035，而是平滑维持在 ~0.55！
            assertEquals((-(grabX + deltaX) / hThreshold).coerceIn(0f, 1f), animDepth.value, 0.005f)
            assertTrue("Depth must remain high (~0.55), not collapse to 0.035!", animDepth.value > 0.50f)

            // 3. 继续推进完成一次正常提交，旧回弹的回调绝不触发
            coordinator.commitSwipe(
                commitSpring = tween(80),
                animDepthProgress = animDepth,
                animOffset = animOffsetX,
                targetOffset = -1000f,
                targetDepthP = 1f,
                onCommitSuccess = {}
            )
            clock.advanceFrames(12)
            assertFalse("Cancelled bounce callback must never be invoked!", bounceCompleted)
        }
    }

    @Test
    fun `fix H1 production coordinator regrab during upward bounce maintains vertical continuity and depth`() = runBlocking {
        val clock = ManualFrameClock()
        withContext(clock) {
            val coordinator = DeckGestureCoordinator(this)
            val animDepth = Animatable(0f)
            val animOffsetX = Animatable(0f)
            val animOffsetY = Animatable(0f)
            var bounceCompleted = false

            coordinator.bindPhoto(6002L)
            coordinator.onDragStart()
            coordinator.onDragUpdate(0f, -100f, hThreshold, vThreshold, animDepth)
            coordinator.updateVisualOffset(0f, -100f, animOffsetX, animOffsetY)
            drainDispatchers()
            coordinator.onDragEnd()

            val softSpring = spring<Float>(dampingRatio = 1f, stiffness = 260f)
            coordinator.bounceBack(
                softSpring = softSpring,
                animDepthProgress = animDepth,
                animOffsetX = animOffsetX,
                animOffsetY = animOffsetY,
                startOffsetX = coordinator.dragLogicalX,
                startOffsetY = coordinator.dragLogicalY,
                onBounceComplete = { bounceCompleted = true }
            )

            // 推进 3 帧：处于纵向上滑回弹中途
            clock.advanceFrames(3)
            val grabY = animOffsetY.value
            val grabDepth = animDepth.value
            assertTrue("Card should be in mid-bounce vertically", grabY < -80f && grabY > -100f)
            assertTrue("Depth should be in mid-bounce (~0.6)", grabDepth > 0.55f && grabDepth < 0.68f)

            // 用户在回弹中途重新抓取卡片，并向上继续拖拽 10px
            coordinator.onDragStart(
                initialX = animOffsetX.value,
                initialY = animOffsetY.value,
                hThreshold = hThreshold,
                vThreshold = vThreshold
            )
            val deltaY = -10f
            val newY = coordinator.dragLogicalY + deltaY
            coordinator.onDragUpdate(coordinator.dragLogicalX, newY, hThreshold, vThreshold, animDepth)
            coordinator.updateVisualOffset(coordinator.dragLogicalX, newY, animOffsetX, animOffsetY)
            drainDispatchers()

            // 验证 H1 纵向修复：
            assertEquals(grabY + deltaY, animOffsetY.value, 0.001f)
            assertTrue("Vertical position must continue seamlessly", animOffsetY.value < -90f)
            assertEquals((-(grabY + deltaY) / vThreshold).coerceIn(0f, 1f), animDepth.value, 0.005f)
            assertTrue("Depth must remain high (~0.67), not drop to 0.06!", animDepth.value > 0.60f)

            assertFalse("Cancelled bounce callback must never be invoked!", bounceCompleted)
        }
    }

    @Test
    fun `fix H1 production coordinator regrab during bounce with reverse direction transitions smoothly`() = runBlocking {
        val clock = ManualFrameClock()
        withContext(clock) {
            val coordinator = DeckGestureCoordinator(this)
            val animDepth = Animatable(0f)
            val animOffsetX = Animatable(0f)
            val animOffsetY = Animatable(0f)

            coordinator.bindPhoto(6003L)
            coordinator.onDragStart()
            coordinator.onDragUpdate(-80f, 0f, hThreshold, vThreshold, animDepth)
            coordinator.updateVisualOffset(-80f, 0f, animOffsetX, animOffsetY)
            drainDispatchers()
            coordinator.onDragEnd()

            val softSpring = spring<Float>(dampingRatio = 1f, stiffness = 260f)
            coordinator.bounceBack(
                softSpring = softSpring,
                animDepthProgress = animDepth,
                animOffsetX = animOffsetX,
                animOffsetY = animOffsetY,
                startOffsetX = coordinator.dragLogicalX,
                startOffsetY = coordinator.dragLogicalY
            )
            clock.advanceFrames(3)
            val grabX = animOffsetX.value

            // 回弹途中接管并反向向右深滑（向右 +150px）
            coordinator.onDragStart(
                initialX = animOffsetX.value,
                initialY = animOffsetY.value,
                hThreshold = hThreshold,
                vThreshold = vThreshold
            )
            val deltaX = 150f
            val newX = coordinator.dragLogicalX + deltaX
            coordinator.onDragUpdate(newX, 0f, hThreshold, vThreshold, animDepth)
            coordinator.updateVisualOffset(newX, 0f, animOffsetX, animOffsetY)
            drainDispatchers()

            // 验证位移连续
            assertEquals(grabX + deltaX, animOffsetX.value, 0.001f)
            assertTrue("Position must cross to positive right territory", animOffsetX.value > 60f)

            // 等待跨方向平滑过渡完成
            clock.advanceFrames(15)
            // 验证右滑回看景深安全收敛至 0f
            assertEquals(0f, animDepth.value, 0.005f)
        }
    }

    @Test
    fun `fix I1 production coordinator regrab during unsettled transition digests discrepancy without jump`() = runBlocking {
        val clock = ManualFrameClock()
        withContext(clock) {
            val coordinator = DeckGestureCoordinator(this)
            val animDepth = Animatable(0f)
            val animOffsetX = Animatable(0f)
            val animOffsetY = Animatable(0f)
            val soft = spring<Float>(dampingRatio = 1f, stiffness = 260f)

            coordinator.bindPhoto(7001L)
            coordinator.onDragStart(0f, 0f, hThreshold, vThreshold)

            // 1. 方向切换启动景深过渡：建立 (61, -100) RIGHT 意图后微移 2px 至 (59, -100) UP 意图
            coordinator.onDragUpdate(61f, -100f, hThreshold, vThreshold, animDepth)
            coordinator.updateVisualOffset(61f, -100f, animOffsetX, animOffsetY)
            drainDispatchers()

            coordinator.onDragUpdate(59f, -100f, hThreshold, vThreshold, animDepth)
            coordinator.updateVisualOffset(59f, -100f, animOffsetX, animOffsetY)
            drainDispatchers()

            // 2. 过渡尚未完成（仅 2 帧，景深仅约 0.033）即松手回弹
            clock.advanceFrames(2)
            val releaseDepth = animDepth.value
            assertTrue("Depth must be unsettled (< 0.10f)", releaseDepth < 0.10f)

            val action = coordinator.resolveReleaseAction(0f, 0f, hThreshold, vThreshold, true)
            assertEquals(ReleaseAction.BOUNCE, action)
            coordinator.onDragEnd()

            var oldBounceCallback = false
            coordinator.bounceBack(
                softSpring = soft,
                animDepthProgress = animDepth,
                animOffsetX = animOffsetX,
                animOffsetY = animOffsetY,
                startOffsetX = coordinator.dragLogicalX,
                startOffsetY = coordinator.dragLogicalY,
                onBounceComplete = { oldBounceCallback = true }
            )
            drainDispatchers()

            // 3. 回弹推进 3 帧：处于回弹中途
            clock.advanceFrames(3)
            val grabX = animOffsetX.value
            val grabY = animOffsetY.value
            val grabDepth = animDepth.value

            // 4. 接管回弹并输入小幅同方向增量 (deltaY = -1.0px)
            coordinator.onDragStart(grabX, grabY, hThreshold, vThreshold, grabDepth)
            val deltaY = -1.0f
            val nextY = coordinator.dragLogicalY + deltaY
            coordinator.onDragUpdate(coordinator.dragLogicalX, nextY, hThreshold, vThreshold, animDepth)
            coordinator.updateVisualOffset(coordinator.dragLogicalX, nextY, animOffsetX, animOffsetY)
            drainDispatchers()

            // 断言 1：接管后的位置等于抓取位置加输入增量
            assertEquals(grabY + deltaY, animOffsetY.value, 0.001f)

            // 断言 2：新目标到达后、下一动画帧推进前，实际景深绝不直接跳变为目标值 (0.6099f)，跳跃量为 0！
            assertEquals(grabDepth, animDepth.value, 0.001f)
            assertTrue("Depth must not jump immediately to target", animDepth.value < 0.10f)

            // 断言 3：随受控帧推进，景深连续平滑收敛至最后一次位移对应的目标 (91.49 / 150 ≈ 0.6099)
            val targetDepth = DeckDepthModel.computeTargetProgress(coordinator.dragLogicalX, nextY, hThreshold, vThreshold)
            clock.advanceFrames(20)
            assertEquals(targetDepth, animDepth.value, 0.005f)

            // 断言 4：被取消的旧回弹不触发完成回调，也不覆盖新手势
            assertFalse("Cancelled bounce callback must not be invoked", oldBounceCallback)
        }
    }

    @Test
    fun `queued drag depth cannot overwrite a newly grabbed gesture`() = runBlocking {
        val coordinator = DeckGestureCoordinator(this)
        val depth = Animatable(0.25f)
        val x = Animatable(-35f)
        val y = Animatable(0f)
        coordinator.bindPhoto(8001L)
        coordinator.onDragStart(x.value, y.value, hThreshold, vThreshold, depth.value)

        // 输入已到达，但同方向的显示更新仍在队列中。
        coordinator.onDragUpdate(-70f, 0f, hThreshold, vThreshold, depth)
        coordinator.onDragEnd()
        coordinator.bounceBack(spring(), depth, x, y)
        coordinator.onDragStart(x.value, y.value, hThreshold, vThreshold, depth.value)
        drainDispatchers()

        assertEquals(-35f, coordinator.dragLogicalX, 0.001f)
        assertEquals("An old queued update must not change the grabbed depth", 0.25f, depth.value, 0.001f)
    }

    @Test
    fun `queued drag depth cannot jump ahead of commit animation`() = runBlocking {
        val clock = ManualFrameClock()
        withContext(clock) {
            val coordinator = DeckGestureCoordinator(this)
            val depth = Animatable(0.25f)
            val x = Animatable(-35f)
            coordinator.bindPhoto(8002L)
            coordinator.onDragStart(x.value, 0f, hThreshold, vThreshold, depth.value)
            coordinator.onDragUpdate(-70f, 0f, hThreshold, vThreshold, depth)
            coordinator.onDragEnd()
            var callbacks = 0
            coordinator.commitSwipe(
                commitSpring = tween(80), animDepthProgress = depth, animOffset = x,
                targetOffset = -1000f, targetDepthP = 1f, startOffset = coordinator.dragLogicalX,
                onCommitSuccess = { callbacks++ }
            )
            drainDispatchers()

            // 尚未推进动画帧，提交必须继承最后显示的景深。
            assertEquals(0.25f, depth.value, 0.001f)
            clock.advanceFrames(10)
            assertEquals(1f, depth.value, 0.001f)
            assertEquals(1, callbacks)
        }
    }

    @Test
    fun `queued same direction updates follow latest depth without a transition`() = runBlocking {
        val coordinator = DeckGestureCoordinator(this)
        val depth = Animatable(0f)
        coordinator.bindPhoto(8003L)
        coordinator.onDragStart()
        for (x in listOf(-35f, -70f, -105f)) {
            coordinator.onDragUpdate(x, 0f, hThreshold, vThreshold, depth)
        }
        drainDispatchers()
        assertEquals(0.75f, depth.value, 0.001f)
        assertNull(coordinator.depthTransitionJob)
    }

    @Test
    fun `release action priority matches right-left-up-bounce contract`() {
        // 首张照片右滑无上一张 -> BOUNCE_RIGHT_FIRST
        val firstBounce = DeckDepthModel.resolveReleaseAction(
            dx = 150f, dy = 0f, vx = 0f, vy = 0f,
            hThreshold = hThreshold, vThreshold = vThreshold,
            hasPreviousPhoto = false
        )
        assertEquals(ReleaseAction.BOUNCE_RIGHT_FIRST, firstBounce)

        // 非首张照片右滑 -> COMMIT_RIGHT
        val rightCommit = DeckDepthModel.resolveReleaseAction(
            dx = 150f, dy = 0f, vx = 0f, vy = 0f,
            hThreshold = hThreshold, vThreshold = vThreshold,
            hasPreviousPhoto = true
        )
        assertEquals(ReleaseAction.COMMIT_RIGHT, rightCommit)

        // 快速轻弹左滑（位移未达阈值但速度达标）-> COMMIT_LEFT
        val fastLeft = DeckDepthModel.resolveReleaseAction(
            dx = -30f, dy = 0f, vx = -460f, vy = 0f,
            hThreshold = hThreshold, vThreshold = vThreshold,
            hasPreviousPhoto = true
        )
        assertEquals(ReleaseAction.COMMIT_LEFT, fastLeft)

        // 快速轻弹上滑（位移未达阈值但速度达标）-> COMMIT_UP
        val fastUp = DeckDepthModel.resolveReleaseAction(
            dx = 0f, dy = -20f, vx = 0f, vy = -360f,
            hThreshold = hThreshold, vThreshold = vThreshold,
            hasPreviousPhoto = true
        )
        assertEquals(ReleaseAction.COMMIT_UP, fastUp)

        // 位移不足且速度不足 -> BOUNCE
        val normalBounce = DeckDepthModel.resolveReleaseAction(
            dx = -50f, dy = -20f, vx = 0f, vy = 0f,
            hThreshold = hThreshold, vThreshold = vThreshold,
            hasPreviousPhoto = true
        )
        assertEquals(ReleaseAction.BOUNCE, normalBounce)
    }
}
