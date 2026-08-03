package io.legado.app.ui.book.import.local

import io.legado.app.constant.AppPattern.archiveFileRegex
import io.legado.app.data.appDb
import io.legado.app.utils.FileDoc

data class ImportBook(
    val file: FileDoc,
    val isUpDir: Boolean = false,
    val isFileManageMode: Boolean = false,
    var isOnBookShelf: Boolean = when {
        isFileManageMode || isUpDir || file.isDir -> false
        file.name.matches(archiveFileRegex) -> appDb.bookDao.hasFile(file.name)
        else -> appDb.bookDao.has(file.uri.toString())
    }
) {
    val name get() = if (isUpDir) ".." else file.name
    val isDir get() = file.isDir
    val size get() = file.size
    val lastModified get() = file.lastModified
}
