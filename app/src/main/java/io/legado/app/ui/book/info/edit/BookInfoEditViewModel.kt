package io.legado.app.ui.book.info.edit

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.help.IntentData
import io.legado.app.help.book.isLocal
import io.legado.app.model.ReadBook

class BookInfoEditViewModel(application: Application) : BaseViewModel(application) {
    var book: Book? = null

    fun loadBook() {
        book = IntentData.book as? Book
    }

    fun saveBook(book: Book, bookUrl: String?, success: (() -> Unit)?) {
        execute {
            if (ReadBook.book?.bookUrl == book.bookUrl) {
                ReadBook.book = book
            }
            if (bookUrl != null && bookUrl != book.bookUrl) {
                appDb.bookDao.relocate(book, bookUrl, null)
            } else {
                if (!book.isLocal) book.localFileKey = null
                appDb.bookDao.update(book)
            }
        }.onSuccess {
            success?.invoke()
        }.onError {
            if (it is SQLiteConstraintException) {
                AppLog.put("书籍信息保存失败，目标文件或书籍地址已存在\n$it", it, true)
            } else {
                AppLog.put("书籍信息保存失败\n$it", it, true)
            }
        }
    }
}
