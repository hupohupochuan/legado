package io.legado.app.model.fileBook

import io.legado.app.data.entities.Book
import java.io.IOException

interface LocalBookFormatHandler : BaseFileBook {

    fun supports(book: Book): Boolean

    fun supportsReadableCheck(book: Book): Boolean = supports(book)

    @Throws(IOException::class, SecurityException::class)
    fun checkReadable(book: Book) {
        getBookInputStream(book).use { }
    }
}
