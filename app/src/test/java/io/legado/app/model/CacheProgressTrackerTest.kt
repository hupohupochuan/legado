package io.legado.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheProgressTrackerTest {

    @Test
    fun inactiveTrackerIgnoresBackgroundDownloads() {
        val tracker = CacheProgressTracker()

        tracker.add("book", "测试书", 3)
        tracker.finish("book")

        assertEquals(CacheProgress(), tracker.snapshot())
    }

    @Test
    fun progressIncludesSuccessFailureAndCancellation() {
        val tracker = CacheProgressTracker()
        tracker.begin()
        tracker.add("book", "测试书", 5)

        tracker.finish("book", count = 2)
        tracker.finish("book", failed = true)
        tracker.add("second", "另一本书", 1)
        tracker.cancelRemaining("second")

        val progress = tracker.snapshot()
        val book = progress.books.getValue("book")
        assertEquals(6, progress.total)
        assertEquals(4, progress.processed)
        assertEquals(1, progress.failed)
        assertEquals(1, progress.canceled)
        assertEquals(2, book.remaining)
        assertFalse(progress.isComplete)

        tracker.finish("book", count = 2)

        assertTrue(tracker.snapshot().isComplete)
    }

    @Test
    fun repeatedRangesExtendTotalWithoutOverCompleting() {
        val tracker = CacheProgressTracker()
        tracker.begin()
        tracker.add("book", "测试书", 2)
        tracker.finish("book", count = 5)
        tracker.add("book", "测试书", 1)

        val progress = tracker.snapshot()
        assertEquals(3, progress.total)
        assertEquals(2, progress.processed)
        assertEquals(1, progress.books.getValue("book").remaining)
    }

    @Test
    fun beginStartsFreshSessionAndEndClearsIt() {
        val tracker = CacheProgressTracker()
        tracker.begin()
        tracker.add("book", "旧任务", 2)
        tracker.finish("book")

        tracker.begin()

        assertEquals(0, tracker.snapshot().total)
        tracker.add("new", "新任务", 1)
        assertEquals(setOf("new"), tracker.snapshot().books.keys)

        tracker.end()
        assertEquals(CacheProgress(), tracker.snapshot())
    }

    @Test
    fun completionGateSuppressesNotificationAfterManualStop() {
        val gate = CacheCompletionGate()
        val progress = CacheProgress(total = 2, processed = 2)

        assertFalse(gate.shouldShow(CacheProgress()))
        assertTrue(gate.shouldShow(progress))

        gate.suppress()

        assertFalse(gate.shouldShow(progress))
    }
}
