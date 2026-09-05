package io.legado.app.model

import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.PageAnim
import io.legado.app.constant.PageAnim.scrollPageAnim
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.entities.BookSource
import io.legado.app.help.BookProgressSyncProvider
import io.legado.app.help.ProgressCheckMode
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isPdf
import io.legado.app.help.book.isSameNameAuthor
import io.legado.app.help.book.readSimulating
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.help.book.update
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.globalExecutor
import io.legado.app.model.fileBook.TextFile
import io.legado.app.model.webBook.WebBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.service.CacheBookService
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.ui.book.read.page.provider.LayoutProgressListener
import io.legado.app.utils.ReaderPerformance
import io.legado.app.utils.postEvent
import io.legado.app.utils.stackTraceStr
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min


/**
 * 全局共享的阅读状态单例 + 协程作用域。
 *
 * 职责：
 * - 持有当前阅读的 Book、ChapterList、TextChapter 状态
 * - 提供翻页（nextPage/prevPage）、跳转（skipTo）、进度管理（setProgress）接口
 * - 与 WebDAV 同步、Web 远程阅读、朗读服务交互
 *
 * 线程安全：
 * - 可变字段在 Main 线程更新（通过 MainScope），ViewModel/Activity 直接读取
 * - 后台加载通过 launch(IO) / withContext(IO) 切线程，结果 post 回 Main
 * - 多章节并发加载用 Mutex + Semaphore 保护，避免竞争
 */
@Suppress("MemberVisibilityCanBePrivate")
object ReadBook : CoroutineScope by MainScope() {
    var book: Book? = null
    var callBack: CallBack? = null
    var inBookshelf = false
    var chapterList : List<BookChapter>? = null
    var chapterSize = 0
    var simulatedChapterSize = 0
    var durChapterIndex = 0
    var durChapterPos = 0
    var isLocalBook = true
    var chapterChanged = false
    var prevTextChapter: TextChapter? = null
    var curTextChapter: TextChapter? = null
    var nextTextChapter: TextChapter? = null
    var bookSource: BookSource? = null
    var msg: String? = null
    private val chapterLoadState = ChapterLoadState()
    // 每个章节独立的加载 Job，用于取消正在加载的章节
    private val chapterLoadingJobs = ConcurrentHashMap<Int, Coroutine<*>>()
    private val contentLoadStarts = ConcurrentHashMap<Int, Long>()
    // 三章节预加载互斥锁，避免同一章节重复加载
    private val prevChapterLoadingLock = Mutex()
    private val curChapterLoadingLock = Mutex()
    private val nextChapterLoadingLock = Mutex()

    /** 跳转进度前进度记录，用于「返回跳转前」功能 */
    var lastBookProgress: BookProgress? = null

    /** web端阅读进度记录，用于 web 阅读时 app 在后台接收远程进度 */
    var webBookProgress: BookProgress? = null

    var preDownloadTask: Job?
        get() = downloadState.preDownloadTask
        set(value) { downloadState.preDownloadTask = value }
    val downloadedChapters get() = downloadState.downloadedChapters
    val downloadFailChapters get() = downloadState.downloadFailChapters
    val downloadScope get() = downloadState.downloadScope
    val preDownloadSemaphore get() = downloadState.preDownloadSemaphore
    var contentProcessor: ContentProcessor? = null
    private val downloadState = ContentDownloadState()
    val executor = globalExecutor
    private val chapterStore = ReadBookChapterStore(
        getChapterCount = { bookUrl -> appDb.bookChapterDao.getChapterCount(bookUrl) },
        getChapter = { bookUrl, index -> appDb.bookChapterDao.getChapter(bookUrl, index) }
    )

