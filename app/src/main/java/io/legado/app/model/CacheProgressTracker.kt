package io.legado.app.model

import kotlin.math.min

data class CacheBookProgress(
    val bookUrl: String,
    val bookName: String,
    val total: Int,
    val processed: Int,
    val failed: Int,
    val canceled: Int
) {
    val remaining: Int
        get() = (total - processed).coerceAtLeast(0)
}

data class CacheProgress(
    val books: Map<String, CacheBookProgress> = emptyMap(),
    val total: Int = 0,
    val processed: Int = 0,
    val failed: Int = 0,
    val canceled: Int = 0,
    val downloading: Int = 0,
    val waiting: Int = 0,
    val loadingBookCount: Int = 0
) {
    val isIndeterminate: Boolean
        get() = total == 0 && loadingBookCount > 0

    val isComplete: Boolean
        get() = total > 0 && processed >= total
}

internal class CacheProgressTracker {

    private data class MutableBookProgress(
        var bookName: String,
        var total: Int = 0,
        var processed: Int = 0,
        var failed: Int = 0,
        var canceled: Int = 0
    )

    private val books = linkedMapOf<String, MutableBookProgress>()
    private var isActive = false

    @Synchronized
    fun begin() {
        books.clear()
        isActive = true
    }

    @Synchronized
    fun end() {
        isActive = false
        books.clear()
    }

    @Synchronized
    fun add(bookUrl: String, bookName: String, count: Int) {
        if (!isActive || count <= 0) return
        val progress = books.getOrPut(bookUrl) {
            MutableBookProgress(bookName)
        }
        progress.bookName = bookName
        progress.total += count
    }

    @Synchronized
    fun finish(bookUrl: String, count: Int = 1, failed: Boolean = false) {
        if (!isActive || count <= 0) return
        val progress = books[bookUrl] ?: return
        val finishedCount = min(count, progress.total - progress.processed)
        if (finishedCount <= 0) return
        progress.processed += finishedCount
        if (failed) {
            progress.failed += finishedCount
        }
    }

    @Synchronized
    fun cancelRemaining(bookUrl: String) {
        if (!isActive) return
        val progress = books[bookUrl] ?: return
        val canceledCount = progress.total - progress.processed
        if (canceledCount <= 0) return
        progress.processed += canceledCount
        progress.canceled += canceledCount
    }

    @Synchronized
    fun snapshot(): CacheProgress {
        val snapshots = books.mapValues { (bookUrl, progress) ->
            progress.toSnapshot(bookUrl)
        }
        return CacheProgress(
            books = snapshots,
            total = snapshots.values.sumOf { it.total },
            processed = snapshots.values.sumOf { it.processed },
            failed = snapshots.values.sumOf { it.failed },
            canceled = snapshots.values.sumOf { it.canceled }
        )
    }

    @Synchronized
    fun snapshot(bookUrl: String): CacheBookProgress? {
        return books[bookUrl]?.toSnapshot(bookUrl)
    }

    private fun MutableBookProgress.toSnapshot(bookUrl: String): CacheBookProgress {
        return CacheBookProgress(
            bookUrl = bookUrl,
            bookName = bookName,
            total = total,
            processed = processed,
            failed = failed,
            canceled = canceled
        )
    }
}
