package io.legado.app.api.controller

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.core.graphics.drawable.toBitmap
import com.bumptech.glide.Glide
import io.legado.app.api.ReturnData
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.entities.BookSource
import io.legado.app.help.BookProgressSyncProvider
import io.legado.app.help.CacheManager
import io.legado.app.help.AppWebDav
import io.legado.app.help.ProgressCheckMode
import io.legado.app.help.WebBookProgressSyncCoordinator
import io.legado.app.help.WebBookProgressUploadScheduler
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.AppConfig
import io.legado.app.help.glide.ImageLoader
import io.legado.app.model.BookCover
import io.legado.app.model.ImageProvider
import io.legado.app.model.ReadBook
import io.legado.app.model.fileBook.FileBook
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.GSON
import io.legado.app.utils.cnCompare
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.stackTraceStr
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import splitties.init.appCtx
import java.io.File
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit

/**
 * Web API 控制器 — 图书/章节/进度相关接口。
 *
 * 注意: 由于是 object（单例），getImg 中缓存的 book/bookSource/bookUrl
 * 在多请求并发时可能被覆盖。当前使用场景为单用户顺序访问，暂未引入并发防护。
 */
object BookController {

    private lateinit var book: Book
    private var bookSource: BookSource? = null
    private var bookUrl: String = ""
    private val defaultCoverCache by lazy { WeakHashMap<Drawable, Bitmap>() }

    private data class WebBookProgressPayload(
        val bookUrl: String? = null,
        val name: String,
        val author: String,
        val durChapterIndex: Int,
        val durChapterPos: Int,
        val durChapterTime: Long,
        val durChapterTitle: String?
    ) {
        fun toBookProgress() = BookProgress(
            name,
            author,
            durChapterIndex,
            durChapterPos,
            durChapterTime,
            durChapterTitle
        )
    }

    private data class SyncBookProgressRequest(val bookUrl: String)

    private data class SyncBookProgressResponse(
        val progress: BookProgress,
        val remoteApplied: Boolean,
        val warning: String?
    )

    /** 所有分组（id + name） */
    val groups: ReturnData
        get() {
            val returnData = ReturnData()
            return returnData.setData(appDb.bookGroupDao.all)
        }

    /**
     * 按分组获取书籍列表，并按书架排序规则排序。
     */
    fun getBooks(parameters: Map<String, List<String>>): ReturnData {
        val groupId = parameters["groupId"]?.firstOrNull()?.toLongOrNull()
        val books = if(groupId==null)appDb.bookDao.all else runBlocking{appDb.bookDao.flowByGroup(groupId).first()}
        return if (books.isEmpty()) {
            ReturnData().setErrorMsg("未找到")
        }else {
            val sorted = sortBooks(books)
            ReturnData().setData(sorted)
        }
    }

    /** 按书架配置排序 */
    private fun sortBooks(books: List<Book>): List<Book> = when (AppConfig.bookshelfSort) {
        1 -> books.sortedByDescending { it.latestChapterTime }
        2 -> books.sortedWith { o1, o2 -> o1.name.cnCompare(o2.name) }
        3 -> books.sortedBy { it.order }
        else -> books.sortedByDescending { it.durChapterTime }
    }
    /**
     * 获取封面
     */
    fun getCover(parameters: Map<String, List<String>>): ReturnData {
        val returnData = ReturnData()
        val coverPath = parameters["path"]?.firstOrNull()
        val ftBitmap = ImageLoader.loadBitmap(appCtx, coverPath)
            .override(84, 112)
            .centerCrop()
            .submit()
        return try {
            returnData.setData(ftBitmap.get(3, TimeUnit.SECONDS))
        } catch (e: Exception) {
            try {
                val defaultBitmap = defaultCoverCache.getOrPut(BookCover.defaultDrawable) {
                    Glide.with(appCtx)
                        .asBitmap()
                        .load(BookCover.defaultDrawable.toBitmap())
                        .override(84, 112)
                        .centerCrop()
                        .submit()
                        .get()
                }
                returnData.setData(defaultBitmap)
            } catch (e: Exception) {
                returnData.setErrorMsg(e.localizedMessage ?: "getCover error")
            }
        }
    }