    fun initData(book: Book) {
        releaseAndCancel()
        val isDiffBook = this.book?.bookUrl != book.bookUrl
        this.book = book
        if (isDiffBook){
            ReadTimeRecorder.setBook(ReadTimeRecorder.Source.READ_BOOK, book.name)
        }
        if (chapterList?.firstOrNull()?.bookUrl != book.bookUrl){
            chapterList = null
        }
        chapterSize = chapterStore.count(book, chapterList)
        simulatedChapterSize = if (book.readSimulating()) book.simulatedTotalChapterNum()
        else chapterSize
        contentProcessor = ContentProcessor.get(book)
        if (isDiffBook||durChapterIndex != book.durChapterIndex){
            durChapterIndex = book.durChapterIndex
            durChapterPos = book.durChapterPos  * (if (book.durChapterPos<0)-1 else 1)
            isLocalBook = book.isLocal
            clearTextChapter()
        }
        if (simulatedChapterSize > 0 && durChapterIndex !in 0 until simulatedChapterSize) {
            AppLog.put("initData 检测到越界 durChapterIndex=$durChapterIndex, simulatedChapterSize=$simulatedChapterSize, 已重置为 0")
            book.durChapterIndex = 0
            durChapterIndex = 0
            durChapterPos = 0
        }
        if (!isDiffBook){
            if (curTextChapter?.let { !it.isCompleted || !hasCurrentPageSize(it) } == true) {
                curTextChapter = null
            }
            if (nextTextChapter?.let { !it.isCompleted || !hasCurrentPageSize(it) } == true) {
                nextTextChapter = null
            }
            if (prevTextChapter?.let { !it.isCompleted || !hasCurrentPageSize(it) } == true) {
                prevTextChapter = null
            }
        }else{
            callBack?.upContent()
            callBack?.upPageAnim()
            lastBookProgress = null
            webBookProgress = null
            TextFile.clear()
        }

        callBack?.upMenuView()
        upWebBook(book)
        synchronized(this) {
            chapterLoadState.clear()
            contentLoadStarts.clear()
            downloadState.clear()
        }
    }

    fun upWebBook(book: Book) {
        if (book.isLocal) {
            bookSource = null
            if (book.getImageStyle().isNullOrBlank() && (book.isImage || book.isPdf)) {
                book.setImageStyle(Book.imgStyleFull)
            }
        } else {
            appDb.bookSourceDao.getBookSource(book.origin)?.let {
                bookSource = it
                if (book.getImageStyle().isNullOrBlank()) {
                    var imageStyle = it.getContentRule().imageStyle
                    if (imageStyle.isNullOrBlank() && (book.isImage || book.isPdf)) {
                        imageStyle = Book.imgStyleFull
                    }
                    book.setImageStyle(imageStyle)
                }
            } ?: let {
                bookSource = null
            }
        }
    }

    fun upReadBookConfig(book: Book) {
        val oldIndex = ReadBookConfig.styleSelect
        ReadBookConfig.isComic = book.isImage
        if (oldIndex != ReadBookConfig.styleSelect) {
            postEvent(EventBus.UP_CONFIG, arrayListOf(1, 2, 5))
            if (AppConfig.readBarStyleFollowPage) {
                postEvent(EventBus.UPDATE_READ_ACTION_BAR, true)
            }
        }
    }

    fun setProgress(progress: BookProgress) {
        val readChapterPos = progress.readChapterPos
        val targetIndex = progress.durChapterIndex.coerceAtLeast(0)
        if (targetIndex < chapterSize &&
            (durChapterIndex != targetIndex
                    || durChapterPos != readChapterPos)
        ) {
            durChapterIndex = targetIndex
            durChapterPos = readChapterPos
            clearTextChapter()
            callBack?.upContent()
            loadContent(resetPageOffset = true)
        }
    }

    //暂时保存跳转前进度
    fun saveCurrentBookProgress() {
        if (lastBookProgress != null) return //避免进度条连续跳转不能覆盖最初的进度记录
        lastBookProgress = book?.let { BookProgress(it) }
    }

    //恢复跳转前进度
    fun restoreLastBookProgress() {
        lastBookProgress?.let {
            setProgress(it)
            lastBookProgress = null
        }
    }

    fun clearTextChapter() {
        clearExpiredChapterLoadingJob(true)
        prevTextChapter?.cancelLayout()
        curTextChapter?.cancelLayout()
        nextTextChapter?.cancelLayout()
        prevTextChapter = null
        curTextChapter = null
        nextTextChapter = null
    }

    /** Called on the main thread after the actual content View's size has been applied. */
    fun onPageSizeChanged() {
        clearTextChapter()
        callBack?.upContent(resetPageOffset = false)
        if (callBack?.isInitFinish == true) {
            loadContent(resetPageOffset = false)
        }
    }

    private fun hasCurrentPageSize(chapter: TextChapter): Boolean {
        return chapter.layoutSizeGeneration == ChapterProvider.sizeGeneration
    }

    internal fun requireCurrentPageSize(chapter: TextChapter) {
        if (!hasCurrentPageSize(chapter)) {
            chapter.cancelLayout()
            throw CancellationException("Pagination belongs to an obsolete View size")
        }
    }

    fun clearSearchResult() {
        curTextChapter?.clearSearchResult()
        prevTextChapter?.clearSearchResult()
        nextTextChapter?.clearSearchResult()
    }

    fun uploadProgress(toast: Boolean = false, successAction: (() -> Unit)? = null) {
        book?.let {
            launch(IO) {
                BookProgressSyncProvider.current.uploadBookProgress(it, toast) {
                    successAction?.invoke()
                }
                ensureActive()
                it.update()
            }
        }
    }

