package io.legado.app.help.book

import androidx.annotation.Keep
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * Web 阅读页全文搜索结果。
 *
 * [chapterPos] 与 Web 端 ChapterContent/bookPagination 的段落位置算法保持一致，
 * [snippet] 始终是普通字符串，前端必须按文本节点渲染。
 */
@Keep
data class WebBookContentSearchResult(
    val chapterIndex: Int,
    val chapterTitle: String,
    val chapterPos: Int,
    val queryIndexInChapter: Int,
    val queryIndexInSnippet: Int,
    val snippet: String
)

data class BookContentTextMatch(
    val chapterPos: Int,
    val queryIndexInChapter: Int,
    val queryIndexInSnippet: Int,
    val snippet: String
)

data class BookContentTextSearchResult(
    val matches: List<BookContentTextMatch>,
    val truncated: Boolean
)

/** 纯文本匹配器，不依赖 Room、文件系统或 Android UI。 */
object BookContentSearcher {

    const val MAX_RESULTS = 500
    const val RESULT_BATCH_SIZE = 20
    private const val SNIPPET_CONTEXT_LENGTH = 20

    // 与 modules/web/src/components/ChapterContent.vue、bookPagination.ts 保持一致：
    // Web 会先按 \n 拆段，因此这里只把同一段内的完整 img 标签压成一个字符，
    // 不能让 Kotlin 字符类跨 LF 吞掉多段内容；裸 CR 在 JS 中仍属于段内字符。
    private val imageTagPattern =
        Regex("""<img[^\n>]*src=['"][^\n'"]*(?:['"][^\n>]+\})?['"][^\n>]*>""")

    /**
     * 在已经过 ContentProcessor 处理的章节正文中做普通字面量搜索。
     *
     * 搜索结果按正文位置稳定排序；匹配不重叠。达到 [maxResults] 后立即停止，
     * 并用 truncated 标记调用方不应继续扫描后续正文或章节。
     */
    suspend fun search(
        content: String,
        query: String,
        maxResults: Int = MAX_RESULTS
    ): BookContentTextSearchResult {
        require(query.isNotBlank()) { "搜索关键词不能为空" }
        require(maxResults > 0) { "搜索结果上限必须大于 0" }

        val context = currentCoroutineContext()
        context.ensureActive()
        val normalizedContent = normalizeImages(content)
        val searchableText = normalizedContent.text
        val paragraphs = buildParagraphPositions(searchableText)
        val matches = ArrayList<BookContentTextMatch>(minOf(maxResults, 32))
        var fromIndex = 0
        var truncated = false

        while (fromIndex <= searchableText.length - query.length) {
            context.ensureActive()
            val normalizedQueryIndex = searchableText.indexOf(query, fromIndex)
            if (normalizedQueryIndex < 0) break

            val snippetStart = safeSnippetStart(
                searchableText,
                maxOf(0, normalizedQueryIndex - SNIPPET_CONTEXT_LENGTH)
            )
            val snippetEnd = safeSnippetEnd(
                searchableText,
                minOf(
                    searchableText.length,
                    normalizedQueryIndex + query.length + SNIPPET_CONTEXT_LENGTH
                )
            )
            matches.add(
                BookContentTextMatch(
                    chapterPos = findChapterPos(paragraphs, normalizedQueryIndex),
                    queryIndexInChapter = normalizedContent.rawIndexAt(normalizedQueryIndex),
                    queryIndexInSnippet = normalizedQueryIndex - snippetStart,
                    snippet = searchableText.substring(snippetStart, snippetEnd)
                )
            )
            if (matches.size >= maxResults) {
                truncated = true
                break
            }
            fromIndex = normalizedQueryIndex + query.length
        }

        return BookContentTextSearchResult(matches, truncated)
    }