    /**
     * 获取正文图片
     */
    fun getImg(parameters: Map<String, List<String>>): ReturnData {
        val returnData = ReturnData()
        val bookUrl = parameters["url"]?.firstOrNull()
            ?: return returnData.setErrorMsg("bookUrl为空")
        val src = parameters["path"]?.firstOrNull()
            ?: return returnData.setErrorMsg("图片链接为空")
        val width = parameters["width"]?.firstOrNull()?.toIntOrNull() ?: 640
        if (this.bookUrl != bookUrl) {
            this.book = appDb.bookDao.getBook(bookUrl)
                ?: return returnData.setErrorMsg("bookUrl不对")
            this.bookSource = appDb.bookSourceDao.getBookSource(book.origin)
        }
        this.bookUrl = bookUrl
        val bitmap = runBlocking {
            ImageProvider.cacheImage(book, src, bookSource)
            ImageProvider.getImage(book, src, width)
        }
        return returnData.setData(bitmap)
    }

    /**
     * 更新目录
     */
    fun refreshToc(parameters: Map<String, List<String>>): ReturnData {
        val returnData = ReturnData()
        try {
            val bookUrl = parameters["url"]?.firstOrNull()
            if (bookUrl.isNullOrEmpty()) {
                return returnData.setErrorMsg("参数url不能为空，请指定书籍地址")
            }
            val book = appDb.bookDao.getBook(bookUrl)
                ?: return returnData.setErrorMsg("未在数据库找到对应书籍，请先添加")
            if (book.isLocal) {
                val toc = FileBook.getChapterList(book)
                appDb.bookChapterDao.delByBook(book.bookUrl)
                appDb.bookChapterDao.insert(*toc.toTypedArray())
                appDb.bookDao.update(book)
                return returnData.setData(toc)
            } else {
                val bookSource = appDb.bookSourceDao.getBookSource(book.origin)
                    ?: return returnData.setErrorMsg("未找到对应书源,请换源")
                val toc = runBlocking {
                    if (book.tocUrl.isBlank()) {
                        WebBook.getBookInfoAwait(bookSource, book)
                    }
                    WebBook.getChapterListAwait(bookSource, book).getOrThrow()
                }
                appDb.bookChapterDao.delByBook(book.bookUrl)
                appDb.bookChapterDao.insert(*toc.toTypedArray())
                appDb.bookDao.update(book)
                return returnData.setData(toc)
            }
        } catch (e: Exception) {
            return returnData.setErrorMsg(e.localizedMessage ?: "refresh toc error")
        }
    }

    /**
     * 获取目录
     */
    fun getChapterList(parameters: Map<String, List<String>>): ReturnData {
        val bookUrl = parameters["url"]?.firstOrNull()
        val returnData = ReturnData()
        if (bookUrl.isNullOrEmpty()) {
            return returnData.setErrorMsg("参数url不能为空，请指定书籍地址")
        }
        val chapterList = appDb.bookChapterDao.getChapterList(bookUrl)
        if (chapterList.isEmpty()) {
            return refreshToc(parameters)
        }
        return returnData.setData(chapterList)
    }