    /**
     * 同步阅读进度
     * 如果当前进度快于服务器进度或者没有进度进行上传，如果慢与服务器进度则执行传入动作
     */
    fun syncProgress(
        newProgressAction: ((progress: BookProgress) -> Unit)? = null,
        uploadSuccessAction: (() -> Unit)? = null,
        syncSuccessAction: (() -> Unit)? = null
    ) {
        if (!AppConfig.syncBookProgress) return
        val book = book ?: return
        Coroutine.async {
            BookProgressSyncProvider.current.getBookProgressResult(book)
        }.onError {
            AppLog.put("拉取阅读进度失败", it)
        }.onSuccess { result ->
            val progress = result.getOrElse {
                AppLog.put("拉取阅读进度失败《${book.name}》\n${it.localizedMessage}", it)
                return@onSuccess
            }
            val compare = progress?.compareReadPosition(book)
            if (compare == null || compare < 0) {
                // 服务器没有进度或者进度比服务器快，上传现有进度
                Coroutine.async {
                    BookProgressSyncProvider.current.uploadBookProgress(
                        BookProgress(book),
                        uploadSuccessAction
                    )
                    book.update()
                }
            } else if (compare > 0) {
                // 进度比服务器慢，执行传入动作
                if (!BookProgressSyncProvider.current.canApplyBookProgress(
                        book,
                        progress,
                        "WebDav syncProgress",
                        ProgressCheckMode.RangeOnly
                    )
                ) {
                    return@onSuccess
                }
                newProgressAction?.invoke(progress)
            } else {
                syncSuccessAction?.invoke()
            }
        }
    }

    fun upMsg(msg: String?) {
        if (ReadBook.msg != msg) {
            ReadBook.msg = msg
            callBack?.upContent()
        }
    }

    fun moveToNextPage(): Boolean {
        var hasNextPage = false
        curTextChapter?.let {
            val nextPagePos = it.getNextPageLength(durChapterPos)
            if (nextPagePos >= 0) {
                hasNextPage = true
                it.getPage(durPageIndex)?.removePageAloudSpan()
                durChapterPos = nextPagePos
                callBack?.cancelSelect()
                callBack?.upContent()
            }
        }
        return hasNextPage
    }

    fun moveToPrevPage(): Boolean {
        var hasPrevPage = false
        curTextChapter?.let {
            val prevPagePos = it.getPrevPageLength(durChapterPos)
            if (prevPagePos >= 0) {
                hasPrevPage = true
                durChapterPos = prevPagePos
                callBack?.upContent()
            }
        }
        return hasPrevPage
    }

    fun moveToNextChapter(upContent: Boolean, upContentInPlace: Boolean = true): Boolean {
        if (durChapterIndex < simulatedChapterSize - 1) {
            durChapterPos = 0
            durChapterIndex++
            clearExpiredChapterLoadingJob()
            prevTextChapter = curTextChapter
            curTextChapter = nextTextChapter
            nextTextChapter = null
            if (curTextChapter == null) {
                AppLog.putDebug("moveToNextChapter-章节未加载,开始加载")
                if (upContentInPlace) callBack?.upContent()
                loadContent(durChapterIndex, upContent, resetPageOffset = false)
            } else if (upContent && upContentInPlace) {
                AppLog.putDebug("moveToNextChapter-章节已加载,刷新视图")
                callBack?.upContent()
            }
            loadContent(durChapterIndex.plus(1), upContent, false)
            saveRead()
            callBack?.upMenuView()
            AppLog.putDebug("moveToNextChapter-curPageChanged()")
            curPageChanged()
            return true
        } else {
            AppLog.putDebug("跳转下一章失败,没有下一章")
            return false
        }
    }

    suspend fun moveToNextChapterAwait(
        upContent: Boolean,
        upContentInPlace: Boolean = true
    ): Boolean {
        if (durChapterIndex < simulatedChapterSize - 1) {
            durChapterPos = 0
            durChapterIndex++
            clearExpiredChapterLoadingJob()
            prevTextChapter = curTextChapter
            curTextChapter = nextTextChapter
            nextTextChapter = null
            if (curTextChapter == null) {
                AppLog.putDebug("moveToNextChapter-章节未加载,开始加载")
                if (upContentInPlace) callBack?.upContentAwait()
                loadContentAwait(durChapterIndex, upContent, resetPageOffset = false)
            } else if (upContent && upContentInPlace) {
                AppLog.putDebug("moveToNextChapter-章节已加载,刷新视图")
                callBack?.upContentAwait()
            }
            loadContent(durChapterIndex.plus(1), upContent, false)
            saveRead()
            callBack?.upMenuView()
            AppLog.putDebug("moveToNextChapter-curPageChanged()")
            curPageChanged()
            return true
        } else {
            AppLog.putDebug("跳转下一章失败,没有下一章")
            return false
        }
    }

