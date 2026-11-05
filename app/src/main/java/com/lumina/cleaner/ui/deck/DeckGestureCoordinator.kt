package com.lumina.cleaner.ui.deck

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 卡片舞台手势与景深连续过渡协同器 (DeckGestureCoordinator)
 *
 * 核心架构规范：
 * 1. 【回弹中途重新拖拽平滑接管与景深落差消化】（解决 H1 & I1）：
 *    当在回弹途中重新触控拖拽时，以接管瞬间的实际显示位置作为逻辑位移基准，
 *    以接管瞬间的实际景深值作为过渡起点。若接管瞬间实际显示景深与位移目标景深存在落差（如景深尚未追上位置），
 *    自动启动平滑过渡渐进消化落差，绝不直接 snapTo 造成景深暴涨闪变；对已对齐的正常拖动保持 1:1 跟手零延迟。
 *
 * 2. 【同步手势逻辑坐标与确定性松手裁决】（解决 G1）：
 *    在输入事件回调中同步维护当前手势逻辑位移 (dragLogicalX, dragLogicalY)，松手裁决绝对
 *    采用最后一次有效输入坐标，视觉动画与景深更新异步解耦，杜绝协程排队导致过期判定或晚到污染。
 *
 * 3. 【活跃过渡中的即时重定向与反向消除】（解决 G2）：
 *    跟踪过渡动画的目标 animatingTargetDepthP。当用户在同一操作意图内部反向收回手指，
 *    或目标发生大幅变更时，立即从当前显示进度重定向至新目标，坚决杜绝跑完旧目标导致的逆向运动与滞后。
 *
 * 4. 【照片身份与代次双重隔离】（解决 F1）：
 *    照片切换时立即取消所有任务并递增代次 (gestureEpoch)，严禁过期回调分发到新照片。
 *
 * 5. 【双动画结构化并发终验】（解决 R3/F3/H2）：
 *    提交飞出时支持双动画规格或不同物理沉降时间，并发等待位移动画与景深动画均到达终点，保证回调幂等且仅触发一次。
 */