    /**
     * 获取正文
     */
    fun getBookContent(parameters: Map<String, List<String>>): ReturnData {
        val bookUrl = parameters["url"]?.firstOrNull()
        val indexStr = parameters["index"]?.firstOrNull()
        val index = indexStr?.toIntOrNull()
        val returnData = ReturnData()
        if (bookUrl.isNullOrEmpty()) {
            return returnData.setErrorMsg("参数url不能为空，请指定书籍地址")
        }
        if (indexStr.isNullOrBlank()) {
            return returnData.setErrorMsg("参数index不能为空, 请指定目录序号")
        }
        if (index == null) {
            return returnData.setErrorMsg("参数index格式不正确")
        }
        if (index < 0) {
            return returnData.setErrorMsg("参数index不能为负数")
        }
        val book = appDb.bookDao.getBook(bookUrl)
        val chapter = runBlocking {
            var chapter = appDb.bookChapterDao.getChapter(bookUrl, index)
            var wait = 0
            while (chapter == null && wait < 30) {
                delay(1000)
                chapter = appDb.bookChapterDao.getChapter(bookUrl, index)
                wait++
            }
            chapter
        }
        if (book == null || chapter == null) {
            return returnData.setErrorMsg("未找到")
        }
        var content: String? = BookHelp.getContent(book, chapter)
        if (content != null) {
            val contentProcessor = ContentProcessor.get(book.name, book.origin)
            content = runBlocking {
                contentProcessor.getContent(book, chapter, content, includeTitle = false)
                    .toString()
            }
            return returnData.setData(content)
        }
        val bookSource = appDb.bookSourceDao.getBookSource(book.origin)
            ?: return returnData.setErrorMsg("未找到书源")
        try {
            content = runBlocking {
                WebBook.getContentAwait(bookSource, book, chapter).let {
                    val contentProcessor = ContentProcessor.get(book.name, book.origin)
                    contentProcessor.getContent(book, chapter, it, includeTitle = false)
                        .toString()
                }
            }
            returnData.setData(content)
        } catch (e: Exception) {
            returnData.setErrorMsg(e.stackTraceStr)
        }
        return returnData
    }

    /**
     * 保存书籍
     */
    suspend fun saveBook(postData: String?): ReturnData {
        val returnData = ReturnData()
        GSON.fromJsonObject<Book>(postData).getOrNull()?.let { book ->
            BookProgressSyncProvider.current.uploadBookProgress(book)
            book.save()
            return returnData.setData("")
        }
        return returnData.setErrorMsg("格式不对")
    }

    /**
     * 删除书籍
     */
    fun deleteBook(postData: String?): ReturnData {
        val returnData = ReturnData()
        GSON.fromJsonObject<Book>(postData).getOrNull()?.let { book ->
            book.delete()
            return returnData.setData("")
        }
        return returnData.setErrorMsg("格式不对")
    }

    /**
     * 保存进度
     */
    suspend fun syncBookProgress(postData: String?): ReturnData {
        val returnData = ReturnData()
        val request = GSON.fromJsonObject<SyncBookProgressRequest>(postData)
            .onFailure { it.printOnDebug() }
            .getOrNull() ?: return returnData.setErrorMsg("格式不对")
        val book = appDb.bookDao.getBook(request.bookUrl)
            ?: return returnData.setErrorMsg("未找到书籍")
        var finalProgress: BookProgress? = null
        val syncResult = WebBookProgressSyncCoordinator.withBook(book.name, book.author) {
            val localBook = appDb.bookDao.getBook(request.bookUrl)
                ?: return@withBook returnData.setErrorMsg("未找到书籍")
            if (!AppConfig.syncBookProgress || !AppWebDav.isOk) {
                val progress = BookProgress(localBook)
                finalProgress = progress
                return@withBook returnData.setData(
                    SyncBookProgressResponse(progress, false, null)
                )
            }
            val remoteResult = BookProgressSyncProvider.current.getBookProgressResult(localBook)
            val remote = remoteResult.getOrNull()
            var remoteApplied = false
            if (remote != null &&
                BookProgressSyncProvider.current.canApplyBookProgress(
                    localBook,
                    remote,
                    "Web syncBookProgress",
                    ProgressCheckMode.RangeOnly
                ) && remote.compareReadPosition(localBook) > 0
            ) {
                localBook.durChapterIndex = remote.durChapterIndex
                localBook.durChapterPos = remote.readChapterPos
                localBook.durChapterTitle = remote.durChapterTitle
                localBook.durChapterTime = remote.durChapterTime
                localBook.syncTime = System.currentTimeMillis()
                appDb.bookDao.update(localBook)
                remoteApplied = true
            }
            val warning = if (remoteResult.isFailure) {
                "WebDAV进度同步失败，已使用手机本地进度"
            } else {
                null
            }
            val progress = BookProgress(localBook)
            finalProgress = progress
            return@withBook returnData.setData(
                SyncBookProgressResponse(progress, remoteApplied, warning)
            )
        }
        if (AppConfig.syncBookProgress && AppWebDav.isOk) {
            finalProgress?.let { WebBookProgressUploadScheduler.shared.retryOnOpen(it) }
        }
        return syncResult
    }