    fun moveToPrevChapter(
        upContent: Boolean,
        toLast: Boolean = true,
        upContentInPlace: Boolean = true
    ): Boolean {
        if (durChapterIndex > 0) {
            durChapterPos = if (toLast) prevTextChapter?.lastReadLength ?: Int.MAX_VALUE else 0
            durChapterIndex--
            clearExpiredChapterLoadingJob()
            nextTextChapter = curTextChapter
            curTextChapter = prevTextChapter
            prevTextChapter = null
            if (curTextChapter == null) {
                if (upContentInPlace) callBack?.upContent()
                loadContent(durChapterIndex, upContent, resetPageOffset = false)
            } else if (upContent && upContentInPlace) {
                callBack?.upContent()
            }
            loadContent(durChapterIndex.minus(1), upContent, false)
            saveRead()
            callBack?.upMenuView()
            curPageChanged()
            return true
        } else {
            return false
        }
    }

    fun skipToPage(index: Int, success: (() -> Unit)? = null) {
        val safeIndex = index.coerceAtLeast(0)
        durChapterPos = curTextChapter?.getReadLength(safeIndex) ?: safeIndex
        callBack?.upContent {
            success?.invoke()
        }
        curPageChanged()
    }

    fun setPageIndex(index: Int) {
        if (index < 0) return
        recycleRecorders(durPageIndex, index)
        durChapterPos = curTextChapter?.getReadLength(index) ?: index
        curPageChanged(true)
    }

    fun recycleRecorders(beforeIndex: Int, afterIndex: Int) {
        if (!AppConfig.optimizeRender) {
            return
        }
        executor.execute {
            val textChapter = curTextChapter ?: return@execute
            if (afterIndex > beforeIndex) {
                textChapter.getPage(afterIndex - 2)?.recycleRecorders()
            }
            if (afterIndex < beforeIndex) {
                textChapter.getPage(afterIndex + 3)?.recycleRecorders()
            }
        }
    }

    fun openChapter(
        index: Int,
        durChapterPos: Int = 0,
        upContent: Boolean = true,
        success: (() -> Unit)? = null
    ) {
        if (index in 0 until chapterSize) {
            clearTextChapter()
            if (upContent) callBack?.upContent()
            durChapterIndex = index
            ReadBook.durChapterPos = durChapterPos
            saveRead()
            loadContent(resetPageOffset = true) {
                success?.invoke()
            }
        }
    }

    /**
     * 当前页面变化
     */
    private fun curPageChanged(pageChanged: Boolean = false) {
        callBack?.pageChanged()
        curTextChapter?.let {
            it.notifyPageChanged()
            if (BaseReadAloudService.isRun && it.isCompleted) {
                val scrollPageAnim = pageAnim() == 3
                if (scrollPageAnim && pageChanged) {
                    ReadAloud.pause(appCtx)
                } else {
                    readAloud(!BaseReadAloudService.pause)
                }
            }
        }
        preDownload()
    }

    /**
     * 朗读
     */
    fun readAloud(play: Boolean = true, startPos: Int = 0) {
        book ?: return
        val textChapter = curTextChapter ?: return
        if (textChapter.isCompleted) {
            ReadAloud.play(appCtx, play, startPos = startPos)
        }
    }

    /**
     * 当前页数
     */
    val durPageIndex: Int
        get() {
            return curTextChapter?.getPageIndexByCharIndex(durChapterPos) ?: durChapterPos
        }

    val isScroll inline get() = pageAnim() == scrollPageAnim

    val contentLoadFinish get() = curTextChapter != null || msg != null

    /**
     * chapterOnDur: 0为当前页,1为下一页,-1为上一页
     */
    fun textChapter(chapterOnDur: Int = 0): TextChapter? {
        return when (chapterOnDur) {
            0 -> curTextChapter
            1 -> nextTextChapter
            -1 -> prevTextChapter
            else -> null
        }
    }

    /**
     * 加载当前章节和前后一章内容
     * @param resetPageOffset 滚动阅读是否重置滚动位置
     * @param success 当前章节加载完成回调
     */
    fun loadContent(
        resetPageOffset: Boolean,
        success: (() -> Unit)? = null
    ) {
        loadContent(durChapterIndex, resetPageOffset = resetPageOffset) {
            success?.invoke()
        }
        loadContent(durChapterIndex + 1, resetPageOffset = resetPageOffset)
        loadContent(durChapterIndex - 1, resetPageOffset = resetPageOffset)
    }

