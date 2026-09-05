package io.legado.app.ui.book.read.page.provider

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.constant.PreferKey
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookContent
import io.legado.app.help.config.AppConfig
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.utils.putPrefString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import splitties.init.appCtx

/** Uses real Android text measurement; does not open books, write progress or access the network. */
@RunWith(AndroidJUnit4::class)
class ReaderPageSizeTest {

    @Test
    fun rotationClearsCompletedCachedChaptersEvenWithoutActivityObserver() = withReaderSize {
        val landscape = withContext(Main) {
            ChapterProvider.upViewSize(2400, 1500)
            createChapter()
        }
        for (page in landscape.layoutChannel) { /* Wait for actual pagination. */ }
        withContext(Main) {
            assertTrue(landscape.isCompleted)
            val position = ReadBook.durChapterPos
            ReadBook.prevTextChapter = landscape
            ReadBook.curTextChapter = landscape
            ReadBook.nextTextChapter = landscape

            ChapterProvider.upViewSize(1600, 2300)

            assertNull(ReadBook.prevTextChapter)
            assertNull(ReadBook.curTextChapter)
            assertNull(ReadBook.nextTextChapter)
            assertEquals(position, ReadBook.durChapterPos)
            assertObsolete(landscape)
        }
    }

    @Test
    fun delayedLandscapeResultCannotPassPortraitPublicationGate() = withReaderSize {
        val loadingScope = this
        val (landscape, portrait) = withContext(Main) {
            ChapterProvider.upViewSize(2400, 1500)
            val old = loadingScope.createChapter()
            ChapterProvider.upViewSize(1600, 2300)
            old to loadingScope.createChapter()
        }
        // Consume the older result after the new viewport has already been applied.
        for (page in landscape.layoutChannel) { /* Drain the old result. */ }
        for (page in portrait.layoutChannel) { /* Drain the current result. */ }
        withContext(Main) {
            assertNotEquals(landscape.layoutSizeGeneration, portrait.layoutSizeGeneration)
            assertObsolete(landscape)
            ReadBook.requireCurrentPageSize(portrait)
            assertTrue(portrait.pages.isNotEmpty())
            assertTrue(portrait.pages.flatMap { it.lines }.any { it.columns.isNotEmpty() })
            portrait.pages.flatMap { it.lines }.forEach { line ->
                line.columns.forEach { column ->
                    assertTrue("Text extends beyond portrait right edge: ${column.end}",
                        column.end <= ChapterProvider.visibleRight + 1f)
                }
            }
        }
    }

    private fun CoroutineScope.createChapter(): TextChapter = ChapterProvider.getTextChapterAsync(
        this,
        Book(name = "rotation-test"),
        BookChapter(title = "旋转回归"),
        "旋转回归",
        BookContent(false, List(40) { "横屏转竖屏后正文应在可见区域内重新换行。".repeat(12) }, null),
        1,
    )

    private fun assertObsolete(chapter: TextChapter) {
        try {
            ReadBook.requireCurrentPageSize(chapter)
            fail("Old pagination must not be published for the new viewport")
        } catch (_: CancellationException) {
            // Expected: same guard used by async and await publication paths.
        }
    }

    private fun withReaderSize(block: suspend CoroutineScope.() -> Unit) = runBlocking {
        val previous = withContext(Main) {
            // Run in an idle instrumentation process, never against an active reader.
            assertNull(ReadBook.callBack)
            assertNull(ReadBook.curTextChapter)
            assertNull(ReadBook.prevTextChapter)
            assertNull(ReadBook.nextTextChapter)
            Triple(ChapterProvider.viewWidth, ChapterProvider.viewHeight, AppConfig.doublePageHorizontal)
        }
        try {
            withContext(Main) { appCtx.putPrefString(PreferKey.doublePageHorizontal, "0") }
            withTimeout(15_000) { block() }
        } finally {
            withContext(Main) {
                ReadBook.clearTextChapter()
                appCtx.putPrefString(PreferKey.doublePageHorizontal, previous.third)
                ChapterProvider.upViewSize(previous.first, previous.second)
                ChapterProvider.upLayout()
            }
        }
    }
}