    /**
     * 保存 Web 阅读进度到 Room；WebDAV 上传由独立调度器处理。
     */
    suspend fun saveBookProgress(postData: String?, flush: Boolean = false): ReturnData {
        val returnData = ReturnData()
        val payload = GSON.fromJsonObject<WebBookProgressPayload>(postData)
            .onFailure { it.printOnDebug() }
            .getOrNull() ?: return returnData.setErrorMsg("格式不对")
        val bookProgress = payload.toBookProgress()
        if (bookProgress.durChapterIndex < 0) {
            return returnData.setErrorMsg("durChapterIndex 不能为负数")
        }
        if (bookProgress.durChapterPos == Int.MIN_VALUE) {
            return returnData.setErrorMsg("durChapterPos 非法")
        }
        var finalProgress: BookProgress? = null
        var changed = false
        val saveResult = WebBookProgressSyncCoordinator.withBook(bookProgress.name, bookProgress.author) {
            val book = payload.bookUrl?.let(appDb.bookDao::getBook)
                ?: appDb.bookDao.getBook(bookProgress.name, bookProgress.author)
                ?: return@withBook returnData.setErrorMsg("格式不对")
            val chapterCount = appDb.bookChapterDao.getChapterCount(book.bookUrl)
            if (chapterCount <= 0) {
                return@withBook returnData.setErrorMsg("未找到章节")
            }
            val finalIndex = bookProgress.durChapterIndex.coerceIn(0, chapterCount - 1)
            val finalPos = bookProgress.readChapterPos
            changed = book.durChapterIndex != finalIndex ||
                    kotlin.math.abs(book.durChapterPos) != finalPos ||
                    book.durChapterTitle != bookProgress.durChapterTitle
            if (changed) {
                book.durChapterIndex = finalIndex
                book.durChapterPos = finalPos
                book.durChapterTitle = bookProgress.durChapterTitle
                book.durChapterTime = bookProgress.durChapterTime
                appDb.bookDao.update(book)
            }
            ReadBook.book?.let {
                if (it.bookUrl == book.bookUrl) {
                    ReadBook.webBookProgress = BookProgress(book)
                }
            }
            finalProgress = BookProgress(book)
            return@withBook returnData.setData("")
        }
        finalProgress?.takeIf { saveResult.isSuccess && AppConfig.syncBookProgress && AppWebDav.isOk }
            ?.let { progress ->
                if (changed) {
                    WebBookProgressUploadScheduler.shared.enqueue(progress)
                }
                if (flush) {
                    WebBookProgressUploadScheduler.shared.flush(progress)
                }
            }
        return saveResult
    }

    /**
     * 添加本地书籍
     */
    fun addLocalBook(
        parameters: Map<String, List<String>>,
        files: Map<String, String>
    ): ReturnData {
        val returnData = ReturnData()
        val fileName = parameters["fileName"]?.firstOrNull()
            ?: return returnData.setErrorMsg("fileName 不能为空")
        val fileData = files["fileData"]
            ?: return returnData.setErrorMsg("fileData 不能为空")
        kotlin.runCatching {
            val uri = FileBook.saveBookFile(File(fileData).inputStream(), fileName)
            FileBook.importLocalFile(uri)
        }.onFailure {
            return when (it) {
                is SecurityException -> returnData.setErrorMsg("需重新设置书籍保存位置!")
                else -> returnData.setErrorMsg("保存书籍错误\n${it.localizedMessage}")
            }
        }
        return returnData.setData(true)
    }

    /**
     * 保存web阅读界面配置
     */
    fun saveWebReadConfig(postData: String?): ReturnData {
        val returnData = ReturnData()
        postData?.let {
            CacheManager.put("webReadConfig", postData)
        } ?: CacheManager.delete("webReadConfig")
        return returnData.setData("")
    }

    /**
     * 获取web阅读界面配置
     */
    fun getWebReadConfig(): ReturnData {
        val returnData = ReturnData()
        val data = CacheManager.get("webReadConfig")
            ?: return returnData.setErrorMsg("没有配置")
        return returnData.setData(data)
    }

}
