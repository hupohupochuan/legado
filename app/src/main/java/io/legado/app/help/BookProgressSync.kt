package io.legado.app.help

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookProgress

interface BookProgressSync {

    suspend fun uploadBookProgress(
        book: Book,
        toast: Boolean = false,
        onSuccess: (() -> Unit)? = null
    )

    suspend fun uploadBookProgress(
        bookProgress: BookProgress,
        onSuccess: (() -> Unit)? = null
    )

    suspend fun getBookProgress(book: Book): BookProgress?

    suspend fun getBookProgressResult(book: Book): Result<BookProgress?>

    fun canApplyBookProgress(
        book: Book,
        bookProgress: BookProgress,
        logPrefix: String,
        mode: ProgressCheckMode = ProgressCheckMode.RangeOnly
    ): Boolean

    suspend fun downloadAllBookProgress()

    suspend fun restoreBookProgressOnly()
}

object BookProgressSyncProvider {
    val current: BookProgressSync
        get() = AppWebDav
}

/**
 * 进度校验模式
 * - [RangeOnly]: 仅校验章节范围, 用于只同步进度的场景。
 * - [ReadableRequired]: 校验章节范围, 并对本地书执行可读性检查。
 */
enum class ProgressCheckMode {
    RangeOnly,
    ReadableRequired
}
