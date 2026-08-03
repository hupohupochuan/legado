package io.legado.app.ui.main.bookshelf

import io.legado.app.data.entities.Book
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookshelfItemIdentityTest {

    @Test
    fun `same title and author with different paths are different items`() {
        val first = Book(
            bookUrl = "file:///books/first.txt",
            name = "同名书",
            author = "同一作者"
        )
        val second = Book(
            bookUrl = "file:///books/second.epub",
            name = "同名书",
            author = "同一作者"
        )

        assertFalse(first.hasSameBookshelfIdentity(second))
    }

    @Test
    fun `metadata changes do not replace bookshelf item identity`() {
        val first = Book(
            bookUrl = "file:///books/book.txt",
            name = "旧书名",
            author = "旧作者"
        )
        val updated = Book(
            bookUrl = first.bookUrl,
            name = "新书名",
            author = "新作者"
        )

        assertTrue(first.hasSameBookshelfIdentity(updated))
    }
}
