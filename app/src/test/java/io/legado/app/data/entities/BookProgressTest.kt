package io.legado.app.data.entities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookProgressTest {

    @Test
    fun readChapterPosConvertsLastPageMarkerToPositivePosition() {
        val progress = progress(pos = -3261)

        assertEquals(3261, progress.readChapterPos)
    }

    @Test
    fun compareReadPositionUsesPositivePositionForLastPageMarker() {
        val book = Book(bookUrl = "book-url").apply {
            durChapterIndex = 291
            durChapterPos = 3000
        }
        val progress = progress(chapter = 291, pos = -3261)

        assertTrue(progress.compareReadPosition(book) > 0)
    }

    @Test
    fun compareReadPositionTreatsSamePositiveAndNegativePositionAsEqual() {
        val book = Book(bookUrl = "book-url").apply {
            durChapterIndex = 291
            durChapterPos = 3261
        }
        val progress = progress(chapter = 291, pos = -3261)

        assertEquals(0, progress.compareReadPosition(book))
    }

    private fun progress(chapter: Int = 291, pos: Int) = BookProgress(
        name = "诸神愚戏",
        author = "一月九十秋",
        durChapterIndex = chapter,
        durChapterPos = pos,
        durChapterTime = 0L,
        durChapterTitle = null
    )
}
