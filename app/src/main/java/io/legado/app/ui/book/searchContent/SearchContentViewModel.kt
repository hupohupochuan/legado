package io.legado.app.ui.book.searchContent


import android.app.Application
import io.legado.app.base.BaseViewModel
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.IntentData
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.ChineseUtils
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class SearchContentViewModel(application: Application) : BaseViewModel(application) {
    var bookUrl: String = ""
    var book: Book? = null
    private var contentProcessor: ContentProcessor? = null
    var lastQuery: String = ""
    var searchResultCounts = 0
    val cacheChapterNames = hashSetOf<String>()
    val searchResultList: MutableList<SearchResult> = mutableListOf()
    var replaceEnabled = false

    /**
     * 从 [IntentData.book] 取出当前书。
     *
     * 进程被系统杀死重建后, [IntentData] 这种静态 Map 会清空, `IntentData.book` 变 null。
     * 旧写法 `IntentData.book as Book` 会抛 NPE 并被 execute 静默吞掉, Activity 卡在空白界面。
     * 现在显式走 `as? Book`, 并通过 [error] 回调让上层 finish + toast, 实现"安全失败".
     */
    fun initBook(success: () -> Unit, error: (String) -> Unit = {}) {
        execute {
            val safeBook = IntentData.book as? Book
            if (safeBook == null) {
                throw NoStackTraceException("数据获取失败")
            }
            book = safeBook
            bookUrl = safeBook.bookUrl
            contentProcessor = ContentProcessor.get(safeBook.name, safeBook.origin)
        }.onSuccess {
            success.invoke()
        }.onError {
            error.invoke("数据获取失败\n${it.localizedMessage}")
        }
    }

    suspend fun searchChapter(
        query: String,
        chapter: BookChapter
    ): List<SearchResult> {
        val searchResultsWithinChapter: MutableList<SearchResult> = mutableListOf()
        val book = book ?: return searchResultsWithinChapter
        // initBook 走安全失败后, book / contentProcessor 可能同时为 null (Activity 已 finish),
        // 此时被 searchJob 调用进来, contentProcessor!! 会 NPE, 这里兜底返回空结果。
        val processor = contentProcessor ?: return searchResultsWithinChapter
        val chapterContent = BookHelp.getContent(book, chapter) ?: return searchResultsWithinChapter
        currentCoroutineContext().ensureActive()
        chapter.title = when (AppConfig.chineseConverterType) {
            1 -> ChineseUtils.t2s(chapter.title)
            2 -> ChineseUtils.s2t(chapter.title)
            else -> chapter.title
        }
        currentCoroutineContext().ensureActive()
        val mContent = processor.getContent(
            book, chapter, chapterContent, useReplace = replaceEnabled
        ).toString()
        val positions = searchPosition(mContent, query)
        positions.forEachIndexed { index, position ->
            currentCoroutineContext().ensureActive()
            val construct = getResultAndQueryIndex(mContent, position, query)
            val result = SearchResult(
                resultCountWithinChapter = index,
                resultText = construct.second,
                chapterTitle = chapter.title,
                query = query,
                chapterIndex = chapter.index,
                queryIndexInResult = construct.first,
                queryIndexInChapter = position
            )
            searchResultsWithinChapter.add(result)
        }
        searchResultCounts += searchResultsWithinChapter.size
        return searchResultsWithinChapter
    }

    private suspend fun searchPosition(content: String, pattern: String): List<Int> {
        val position: MutableList<Int> = mutableListOf()
        var index = content.indexOf(pattern)
        while (index >= 0) {
            currentCoroutineContext().ensureActive()
            position.add(index)
            index = content.indexOf(pattern, index + pattern.length)
        }
        return position
    }

    private fun getResultAndQueryIndex(
        content: String,
        queryIndexInContent: Int,
        query: String
    ): Pair<Int, String> {
        // 左右移动20个字符，构建关键词周边文字，在搜索结果里显示
        // 判断段落，只在关键词所在段落内分割
        // 利用标点符号分割完整的句
        // length和设置结合，自由调整周边文字长度
        val length = 20
        var po1 = queryIndexInContent - length
        var po2 = queryIndexInContent + query.length + length
        if (po1 < 0) {
            po1 = 0
        }
        if (po2 > content.length) {
            po2 = content.length
        }
        val queryIndexInResult = queryIndexInContent - po1
        val newText = content.substring(po1, po2)
        return queryIndexInResult to newText
    }

}
