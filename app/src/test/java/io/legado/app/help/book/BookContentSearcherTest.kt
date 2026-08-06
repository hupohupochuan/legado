package io.legado.app.help.book

import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

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
    fun exactResultLimitWithoutAnotherMatchIsNotTruncated() = runBlocking {
        val result = BookContentSearcher.search("词 词", "词", maxResults = 2)

        assertEquals(2, result.matches.size)
        assertEquals(null, result.nextFromIndex)
        assertFalse(result.truncated)
    }

    @Test
    fun matcherCursorContinuesAtFirstOmittedMatchWithoutDuplicates() = runBlocking {
        val content = "词<img src='x'>词 词 词 词"
        val expectedIndexes = BookContentSearcher.search(content, "词").matches
            .map { it.queryIndexInChapter }

        val first = BookContentSearcher.search(content, "词", maxResults = 2)
        val second = BookContentSearcher.search(
            content,
            "词",
            maxResults = 2,
            fromIndex = checkNotNull(first.nextFromIndex)
        )
        val third = BookContentSearcher.search(
            content,
            "词",
            maxResults = 2,
            fromIndex = checkNotNull(second.nextFromIndex)
        )

        assertEquals(
            expectedIndexes,
            (first.matches + second.matches + third.matches).map { it.queryIndexInChapter }
        )
        assertEquals(null, third.nextFromIndex)
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

        // 并行化后第一章与第二章的读取可能都已启动，但取消后不得上报结果或完成。
        assertEquals(setOf(0, 1), reads.toSet())
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
    fun webDavLinkedLocalBookSearchesOriginalFileWithoutRemoteFallback() = runBlocking {
        val chapters = listOf(chapter(0), chapter(1))
        val reads = ConcurrentHashMap.newKeySet<Int>()
        var cacheSnapshotCalls = 0
        val listener = RecordingListener()
        val remoteOrigin = BookType.webDavTag + "https://example.invalid/book.txt"
        val book = Book(
            bookUrl = "content://books/book.txt",
            name = "已上传的本地书",
            originName = "book.txt",
            origin = remoteOrigin,
            type = BookType.text or BookType.local
        )
        val service = BookContentSearchService(
            findBook = { book },
            findChapters = { chapters },
            getCachedChapterNames = {
                cacheSnapshotCalls++
                emptySet()
            },
            readLocalChapterContent = { localOnlyBook, chapter ->
                // 搜索期间即使本地文件失效，也不能凭 WebDAV origin 回源下载。
                assertEquals(BookType.localTag, localOnlyBook.origin)
                assertEquals(book.bookUrl, localOnlyBook.bookUrl)
                reads.add(chapter.index)
                "本章有词"
            },
            readCachedChapterContent = { _, _ -> error("本地文件书不应走仅缓存读取") },
            processChapterContent = { originalBook, _, raw ->
                // 替换规则等正文处理仍保留原始 WebDAV 来源语义。
                assertEquals(remoteOrigin, originalBook.origin)
                raw
            },
            chapterFileName = { chapter -> "chapter-${chapter.index}" }
        )

        service.search(book.bookUrl, "词", listener = listener)

        assertEquals(0, cacheSnapshotCalls)
        assertEquals(setOf(0, 1), reads)
        assertEquals(listOf(0, 1), listener.items.map { it.chapterIndex })
        assertTrue(listener.start?.isLocalBook == true)
        assertEquals(0, listener.complete?.skippedUncachedChapters)
        assertEquals(remoteOrigin, book.origin)
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
        // 并行读取顺序不确定，但全部章节都会读取。
        assertEquals(listOf(0, 1, 2), reads.sorted())
        // 结果仍按 chapterIndex 有序上报。
        assertEquals(listOf(0, 1, 2), listener.items.map { it.chapterIndex })
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

    @Test
    fun parallelSearchKeepsResultsInChapterOrderEvenWhenReadsCompleteOutOfOrder() = runBlocking {
        val chapters = listOf(chapter(0), chapter(1), chapter(2))
        val reads = mutableListOf<Int>()
        val listener = RecordingListener()
        val service = BookContentSearchService(
            searchConcurrency = 16,
            findBook = { Book(bookUrl = "book", name = "测试书", origin = "test-origin") },
            findChapters = { chapters },
            getCachedChapterNames = { emptySet() },
            readLocalChapterContent = { _, ch ->
                reads.add(ch.index)
                // 中间章节故意慢，验证乱序完成仍按章序上报。
                if (ch.index == 1) delay(200)
                "本章有词"
            },
            readCachedChapterContent = { _, _ -> error("本地书不应走仅缓存读取") },
            processChapterContent = { _, _, raw -> raw },
            isLocalBook = { true },
            chapterFileName = { chapter -> "chapter-${chapter.index}" }
        )

        service.search("book", "词", listener = listener)

        assertEquals(listOf(0, 1, 2), listener.items.map { it.chapterIndex })
        assertEquals(setOf(0, 1, 2), reads.toSet())
        assertEquals(3, listener.complete?.scannedChapters)
        assertEquals(3, listener.complete?.matchCount)
        assertFalse(listener.complete?.truncated ?: true)
    }

    @Test
    fun slidingWindowStartsLaterChapterBeforeSlowSiblingFinishes() = runBlocking {
        val chapters = List(6) { chapter(it) }
        val slowChapterFinished = CompletableDeferred<Unit>()
        val laterChapterStarted = CompletableDeferred<Unit>()
        val listener = RecordingListener()
        val service = BookContentSearchService(
            searchConcurrency = 2,
            findBook = { Book(bookUrl = "book", name = "测试书", origin = "test-origin") },
            findChapters = { chapters },
            getCachedChapterNames = { emptySet() },
            readLocalChapterContent = { _, ch ->
                if (ch.index == 1) slowChapterFinished.await()
                if (ch.index == 4) laterChapterStarted.complete(Unit)
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
        try {
            withTimeout(5_000) {
                laterChapterStarted.await()
            }
        } finally {
            slowChapterFinished.complete(Unit)
            searchJob.join()
        }

        assertEquals(List(6) { it }, listener.items.map { it.chapterIndex })
    }

    @Test
    fun parallelSearchUsesRealThreadsWithoutExceedingConfiguredLimit() = runBlocking {
        val concurrency = 4
        val chapters = List(12) { chapter(it) }
        val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)
        val workerThreads = ConcurrentHashMap.newKeySet<String>()
        val listener = RecordingListener()
        val service = BookContentSearchService(
            searchConcurrency = concurrency,
            findBook = { Book(bookUrl = "book", name = "测试书", origin = "test-origin") },
            findChapters = { chapters },
            getCachedChapterNames = { emptySet() },
            readLocalChapterContent = { _, _ ->
                val active = inFlight.incrementAndGet()
                while (true) {
                    val previousMax = maxInFlight.get()
                    if (previousMax >= active || maxInFlight.compareAndSet(previousMax, active)) break
                }
                workerThreads.add(Thread.currentThread().name)
                try {
                    Thread.sleep(50)
                    "本章有词"
                } finally {
                    inFlight.decrementAndGet()
                }
            },
            readCachedChapterContent = { _, _ -> error("本地书不应走仅缓存读取") },
            processChapterContent = { _, _, raw -> raw },
            isLocalBook = { true },
            chapterFileName = { chapter -> "chapter-${chapter.index}" }
        )

        Executors.newFixedThreadPool(8).asCoroutineDispatcher().use { dispatcher ->
            withContext(dispatcher) {
                service.search("book", "词", listener = listener)
            }
        }

        assertEquals(concurrency, maxInFlight.get())
        assertTrue(workerThreads.size >= concurrency)
        assertEquals(List(12) { it }, listener.items.map { it.chapterIndex })
    }

    @Test
    fun searchConcurrencyOneBehavesSequentiallyAndReadsChaptersInOrder() = runBlocking {
        val chapters = listOf(chapter(0), chapter(1), chapter(2))
        val reads = mutableListOf<Int>()
        val listener = RecordingListener()
        val service = service(
            local = true,
            chapters = chapters,
            contents = chapters.associate { it.index to "本章有词" },
            onRead = { reads.add(it.index) },
            searchConcurrency = 1
        )

        service.search("book", "词", listener = listener)

        // 并行度为 1 时章节读取严格按序，等价于旧的串行实现。
        assertEquals(listOf(0, 1, 2), reads)
        assertEquals(listOf(0, 1, 2), listener.items.map { it.chapterIndex })
    }

    @Test
    fun parallelSearchStopsAtResultLimitAndMarksTruncated() = runBlocking {
        // 4 章，每章 10 处命中；resultLimit=25 应在第三章中段截断。
        val chapters = listOf(chapter(0), chapter(1), chapter(2), chapter(3))
        val listener = RecordingListener()
        val service = service(
            local = true,
            chapters = chapters,
            contents = chapters.associate { it.index to List(10) { "词" }.joinToString(" ") },
            searchConcurrency = 16
        )

        service.search("book", "词", maxResults = 25, listener = listener)

        assertEquals(25, listener.items.size)
        assertEquals(25, listener.complete?.matchCount)
        assertTrue(listener.complete?.truncated ?: false)
    }

    @Test
    fun exactResultLimitWithLaterMatchMarksTruncated() = runBlocking {
        val chapters = List(4) { chapter(it) }
        val listener = RecordingListener()
        val service = service(
            local = true,
            chapters = chapters,
            contents = mapOf(
                0 to List(10) { "词" }.joinToString(" "),
                1 to List(10) { "词" }.joinToString(" "),
                2 to List(5) { "词" }.joinToString(" "),
                3 to "边界之后还有词"
            ),
            searchConcurrency = 4
        )

        service.search("book", "词", maxResults = 25, listener = listener)

        assertEquals(25, listener.items.size)
        assertEquals(25, listener.complete?.matchCount)
        assertEquals(4, listener.complete?.scannedChapters)
        assertTrue(listener.complete?.truncated ?: false)
        assertEquals(2, listener.items.last().chapterIndex)
    }

    @Test
    fun exactResultLimitWithoutLaterMatchCompletesNormally() = runBlocking {
        val chapters = List(4) { chapter(it) }
        val listener = RecordingListener()
        val service = service(
            local = true,
            chapters = chapters,
            contents = mapOf(
                0 to List(10) { "词" }.joinToString(" "),
                1 to List(10) { "词" }.joinToString(" "),
                2 to List(5) { "词" }.joinToString(" "),
                3 to "边界之后没有匹配"
            ),
            searchConcurrency = 4
        )

        service.search("book", "词", maxResults = 25, listener = listener)

        assertEquals(25, listener.items.size)
        assertEquals(25, listener.complete?.matchCount)
        assertEquals(4, listener.complete?.scannedChapters)
        assertFalse(listener.complete?.truncated ?: true)
    }

    @Test
    fun serviceCursorContinuesAcrossChapterBoundaryWithoutRepeatingResults() = runBlocking {
        val chapters = List(3) { chapter(it) }
        val contents = mapOf(
            0 to List(3) { "词" }.joinToString(" "),
            1 to List(3) { "词" }.joinToString(" "),
            2 to "最后一词"
        )
        val service = service(
            local = true,
            chapters = chapters,
            contents = contents,
            searchConcurrency = 3
        )
        val first = RecordingListener()
        val second = RecordingListener()

        service.search("book", "词", maxResults = 4, listener = first)
        service.search(
            bookUrl = "book",
            query = "词",
            maxResults = 4,
            cursor = checkNotNull(first.complete?.nextCursor),
            listener = second
        )

        assertEquals(4, first.items.size)
        assertEquals(listOf(0, 0, 0, 1), first.items.map { it.chapterIndex })
        assertEquals(0, first.complete?.resultOffset)
        assertTrue(first.complete?.truncated ?: false)
        assertEquals(4, second.start?.resultOffset)
        assertEquals(listOf(1, 1, 2), second.items.map { it.chapterIndex })
        assertEquals(4, second.complete?.resultOffset)
        assertEquals(3, second.complete?.matchCount)
        assertEquals(null, second.complete?.nextCursor)
        assertFalse(second.complete?.truncated ?: true)

        val combinedKeys = (first.items + second.items).map {
            it.chapterIndex to it.queryIndexInChapter
        }
        assertEquals(7, combinedKeys.size)
        assertEquals(7, combinedKeys.distinct().size)
    }

    @Test
    fun cursorIsRejectedWhenSearchableChapterSnapshotChanges() = runBlocking {
        val chapters = List(2) { chapter(it) }
        val first = RecordingListener()
        service(
            local = true,
            chapters = chapters,
            contents = chapters.associate { it.index to "词 词" }
        ).search("book", "词", maxResults = 1, listener = first)
        val cursor = checkNotNull(first.complete?.nextCursor)
        val changedService = service(
            local = true,
            chapters = listOf(chapter(1)),
            contents = mapOf(1 to "词")
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                changedService.search(
                    bookUrl = "book",
                    query = "词",
                    maxResults = 1,
                    cursor = cursor,
                    listener = RecordingListener()
                )
            }
        }

        assertEquals("搜索位置已失效，请重新搜索", error.message)
    }

    private fun service(
        local: Boolean,
        chapters: List<BookChapter>,
        contents: Map<Int, String>,
        cachedNames: Set<String> = emptySet(),
        onCacheSnapshot: () -> Unit = {},
        onRead: (BookChapter) -> Unit = {},
        process: (String) -> String = { it },
        searchConcurrency: Int = BookContentSearchService.DEFAULT_SEARCH_CONCURRENCY
    ): BookContentSearchService {
        val book = Book(bookUrl = "book", name = "测试书", origin = "test-origin")
        val readContent: suspend (Book, BookChapter) -> String? = { _, chapter ->
            onRead(chapter)
            contents[chapter.index]
        }
        return BookContentSearchService(
            searchConcurrency = searchConcurrency,
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
