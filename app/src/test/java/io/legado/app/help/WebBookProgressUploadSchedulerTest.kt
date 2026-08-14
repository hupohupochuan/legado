package io.legado.app.help

import io.legado.app.data.entities.BookProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Collections

class WebBookProgressUploadSchedulerTest {

    @Test
    fun continuousChangesKeepFirstTimerAndUploadLatestProgress() = runBlocking {
        val uploaded = Collections.synchronizedList(mutableListOf<BookProgress>())
        val scheduler = scheduler(40) {
            uploaded += it
            true
        }

        scheduler.enqueue(progress(1))
        delay(15)
        scheduler.enqueue(progress(2))
        delay(15)
        scheduler.enqueue(progress(3))
        delay(35)

        assertEquals(listOf(3), uploaded.map { it.durChapterPos })
    }

    @Test
    fun timestampOnlyChangeDoesNotScheduleAnotherUpload() = runBlocking {
        val uploaded = Collections.synchronizedList(mutableListOf<BookProgress>())
        val scheduler = scheduler(20) {
            uploaded += it
            true
        }

        scheduler.enqueue(progress(8, time = 1))
        delay(35)
        scheduler.enqueue(progress(8, time = 2))
        delay(35)

        assertEquals(1, uploaded.size)
    }

    @Test
    fun failedUploadDoesNotRetryUntilNewChange() = runBlocking {
        var attempts = 0
        val scheduler = scheduler(20) {
            attempts++
            attempts > 1
        }

        scheduler.enqueue(progress(1))
        delay(55)
        assertEquals(1, attempts)

        scheduler.enqueue(progress(2))
        delay(35)
        assertEquals(2, attempts)
    }

    @Test
    fun progressChangedDuringUploadIsKeptForNextWindow() = runBlocking {
        val uploaded = Collections.synchronizedList(mutableListOf<BookProgress>())
        val scheduler = scheduler(15) {
            uploaded += it
            delay(25)
            true
        }

        scheduler.enqueue(progress(1))
        delay(20)
        scheduler.enqueue(progress(2))
        delay(65)

        assertEquals(listOf(1, 2), uploaded.map { it.durChapterPos })
    }

    @Test
    fun flushFailureAfterEarlierSuccessDoesNotLoop() = runBlocking {
        var attempts = 0
        val scheduler = scheduler(15) {
            attempts++
            attempts == 1
        }

        scheduler.enqueue(progress(1))
        delay(30)
        scheduler.enqueue(progress(2))
        scheduler.flush(progress(2))
        delay(30)

        assertEquals(2, attempts)
    }

    @Test
    fun sameNameBooksWithDifferentProgressKeysUploadIndependently() = runBlocking {
        val uploaded = Collections.synchronizedList(mutableListOf<BookProgress>())
        val scheduler = scheduler(20) {
            uploaded += it
            true
        }

        scheduler.enqueue(progress(1, progressKey = "content-sha1:first"))
        scheduler.enqueue(progress(2, progressKey = "content-sha1:second"))
        delay(45)

        assertEquals(
            setOf("content-sha1:first", "content-sha1:second"),
            uploaded.mapNotNull { it.bookProgressKey }.toSet()
        )
    }

    private fun scheduler(
        delayMillis: Long,
        uploader: suspend (BookProgress) -> Boolean
    ) = WebBookProgressUploadScheduler(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        uploadDelayMillis = delayMillis,
        uploader = uploader
    )

    private fun progress(
        pos: Int,
        time: Long = 0,
        progressKey: String? = null
    ) = BookProgress(
        name = "book",
        author = "author",
        durChapterIndex = 1,
        durChapterPos = pos,
        durChapterTime = time,
        durChapterTitle = "chapter",
        bookProgressKey = progressKey
    )
}
