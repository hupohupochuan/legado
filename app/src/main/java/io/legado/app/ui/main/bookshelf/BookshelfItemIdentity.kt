package io.legado.app.ui.main.bookshelf

import io.legado.app.data.entities.Book

/**
 * 书架条目身份跟随 Room 主键，不使用可重复的书名和作者。
 */
internal fun Book.hasSameBookshelfIdentity(other: Book): Boolean {
    return bookUrl == other.bookUrl
}