    fun loadOrUpContent() {
        if (curTextChapter == null) {
            loadContent(durChapterIndex)
        } else {
            callBack?.upContent()
        }
        if (nextTextChapter == null) {
            loadContent(durChapterIndex + 1)
        }
        if (prevTextChapter == null) {
            loadContent(durChapterIndex - 1)
        }
    }

    /**
     * 加载章节内容
     * @param index 章节序号
     * @param upContent 是否更新视图
     * @param resetPageOffset 滚动阅读是否重置滚动位置
     * @param success 加载完成回调
     */
    fun loadContent(
        index: Int,
        upContent: Boolean = true,
        resetPageOffset: Boolean = false,
        success: (() -> Unit)? = null
    ) {
        Coroutine.async {
            val book = book ?: return@async
            val chapter = getChapter(book, index) ?: return@async
            if (addLoading(index)) {
                contentLoadStarts[index] = ReaderPerformance.now()
                ReaderPerformance.trace(
                    "android.read.contentCache",
                    20,
                    "index=$index"
                ) {
                    BookHelp.getContent(book, chapter)
                }?.let {
                    contentLoadFinish(
                        book,
                        chapter,
                        it,
                        upContent,
                        resetPageOffset,
                        success = success
                    )
                } ?: download(
                    downloadScope,
                    chapter,
                    resetPageOffset
                )
            }
        }.onError {
            contentLoadStarts.remove(index)
            if (it !is CancellationException) {
                markLoadingFailed(index)
            }
            AppLog.put("加载正文出错\n${it.localizedMessage}")
        }
    }

    suspend fun loadContentAwait(
        index: Int,
        upContent: Boolean = true,
        resetPageOffset: Boolean = false,
        success: (() -> Unit)? = null
    ) = withContext(IO) {
        val book = book ?: return@withContext
        val chapter = getChapter(book, index) ?: return@withContext
        if (addLoading(index)) {
            contentLoadStarts[index] = ReaderPerformance.now()
            var failed = false
            try {
                val content = ReaderPerformance.trace(
                    "android.read.contentCache",
                    20,
                    "index=$index"
                ) {
                    BookHelp.getContent(book, chapter)
                } ?: downloadAwait(chapter)
                contentLoadFinishAwait(book, chapter, content, upContent, resetPageOffset)
                success?.invoke()
            } catch (e: Exception) {
                contentLoadStarts.remove(index)
                if (e is CancellationException) throw e
                failed = true
                markLoadingFailed(index)
                AppLog.put("加载正文出错\n${e.localizedMessage}")
            } finally {
                if (!failed) {
                    removeLoading(index)
                }
            }
        }
    }

    /**
     * 下载正文
     */
    private suspend fun downloadIndex(index: Int) {
        if (index < 0) return
        if (index > chapterSize - 1)return
        val book = book ?: return
        val chapter = getChapter(book, index) ?: return
        if (BookHelp.hasContent(book, chapter)) {
            downloadState.markDownloaded(chapter.index)
        } else {
            delay(1000)
            if (addLoading(index)) {
                contentLoadStarts[index] = ReaderPerformance.now()
                download(downloadScope, chapter, false, preDownloadSemaphore)
            }
        }
    }

    /**
     * 下载正文
     */
    private fun download(
        scope: CoroutineScope,
        chapter: BookChapter,
        resetPageOffset: Boolean,
        semaphore: Semaphore? = null,
        success: (() -> Unit)? = null
    ) {
        val book = book ?: return removeLoading(chapter.index)
        val bookSource = bookSource
        if (bookSource != null) {
            CacheBook.getOrCreate(bookSource, book).download(scope, chapter, semaphore)
        } else {
            val msg = if (book.isLocal) "无内容" else "没有书源"
            contentLoadFinish(
                book,
                chapter,
                "加载正文失败\n$msg",
                resetPageOffset = resetPageOffset,
                success = success
            )
        }
    }

    private suspend fun downloadAwait(chapter: BookChapter): String {
        val book = book ?: return "加载正文失败\n书籍未加载"
        val bookSource = bookSource
        if (bookSource != null) {
            return ReaderPerformance.trace(
                "android.read.downloadAwait",
                50,
                "index=${chapter.index}"
            ) {
                CacheBook.getOrCreate(bookSource, book).downloadAwait(chapter)
            }
        } else {
            val msg = if (book.isLocal) "无内容" else "没有书源"
            return "加载正文失败\n$msg"
        }
    }

