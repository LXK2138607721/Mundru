package com.lumina.cleaner.ui.deck

import com.lumina.cleaner.core.model.SamplingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeckIdleWorkPolicyTest {

    @Test
    fun `ambient aura updates immediately on slow browse and debounces during rapid swipe`() {
        assertTrue(DeckIdleWorkPolicy.shouldDebounceAmbientUpdate(0L))
        assertTrue(DeckIdleWorkPolicy.shouldDebounceAmbientUpdate(120L))
        assertTrue(DeckIdleWorkPolicy.shouldDebounceAmbientUpdate(239L))
        assertFalse(DeckIdleWorkPolicy.shouldDebounceAmbientUpdate(240L))
        assertFalse(DeckIdleWorkPolicy.shouldDebounceAmbientUpdate(900L))
    }

    @Test
    fun `idle delay gate yields full remaining window during active swiping and opens at threshold`() {
        assertEquals(
            1400L,
            DeckIdleWorkPolicy.remainingIdleDelayMs(
                nowElapsedMs = 5000L,
                lastInteractionElapsedMs = 5000L,
                requiredIdleMs = 1400L
            )
        )
        assertEquals(
            400L,
            DeckIdleWorkPolicy.remainingIdleDelayMs(
                nowElapsedMs = 6000L,
                lastInteractionElapsedMs = 5000L,
                requiredIdleMs = 1400L
            )
        )
        assertEquals(
            0L,
            DeckIdleWorkPolicy.remainingIdleDelayMs(
                nowElapsedMs = 7000L,
                lastInteractionElapsedMs = 5000L,
                requiredIdleMs = 1400L
            )
        )
    }

    @Test
    fun `backdrop recording pauses only on pure horizontal swipes without top chrome overlap`() {
        assertTrue(
            DeckIdleWorkPolicy.shouldPauseBackdropRecording(
                isHorizontalSwipeOnly = true,
                isTrashPullActive = false,
                isAmbientCrossfading = false,
                isModeMenuOpen = false,
                isVortexActive = false
            )
        )
        assertFalse(
            DeckIdleWorkPolicy.shouldPauseBackdropRecording(
                isHorizontalSwipeOnly = false,
                isTrashPullActive = false,
                isAmbientCrossfading = false,
                isModeMenuOpen = false,
                isVortexActive = false
            )
        )
        assertFalse(
            DeckIdleWorkPolicy.shouldPauseBackdropRecording(
                isHorizontalSwipeOnly = true,
                isTrashPullActive = true,
                isAmbientCrossfading = false,
                isModeMenuOpen = false,
                isVortexActive = false
            )
        )
        assertFalse(
            DeckIdleWorkPolicy.shouldPauseBackdropRecording(
                isHorizontalSwipeOnly = true,
                isTrashPullActive = false,
                isAmbientCrossfading = true,
                isModeMenuOpen = false,
                isVortexActive = false
            )
        )
        assertFalse(
            DeckIdleWorkPolicy.shouldPauseBackdropRecording(
                isHorizontalSwipeOnly = true,
                isTrashPullActive = false,
                isAmbientCrossfading = false,
                isModeMenuOpen = true,
                isVortexActive = false
            )
        )
        assertFalse(
            DeckIdleWorkPolicy.shouldPauseBackdropRecording(
                isHorizontalSwipeOnly = true,
                isTrashPullActive = false,
                isAmbientCrossfading = false,
                isModeMenuOpen = false,
                isVortexActive = true
            )
        )
    }

    @Test
    fun `rolling lookahead selects up to two uncached indices ahead within window`() {
        val cached = setOf(0, 1, 2)
        assertEquals(
            listOf(3, 4),
            DeckIdleWorkPolicy.selectLookaheadIndices(
                deckSize = 15,
                currentIndex = 0,
                isCachedAt = { it in cached }
            )
        )
        // Near end of batch, never exceeds deckSize - 1
        assertEquals(
            listOf(14),
            DeckIdleWorkPolicy.selectLookaheadIndices(
                deckSize = 15,
                currentIndex = 13,
                isCachedAt = { false }
            )
        )
        // At last item, empty
        assertEquals(
            emptyList<Int>(),
            DeckIdleWorkPolicy.selectLookaheadIndices(
                deckSize = 15,
                currentIndex = 14,
                isCachedAt = { false }
            )
        )
    }

    @Test
    fun `second and third visible cards prewarm across all modes without skipping heavy pngs`() {
        // 全 5 种模式互切（含随机漫步↔时光考古、大图专清↔随机漫步）第 2、3 张可见卡均必预解码，绝不露黑卡
        for (mode in SamplingMode.values()) {
            assertEquals(
                DeckIdleWorkPolicy.SECOND_CARD_PREWARM_TIMEOUT_MS,
                DeckIdleWorkPolicy.secondCardPrewarmTimeoutMs(
                    mode = mode,
                    sizeBytes = 22_000_000L,
                    displayName = "4k_raw.png",
                    hasFastThumbOnDisk = false,
                    isVortexTransition = true
                )
            )
            assertEquals(
                DeckIdleWorkPolicy.SECOND_CARD_FAST_THUMB_TIMEOUT_MS,
                DeckIdleWorkPolicy.secondCardPrewarmTimeoutMs(
                    mode = mode,
                    sizeBytes = 22_000_000L,
                    displayName = "4k_raw.png",
                    hasFastThumbOnDisk = true,
                    isVortexTransition = true
                )
            )
            assertTrue(
                DeckIdleWorkPolicy.thirdCardPrewarmTimeoutMs(
                    mode = mode,
                    sizeBytes = 18_000_000L,
                    displayName = "heavy.png",
                    hasFastThumbOnDisk = false,
                    isVortexTransition = true
                ) >= DeckIdleWorkPolicy.THIRD_CARD_PREWARM_TIMEOUT_MS
            )
            assertEquals(
                DeckIdleWorkPolicy.THIRD_CARD_FAST_THUMB_TIMEOUT_MS,
                DeckIdleWorkPolicy.thirdCardPrewarmTimeoutMs(
                    mode = mode,
                    sizeBytes = 18_000_000L,
                    displayName = "heavy.png",
                    hasFastThumbOnDisk = true,
                    isVortexTransition = true
                )
            )
        }
    }
}