    // JavaScript、Kotlin 字符串索引都使用 UTF-16 坐标，但摘要边界不能落在
    // surrogate pair 中间，否则 JSON 到浏览器后会显示替换字符。
    private fun safeSnippetStart(text: String, start: Int): Int {
        return if (
            start > 0 &&
            start < text.length &&
            Character.isHighSurrogate(text[start - 1]) &&
            Character.isLowSurrogate(text[start])
        ) {
            start - 1
        } else {
            start
        }
    }

    private fun safeSnippetEnd(text: String, end: Int): Int {
        return if (
            end > 0 &&
            end < text.length &&
            Character.isHighSurrogate(text[end - 1]) &&
            Character.isLowSurrogate(text[end])
        ) {
            end + 1
        } else {
            end
        }
    }

    /**
     * Web 展示正文时把完整 img 标签按一个字符计数。搜索也先做同样归一化，
     * 避免命中图片 URL/属性，并让摘要只包含可按普通文本显示的占位空格。
     * normalizedToRawIndex 保留正文原始 UTF-16 坐标，供后续正文内高亮使用。
     */
    private suspend fun normalizeImages(content: String): NormalizedContent {
        val context = currentCoroutineContext()
        val text = StringBuilder(content.length)
        val indexMappings = ArrayList<IndexMapping>()
        var rawIndex = 0
        for (match in imageTagPattern.findAll(content)) {
            context.ensureActive()
            if (rawIndex < match.range.first) {
                indexMappings.add(
                    IndexMapping(
                        normalizedStart = text.length,
                        rawStart = rawIndex,
                        fixedRawIndex = false
                    )
                )
                text.append(content, rawIndex, match.range.first)
            }
            indexMappings.add(
                IndexMapping(
                    normalizedStart = text.length,
                    rawStart = match.range.first,
                    fixedRawIndex = true
                )
            )
            text.append(' ')
            rawIndex = match.range.last + 1
        }
        if (rawIndex < content.length) {
            context.ensureActive()
            indexMappings.add(
                IndexMapping(
                    normalizedStart = text.length,
                    rawStart = rawIndex,
                    fixedRawIndex = false
                )
            )
            text.append(content, rawIndex, content.length)
        }
        return NormalizedContent(
            text = text.toString(),
            indexMappings = indexMappings
        )
    }

    private suspend fun buildParagraphPositions(content: String): List<ParagraphPosition> {
        val context = currentCoroutineContext()
        val paragraphs = ArrayList<ParagraphPosition>()
        var rawStart = 0
        var chapterPos = -1

        while (true) {
            context.ensureActive()
            val newlineIndex = content.indexOf('\n', rawStart)
            val rawEnd = if (newlineIndex >= 0) newlineIndex else content.length
            // content 已在 normalizeImages 中把每个图片标签压成一个字符。
            chapterPos += rawEnd - rawStart + 1
            paragraphs.add(ParagraphPosition(rawStart, chapterPos))

            if (newlineIndex < 0) break
            var nextStart = newlineIndex + 1
            while (nextStart < content.length && content[nextStart] == '\n') {
                context.ensureActive()
                nextStart++
            }
            rawStart = nextStart
            if (rawStart == content.length) {
                // JavaScript 的 "text\n".split(/\n+/) 会保留末尾空段。
                chapterPos += 1
                paragraphs.add(ParagraphPosition(rawStart, chapterPos))
                break
            }
        }

        return paragraphs
    }

