package io.legado.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentDownloadStateTest {

    @Test
    fun markDownloadedAddsToDownloadedAndClearsFailure() {
        val state = ContentDownloadState()

        state.markFailed(5)
        assertTrue(state.isFailedTooMany(5).not())
        state.markDownloaded(5)

        assertTrue(state.isDownloaded(5))
        assertFalse(state.isFailedTooMany(5))
    }

    @Test
    fun markFailedIncrementsCount() {
        val state = ContentDownloadState()

        assertEquals(1, state.markFailed(3))
        assertEquals(2, state.markFailed(3))
        assertEquals(3, state.markFailed(3))

        assertTrue(state.isFailedTooMany(3))
        assertFalse(state.isFailedTooMany(3, max = 4))
    }

    @Test
    fun isFailedTooManyRespectsCustomMax() {
        val state = ContentDownloadState()

        repeat(5) { state.markFailed(1) }

        assertTrue(state.isFailedTooMany(1))
        assertTrue(state.isFailedTooMany(1, max = 5))
        assertFalse(state.isFailedTooMany(1, max = 6))
    }

    @Test
    fun clearRemovesAllRecords() {
        val state = ContentDownloadState()

        state.markDownloaded(1)
        state.markDownloaded(2)
        state.markFailed(3)
        state.preDownloadTask = kotlinx.coroutines.CompletableDeferred(Unit)

        state.clear()

        assertFalse(state.isDownloaded(1))
        assertFalse(state.isDownloaded(2))
        assertFalse(state.isFailedTooMany(3))
        assertNull(state.preDownloadTask)
    }

    @Test
    fun cancelPreDownloadClearsTaskAndCancelsScope() {
        val state = ContentDownloadState()
        val deferred = kotlinx.coroutines.CompletableDeferred(Unit)
        state.preDownloadTask = deferred

        state.cancelPreDownload()

        assertNull(state.preDownloadTask)
    }

    @Test
    fun differentIndexesAreIndependent() {
        val state = ContentDownloadState()

        state.markDownloaded(1)
        state.markFailed(2)

        assertTrue(state.isDownloaded(1))
        assertFalse(state.isDownloaded(2))
        assertFalse(state.isFailedTooMany(1))
        assertTrue(state.isFailedTooMany(2).not() || state.isFailedTooMany(2))
    }

    @Test
    fun downloadedChaptersAndFailChaptersAreSharedInstances() {
        val state = ContentDownloadState()

        val downloaded = state.downloadedChapters
        val failed = state.downloadFailChapters

        downloaded.add(7)
        failed[8] = 2

        assertTrue(state.isDownloaded(7))
        assertTrue(state.downloadFailChapters[8] == 2)
    }
}
