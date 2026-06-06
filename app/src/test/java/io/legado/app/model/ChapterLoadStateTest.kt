package io.legado.app.model

import io.legado.app.model.ChapterLoadState.Status
import org.junit.Assert.assertEquals
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
        assertEquals(Status.Loading, state.status(3))
    }

    @Test
    fun finishAllowsSameIndexToStartAgain() {
        val state = ChapterLoadState()

        assertTrue(state.tryStart(3))
        state.finish(3)

        assertFalse(state.isLoading(3))
        assertEquals(Status.Idle, state.status(3))
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
        assertEquals(Status.Idle, state.status(3))
        assertEquals(Status.Idle, state.status(4))
        assertTrue(state.tryStart(3))
        assertTrue(state.tryStart(4))
    }

    @Test
    fun failMarksIndexFailedWithoutBlockingRetry() {
        val state = ChapterLoadState()

        assertTrue(state.tryStart(3))
        state.fail(3)

        assertFalse(state.isLoading(3))
        assertTrue(state.isFailed(3))
        assertEquals(Status.Failed, state.status(3))
        assertTrue(state.tryStart(3))
        assertEquals(Status.Loading, state.status(3))
    }

    @Test
    fun finishClearsFailedStatus() {
        val state = ChapterLoadState()

        state.fail(3)
        state.finish(3)

        assertFalse(state.isFailed(3))
        assertEquals(Status.Idle, state.status(3))
    }
}
