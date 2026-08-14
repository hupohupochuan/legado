package io.legado.app.data.entities

import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun serializedProgressKeepsPortableKeyButOmitsLocalBookUrl() {
        val progress = progress(pos = 10).copy(
            bookProgressKey = "content-sha1:portable",
            bookUrl = "content://private/local-book"
        )

        val json = GSON.toJson(progress)

        assertTrue(json.contains("content-sha1:portable"))
        assertFalse(json.contains("content://private/local-book"))
        assertFalse(json.contains("bookUrl"))
    }

    @Test
    fun oldProgressJsonWithoutIdentityStillDeserializes() {
        val json = """{
            "name":"旧书",
            "author":"作者",
            "durChapterIndex":1,
            "durChapterPos":2,
            "durChapterTime":3,
            "durChapterTitle":"章节"
        }""".trimIndent()

        val progress = GSON.fromJsonObject<BookProgress>(json).getOrThrow()

        assertEquals("旧书", progress.name)
        assertEquals(2, progress.durChapterPos)
        assertNull(progress.bookProgressKey)
        assertNull(progress.bookUrl)
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
