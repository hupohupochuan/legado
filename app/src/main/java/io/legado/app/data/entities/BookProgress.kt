package io.legado.app.data.entities

data class BookProgress(
    val name: String,
    val author: String,
    val durChapterIndex: Int,
    val durChapterPos: Int,
    val durChapterTime: Long,
    val durChapterTitle: String?
) {

    constructor(book: Book) : this(
        name = book.name,
        author = book.author,
        durChapterIndex = book.durChapterIndex,
        durChapterPos = book.durChapterPos,
        durChapterTime = book.durChapterTime,
        durChapterTitle = book.durChapterTitle
    )

    val readChapterPos: Int
        get() = if (durChapterPos == Int.MIN_VALUE) Int.MAX_VALUE else kotlin.math.abs(durChapterPos)

    fun compareReadPosition(book: Book): Int {
        val chapterCompare = durChapterIndex.compareTo(book.durChapterIndex)
        val bookChapterPos = if (book.durChapterPos == Int.MIN_VALUE) {
            Int.MAX_VALUE
        } else {
            kotlin.math.abs(book.durChapterPos)
        }
        return if (chapterCompare != 0) {
            chapterCompare
        } else {
            readChapterPos.compareTo(bookChapterPos)
        }
    }

}
