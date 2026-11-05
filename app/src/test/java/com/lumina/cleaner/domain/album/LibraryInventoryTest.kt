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
}