    private fun findChapterPos(
        paragraphs: List<ParagraphPosition>,
        queryIndex: Int
    ): Int {
        var low = 0
        var high = paragraphs.lastIndex
        var result = paragraphs.first().chapterPos
        while (low <= high) {
            val middle = (low + high).ushr(1)
            val paragraph = paragraphs[middle]
            if (paragraph.rawStart <= queryIndex) {
                result = paragraph.chapterPos
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return result
    }

    private data class ParagraphPosition(
        val rawStart: Int,
        val chapterPos: Int
    )

    private data class NormalizedContent(
        val text: String,
        val indexMappings: List<IndexMapping>
    ) {
        fun rawIndexAt(normalizedIndex: Int): Int {
            var low = 0
            var high = indexMappings.lastIndex
            var result = indexMappings.first()
            while (low <= high) {
                val middle = (low + high).ushr(1)
                val mapping = indexMappings[middle]
                if (mapping.normalizedStart <= normalizedIndex) {
                    result = mapping
                    low = middle + 1
                } else {
                    high = middle - 1
                }
            }
            return if (result.fixedRawIndex) {
                result.rawStart
            } else {
                result.rawStart + normalizedIndex - result.normalizedStart
            }
        }
    }

    private data class IndexMapping(
        val normalizedStart: Int,
        val rawStart: Int,
        val fixedRawIndex: Boolean
    )
}

data class BookContentSearchStart(
    val totalChapters: Int,
    val searchableChapters: Int,
    val isLocalBook: Boolean
)

data class BookContentSearchProgress(
    val scannedChapters: Int,
    val searchableChapters: Int,
    val matchCount: Int
)

data class BookContentSearchComplete(
    val scannedChapters: Int,
    val matchCount: Int,
    val skippedUncachedChapters: Int,
    val truncated: Boolean
)

interface BookContentSearchListener {
    suspend fun onStart(start: BookContentSearchStart) = Unit

    suspend fun onResults(items: List<WebBookContentSearchResult>) = Unit

    suspend fun onProgress(progress: BookContentSearchProgress) = Unit

    suspend fun onComplete(complete: BookContentSearchComplete) = Unit
}

/**
 * 手机端书籍全文搜索编排服务。
 *
 * 默认依赖只读取 Room 和 BookHelp 已有内容；在线书先固定缓存文件集合，再只读取
 * 文件名命中的章节。这里没有 WebBook 依赖，因此缓存未命中时不会走书源网络请求。
 */
class BookContentSearchService(
    /**
     * 单次搜索章节并行度。Web 端通过 [BookContentSearchWebSocket] 注入 [AppConfig.threadCount]；
     * 纯 JVM 单测可用固定常量避免触碰 Android 依赖。实际并行度还会被
     * [MAX_SEARCH_CONCURRENCY] 与可搜索章节数封顶。
     */
    private val searchConcurrency: Int = DEFAULT_SEARCH_CONCURRENCY,
    private val findBook: suspend (String) -> Book? = { bookUrl ->
        appDb.bookDao.getBook(bookUrl)
    },
    private val findChapters: suspend (String) -> List<BookChapter> = { bookUrl ->
        appDb.bookChapterDao.getChapterList(bookUrl)
    },
    private val getCachedChapterNames: suspend (Book) -> Set<String> = { book ->
        BookHelp.getChapterFiles(book)
    },
    private val readLocalChapterContent: suspend (Book, BookChapter) -> String? = { book, chapter ->
        BookHelp.getContent(book, chapter)
    },
    private val readCachedChapterContent: suspend (Book, BookChapter) -> String? = { book, chapter ->
        BookHelp.getCachedContent(book, chapter)
    },
    private val processChapterContent: suspend (Book, BookChapter, String) -> String =
        { book, chapter, content ->
            ContentProcessor.get(book.name, book.origin)
                .getContent(book, chapter, content, includeTitle = false)
                .toString()
        },
    // origin 只表示来源/同步关系：普通本地书上传 WebDAV 后也会变成 webDav::，
    // 但 bookUrl 仍指向手机文件。正文读取能力必须看实际定位字段 bookUrl；只有
    // bookUrl 本身是 webDav:: 时才是远程文件，需要限制为仅搜索已有章节缓存。
    private val isLocalBook: (Book) -> Boolean = { book ->
        book.isLocal && !book.bookUrl.startsWith(BookType.webDavTag)
    },
    private val chapterFileName: (BookChapter) -> String = { chapter -> chapter.getFileName() }
) {

    suspend fun search(
        bookUrl: String,
        query: String,
        maxResults: Int = BookContentSearcher.MAX_RESULTS,
        listener: BookContentSearchListener
    ) {
        require(bookUrl.isNotBlank()) { "书籍地址不能为空" }
        require(query.isNotBlank()) { "搜索关键词不能为空" }
        require(maxResults > 0) { "搜索结果上限必须大于 0" }
        val resultLimit = minOf(maxResults, BookContentSearcher.MAX_RESULTS)
        val context = currentCoroutineContext()
        context.ensureActive()

        val book = checkNotNull(findBook(bookUrl)) { "未找到书籍" }
        val localBook = isLocalBook(book)
        val chapters = findChapters(bookUrl).sortedBy { it.index }
        context.ensureActive()
        val cachedChapterNames = if (localBook) {
            emptySet()
        } else {
            getCachedChapterNames(book).toHashSet()
        }
        val searchableChapters = if (localBook) {
            chapters
        } else {
            chapters.filter { chapterFileName(it) in cachedChapterNames }
        }
        val skippedUncachedChapters = if (localBook) {
            0
        } else {
            chapters.size - searchableChapters.size
        }

        listener.onStart(
            BookContentSearchStart(
                totalChapters = chapters.size,
                searchableChapters = searchableChapters.size,
                isLocalBook = localBook
            )
        )

        var scannedChapters = 0
        var matchCount = 0
        var truncated = false
        val pendingResults = ArrayList<WebBookContentSearchResult>(
            BookContentSearcher.RESULT_BATCH_SIZE
        )

        val concurrency = minOf(searchConcurrency, searchableChapters.size, MAX_SEARCH_CONCURRENCY)
            .coerceAtLeast(1)

        if (searchableChapters.isNotEmpty()) coroutineScope {
            // 只允许有限数量的章节领先于当前上报位置，既持续补位，又避免慢章前堆积整本书结果。
            val windowSize = minOf(
                searchableChapters.size,
                concurrency * SEARCH_WINDOW_MULTIPLIER
            )
            val taskChannel = Channel<Int>(windowSize)
            val outputChannel = Channel<IndexedChapterSearchOutput>(windowSize)
            val workers = List(concurrency) {
                launch {
                    for (position in taskChannel) {
                        val chapter = searchableChapters[position]
                        outputChannel.send(
                            IndexedChapterSearchOutput(
                                position = position,
                                output = searchChapter(
                                    book = book,
                                    chapter = chapter,
                                    localBook = localBook,
                                    query = query,
                                    resultLimit = resultLimit
                                )
                            )
                        )
                    }
                }
            }

            var nextToSchedule = 0
            var nextToReport = 0
            val completedByPosition = HashMap<Int, ChapterSearchOutput?>(windowSize)

            suspend fun fillWindow() {
                while (
                    nextToSchedule < searchableChapters.size &&
                    nextToSchedule - nextToReport < windowSize
                ) {
                    taskChannel.send(nextToSchedule)
                    nextToSchedule++
                }
                if (nextToSchedule >= searchableChapters.size) {
                    taskChannel.close()
                }
            }

            try {
                fillWindow()
                while (nextToReport < searchableChapters.size && !truncated) {
                    context.ensureActive()
                    val completed = outputChannel.receive()
                    completedByPosition[completed.position] = completed.output

                    while (completedByPosition.containsKey(nextToReport) && !truncated) {
                        val output = completedByPosition.remove(nextToReport)
                        if (output != null) {
                            for (result in output.results) {
                                context.ensureActive()
                                if (matchCount >= resultLimit) {
                                    // 已达到上限后又确认存在一条结果，才算真正截断。
                                    truncated = true
                                    break
                                }
                                pendingResults.add(result)
                                matchCount++
                                if (pendingResults.size >= BookContentSearcher.RESULT_BATCH_SIZE) {
                                    listener.onResults(pendingResults.toList())
                                    pendingResults.clear()
                                }
                            }
                            if (output.chapterTruncated) {
                                truncated = true
                            }
                        }

                        scannedChapters++
                        nextToReport++
                        context.ensureActive()
                        listener.onProgress(
                            BookContentSearchProgress(
                                scannedChapters = scannedChapters,
                                searchableChapters = searchableChapters.size,
                                matchCount = matchCount
                            )
                        )
                    }

                    if (!truncated) {
                        fillWindow()
                    }
                }
            } finally {
                taskChannel.cancel()
                outputChannel.cancel()
                if (truncated) {
                    workers.forEach { it.cancel() }
                }
            }
        }

        context.ensureActive()
        if (pendingResults.isNotEmpty()) {
            listener.onResults(pendingResults.toList())
        }
        context.ensureActive()
        listener.onComplete(
            BookContentSearchComplete(
                scannedChapters = scannedChapters,
                matchCount = matchCount,
                skippedUncachedChapters = skippedUncachedChapters,
                truncated = truncated
            )
        )
    }

    private suspend fun searchChapter(
        book: Book,
        chapter: BookChapter,
        localBook: Boolean,
        query: String,
        resultLimit: Int
    ): ChapterSearchOutput? {
        val context = currentCoroutineContext()
        context.ensureActive()
        // 远程定位的书只能走“仅缓存”入口。不能在快照后再次调用通用 getContent，
        // 否则 WebDAV 缓存恰好被清理时会回退 FileBook 并发起网络读取。
        // 对“本地 bookUrl + WebDAV origin”的书传入去掉远程来源的只读副本；这样
        // 本地文件在搜索期间失效时也只会读取失败，不会由 BaseFileBook 回源下载。
        val rawContent = if (localBook) {
            val localOnlyBook = if (book.isWebDavBook) {
                book.copy(origin = BookType.localTag)
            } else {
                book
            }
            readLocalChapterContent(localOnlyBook, chapter)
        } else {
            readCachedChapterContent(book, chapter)
        }
        context.ensureActive()
        if (rawContent == null) return null

        val processedContent = processChapterContent(book, chapter, rawContent)
        context.ensureActive()
        val chapterSearch = BookContentSearcher.search(
            content = processedContent,
            query = query,
            maxResults = resultLimit
        )
        val results = ArrayList<WebBookContentSearchResult>(chapterSearch.matches.size)
        for (match in chapterSearch.matches) {
            context.ensureActive()
            results.add(
                WebBookContentSearchResult(
                    chapterIndex = chapter.index,
                    chapterTitle = chapter.title,
                    chapterPos = match.chapterPos,
                    queryIndexInChapter = match.queryIndexInChapter,
                    queryIndexInSnippet = match.queryIndexInSnippet,
                    snippet = match.snippet
                )
            )
        }
        return ChapterSearchOutput(results, chapterSearch.truncated)
    }

    private data class ChapterSearchOutput(
        val results: ArrayList<WebBookContentSearchResult>,
        val chapterTruncated: Boolean
    )

    private data class IndexedChapterSearchOutput(
        val position: Int,
        val output: ChapterSearchOutput?
    )

    companion object {
        /**
         * 纯 JVM 默认并行度，避免单测触碰 [AppConfig]。生产路径由 WebSocket 注入用户配置。
         */
        const val DEFAULT_SEARCH_CONCURRENCY = 16

        /**
         * 并行度硬上限。即便用户把 threadCount 调到 999，也限制并行的章节读取数，
         * 避免同时打开过多文件句柄/同步锁竞争引发的 IO 抖动与内存峰值。
         */
        const val MAX_SEARCH_CONCURRENCY = 16

        private const val SEARCH_WINDOW_MULTIPLIER = 2
    }
}
