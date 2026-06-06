package io.legado.app.model

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter

class ReadBookChapterStore(
    private val getChapterCount: (bookUrl: String) -> Int,
    private val getChapter: (bookUrl: String, index: Int) -> BookChapter?
) {

    fun count(book: Book, chapterList: List<BookChapter>?): Int {
        return chapterList?.size ?: getChapterCount(book.bookUrl)
    }

    fun get(book: Book, index: Int, chapterList: List<BookChapter>?, chapterSize: Int): BookChapter? {
        if (index < 0 || index >= chapterSize) return null
        return chapterList?.getOrNull(index) ?: getChapter(book.bookUrl, index)
    }
}
