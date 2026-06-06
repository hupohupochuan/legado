package io.legado.app.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterLoadStateTest {

    @Test
    fun tryStartRejectsDuplicateLoadingIndex() {
        val state = ChapterLoadState()

        assertTrue(state.tryStart(3))
        assertFalse(state.tryStart(3))
        assertTrue(state.isLoading(3))
    }

    @Test
    fun finishAllowsSameIndexToStartAgain() {
        val state = ChapterLoadState()

        assertTrue(state.tryStart(3))
        state.finish(3)

        assertFalse(state.isLoading(3))
        assertTrue(state.tryStart(3))
    }

    @Test
    fun loadingIndexesAreIndependent() {
        val state = ChapterLoadState()

        assertTrue(state.tryStart(3))
        assertTrue(state.tryStart(4))
        state.finish(3)

        assertFalse(state.isLoading(3))
        assertTrue(state.isLoading(4))
        assertFalse(state.tryStart(4))
    }

    @Test
    fun finishingWrongIndexDoesNotClearRequestedLoadingIndex() {
        val state = ChapterLoadState()

        assertTrue(state.tryStart(4))
        state.finish(3)

        assertTrue(state.isLoading(4))
        assertFalse(state.tryStart(4))
    }

    @Test
    fun clearRemovesAllLoadingIndexes() {
        val state = ChapterLoadState()

        assertTrue(state.tryStart(3))
        assertTrue(state.tryStart(4))
        state.clear()

        assertFalse(state.isLoading(3))
        assertFalse(state.isLoading(4))
        assertTrue(state.tryStart(3))
        assertTrue(state.tryStart(4))
    }
}