    private fun getChapter(book: Book, index: Int): BookChapter? {
        return chapterStore.get(book, index, chapterList, chapterSize)
    }

    @Synchronized
    private fun addLoading(index: Int): Boolean {
        return chapterLoadState.tryStart(index)
    }

    @Synchronized
    fun removeLoading(index: Int) {
        chapterLoadState.finish(index)
    }

    @Synchronized
    private fun markLoadingFailed(index: Int) {
        contentLoadStarts.remove(index)
        chapterLoadState.fail(index)
    }

    /**
     * 内容加载完成
     */
    @Synchronized
    fun contentLoadFinish(
        book: Book,
        chapter: BookChapter,
        content: String,
        upContent: Boolean = true,
        resetPageOffset: Boolean,
        canceled: Boolean = false,
        success: (() -> Unit)? = null
    ) {
        removeLoading(chapter.index)
        contentLoadStarts.remove(chapter.index)?.let {
            ReaderPerformance.logElapsed(
                "android.read.contentReady",
                it,
                50,
                "index=${chapter.index}, current=${chapter.index == durChapterIndex}"
            )
        }
        if (canceled || chapter.index !in durChapterIndex - 1..durChapterIndex + 1) {
            return
        }
        chapterLoadingJobs[chapter.index]?.cancel()
        val job = Coroutine.async(this, start = CoroutineStart.LAZY) {
            val layoutStart = ReaderPerformance.now()
            try {
                val textChapter = processContent(book, chapter, content)
                when (val offset = chapter.index - durChapterIndex) {
                    0 -> curChapterLoadingLock.withLock {
                        withContext(Main) {
                            ensureActive()
                            requireCurrentPageSize(textChapter)
                            curTextChapter = textChapter
                        }
                        callBack?.upMenuView()
                        var available = false
                        for (page in textChapter.layoutChannel) {
                            requireCurrentPageSize(textChapter)
                            val index = page.index
                            if (!available && page.containPos(durChapterPos)) {
                                if (upContent) {
                                    callBack?.upContent(offset, resetPageOffset)
                                }
                                available = true
                            }
                            if (upContent && isScroll) {
                                if (max(index - 3, 0) < durPageIndex) {
                                    callBack?.upContent(offset, false)
                                }
                            }
                            callBack?.onLayoutPageCompleted(index, page)
                        }
                        requireCurrentPageSize(textChapter)
                        if (upContent) callBack?.upContent(offset, !available && resetPageOffset)
                        curPageChanged()
                        callBack?.contentLoadFinish()
                    }

                    -1 -> prevChapterLoadingLock.withLock {
                        withContext(Main) {
                            ensureActive()
                            requireCurrentPageSize(textChapter)
                            prevTextChapter = textChapter
                        }
                        textChapter.layoutChannel.receiveAsFlow().collect()
                        requireCurrentPageSize(textChapter)
                        if (upContent) callBack?.upContent(offset, resetPageOffset)
                    }

                    1 -> nextChapterLoadingLock.withLock {
                        withContext(Main) {
                            ensureActive()
                            requireCurrentPageSize(textChapter)
                            nextTextChapter = textChapter
                        }
                        for (page in textChapter.layoutChannel) {
                            requireCurrentPageSize(textChapter)
                            if (page.index > 1) {
                                continue
                            }
                            if (upContent) callBack?.upContent(offset, resetPageOffset)
                        }
                    }
                }
            } finally {
                ReaderPerformance.logElapsed(
                    "android.read.layoutReady",
                    layoutStart,
                    20,
                    "index=${chapter.index}, offset=${chapter.index - durChapterIndex}"
                )
            }

            return@async
        }.onError {
            if (it is CancellationException) {
                return@onError
            }
            AppLog.put("ChapterProvider ERROR", it)
            appCtx.toastOnUi("ChapterProvider ERROR:\n${it.stackTraceStr}")
        }.onSuccess {
            success?.invoke()
        }
        chapterLoadingJobs[chapter.index] = job
        job.start()
    }

