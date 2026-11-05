package com.lumina.cleaner.ui.deck

import androidx.compose.ui.geometry.Offset

internal data class DeckDragSample(val position: Offset, val delta: Offset)

/** 两点使用同一帧的变换：只映射输入增量，不把卡片自身的移动计作手指移动。 */
internal inline fun deckDragSample(
    position: Offset, amount: Offset, localToRoot: (Offset) -> Offset
): DeckDragSample {
    val root = localToRoot(position)
    return DeckDragSample(root, root - localToRoot(position - amount))
}
