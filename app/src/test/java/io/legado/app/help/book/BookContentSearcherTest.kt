package io.legado.app.help.book

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BookContentSearcherTest {

    @Test
    fun singleChapterSingleResult() = runBlocking {
        val content = "开头关键词结尾"

        val result = BookContentSearcher.search(content, "关键词")

        assertEquals(1, result.matches.size)
        assertEquals(content.indexOf("关键词"), result.matches.single().queryIndexInChapter)
        assertEquals(content.length, result.matches.single().chapterPos)
        assertFalse(result.truncated)
    }

    @Test
    fun sameChapterMultipleResultsKeepTextOrder() = runBlocking {
        val result = BookContentSearcher.search("词 abc 词 xyz 词", "词")

        assertEquals(listOf(0, 6, 12), result.matches.map { it.queryIndexInChapter })
    }

    @Test
    fun multipleChaptersKeepChapterIndexOrder() = runBlocking {
        val chapters = listOf(chapter(2), chapter(0), chapter(1))
        val listener = RecordingListener()
        val service = service(
            local = true,
            chapters = chapters,
            contents = chapters.associate { it.index to "第${it.index}章有词" }
        )

        service.search("book", "词", listener = listener)

        assertEquals(listOf(0, 1, 2), listener.items.map { it.chapterIndex })
    }

    @Test
    fun keywordAtContentStartAndEndHasBoundedSnippet() = runBlocking {
        val start = BookContentSearcher.search("关键词${"甲".repeat(30)}", "关键词")
            .matches.single()
        val end = BookContentSearcher.search("${"乙".repeat(30)}关键词", "关键词")
            .matches.single()

        assertEquals(0, start.queryIndexInSnippet)
        assertEquals("关键词", start.snippet.substring(0, 3))
        assertEquals(20, end.queryIndexInSnippet)
        assertEquals("关键词", end.snippet.substring(end.queryIndexInSnippet))
    }

    @Test
    fun blankKeywordIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { BookContentSearcher.search("正文", "   ") }
        }
    }

    @Test
    fun resultLimitStopsSearchAndMarksTruncated() = runBlocking {
        val result = BookContentSearcher.search("词 词 词", "词", maxResults = 2)

        assertEquals(2, result.matches.size)
        assertTrue(result.truncated)
    }

    @Test
    fun snippetNeverCrossesContentBoundary() = runBlocking {
        val content = "关键词${"中".repeat(50)}关键词"
        val result = BookContentSearcher.search(content, "关键词")

        assertEquals(2, result.matches.size)
        assertTrue(result.matches.all { it.queryIndexInSnippet >= 0 })
        assertTrue(result.matches.all { it.queryIndexInSnippet + 3 <= it.snippet.length })
        assertEquals("关键词", result.matches.first().snippet.substring(0, 3))
        assertEquals("关键词", result.matches.last().snippet.takeLast(3))
    }

    @Test
    fun snippetKeepsEmojiSurrogatePairsAndConsecutiveNewlineChapterPos() = runBlocking {
        val emojiBefore = "😀${"甲".repeat(19)}关键词\n\n下一段"
        val beforeMatch = BookContentSearcher.search(emojiBefore, "关键词").matches.single()
        val nextParagraphMatch = BookContentSearcher.search(emojiBefore, "下一段").matches.single()
        val emojiAfter = "关键词${"乙".repeat(19)}😀\n\n下一段"
        val afterMatch = BookContentSearcher.search(emojiAfter, "关键词").matches.single()

        assertTrue(beforeMatch.snippet.startsWith("😀"))
        assertEquals(21, beforeMatch.queryIndexInSnippet)
        assertEquals(24, beforeMatch.chapterPos)
        assertEquals(28, nextParagraphMatch.chapterPos)
        assertTrue(afterMatch.snippet.endsWith("😀"))
        assertEquals(24, afterMatch.chapterPos)
    }

    @Test
    fun imageTagIsPlainPlaceholderAndChapterPosMatchesWebAlgorithm() = runBlocking {
        val content = "甲<img src='https://example/key.png'>乙关键词\n第二段关键词"

        val result = BookContentSearcher.search(content, "关键词")

        assertEquals(2, result.matches.size)
        assertEquals(listOf(6, 13), result.matches.map { it.chapterPos })
        assertEquals(
            listOf(content.indexOf("关键词"), content.lastIndexOf("关键词")),
            result.matches.map { it.queryIndexInChapter }
        )
        assertTrue(result.matches.all { "<img" !in it.snippet })
        assertTrue(result.matches.all {
            it.snippet.substring(it.queryIndexInSnippet, it.queryIndexInSnippet + 3) == "关键词"
        })

        val imageUrlResult = BookContentSearcher.search(content, "key.png")
        assertTrue(imageUrlResult.matches.isEmpty())
    }

    @Test
    fun fullImageParagraphCountsAsOneCharacter() = runBlocking {
        val result = BookContentSearcher.search("<img src='x'>\n关键词", "关键词")

        // 第一段图片位置为 1；第二段三个字符再加换行，累计位置为 5。
        assertEquals(5, result.matches.single().chapterPos)
    }

    @Test
    fun multilineImageLikeMarkupIsNotCollapsedAcrossWebParagraphs() = runBlocking {
        val content = "甲<img\n src='x'>乙\n关键词"
        val bareCarriageReturnContent = "甲<img\r src='x'>乙\n关键词"

        val result = BookContentSearcher.search(content, "关键词")
        val bareCarriageReturnResult =
            BookContentSearcher.search(bareCarriageReturnContent, "关键词")

        // Web 先 split(/\n+/)，再逐段替换 img；跨行标签不是完整图片占位符。
        assertEquals(20, result.matches.single().chapterPos)
        // 裸 CR 不会被 split(/\n+/) 拆段，前后端都仍将该 img 压成一个字符。
        assertEquals(7, bareCarriageReturnResult.matches.single().chapterPos)
    }

    @Test
    fun cancelledCoroutineStopsBeforeMatching() {
        val cancelledJob = Job().apply { cancel() }

        assertThrows(CancellationException::class.java) {
            runBlocking {
                withContext(cancelledJob) {
                    BookContentSearcher.search("词".repeat(100_000), "词")
                }
            }
        }
    }

    @Test
    fun cancellationAfterSearchStartsDoesNotReadAnotherChapterOrComplete() = runBlocking {
        val chapters = listOf(chapter(0), chapter(1))
        val firstReadStarted = CompletableDeferred<Unit>()
        val keepFirstReadOpen = CompletableDeferred<Unit>()
        val reads = mutableListOf<Int>()
        val listener = RecordingListener()
        val service = BookContentSearchService(
            findBook = { Book(bookUrl = "book", name = "测试书", origin = "test-origin") },
            findChapters = { chapters },
            getCachedChapterNames = { emptySet() },
            readLocalChapterContent = { _, chapter ->
                reads.add(chapter.index)
                firstReadStarted.complete(Unit)
                keepFirstReadOpen.await()
                "本章有词"
            },
            readCachedChapterContent = { _, _ -> error("本地书不应走仅缓存读取") },
            processChapterContent = { _, _, raw -> raw },
            isLocalBook = { true },
            chapterFileName = { chapter -> "chapter-${chapter.index}" }
        )

        val searchJob = launch {
            service.search("book", "词", listener = listener)
        }
        firstReadStarted.await()
        searchJob.cancelAndJoin()

        assertEquals(listOf(0), reads)
        assertTrue(listener.items.isEmpty())
        assertEquals(null, listener.complete)
    }

    @Test
    fun onlineBookReadsOnlyCachedChaptersFromSingleSnapshot() = runBlocking {
        val chapters = listOf(chapter(0), chapter(1), chapter(2))
        val reads = mutableListOf<Int>()
        var cacheSnapshotCalls = 0
        val listener = RecordingListener()
        val service = service(
            local = false,
            chapters = chapters,
            contents = chapters.associate { it.index to "本章有词" },
            cachedNames = setOf("chapter-1"),
            onCacheSnapshot = { cacheSnapshotCalls++ },
            onRead = { reads.add(it.index) }
        )

        service.search("book", "词", listener = listener)

        assertEquals(1, cacheSnapshotCalls)
        assertEquals(listOf(1), reads)
        assertEquals(listOf(1), listener.items.map { it.chapterIndex })
        assertEquals(3, listener.start?.totalChapters)
        assertEquals(1, listener.start?.searchableChapters)
        assertFalse(listener.start?.isLocalBook ?: true)
        assertEquals(2, listener.complete?.skippedUncachedChapters)
    }

    @Test
    fun webDavBookWithoutCachedChapterNeverFallsBackToRemoteFile() = runBlocking {
        val chapters = listOf(chapter(0))
        val reads = mutableListOf<Int>()
        val listener = RecordingListener()
        val book = Book(
            bookUrl = "webDav::https://example.invalid/book.epub",
            name = "远程书",
            originName = "book.epub",
            origin = "webDav::https://example.invalid/book.epub"
        )
        val service = BookContentSearchService(
            findBook = { book },
            findChapters = { chapters },
            getCachedChapterNames = { emptySet() },
            readLocalChapterContent = { _, chapter ->
                reads.add(chapter.index)
                "不应读取"
            },
            readCachedChapterContent = { _, chapter ->
                reads.add(chapter.index)
                "不应读取"
            },
            processChapterContent = { _, _, raw -> raw },
            chapterFileName = { chapter -> "chapter-${chapter.index}" }
        )

        service.search(book.bookUrl, "读取", listener = listener)

        assertTrue(reads.isEmpty())
        assertEquals(0, listener.start?.searchableChapters)
        assertFalse(listener.start?.isLocalBook ?: true)
        assertEquals(1, listener.complete?.skippedUncachedChapters)
    }

    @Test
    fun disappearingWebDavCacheNeverFallsBackToLocalOrRemoteFile() = runBlocking {
        val chapters = listOf(chapter(0))
        var localReads = 0
        var cachedReads = 0
        val listener = RecordingListener()
        val book = Book(
            bookUrl = "webDav::https://example.invalid/book.epub",
            name = "远程书",
            originName = "book.epub",
            origin = "webDav::https://example.invalid/book.epub"
        )
        val service = BookContentSearchService(
            findBook = { book },
            findChapters = { chapters },
            // 快照时文件仍存在，真正读取前被外部缓存清理删除。
            getCachedChapterNames = { setOf("chapter-0") },
            readLocalChapterContent = { _, _ ->
                localReads++
                "不应回退读取"
            },
            readCachedChapterContent = { _, _ ->
                cachedReads++
                null
            },
            processChapterContent = { _, _, raw -> raw },
            chapterFileName = { chapter -> "chapter-${chapter.index}" }
        )

        service.search(book.bookUrl, "读取", listener = listener)

        assertEquals(0, localReads)
        assertEquals(1, cachedReads)
        assertTrue(listener.items.isEmpty())
        assertEquals(1, listener.complete?.scannedChapters)
        assertEquals(0, listener.complete?.matchCount)
    }

    @Test
    fun localBookReadsEveryChapterWithoutCacheSnapshot() = runBlocking {
        val chapters = listOf(chapter(0), chapter(1), chapter(2))
        val reads = mutableListOf<Int>()
        var cacheSnapshotCalls = 0
        val listener = RecordingListener()
        val service = service(
            local = true,
            chapters = chapters,
            contents = chapters.associate { it.index to "本章有词" },
            onCacheSnapshot = { cacheSnapshotCalls++ },
            onRead = { reads.add(it.index) }
        )

        service.search("book", "词", listener = listener)

        assertEquals(0, cacheSnapshotCalls)
        assertEquals(listOf(0, 1, 2), reads)
        assertEquals(3, listener.items.size)
        assertTrue(listener.start?.isLocalBook == true)
        assertEquals(0, listener.complete?.skippedUncachedChapters)
    }

    @Test
    fun searchUsesProcessedContentThatWebActuallyDisplays() = runBlocking {
        val chapter = chapter(0)
        var processCalls = 0
        val listener = RecordingListener()
        val service = service(
            local = true,
            chapters = listOf(chapter),
            contents = mapOf(0 to "原始标题\n原始正文"),
            process = { raw ->
                processCalls++
                raw.substringAfter('\n').replace("原始", "网页")
            }
        )

        service.search("book", "网页正文", listener = listener)

        assertEquals(1, processCalls)
        assertEquals("网页正文", listener.items.single().snippet)
        assertEquals(0, listener.items.single().queryIndexInChapter)
    }

    @Test
    fun resultsAreSentInBatchesOfTwenty() = runBlocking {
        val chapter = chapter(0)
        val listener = RecordingListener()
        val service = service(
            local = true,
            chapters = listOf(chapter),
            contents = mapOf(0 to List(45) { "词" }.joinToString(" "))
        )

        service.search("book", "词", maxResults = 100, listener = listener)

        assertEquals(listOf(20, 20, 5), listener.batchSizes)
        assertEquals(45, listener.complete?.matchCount)
        assertFalse(listener.complete?.truncated ?: true)
    }

    private fun service(
        local: Boolean,
        chapters: List<BookChapter>,
        contents: Map<Int, String>,
        cachedNames: Set<String> = emptySet(),
        onCacheSnapshot: () -> Unit = {},
        onRead: (BookChapter) -> Unit = {},
        process: (String) -> String = { it }
    ): BookContentSearchService {
        val book = Book(bookUrl = "book", name = "测试书", origin = "test-origin")
        val readContent: suspend (Book, BookChapter) -> String? = { _, chapter ->
            onRead(chapter)
            contents[chapter.index]
        }
        return BookContentSearchService(
            findBook = { book },
            findChapters = { chapters },
            getCachedChapterNames = {
                onCacheSnapshot()
                cachedNames
            },
            readLocalChapterContent = readContent,
            readCachedChapterContent = readContent,
            processChapterContent = { _, _, raw -> process(raw) },
            isLocalBook = { local },
            chapterFileName = { chapter -> "chapter-${chapter.index}" }
        )
    }

    private fun chapter(index: Int) = BookChapter(
        url = "chapter-$index",
        title = "第${index}章",
        bookUrl = "book",
        index = index
    )

    private class RecordingListener : BookContentSearchListener {
        var start: BookContentSearchStart? = null
        var complete: BookContentSearchComplete? = null
        val items = mutableListOf<WebBookContentSearchResult>()
        val batchSizes = mutableListOf<Int>()

        override suspend fun onStart(start: BookContentSearchStart) {
            this.start = start
        }

        override suspend fun onResults(items: List<WebBookContentSearchResult>) {
            batchSizes.add(items.size)
            this.items.addAll(items)
        }

        override suspend fun onComplete(complete: BookContentSearchComplete) {
            this.complete = complete
        }
    }
}
