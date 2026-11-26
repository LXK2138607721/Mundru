package com.lumina.cleaner.ui.onboarding

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.lumina.cleaner.core.util.HapticFeedback
import com.lumina.cleaner.ui.glass.AnimatedTrashIcon
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

private enum class DemoSlotRole { FOURTH, THIRD, NEXT, FRONT, PREV }
private data class DemoCardSlot(val role: DemoSlotRole, val card: DemoArtCard)

@Composable
internal fun OnboardingPlayground(
    isDark: Boolean,
    doneLeft: Boolean,
    doneUp: Boolean,
    doneRight: Boolean,
    onDoneLeft: () -> Unit,
    onDoneUp: () -> Unit,
    onDoneRight: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val scope = rememberCoroutineScope()
    val blurCache = remember(density.density) { DemoBlurEffectCache(density.density) }

    var virtualCursor by remember { mutableIntStateOf(0) }
    var demoTrashCount by remember { mutableIntStateOf(0) }
    var isCommitting by remember { mutableStateOf(false) }
    var isUserTouching by remember { mutableStateOf(false) }
    var hasUserSwipedOnce by remember { mutableStateOf(false) }

    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var depthProgress by remember { mutableFloatStateOf(0f) }
    var trashFlyProgress by remember { mutableFloatStateOf(0f) }
    var lidProgress by remember { mutableFloatStateOf(0f) }
    val trashBounce = remember { Animatable(1f) }
    var activeMotionJob by remember { mutableStateOf<Job?>(null) }
    val coachState = remember { DemoCoachState() }

    val hThresholdPx = with(density) { 34.dp.toPx() }
    val vThresholdPx = with(density) { 36.dp.toPx() }
    val minFlickDispPx = with(density) { 12.dp.toPx() }
    val cardWidthPx = with(density) { 188.dp.toPx() }
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val flyLeftXPx = -(screenWidthPx * 0.5f + cardWidthPx * 0.6f)
    // 让上一张回看卡静息时完全位于屏幕左侧外，拉入瞬间即以 100% 实体不透明覆盖当前卡（绝无透视叠影）
    val pullFromLeftStartX = -(screenWidthPx * 0.5f + cardWidthPx * 0.5f + with(density) { 16.dp.toPx() })
    val absPullDist = abs(pullFromLeftStartX)

    val allDone = doneLeft && doneUp && doneRight
    val remainingDirections = remember(doneLeft, doneUp, doneRight) {
        buildList {
            if (!doneLeft) add(DemoCoachDirection.LEFT)
            if (!doneUp) add(DemoCoachDirection.UP)
            if (!doneRight) add(DemoCoachDirection.RIGHT)
        }
    }

    LaunchedEffect(isUserTouching, isCommitting, remainingDirections, hasUserSwipedOnce) {
        if (isUserTouching || isCommitting || remainingDirections.isEmpty()) {
            coachState.resetImmediate()
            return@LaunchedEffect
        }
        runDemoCoachLoop(
            state = coachState,
            density = density.density,
            directions = remainingDirections,
            initialDelayMs = if (hasUserSwipedOnce) 1900L else 550L
        )
    }

    val visibleSlots = remember(virtualCursor) {
        listOf(
            DemoCardSlot(DemoSlotRole.FOURTH, demoCardAt(virtualCursor + 3)),
            DemoCardSlot(DemoSlotRole.THIRD, demoCardAt(virtualCursor + 2)),
            DemoCardSlot(DemoSlotRole.NEXT, demoCardAt(virtualCursor + 1)),
            DemoCardSlot(DemoSlotRole.FRONT, demoCardAt(virtualCursor)),
            DemoCardSlot(DemoSlotRole.PREV, demoCardAt(virtualCursor - 1))
        )
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(316.dp),
            contentAlignment = Alignment.Center
        ) {
            val trashRed = MaterialTheme.colorScheme.error
            Surface(
                shape = CircleShape,
                color = if (isDark) Color(0xFF1D1E24) else Color.White,
                border = BorderStroke(
                    width = 0.8.dp,
                    color = if (demoTrashCount > 0) trashRed.copy(alpha = 0.45f)
                    else if (isDark) Color.White.copy(alpha = 0.14f)
                    else Color.Black.copy(alpha = 0.08f)
                ),
                shadowElevation = if (isDark) 0.dp else 4.dp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 6.dp, top = 2.dp)
                    .graphicsLayer {
                        val effLid = maxOf(lidProgress, coachState.cardLidP)
                        val s = trashBounce.value * (1f + effLid * 0.14f)
                        scaleX = s
                        scaleY = s
                    }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp)
                ) {
                    AnimatedTrashIcon(
                        tint = if (demoTrashCount > 0) trashRed else MaterialTheme.colorScheme.onSurfaceVariant,
                        lidOpenDegrees = { maxOf(lidProgress, coachState.cardLidP) * 44f },
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = "$demoTrashCount",
                        style = TextStyle(fontFeatureSettings = "tnum"),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (demoTrashCount > 0) trashRed else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Box(
                modifier = Modifier
                    .size(width = 224.dp, height = 274.dp)
                    .pointerInput(Unit) {
                        val slop = viewConfiguration.touchSlop
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if (isCommitting) return@awaitEachGesture

                            activeMotionJob?.cancel()
                            isUserTouching = true
                            coachState.resetImmediate()

                            val velocityTracker = VelocityTracker()
                            velocityTracker.addPosition(down.uptimeMillis, down.position)
                            var dragging = false
                            var thresholdTicked = false
                            var accX = 0f
                            var accY = 0f

                            try {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break

                                    val delta = change.positionChange()
                                    velocityTracker.addPosition(change.uptimeMillis, change.position)
                                    if (!dragging) {
                                        accX += delta.x
                                        accY += delta.y
                                        if (abs(accX) > slop * 0.7f || abs(accY) > slop * 0.7f) {
                                            dragging = true
                                        }
                                    }
                                    if (dragging) {
                                        change.consume()
                                        val newX = offsetX + delta.x
                                        val newY = (offsetY + delta.y).let { if (it > 0f) it * 0.20f else it }
                                        val leftPull = (-newX / (hThresholdPx * 2.1f)).coerceIn(0f, 1f)
                                        val upPull = (-newY / (vThresholdPx * 1.6f)).coerceIn(0f, 1f)
                                        val isUpDom = newY < -10f && abs(newY) >= abs(newX) * 0.72f
                                        val crossed = newX < -hThresholdPx || newX > hThresholdPx || (isUpDom && newY < -vThresholdPx)
                                        if (crossed && !thresholdTicked) {
                                            thresholdTicked = true
                                            HapticFeedback.tick(context)
                                        } else if (!crossed) {
                                            thresholdTicked = false
                                        }
                                        Snapshot.withMutableSnapshot {
                                            offsetX = newX
                                            offsetY = newY
                                            depthProgress = maxOf(leftPull, upPull)
                                            lidProgress = if (isUpDom) upPull else 0f
                                        }
                                    }
                                }
                            } finally {
                                isUserTouching = false
                            }

                            if (!dragging || isCommitting) return@awaitEachGesture
                            hasUserSwipedOnce = true

                            val vel = velocityTracker.calculateVelocity()
                            val dx = offsetX
                            val dy = offsetY
                            val startDepth = depthProgress
                            val startLid = lidProgress
                            val isUpAxis = dy < -minFlickDispPx && abs(dy) >= abs(dx) * 0.72f
                            val isUp = isUpAxis && (dy < -vThresholdPx || vel.y < -340f)
                            val isLeft = !isUp && dx < -minFlickDispPx && (dx < -hThresholdPx || vel.x < -320f)
                            val isRight = !isUp && dx > minFlickDispPx && (dx > hThresholdPx || vel.x > 320f)

                            when {
                                isLeft -> {
                                    isCommitting = true
                                    HapticFeedback.click(context)
                                    activeMotionJob = scope.launch {
                                        animate(0f, 1f, animationSpec = tween(175, easing = CubicBezierEasing(0.20f, 0.90f, 0.30f, 1f))) { t, _ ->
                                            Snapshot.withMutableSnapshot {
                                                offsetX = dx + (flyLeftXPx - dx) * t
                                                offsetY = dy * (1f - t)
                                                depthProgress = startDepth + (1f - startDepth) * t
                                                lidProgress = startLid * (1f - t)
                                            }
                                        }
                                        Snapshot.withMutableSnapshot {
                                            offsetX = 0f
                                            offsetY = 0f
                                            depthProgress = 0f
                                            lidProgress = 0f
                                            virtualCursor += 1
                                            isCommitting = false
                                        }
                                        onDoneLeft()
                                    }
                                }

                                isUp -> {
                                    isCommitting = true
                                    activeMotionJob = scope.launch {
                                        animate(0f, 1f, animationSpec = tween(215, easing = CubicBezierEasing(0.22f, 0.88f, 0.32f, 1f))) { t, _ ->
                                            Snapshot.withMutableSnapshot {
                                                trashFlyProgress = t
                                                depthProgress = startDepth + (1f - startDepth) * t
                                                lidProgress = startLid + (1f - startLid) * (t * 1.6f).coerceAtMost(1f)
                                            }
                                        }
                                        HapticFeedback.heavy(context)
                                        Snapshot.withMutableSnapshot {
                                            offsetX = 0f
                                            offsetY = 0f
                                            depthProgress = 0f
                                            trashFlyProgress = 0f
                                            virtualCursor += 1
                                            demoTrashCount += 1
                                            isCommitting = false
                                        }
                                        onDoneUp()
                                        launch {
                                            trashBounce.snapTo(1.24f)
                                            trashBounce.animateTo(1f, spring(dampingRatio = 0.52f, stiffness = 480f))
                                        }
                                        launch {
                                            animate(lidProgress, 0f, animationSpec = spring(dampingRatio = 0.56f, stiffness = 420f)) { v, _ ->
                                                lidProgress = v
                                            }
                                        }
                                    }
                                }

                                isRight -> {
                                    isCommitting = true
                                    HapticFeedback.click(context)
                                    activeMotionJob = scope.launch {
                                        animate(
                                            initialValue = dx,
                                            targetValue = absPullDist,
                                            animationSpec = tween(220, easing = CubicBezierEasing(0.18f, 0.92f, 0.28f, 1f))
                                        ) { v, _ ->
                                            Snapshot.withMutableSnapshot {
                                                offsetX = v
                                                offsetY = dy * ((absPullDist - v) / (absPullDist - dx).coerceAtLeast(1f)).coerceIn(0f, 1f)
                                                depthProgress = 0f
                                                lidProgress = 0f
                                            }
                                        }
                                        Snapshot.withMutableSnapshot {
                                            offsetX = 0f
                                            offsetY = 0f
                                            depthProgress = 0f
                                            lidProgress = 0f
                                            virtualCursor -= 1
                                            isCommitting = false
                                        }
                                        onDoneRight()
                                    }
                                }

                                else -> {
                                    activeMotionJob = scope.launch {
                                        animate(1f, 0f, animationSpec = spring(dampingRatio = 0.78f, stiffness = 460f)) { rem, _ ->
                                            Snapshot.withMutableSnapshot {
                                                offsetX = dx * rem
                                                offsetY = dy * rem
                                                depthProgress = startDepth * rem
                                                lidProgress = startLid * rem
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                // 单一循环 + key(card.serial)：切卡时复用原组件与离屏层，边界参数像素级严丝合缝，0 闪动
                for (slot in visibleSlots) {
                    key(slot.card.serial) {
                        val role = slot.role
                        DemoArtCardView(
                            card = slot.card,
                            blurCache = blurCache,
                            blurDpProvider = {
                                val p = maxOf(depthProgress, coachState.cardDepthP).coerceIn(0f, 1f)
                                val effOx = offsetX + coachState.cardOffsetX
                                val rightFrac = if (effOx > 1f && trashFlyProgress <= 0.001f) {
                                    (effOx / absPullDist).coerceIn(0f, 1f)
                                } else 0f
                                when (role) {
                                    DemoSlotRole.FOURTH -> 6f
                                    DemoSlotRole.THIRD -> 6f - 3f * p
                                    DemoSlotRole.NEXT -> (3f * (1f - p)) + 3f * rightFrac
                                    DemoSlotRole.FRONT -> 3f * rightFrac
                                    DemoSlotRole.PREV -> 0f
                                }
                            },
                            trashGlowProvider = {
                                if (role != DemoSlotRole.FRONT) 0f
                                else if (trashFlyProgress > 0.01f) 1f
                                else {
                                    val effOy = offsetY + coachState.cardOffsetY
                                    val effOx = offsetX + coachState.cardOffsetX
                                    if (effOy < -10f && abs(effOy) >= abs(effOx) * 0.72f) {
                                        (-effOy / (vThresholdPx * 1.4f)).coerceIn(0f, 1f)
                                    } else 0f
                                }
                            },
                            modifier = Modifier
                                .width(188.dp)
                                .zIndex(
                                    when (role) {
                                        DemoSlotRole.FOURTH -> 0f
                                        DemoSlotRole.THIRD -> 1f
                                        DemoSlotRole.NEXT -> 2f
                                        DemoSlotRole.FRONT -> 3f
                                        DemoSlotRole.PREV -> 4f
                                    }
                                )
                                .graphicsLayer {
                                    val p = maxOf(depthProgress, coachState.cardDepthP).coerceIn(0f, 1f)
                                    val flyP = trashFlyProgress
                                    val effOx = offsetX + coachState.cardOffsetX
                                    val effOy = offsetY + coachState.cardOffsetY
                                    val pullingRight = effOx > 1f && flyP <= 0.001f
                                    val rightFrac = if (pullingRight) (effOx / absPullDist).coerceIn(0f, 1f) else 0f
                                    val d = density.density

                                    when (role) {
                                        DemoSlotRole.FOURTH -> {
                                            val s = 0.82f + 0.06f * p
                                            scaleX = s
                                            scaleY = s
                                            translationX = 0f
                                            translationY = (-34f + 10f * p) * d
                                            rotationZ = 0f
                                            alpha = 0.72f * p * (1f - rightFrac)
                                        }
                                        DemoSlotRole.THIRD -> {
                                            val s = 0.88f + 0.06f * p - 0.06f * rightFrac
                                            scaleX = s
                                            scaleY = s
                                            translationX = 0f
                                            translationY = (-24f + 11f * p - 10f * rightFrac) * d
                                            rotationZ = 0f
                                            alpha = (0.72f + 0.16f * p) * (1f - rightFrac)
                                        }
                                        DemoSlotRole.NEXT -> {
                                            val s = 0.94f + 0.06f * p - 0.06f * rightFrac
                                            scaleX = s
                                            scaleY = s
                                            translationX = 0f
                                            translationY = (-13f + 13f * p - 11f * rightFrac) * d
                                            rotationZ = 0f
                                            alpha = 0.88f + 0.12f * p - 0.16f * rightFrac
                                        }
                                        DemoSlotRole.FRONT -> {
                                            if (flyP > 0.001f) {
                                                val targetX = 126.dp.toPx()
                                                val targetY = -134.dp.toPx()
                                                val s = (1f - 0.76f * flyP).coerceAtLeast(0.20f)
                                                scaleX = s
                                                scaleY = s
                                                translationX = effOx + (targetX - effOx) * flyP
                                                translationY = effOy + (targetY - effOy) * flyP - (1f - flyP) * flyP * 40f
                                                rotationZ = 14f * flyP
                                                alpha = (1f - ((flyP - 0.65f) / 0.35f).coerceIn(0f, 1f))
                                            } else if (pullingRight) {
                                                val s = 1f - 0.06f * rightFrac
                                                scaleX = s
                                                scaleY = s
                                                translationX = 0f
                                                translationY = -13f * rightFrac * d
                                                rotationZ = 0f
                                                alpha = 1f - 0.12f * rightFrac
                                            } else {
                                                val upPull = (-effOy / (vThresholdPx * 1.4f)).coerceIn(0f, 1f)
                                                val isUpPull = effOy < -10f && abs(effOy) >= abs(effOx) * 0.72f
                                                val s = if (isUpPull) (1f - 0.14f * upPull) else 1f
                                                scaleX = s
                                                scaleY = s
                                                translationX = effOx
                                                translationY = effOy
                                                rotationZ = (effOx * 0.036f).coerceIn(-12f, 12f)
                                                alpha = if (effOx < -210f * d) {
                                                    (1f - ((-effOx - 210f * d) / (110f * d))).coerceIn(0f, 1f)
                                                } else 1f
                                            }
                                        }
                                        DemoSlotRole.PREV -> {
                                            if (!pullingRight) {
                                                scaleX = 1f
                                                scaleY = 1f
                                                translationX = pullFromLeftStartX
                                                translationY = 0f
                                                rotationZ = 0f
                                                alpha = 0f
                                            } else {
                                                // 全程 100% 纯实体不透明，彻底遮盖下方当前卡，重合处绝不透视
                                                scaleX = 1f
                                                scaleY = 1f
                                                translationX = pullFromLeftStartX + effOx
                                                translationY = 0f
                                                rotationZ = -6f * (1f - rightFrac)
                                                alpha = 1f
                                            }
                                        }
                                    }
                                }
                        )
                    }
                }

                // 自动演示触控光球与方向说明浮层（触摸即消失，不抢手势）
                OnboardingDemoCoachOverlay(
                    state = coachState,
                    isDark = isDark,
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(10f)
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GestureCheckPill(label = "左滑保留", done = doneLeft, isDark = isDark)
            GestureCheckPill(label = "上滑入桶", done = doneUp, isDark = isDark)
            GestureCheckPill(label = "右滑回看", done = doneRight, isDark = isDark)
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = if (allDone) "全部手势已熟悉，可自由继续试滑或进入下一步"
            else "任意顺序轻滑即可体验：左保留 · 上入桶 · 右回看",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

@Composable
private fun GestureCheckPill(label: String, done: Boolean, isDark: Boolean) {
    val bgColor by animateColorAsState(
        targetValue = if (done) MaterialTheme.colorScheme.primary
        else if (isDark) Color.White.copy(alpha = 0.07f)
        else Color.Black.copy(alpha = 0.05f),
        animationSpec = tween(200),
        label = "gesture_pill_bg"
    )
    val contentColor by animateColorAsState(
        targetValue = if (done) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(200),
        label = "gesture_pill_content"
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(CircleShape)
            .background(bgColor)
            .border(
                width = 0.6.dp,
                color = if (done) Color.Transparent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
                shape = CircleShape
            )
            .padding(horizontal = 11.dp, vertical = 6.dp)
    ) {
        if (done) {
            Icon(Icons.Rounded.Check, contentDescription = null, tint = contentColor, modifier = Modifier.size(13.dp))
            Spacer(modifier = Modifier.width(4.dp))
        }
        Text(label, fontSize = 11.sp, fontWeight = if (done) FontWeight.SemiBold else FontWeight.Medium, color = contentColor)
    }
}