class DeckGestureCoordinator(
    private val scope: CoroutineScope
) {
    // 当前绑定的照片身份
    var currentPhotoId: Long = -1L
        private set

    // 操作代次（每次绑定新照片、提交或重置手势时自增，用于阻断过期协程）
    var gestureEpoch: Long = 0L
        private set

    // 正在运行的手势提交/回弹任务
    var activeSwipeJob: Job? = null
        private set

    // 正在运行的景深过渡任务
    var depthTransitionJob: Job? = null
        private set

    // 正在运行的视觉位移更新任务
    var dragVisualJob: Job? = null
        private set

    // 同方向直接跟手更新也必须参与取消，不能在松手或接管后写回旧值。
    private var dragDepthJob: Job? = null

    // 上一次拖拽确立的意图方向
    var lastDragIntent: SwipeIntent = SwipeIntent.NONE
        private set

    // 最新待达到的景深目标（由手势位移实时刷新）
    @Volatile
    var pendingTargetDepthP: Float = 0f
        private set

    // 正在执行过渡动画的目标景深进度
    var animatingTargetDepthP: Float = 0f
        private set

    // 是否正在提交切图飞出
    var isCommittingSwipe: Boolean = false
        private set

    // G1: 同步手势逻辑坐标与状态（与 UI 线程/输入事件严格同序）
    var dragLogicalX: Float = 0f
        private set
    var dragLogicalY: Float = 0f
        private set
    var isDragging: Boolean = false
        private set

    // H1: 保存当前正在进行回弹动画的 Animatable 引用，以便无显式传参时也能自动感知并接管
    var activeBounceX: Animatable<Float, *>? = null
        private set
    var activeBounceY: Animatable<Float, *>? = null
        private set
    var activeBounceDepth: Animatable<Float, *>? = null
        private set

    // I1: 接管瞬间是否存在实际景深与位移目标景深的未消化落差
    var hasUnsettledDepth: Boolean = false
        private set

    // 缓存上一次使用的手势阈值
    var lastHThreshold: Float = 0f
        private set
    var lastVThreshold: Float = 0f
        private set

    /**
     * 绑定当前顶层照片身份。当照片切换时调用，彻底终结旧任务。
     */
    fun bindPhoto(photoId: Long) {
        if (currentPhotoId == photoId) return
        cancelAllJobs()
        currentPhotoId = photoId
        gestureEpoch++
        isCommittingSwipe = false
        isDragging = false
        dragLogicalX = 0f
        dragLogicalY = 0f
        lastDragIntent = SwipeIntent.NONE
        pendingTargetDepthP = 0f
        animatingTargetDepthP = 0f
        hasUnsettledDepth = false
        activeBounceX = null
        activeBounceY = null
        activeBounceDepth = null
    }

    /**
     * 取消所有活跃任务
     */
    fun cancelAllJobs() {
        activeSwipeJob?.cancel()
        activeSwipeJob = null
        depthTransitionJob?.cancel()
        depthTransitionJob = null
        dragVisualJob?.cancel()
        dragVisualJob = null
        dragDepthJob?.cancel()
        dragDepthJob = null
    }

    /**
     * 手势开始拖拽时初始化状态（解决 H1 & I1：接管回弹中途的显示位置与景深，消化落差，杜绝瞬移突跳）
     */
    fun onDragStart(
        initialX: Float? = null,
        initialY: Float? = null,
        hThreshold: Float = 0f,
        vThreshold: Float = 0f,
        initialDepth: Float? = null
    ) {
        if (isCommittingSwipe) return

        if (hThreshold > 0f) lastHThreshold = hThreshold
        if (vThreshold > 0f) lastVThreshold = vThreshold
        val ht = if (hThreshold > 0f) hThreshold else lastHThreshold
        val vt = if (vThreshold > 0f) vThreshold else lastVThreshold

        // 判断是否正在打断一个活跃的回弹动画
        val isInterruptingBounce = activeSwipeJob != null && activeSwipeJob!!.isActive

        val resolvedX = when {
            initialX != null -> initialX
            isInterruptingBounce && activeBounceX != null -> activeBounceX!!.value
            else -> 0f
        }

        val resolvedY = when {
            initialY != null -> initialY
            isInterruptingBounce && activeBounceY != null -> activeBounceY!!.value
            else -> 0f
        }

        // 读取接管瞬间的实际景深值
        val currentDepth = when {
            initialDepth != null -> initialDepth
            isInterruptingBounce && activeBounceDepth != null -> activeBounceDepth!!.value
            activeBounceDepth != null -> activeBounceDepth!!.value
            else -> 0f
        }

        cancelAllJobs()
        gestureEpoch++
        isDragging = true

        dragLogicalX = resolvedX
        dragLogicalY = resolvedY

        if (resolvedX != 0f || resolvedY != 0f) {
            lastDragIntent = DeckDepthModel.resolveDragIntent(resolvedX, resolvedY, ht, vt)
            val targetP = DeckDepthModel.computeTargetProgress(resolvedX, resolvedY, ht, vt)

            // 解决 I1：若接管瞬间实际显示景深与位移目标景深存在落差（> 0.05f），
            // 标记存在未消化的落差，后续更新必须启动平滑过渡逐渐追平，严禁直接 snapTo
            val depthDiff = abs(targetP - currentDepth)
            hasUnsettledDepth = depthDiff > 0.05f

            pendingTargetDepthP = targetP
            animatingTargetDepthP = currentDepth
        } else {
            lastDragIntent = SwipeIntent.NONE
            pendingTargetDepthP = 0f
            animatingTargetDepthP = 0f
            hasUnsettledDepth = currentDepth > 0.05f
        }
    }

    /**
     * 手势拖拽结束时标记
     */
    fun onDragEnd() {
        isDragging = false
        dragVisualJob?.cancel()
        dragVisualJob = null
        dragDepthJob?.cancel()
        dragDepthJob = null
    }

    /**
     * 拖拽位移更新（解决 G1: 同步更新逻辑坐标；解决 G2: 即时重定向与反向消除；解决 I1: 渐进消化接管景深落差）
     */
    fun onDragUpdate(
        newX: Float,
        newY: Float,
        hThreshold: Float,
        vThreshold: Float,
        animDepthProgress: Animatable<Float, *>,
        updateJobScope: CoroutineScope = scope
    ) {
        if (isCommittingSwipe) return

        dragDepthJob?.cancel()
        dragDepthJob = null
        lastHThreshold = hThreshold
        lastVThreshold = vThreshold

        // 1. 同步更新手势逻辑坐标与状态
        dragLogicalX = newX
        dragLogicalY = newY
        isDragging = true

        val targetDepthP = DeckDepthModel.computeTargetProgress(newX, newY, hThreshold, vThreshold)
        val currentIntent = DeckDepthModel.resolveDragIntent(newX, newY, hThreshold, vThreshold)
        val isIntentChanged = lastDragIntent != SwipeIntent.NONE && currentIntent != lastDragIntent
        lastDragIntent = currentIntent
        pendingTargetDepthP = targetDepthP

        val currentP = animDepthProgress.value
        val diff = abs(targetDepthP - currentP)

        val isTransitionActive = depthTransitionJob != null && depthTransitionJob!!.isActive

        // G2: 识别过渡中的目标反向变化（动画向一个方向推进，但新目标位于反方向）
        val isReversed = isTransitionActive && (
            (animatingTargetDepthP - currentP) * (targetDepthP - currentP) < -0.0001f
        )

        // G2: 识别过渡中的目标跨度大幅更新（如 0.2 -> 0.9）
        val isDrasticTargetChange = isTransitionActive && (
            abs(targetDepthP - animatingTargetDepthP) > 0.05f
        )

        // I1: 接管瞬间若存在未消化落差，必须从当前显示值平滑过渡至新目标
        val shouldStartTransition = (isIntentChanged && diff > 0.05f) ||
                isReversed ||
                isDrasticTargetChange ||
                hasUnsettledDepth

        if (shouldStartTransition) {
            hasUnsettledDepth = false
            // 意图切换、反向、大幅更新或接管未消化落差：立即取消旧动画，从当前进度平滑重定向至最新目标
            depthTransitionJob?.cancel()
            depthTransitionJob = updateJobScope.launch {
                animatingTargetDepthP = targetDepthP
                while (isActive) {
                    val target = pendingTargetDepthP
                    animatingTargetDepthP = target
                    val current = animDepthProgress.value
                    if (abs(target - current) < 0.005f) {
                        animDepthProgress.snapTo(target)
                        break
                    }
                    animDepthProgress.animateTo(
                        targetValue = target,
                        animationSpec = tween(durationMillis = 120, easing = FastOutSlowInEasing)
                    )
                    // 循环检测：若在动画执行期间 pendingTarget 又被更新，循环继续追赶；否则退出
                    if (pendingTargetDepthP == target) break
                }
            }
        } else {
            // 若当前无活跃过渡动画，1:1 直接跟手
            if (!isTransitionActive && targetDepthP != currentP) {
                val capturedPhotoId = currentPhotoId
                val capturedEpoch = gestureEpoch
                dragDepthJob = updateJobScope.launch {
                    if (!isDragging || currentPhotoId != capturedPhotoId || gestureEpoch != capturedEpoch) return@launch
                    animDepthProgress.snapTo(targetDepthP)
                }
            }
            // 若过渡正在进行且属于同向小幅位移，由收敛循环自动吸纳最新 pendingTargetDepthP
        }
    }

    /**
     * 更新视觉位移（带代次与拖拽状态保护）
     */
    fun updateVisualOffset(
        targetX: Float,
        targetY: Float,
        animOffsetX: Animatable<Float, *>,
        animOffsetY: Animatable<Float, *>,
        updateJobScope: CoroutineScope = scope
    ) {
        if (isCommittingSwipe || !isDragging) return
        val capturedPhotoId = currentPhotoId
        val capturedEpoch = gestureEpoch
        dragVisualJob?.cancel()
        if (animOffsetX.value == targetX && animOffsetY.value == targetY) {
            dragVisualJob = null
            return
        }
        dragVisualJob = updateJobScope.launch {
            if (!isDragging || currentPhotoId != capturedPhotoId || gestureEpoch != capturedEpoch) return@launch
            if (animOffsetX.value != targetX) animOffsetX.snapTo(targetX)
            if (animOffsetY.value != targetY) animOffsetY.snapTo(targetY)
        }
    }

    /**
     * 同步裁决松手动作（基于最新逻辑坐标，杜绝读取排队中过期动画值）
     */
    fun resolveReleaseAction(
        vx: Float,
        vy: Float,
        hThreshold: Float,
        vThreshold: Float,
        hasPreviousPhoto: Boolean
    ): ReleaseAction {
        lastHThreshold = hThreshold
        lastVThreshold = vThreshold
        return DeckDepthModel.resolveReleaseAction(
            dx = dragLogicalX,
            dy = dragLogicalY,
            vx = vx,
            vy = vy,
            hThreshold = hThreshold,
            vThreshold = vThreshold,
            hasPreviousPhoto = hasPreviousPhoto
        )
    }

    /**
     * 执行切图提交并发等待（解决 G1: 从逻辑位移起飞；解决 R3/F1/H2: 身份守卫与双动画并发同步）
     */
    fun commitSwipe(
        commitSpring: AnimationSpec<Float>,
        animDepthProgress: Animatable<Float, *>,
        animOffset: Animatable<Float, *>,
        targetOffset: Float,
        targetDepthP: Float,
        startOffset: Float = animOffset.value,
        depthSpring: AnimationSpec<Float> = commitSpring,
        onCommitSuccess: () -> Unit
    ) {
        if (isCommittingSwipe) return
        isCommittingSwipe = true
        isDragging = false
        cancelAllJobs()
        gestureEpoch++

        val capturedPhotoId = currentPhotoId
        val capturedEpoch = gestureEpoch

        activeSwipeJob = scope.launch {
            try {
                // 确保动画从逻辑位移处连续起飞
                animOffset.snapTo(startOffset)
                coroutineScope {
                    launch { animDepthProgress.animateTo(targetDepthP, depthSpring) }
                    launch { animOffset.animateTo(targetOffset, commitSpring) }
                }

                // 身份与代次守卫
                if (currentPhotoId == capturedPhotoId && gestureEpoch == capturedEpoch) {
                    onCommitSuccess()
                }
            } catch (_: CancellationException) {}
        }
    }

    /** 右滑回看共用一条时间轴；所有图层就位后才撤回索引。 */
    fun commitReturn(
        progress: Animatable<Float, *>,
        onCommitSuccess: () -> Unit
    ) {
        if (isCommittingSwipe) return
        isCommittingSwipe = true
        isDragging = false
        cancelAllJobs()
        gestureEpoch++
        val capturedPhotoId = currentPhotoId
        val capturedEpoch = gestureEpoch

        activeSwipeJob = scope.launch {
            try {
                progress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(DeckReturnModel.DURATION_MILLIS, easing = LinearEasing)
                )
                if (currentPhotoId == capturedPhotoId && gestureEpoch == capturedEpoch) {
                    onCommitSuccess()
                }
            } catch (_: CancellationException) {}
        }
    }

    /**
     * 执行手势未达阈值或取消时的协同回弹
     */
    fun bounceBack(
        softSpring: AnimationSpec<Float>,
        animDepthProgress: Animatable<Float, *>,
        animOffsetX: Animatable<Float, *>,
        animOffsetY: Animatable<Float, *>,
        startOffsetX: Float = dragLogicalX,
        startOffsetY: Float = dragLogicalY,
        onBounceComplete: (() -> Unit)? = null
    ) {
        if (isCommittingSwipe) return
        isDragging = false
        cancelAllJobs()
        gestureEpoch++

        val capturedPhotoId = currentPhotoId
        val capturedEpoch = gestureEpoch

        activeBounceX = animOffsetX
        activeBounceY = animOffsetY
        activeBounceDepth = animDepthProgress

        activeSwipeJob = scope.launch {
            try {
                animOffsetX.snapTo(startOffsetX)
                animOffsetY.snapTo(startOffsetY)
                coroutineScope {
                    launch { animDepthProgress.animateTo(0f, softSpring) }
                    launch { animOffsetX.animateTo(0f, softSpring) }
                    launch { animOffsetY.animateTo(0f, softSpring) }
                }
                if (currentPhotoId == capturedPhotoId && gestureEpoch == capturedEpoch) {
                    activeBounceX = null
                    activeBounceY = null
                    activeBounceDepth = null
                    onBounceComplete?.invoke()
                }
            } catch (_: CancellationException) {}
        }
    }
}