    suspend fun contentLoadFinishAwait(
        book: Book,
        chapter: BookChapter,
        content: String,
        upContent: Boolean = true,
        resetPageOffset: Boolean
    ) {
        removeLoading(chapter.index)
        contentLoadStarts.remove(chapter.index)?.let {
            ReaderPerformance.logElapsed(
                "android.read.contentReady",
                it,
                50,
                "index=${chapter.index}, current=${chapter.index == durChapterIndex}"
            )
        }
        if (chapter.index !in durChapterIndex - 1..durChapterIndex + 1) {
            return
        }
        kotlin.runCatching {
            val layoutStart = ReaderPerformance.now()
            try {
                val textChapter = processContent(book, chapter, content)
                when (val offset = chapter.index - durChapterIndex) {
                    0 -> {
                        withContext(Main) {
                            ensureActive()
                            requireCurrentPageSize(textChapter)
                            curTextChapter?.cancelLayout()
                            curTextChapter = textChapter
                        }
                        callBack?.upMenuView()
                        var available = false
                        for (page in textChapter.layoutChannel) {
                            requireCurrentPageSize(textChapter)
                            val index = page.index
                            if (!available && page.containPos(durChapterPos)) {
                                if (upContent) {
                                    callBack?.upContent(offset, resetPageOffset)
                                }
                                available = true
                            }
                            if (upContent && isScroll) {
                                if (max(index - 3, 0) < durPageIndex) {
                                    callBack?.upContent(offset, false)
                                }
                            }
                            callBack?.onLayoutPageCompleted(index, page)
                        }
                        requireCurrentPageSize(textChapter)
                        if (upContent) callBack?.upContent(offset, !available && resetPageOffset)
                        curPageChanged()
                        callBack?.contentLoadFinish()
                    }

                    -1 -> {
                        withContext(Main) {
                            ensureActive()
                            requireCurrentPageSize(textChapter)
                            prevTextChapter?.cancelLayout()
                            prevTextChapter = textChapter
                        }
                        textChapter.layoutChannel.receiveAsFlow().collect()
                        requireCurrentPageSize(textChapter)
                        if (upContent) callBack?.upContent(offset, resetPageOffset)
                    }

                    1 -> {
                        withContext(Main) {
                            ensureActive()
                            requireCurrentPageSize(textChapter)
                            nextTextChapter?.cancelLayout()
                            nextTextChapter = textChapter
                        }
                        for (page in textChapter.layoutChannel) {
                            requireCurrentPageSize(textChapter)
                            if (page.index > 1) {
                                continue
                            }
                            if (upContent) callBack?.upContent(offset, resetPageOffset)
                        }
                    }
                }
            } finally {
                ReaderPerformance.logElapsed(
                    "android.read.layoutReady",
                    layoutStart,
                    20,
                    "index=${chapter.index}, offset=${chapter.index - durChapterIndex}"
                )
            }
        }.onFailure {
            if (it is CancellationException) {
                throw it
            }
            AppLog.put("ChapterProvider ERROR", it)
            appCtx.toastOnUi("ChapterProvider ERROR:\n${it.stackTraceStr}")
        }
    }

    private suspend fun CoroutineScope.processContent(
        book: Book, chapter: BookChapter, content: String
    ): TextChapter {
        val contentProcessor = ContentProcessor.get(book.name, book.origin)
        val displayTitle = chapter.getDisplayTitle(
            contentProcessor.getTitleReplaceRules(),
            book.getUseReplaceRule()
        )
        val contents = contentProcessor
            .getContent(book, chapter, content, includeTitle = false)
        ensureActive()
        // Capture all layout dimensions on the same thread that updates ChapterProvider.
        // Keep the outer loading scope: withContext must not wait for the whole pagination job.
        val loadingScope = this
        return withContext(Main) {
            ChapterProvider.getTextChapterAsync(
                loadingScope, book, chapter, displayTitle, contents, simulatedChapterSize
            )
        }
    }

    @Synchronized
    fun upToc() {
        val bookSource = bookSource ?: return
        val book = book ?: return
        if (!book.canUpdate) return
        if (chapterSize - durChapterIndex - 1 >= 3) return
        if (System.currentTimeMillis() - book.lastCheckTime < 600000) return
        book.lastCheckTime = System.currentTimeMillis()
        val oldBook = book.copy()
        Coroutine.async(this) { WebBook.getChapterListAwait(bookSource, book).getOrThrow() }
        .onSuccess { cList ->
            ensureActive()
            if (cList.size > chapterSize) {
                if (oldBook.bookUrl == book.bookUrl) {
                    appDb.bookDao.update(book)
                } else {
                    appDb.bookDao.replace(oldBook, book)
                    BookHelp.updateCacheFolder(oldBook, book)
                }
                appDb.bookChapterDao.delByBook(oldBook.bookUrl)
                appDb.bookChapterDao.insert(*cList.toTypedArray())
                onChapterListUpdated(book, false)
                nextTextChapter ?: loadContent(durChapterIndex + 1)
            }
        }
    }

    fun pageAnim(): Int {
        val anim = ReadBookConfig.pageAnim
        return if (book?.getImageStyle()
                .equals(Book.imgStyleSingle, true) && anim == scrollPageAnim
        ) {
            PageAnim.coverPageAnim
        } else {
            anim
        }
    }

