package com.lumina.cleaner.ui.deck

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 系统相册级零迟滞单击 / 双击 / 实况长按协同手势检测器 (detectSnappyTapAndLivePress)
 *
 * 相比 Compose 默认 detectTapGestures 的核心优化：
 * 1. 将单击等待双击的判停窗口从系统默认 300ms 压缩至 165ms（约提速 45%），
 *    既完整保留刻意双击 2.5x 放大的准确命中率，又彻底消除单击开图/关图时的明显停顿感；
 * 2. 若未注册 onDoubleTap，抬手瞬间 0ms 立即触发 onTap；
 * 3. 与外层 CardStack 拖拽手势及 PhotoViewer 双指捏合缩放完全零冲突（requireUnconsumed = true），
 *    并且长按实况预览在任何取消或离屏路径下均通过 finally 严格复位。
 */
suspend fun PointerInputScope.detectSnappyTapAndLivePress(
    doubleTapWindowMs: Long = 165L,
    enableLongPress: Boolean = false,
    onLongPressStart: (() -> Unit)? = null,
    onLongPressEnd: (() -> Unit)? = null,
    onTap: (Offset) -> Unit,
    onDoubleTap: ((Offset) -> Unit)? = null
) = coroutineScope {
    val doubleTapSlopPx = 96.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = true)
        var longPressed = false
        var longPressJob: Job? = null

        if (enableLongPress && onLongPressStart != null) {
            longPressJob = launch {
                delay(viewConfiguration.longPressTimeoutMillis)
                longPressed = true
                onLongPressStart()
            }
        }

        val firstUp = try {
            waitForUpOrCancellation()
        } finally {
            longPressJob?.cancel()
            if (longPressed) {
                onLongPressEnd?.invoke()
            }
        }

        if (longPressed || firstUp == null) return@awaitEachGesture

        if (onDoubleTap == null) {
            firstUp.consume()
            onTap(firstUp.position)
            return@awaitEachGesture
        }

        val secondDown = withTimeoutOrNull(doubleTapWindowMs) {
            val candidate = awaitFirstDown(requireUnconsumed = true)
            if ((candidate.position - firstUp.position).getDistance() <= doubleTapSlopPx) {
                candidate
            } else {
                null
            }
        }

        if (secondDown == null) {
            onTap(firstUp.position)
        } else {
            val secondUp = waitForUpOrCancellation()
            if (secondUp != null) {
                secondUp.consume()
                onDoubleTap(secondUp.position)
            }
        }
    }
}
