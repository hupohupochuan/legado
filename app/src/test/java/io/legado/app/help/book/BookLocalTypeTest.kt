package io.legado.app.help.book

import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookLocalTypeTest {

    @Test
    fun plainLocalBookIsLocalButNotWebDav() {
        val book = Book(
            origin = BookType.localTag,
            type = BookType.text or BookType.local
        )

        assertTrue(book.isLocal)
        assertTrue(book.isPlainLocalBook)
        assertFalse(book.isWebDavBook)
    }

    @Test
    fun webDavBookIsLocalButNotPlainLocal() {
        val remoteUrl = "https://example.com/dav/books/demo.epub"
        val book = Book(
            origin = BookType.webDavTag + remoteUrl,
            type = BookType.text or BookType.local
        )

        assertTrue(book.isLocal)
        assertTrue(book.isWebDavBook)
        assertFalse(book.isPlainLocalBook)
        assertEquals(remoteUrl, book.getRemoteUrl())
    }

    @Test
    fun onlineBookIsNotLocal() {
        val book = Book(
            origin = "https://example.com/source",
            type = BookType.text
        )

        assertFalse(book.isLocal)
        assertFalse(book.isPlainLocalBook)
        assertFalse(book.isWebDavBook)
    }
}
