package io.legado.app.data.entities

data class BookProgress(
    val name: String,
    val author: String,
    val durChapterIndex: Int,
    val durChapterPos: Int,
    val durChapterTime: Long,
    val durChapterTitle: String?,
    val bookProgressKey: String? = null,
    @Transient val bookUrl: String? = null
) {

    constructor(book: Book) : this(
        name = book.name,
        author = book.author,
        durChapterIndex = book.durChapterIndex,
        durChapterPos = book.durChapterPos,
        durChapterTime = book.durChapterTime,
        durChapterTitle = book.durChapterTitle,
        bookProgressKey = book.bookProgressKey,
        bookUrl = book.bookUrl
    )

    val syncKey: String
        get() = bookProgressKey ?: bookUrl ?: "$name\u0000$author"

    val readChapterPos: Int
        get() = normalizePos(durChapterPos)

    fun compareReadPosition(book: Book): Int {
        val chapterCompare = durChapterIndex.compareTo(book.durChapterIndex)
        if (chapterCompare != 0) return chapterCompare
        return readChapterPos.compareTo(normalizePos(book.durChapterPos))
    }

    companion object {
        private fun normalizePos(pos: Int): Int =
            if (pos == Int.MIN_VALUE) Int.MAX_VALUE else kotlin.math.abs(pos)
    }

}
