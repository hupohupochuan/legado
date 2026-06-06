package io.legado.app.model

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ReadBookChapterStoreTest {

    private val book = Book(bookUrl = "book-url")

    @Test
    fun countUsesMemoryChapterListWhenAvailable() {
        var dbCountCalls = 0
        val store = ReadBookChapterStore(
            getChapterCount = {
                dbCountCalls++
                9
            },
            getChapter = { _, _ -> null }
        )

        val count = store.count(book, listOf(chapter(0), chapter(1)))

        assertEquals(2, count)
        assertEquals(0, dbCountCalls)
    }

    @Test
    fun countFallsBackToDatabaseWhenMemoryListMissing() {
        val store = ReadBookChapterStore(
            getChapterCount = { 9 },
            getChapter = { _, _ -> null }
        )

        assertEquals(9, store.count(book, null))
    }

    @Test
    fun getUsesRequestedIndexFromMemoryList() {
        val target = chapter(1)
        val store = ReadBookChapterStore(
            getChapterCount = { 0 },
            getChapter = { _, _ -> null }
        )

        val result = store.get(book, 1, listOf(chapter(0), target), chapterSize = 2)

        assertSame(target, result)
    }

    @Test
    fun getFallsBackToDatabaseWhenMemoryListMissing() {
        val target = chapter(1)
        val store = ReadBookChapterStore(
            getChapterCount = { 0 },
            getChapter = { bookUrl, index ->
                if (bookUrl == "book-url" && index == 1) target else null
            }
        )

        assertSame(target, store.get(book, 1, null, chapterSize = 2))
    }

    @Test
    fun getDoesNotQueryDatabaseForOutOfBoundsIndex() {
        var dbGetCalls = 0
        val store = ReadBookChapterStore(
            getChapterCount = { 0 },
            getChapter = { _, _ ->
                dbGetCalls++
                chapter(0)
            }
        )

        assertNull(store.get(book, -1, null, chapterSize = 2))
        assertNull(store.get(book, 2, null, chapterSize = 2))
        assertEquals(0, dbGetCalls)
    }

    private fun chapter(index: Int): BookChapter {
        return BookChapter(
            bookUrl = "book-url",
            url = "chapter-$index",
            title = "Chapter $index",
            index = index
        )
    }
}
