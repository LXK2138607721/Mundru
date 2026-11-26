package com.lumina.cleaner.ui.deck

import com.lumina.cleaner.core.model.SamplingMode

/**
 * 极速连滑防卡顿调度策略纯函数模型 (DeckIdleWorkPolicy)
 *
 * 统一规范高负荷后台任务与 GPU 离屏录制在快速连滑期间的退让规则：
 * 1. 顶部氛围光晕：慢速浏览与换批/刷新 0ms 瞬切同步；快速连滑 (<240ms) 防抖 180ms，彻底杜绝 Crossfade 叠加 3~4 层全宽模糊；
 * 2. 液态玻璃底图离屏重录：纯水平滑卡且无上滑拉动/氛围渐变/菜单展开时暂停重录，消除每帧 1224×2664 离屏拷贝与顶栏 5 枚 AGSL 重绘；
 * 3. 后台 IO 解析（实况/HDR、dHash、重型 PNG 预热）：严格在用户停止连滑的空闲窗口温和运行，连滑期间零 CPU/FUSE/LOS 内存争抢；
 * 4. 换批与冷启首二张卡预解码：「大图专清」与冷启动强制保障第 1、2 张同屏即模糊就位，杜绝黑卡到模糊的慢跳变；
 * 5. 滚动前瞻预解码：串行预热 [currentIndex + 1 .. currentIndex + 4] 中未命中显存的最多 2 张照片，确保第 5~15 张同样秒出。
 */
internal object DeckIdleWorkPolicy {
    const val RAPID_AMBIENT_INTERVAL_MS = 240L
    const val RAPID_AMBIENT_DEBOUNCE_MS = 180L

    const val FRONT_METADATA_IDLE_MS = 450L
    const val BATCH_METADATA_IDLE_MS = 1_400L
    const val DHASH_INDEX_IDLE_MS = 2_800L
    const val DHASH_MAX_PER_IDLE_BURST = 8
    const val DHASH_STEP_PAUSE_MS = 80L
    const val HEAVY_PNG_PREWARM_IDLE_MS = 1_200L

    const val LOOKAHEAD_DEBOUNCE_MS = 50L
    const val LOOKAHEAD_WINDOW = 4
    const val LOOKAHEAD_MAX_DECODES = 2
    const val MENU_LARGE_PREWARM_COUNT = 3

    const val FRONT_PREWARM_TIMEOUT_MS = 720L
    const val SECOND_CARD_PREWARM_TIMEOUT_MS = 720L
    const val SECOND_CARD_FAST_THUMB_TIMEOUT_MS = 180L
    const val THIRD_CARD_PREWARM_TIMEOUT_MS = 560L
    const val THIRD_CARD_FAST_THUMB_TIMEOUT_MS = 180L

    /** 快速连滑期间（距上次切图不足 240ms）对氛围光晕施加防抖，避免叠层模糊爆炸 */
    fun shouldDebounceAmbientUpdate(elapsedSinceLastSwitchMs: Long): Boolean =
        elapsedSinceLastSwitchMs in 0L until RAPID_AMBIENT_INTERVAL_MS

    /** 计算距离达到指定空闲门槛还需等待的毫秒数（0 表示已进入空闲安全窗口） */
    fun remainingIdleDelayMs(
        nowElapsedMs: Long,
        lastInteractionElapsedMs: Long,
        requiredIdleMs: Long
    ): Long {
        if (requiredIdleMs <= 0L) return 0L
        val elapsed = (nowElapsedMs - lastInteractionElapsedMs).coerceAtLeast(0L)
        return (requiredIdleMs - elapsed).coerceAtLeast(0L)
    }

    /** 判断当前帧是否可以安全跳过主舞台全屏液态玻璃离屏录制（顶栏像素完全静止、视觉 100% 无损） */
    fun shouldPauseBackdropRecording(
        isHorizontalSwipeOnly: Boolean,
        isTrashPullActive: Boolean,
        isAmbientCrossfading: Boolean,
        isModeMenuOpen: Boolean,
        isVortexActive: Boolean
    ): Boolean = isHorizontalSwipeOnly &&
        !isTrashPullActive &&
        !isAmbientCrossfading &&
        !isModeMenuOpen &&
        !isVortexActive

    /** 选出当前位置前方待滚动预解码的索引列表（跳过已命中显存项，最多返回 [maxToPrewarm] 个） */
    fun selectLookaheadIndices(
        deckSize: Int,
        currentIndex: Int,
        isCachedAt: (Int) -> Boolean,
        windowAhead: Int = LOOKAHEAD_WINDOW,
        maxToPrewarm: Int = LOOKAHEAD_MAX_DECODES
    ): List<Int> {
        if (deckSize <= 0 || currentIndex < 0 || maxToPrewarm <= 0 || windowAhead <= 0) {
            return emptyList()
        }
        val result = ArrayList<Int>(maxToPrewarm)
        val end = minOf(deckSize - 1, currentIndex + windowAhead)
        for (idx in (currentIndex + 1)..end) {
            if (!isCachedAt(idx)) {
                result.add(idx)
                if (result.size >= maxToPrewarm) break
            }
        }
        return result
    }

    /**
     * 计算第 2 张可见景深卡 (NEXT) 在换批/冷启阶段的预解码超时预算：
     * 全 5 种模式（随机漫步 / 时光考古 / 图集专清 / 月份专清 / 大图专清）无条件预解码进显存，
     * 常规照片约 15~35ms 即可在 195ms 漩涡吸入期内零感知完成，重型冷图给足预算杜绝黑卡到模糊的慢跳变。
     */
    fun secondCardPrewarmTimeoutMs(
        mode: SamplingMode,
        sizeBytes: Long,
        displayName: String,
        hasFastThumbOnDisk: Boolean,
        isVortexTransition: Boolean
    ): Long {
        if (hasFastThumbOnDisk) return SECOND_CARD_FAST_THUMB_TIMEOUT_MS
        return SECOND_CARD_PREWARM_TIMEOUT_MS
    }

    /**
     * 计算第 3 张可见景深卡 (THIRD) 在换批/冷启阶段的预解码超时预算：
     * 与首二张同步并发预热，确保三层可见卡堆在旋展第 0 帧即全部以对应模糊态呈现、零黑卡露底。
     */
    fun thirdCardPrewarmTimeoutMs(
        mode: SamplingMode,
        sizeBytes: Long,
        displayName: String,
        hasFastThumbOnDisk: Boolean,
        isVortexTransition: Boolean
    ): Long {
        if (hasFastThumbOnDisk) return THIRD_CARD_FAST_THUMB_TIMEOUT_MS
        return if (mode == SamplingMode.LARGE_PHOTO_CLEANUP || !isVortexTransition) {
            SECOND_CARD_PREWARM_TIMEOUT_MS
        } else {
            THIRD_CARD_PREWARM_TIMEOUT_MS
        }
    }
}