    fun setCharset(charset: String) {
        book?.let {
            it.charset = charset
            callBack?.loadChapterList(it)
        }
    }

    fun saveRead() {
        val book = book ?: return
        // 同步更新 book 进度字段, 避免与同在 onPause 启动的 syncProgress 异步竞争,
        // 后者网络返回后会读取 book.durChapterPos, 若 executor 排队未执行则读到旧值并上传, 导致恢复后少一页
        book.durChapterIndex = durChapterIndex
        book.durChapterPos = durChapterPos * (if (curTextChapter != null && curTextChapter!!.isLastIndex(durPageIndex)) -1 else 1)
        executor.execute {
            kotlin.runCatching {
                appDb.bookChapterDao.getChapter(book.bookUrl, book.durChapterIndex)?.let {
                    book.durChapterTitle = it.getDisplayTitle(
                        ContentProcessor.get(book.name, book.origin).getTitleReplaceRules(),
                        book.getUseReplaceRule()
                    )
                }
                book.saveRead()
            }.onFailure {
                AppLog.put("保存书籍阅读进度信息出错\n$it", it)
            }
        }
    }

    /**
     * 预下载
     */
    private fun preDownload() {
        if (book?.isLocal == true) return
        executor.execute {
            if (AppConfig.preDownloadNum < 2) {
                upToc()
                return@execute
            }
            preDownloadTask?.cancel()
            preDownloadTask = launch(IO) {
                //预下载
                launch {
                    val maxChapterIndex =
                        min(durChapterIndex + AppConfig.preDownloadNum, chapterSize)
                    for (i in durChapterIndex.plus(2)..maxChapterIndex) {
                        if (downloadState.isDownloaded(i)) continue
                        if (downloadState.isFailedTooMany(i)) continue
                        downloadIndex(i)
                    }
                }
                launch {
                    val minChapterIndex = durChapterIndex - min(5, AppConfig.preDownloadNum)
                    for (i in durChapterIndex.minus(2) downTo minChapterIndex) {
                        if (downloadState.isDownloaded(i)) continue
                        if (downloadState.isFailedTooMany(i)) continue
                        downloadIndex(i)
                    }
                }
            }
        }
    }

    fun cancelPreDownloadTask() {
        if (contentLoadFinish) {
            downloadState.cancelPreDownload()
        }
    }

    fun onChapterListUpdated(newBook: Book, loadContent: Boolean = true) {
        if (newBook.isSameNameAuthor(book)) {
            book = newBook
            chapterSize = newBook.totalChapterNum
            simulatedChapterSize = newBook.simulatedTotalChapterNum()
            if (simulatedChapterSize > 0 && durChapterIndex > simulatedChapterSize - 1) {
                durChapterIndex = simulatedChapterSize - 1
            }
            callBack?.upMenuView()
            if (callBack == null) {
                clearTextChapter()
            } else if (loadContent) {
                loadContent(true)
            }
        }
    }

    private fun clearExpiredChapterLoadingJob(clearAll: Boolean = false) {
        val iterator = chapterLoadingJobs.iterator()
        while (iterator.hasNext()) {
            val (index, job) = iterator.next()
            if (clearAll || index !in durChapterIndex - 1..durChapterIndex + 1) {
                job.cancel()
                iterator.remove()
            }
        }
    }

    /**
     * 注册回调
     */
    fun register(cb: CallBack) {
        callBack?.notifyBookChanged()
        callBack = cb
    }

    /**
     * 取消注册回调
     */
    fun unregister(cb: CallBack) {
        if (callBack === cb) {
            callBack = null
        }
        releaseAndCancel()
    }

    private fun releaseAndCancel() {
        msg = null
        downloadState.cancelPreDownload()
        coroutineContext.cancelChildren()
        ImageProvider.clear()
        clearExpiredChapterLoadingJob(true)
        contentLoadStarts.clear()
        if (!CacheBookService.isRun) {
            CacheBook.close()
        }
    }

    interface CallBack : LayoutProgressListener {
        val isInitFinish: Boolean

        fun upMenuView()

        fun loadChapterList(book: Book)

        fun upContent(
            relativePosition: Int = 0,
            resetPageOffset: Boolean = true,
            success: (() -> Unit)? = null
        )

        suspend fun upContentAwait(
            relativePosition: Int = 0,
            resetPageOffset: Boolean = true,
            success: (() -> Unit)? = null
        )

        fun pageChanged()

        fun contentLoadFinish()

        fun upPageAnim(upRecorder: Boolean = false)

        fun notifyBookChanged()

        fun sureNewProgress(progress: BookProgress)

        fun cancelSelect()
    }

}
