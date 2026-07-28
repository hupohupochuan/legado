package io.legado.app.service

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppWebDav
import io.legado.app.help.book.ExportMissingChapterPolicy
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.createBookExportPlan
import io.legado.app.help.book.getExportFileName
import io.legado.app.help.book.isLocalModified
import io.legado.app.help.book.isPlainLocalBook
import io.legado.app.help.book.parseExportChapterScope
import io.legado.app.help.config.AppConfig
import io.legado.app.model.ReadBook
import io.legado.app.model.fileBook.FileBook
import io.legado.app.ui.book.cache.CacheActivity
import io.legado.app.utils.FileDoc
import io.legado.app.utils.FileUtils
import io.legado.app.utils.HtmlFormatter
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.activityPendingIntent
import io.legado.app.utils.cnCompare
import io.legado.app.utils.createFileIfNotExist
import io.legado.app.utils.delete
import io.legado.app.utils.find
import io.legado.app.utils.list
import io.legado.app.utils.mapAsync
import io.legado.app.utils.mapAsyncIndexed
import io.legado.app.utils.openOutputStream
import io.legado.app.utils.postEvent
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.writeFile
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.ag2s.epublib.domain.Author
import me.ag2s.epublib.domain.Date
import me.ag2s.epublib.domain.EpubBook
import me.ag2s.epublib.domain.FileResourceProvider
import me.ag2s.epublib.domain.LazyResource
import me.ag2s.epublib.domain.LazyResourceProvider
import me.ag2s.epublib.domain.Metadata
import me.ag2s.epublib.domain.Resource
import me.ag2s.epublib.domain.TOCReference
import me.ag2s.epublib.epub.EpubWriter
import me.ag2s.epublib.epub.EpubWriterProcessor
import me.ag2s.epublib.util.ResourceUtil
import splitties.init.appCtx
import splitties.systemservices.notificationManager
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * 导出书籍服务
 */
class ExportBookService : BaseService() {

    companion object {
        val exportProgress = ConcurrentHashMap<String, Int>()
        val exportMsg = ConcurrentHashMap<String, String>()
        const val EXTRA_MISSING_CHAPTER_POLICY = "missingChapterPolicy"
        const val EXTRA_USE_REPLACE = "useReplace"
    }

    data class ExportConfig(
        val path: String,
        val type: String,
        val epubSize: Int = 1,
        val epubScope: String? = null,
        val missingChapterPolicy: ExportMissingChapterPolicy =
            ExportMissingChapterPolicy.RequireComplete,
        val useReplace: Boolean = false
    )

    private val groupKey = "${appCtx.packageName}.exportBook"
    private val waitExportBooks = linkedMapOf<String, ExportConfig>()
    private var exportJob: Job? = null
    private var notificationContentText = appCtx.getString(R.string.service_starting)


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            IntentAction.start -> kotlin.runCatching {
                val bookUrl = intent.getStringExtra("bookUrl")!!
                if (!exportProgress.contains(bookUrl)) {
                    val exportConfig = ExportConfig(
                        path = intent.getStringExtra("exportPath")!!,
                        type = intent.getStringExtra("exportType")!!,
                        epubSize = intent.getIntExtra("epubSize", 1),
                        epubScope = intent.getStringExtra("epubScope"),
                        missingChapterPolicy = ExportMissingChapterPolicy.from(
                            intent.getStringExtra(EXTRA_MISSING_CHAPTER_POLICY)
                        ),
                        useReplace = intent.getBooleanExtra(
                            EXTRA_USE_REPLACE,
                            AppConfig.exportUseReplace
                        )
                    )
                    waitExportBooks[bookUrl] = exportConfig
                    exportMsg[bookUrl] = getString(R.string.export_wait)
                    postEvent(EventBus.EXPORT_BOOK, bookUrl)
                    export()
                }
            }.onFailure {
                toastOnUi(it.localizedMessage)
            }

            IntentAction.stop -> {
                notificationManager.cancel(NotificationId.ExportBook)
                stopSelf()
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        super.onDestroy()
        exportProgress.clear()
        exportMsg.clear()
        waitExportBooks.keys.forEach {
            postEvent(EventBus.EXPORT_BOOK, it)
        }
    }

    @SuppressLint("MissingPermission")
    override fun startForegroundNotification() {
        val notification = NotificationCompat.Builder(this, AppConst.channelIdDownload)
            .setSmallIcon(R.drawable.ic_export)
            .setSubText(getString(R.string.export_book))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setGroup(groupKey)
            .setGroupSummary(true)
        startForegroundCompat(
            NotificationId.ExportBookService,
            notification.build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            "创建导出通知出错"
        )
    }

    private fun upExportNotification(finish: Boolean = false) {
        val notification = NotificationCompat.Builder(this, AppConst.channelIdDownload)
            .setSmallIcon(R.drawable.ic_export)
            .setSubText(getString(R.string.export_book))
            .setContentIntent(activityPendingIntent<CacheActivity>("cacheActivity"))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentText(notificationContentText)
            .setDeleteIntent(servicePendingIntent<ExportBookService>(IntentAction.stop))
            .setGroup(groupKey)
            .setOnlyAlertOnce(true)
        if (!finish) {
            notification.setOngoing(true)
            notification.addAction(
                R.drawable.ic_stop_black_24dp,
                getString(R.string.cancel),
                servicePendingIntent<ExportBookService>(IntentAction.stop)
            )
        }
        notificationManager.notify(NotificationId.ExportBook, notification.build())
    }

    private fun export() {
        if (exportJob?.isActive == true) {
            return
        }
        exportJob = lifecycleScope.launch(IO) {
            while (isActive) {
                val (bookUrl, exportConfig) = waitExportBooks.entries.firstOrNull() ?: let {
                    notificationContentText = "导出完成"
                    upExportNotification(true)
                    stopSelf()
                    return@launch
                }
                exportProgress[bookUrl] = 0
                waitExportBooks.remove(bookUrl)
                val book = appDb.bookDao.getBook(bookUrl)
                try {
                    book ?: throw NoStackTraceException("获取${bookUrl}书籍出错")
                    refreshChapterList(book)
                    notificationContentText = getString(
                        R.string.export_book_notification_content,
                        book.name,
                        waitExportBooks.size
                    )
                    upExportNotification()
                    if (exportConfig.type == "epub") {
                        if (exportConfig.epubScope.isNullOrBlank()) {
                            exportEpub(exportConfig.path, book, exportConfig)
                        } else {
                            CustomExporter(
                                exportConfig.epubScope,
                                exportConfig.epubSize,
                                exportConfig
                            ).export(exportConfig.path, book)
                        }
                    } else {
                        exportTxt(exportConfig.path, book, exportConfig)
                    }
                    exportMsg[book.bookUrl] = getString(R.string.export_success)
                } catch (e: Throwable) {
                    ensureActive()
                    exportMsg[bookUrl] = e.localizedMessage ?: "ERROR"
                    AppLog.put("导出书籍<${book?.name ?: bookUrl}>出错", e)
                } finally {
                    exportProgress.remove(bookUrl)
                    postEvent(EventBus.EXPORT_BOOK, bookUrl)
                }
            }
        }
    }

    private fun refreshChapterList(book: Book) {
        if (!book.isPlainLocalBook || !book.isLocalModified()) {
            return
        }
        kotlin.runCatching {
            FileBook.getChapterList(book)
        }.onSuccess {
            appDb.bookChapterDao.delByBook(book.bookUrl)
            appDb.bookChapterDao.insert(*it.toTypedArray())
            appDb.bookDao.update(book)
            ReadBook.onChapterListUpdated(book)
        }
    }

    private data class SrcData(
        val chapterTitle: String,
        val index: Int,
        val src: String
    )

    private suspend fun exportTxt(path: String, book: Book, exportConfig: ExportConfig) {
        exportMsg.remove(book.bookUrl)
        postEvent(EventBus.EXPORT_BOOK, book.bookUrl)
        val fileDoc = FileDoc.fromDir(path)
        exportTxt(fileDoc, book, exportConfig)
    }

    private suspend fun exportTxt(
        fileDoc: FileDoc,
        book: Book,
        exportConfig: ExportConfig
    ) {
        val chapters = getExportChapters(
            book,
            appDb.bookChapterDao.getChapterList(book.bookUrl),
            exportConfig
        )
        val filename = book.getExportFileName("txt")
        fileDoc.find(filename)?.delete()

        val bookDoc = fileDoc.createFileIfNotExist(filename)
        val charset = Charset.forName(AppConfig.exportCharset)
        try {
            bookDoc.openOutputStream().getOrThrow().bufferedWriter(charset).use { bw ->
                getAllContents(book, chapters, exportConfig) { text, srcList ->
                    bw.write(text)
                    srcList?.forEach {
                        val vFile = BookHelp.getImage(book, it.src)
                        if (vFile.exists()) {
                            fileDoc.createFileIfNotExist(
                                "${it.index}-${MD5Utils.md5Encode16(it.src)}.jpg",
                                subDirs = arrayOf(
                                    "${book.name}_${book.author}",
                                    "images",
                                    it.chapterTitle
                                )
                            ).writeFile(vFile)
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            bookDoc.delete()
            throw error
        }
        if (AppConfig.exportToWebDav) {
            // 导出到webdav
            AppWebDav.exportWebDav(bookDoc.uri, filename)
        }
    }

    private suspend fun getAllContents(
        book: Book,
        chapters: List<BookChapter>,
        exportConfig: ExportConfig,
        append: (text: String, srcList: ArrayList<SrcData>?) -> Unit
    ) = coroutineScope {
        val useReplace = exportConfig.useReplace && book.getUseReplaceRule()
        val contentProcessor = ContentProcessor.get(book.name, book.origin)
        val qy = "${book.name}\n${
            getString(R.string.author_show, book.getRealAuthor())
        }\n${
            getString(
                R.string.intro_show,
                "\n" + HtmlFormatter.format(book.getDisplayIntro())
            )
        }"
        append(qy, null)
        val threads = if (AppConfig.parallelExportBook) {
            AppConst.MAX_THREAD
        } else {
            1
        }
        flow {
            chapters.forEach { chapter ->
                emit(chapter)
            }
        }.mapAsync(threads) { chapter ->
            getExportData(book, chapter, contentProcessor, useReplace)
        }.collectIndexed { index, result ->
            postEvent(EventBus.EXPORT_BOOK, book.bookUrl)
            exportProgress[book.bookUrl] = index
            append.invoke(result.first, result.second)
        }

    }

    private fun getExportData(
        book: Book,
        chapter: BookChapter,
        contentProcessor: ContentProcessor,
        useReplace: Boolean
    ): Pair<String, ArrayList<SrcData>?> {
        val content = getRequiredContent(book, chapter)
        val processedContent = contentProcessor
            .getContent(
                book,
                // 不导出vip标识
                chapter.apply { isVip = false },
                content,
                includeTitle = false,
                useReplace = useReplace,
                chineseConvert = false,
                reSegment = false
            ).toString()
        val content1 = if (AppConfig.exportNoChapterName) {
            processedContent
        } else {
            val title = chapter.getDisplayTitle(
                contentProcessor.getTitleReplaceRules(),
                useReplace = useReplace,
                chineseConvert = false
            )
            "$title\n$processedContent"
        }
        if (AppConfig.exportPictureFile) {
            //txt导出图片文件
            val srcList = arrayListOf<SrcData>()
            content.split("\n").forEachIndexed { index, text ->
                val matcher = HtmlFormatter.formatImagePattern.matcher(text)
                if (matcher.find()) {
                    srcList.add(SrcData(chapter.title, index, matcher.group(1)!!))
                }
            }
            return Pair("\n\n$content1", srcList)
        } else {
            return Pair("\n\n$content1", null)
        }
    }

    /**
     * 导出Epub
     */
    private suspend fun exportEpub(path: String, book: Book, exportConfig: ExportConfig) {
        exportMsg.remove(book.bookUrl)
        postEvent(EventBus.EXPORT_BOOK, book.bookUrl)
        val fileDoc = FileDoc.fromDir(path)
        exportEpub(fileDoc, book, exportConfig)
    }

    private suspend fun exportEpub(
        fileDoc: FileDoc,
        book: Book,
        exportConfig: ExportConfig
    ) {
        val filename = book.getExportFileName("epub")
        val chapters = getExportChapters(
            book,
            appDb.bookChapterDao.getChapterList(book.bookUrl),
            exportConfig
        )
        fileDoc.find(filename)?.delete()

        val epubBook = EpubBook()
        epubBook.version = "2.0"
        //set metadata
        setEpubMetadata(book, epubBook)
        //set cover
        setCover(book, epubBook)
        //set css
        val contentModel = setAssets(fileDoc, book, epubBook)

        //设置正文
        setEpubContent(contentModel, book, epubBook, chapters, exportConfig)

        val bookDoc = fileDoc.createFileIfNotExist(filename)
        try {
            bookDoc.openOutputStream().getOrThrow().buffered().use { bookOs ->
                EpubWriter().write(epubBook, bookOs)
            }
        } catch (error: Throwable) {
            bookDoc.delete()
            throw error
        }

        if (AppConfig.exportToWebDav) {
            // 导出到webdav
            AppWebDav.exportWebDav(bookDoc.uri, filename)
        }
    }

    private fun setAssets(doc: FileDoc, book: Book, epubBook: EpubBook): String {
        val customPath = doc.find("Asset")
        val contentModel = if (customPath == null) {//使用内置模板
            setAssets(book, epubBook)
        } else {//外部模板
            setAssetsExternal(customPath, book, epubBook)
        }

        return contentModel
    }

    private fun setAssetsExternal(doc: FileDoc, book: Book, epubBook: EpubBook): String {
        var contentModel = ""
        doc.list().orEmpty().forEach { folder ->
            if (folder.isDir && folder.name == "Text") {
                folder.list().orEmpty().sortedWith { o1, o2 ->
                    o1.name.cnCompare(o2.name)
                }.forEach loop@{ file ->
                    if (file.isDir) {
                        return@loop
                    }
                    when {
                        //正文模板
                        file.name.equals("chapter.html", true)
                                || file.name.equals("chapter.xhtml", true) -> {
                            contentModel = file.readText()
                        }
                        //封面等其他模板
                        file.name.endsWith("html", true) -> {
                            epubBook.addSection(
                                FileUtils.getNameExcludeExtension(file.name),
                                ResourceUtil.createPublicResource(
                                    book.name,
                                    book.getRealAuthor(),
                                    book.getDisplayIntro(),
                                    book.kind,
                                    book.wordCount,
                                    file.readText(),
                                    "${folder.name}/${file.name}"
                                )
                            )
                        }
                        //其他格式文件当做资源文件
                        else -> {
                            epubBook.resources.add(
                                Resource(
                                    file.readBytes(),
                                    "${folder.name}/${file.name}"
                                )
                            )
                        }
                    }
                }
            } else if (folder.isDir) {
                //资源文件
                folder.list().orEmpty().forEach loop2@{
                    if (it.isDir) {
                        return@loop2
                    }
                    epubBook.resources.add(
                        Resource(
                            it.readBytes(),
                            "${folder.name}/${it.name}"
                        )
                    )
                }
            } else {//Asset下面的资源文件
                epubBook.resources.add(
                    Resource(
                        folder.readBytes(),
                        folder.name
                    )
                )
            }
        }
        return contentModel
    }

    private fun setAssets(book: Book, epubBook: EpubBook): String {
        epubBook.resources.add(
            Resource(
                appCtx.assets.open("epub/fonts.css").readBytes(),
                "Styles/fonts.css"
            )
        )
        epubBook.resources.add(
            Resource(
                appCtx.assets.open("epub/main.css").readBytes(),
                "Styles/main.css"
            )
        )
        epubBook.resources.add(
            Resource(
                appCtx.assets.open("epub/logo.png").readBytes(),
                "Images/logo.png"
            )
        )
        epubBook.addSection(
            getString(R.string.img_cover),
            ResourceUtil.createPublicResource(
                book.name,
                book.getRealAuthor(),
                book.getDisplayIntro(),
                book.kind,
                book.wordCount,
                String(appCtx.assets.open("epub/cover.html").readBytes()),
                "Text/cover.html"
            )
        )
        epubBook.addSection(
            getString(R.string.book_intro),
            ResourceUtil.createPublicResource(
                book.name,
                book.getRealAuthor(),
                book.getDisplayIntro(),
                book.kind,
                book.wordCount,
                String(appCtx.assets.open("epub/intro.html").readBytes()),
                "Text/intro.html"
            )
        )
        return String(appCtx.assets.open("epub/chapter.html").readBytes())
    }

    private fun setCover(book: Book, epubBook: EpubBook) {
        kotlin.runCatching {
            val request = Glide.with(this)
                .asFile()
                .load(book.getDisplayCover())
            if (!book.isPlainLocalBook) {
                request.onlyRetrieveFromCache(true)
            }
            val file = request
                .submit()
                .get()
            val provider = LazyResourceProvider { _ ->
                file.inputStream()
            }
            epubBook.coverImage = LazyResource(provider, "Images/cover.jpg")
        }.onFailure {
            AppLog.put("获取书籍封面出错\n${it.localizedMessage}", it)
        }
    }

    private suspend fun setEpubContent(
        contentModel: String,
        book: Book,
        epubBook: EpubBook,
        chapters: List<BookChapter>,
        exportConfig: ExportConfig
    ) = coroutineScope {
        //正文
        val useReplace = exportConfig.useReplace && book.getUseReplaceRule()
        val contentProcessor = ContentProcessor.get(book.name, book.origin)
        val threads = if (AppConfig.parallelExportBook) {
            AppConst.MAX_THREAD
        } else {
            1
        }
        var parentSection: TOCReference? = null
        flow {
            chapters.forEach { chapter ->
                emit(chapter)
            }
        }.mapAsyncIndexed(threads) { index, chapter ->
            val content = getRequiredContent(book, chapter)
            val (contentFix, resources) = fixPic(
                book,
                content,
                chapter
            )
            // 不导出vip标识
            chapter.isVip = false
            val content1 = contentProcessor
                .getContent(
                    book,
                    chapter,
                    contentFix,
                    includeTitle = false,
                    useReplace = useReplace,
                    chineseConvert = false,
                    reSegment = false
                ).toString()
            val title = chapter.run {
                // 不导出vip标识
                isVip = false
                getDisplayTitle(
                    contentProcessor.getTitleReplaceRules(),
                    useReplace = useReplace,
                    chineseConvert = false
                )
            }
            val chapterResource = ResourceUtil.createChapterResource(
                title.replace("\uD83D\uDD12", ""),
                content1,
                contentModel,
                "Text/chapter_${index}.html"
            )
            ExportChapter(title, chapterResource, resources, chapter)
        }.collectIndexed { index, exportChapter ->
            postEvent(EventBus.EXPORT_BOOK, book.bookUrl)
            exportProgress[book.bookUrl] = index
            val (title, chapterResource, resources, chapter) = exportChapter
            epubBook.resources.addAll(resources)
            if (chapter.isVolume) {
                parentSection = epubBook.addSection(title, chapterResource)
            } else if (parentSection == null) {
                epubBook.addSection(title, chapterResource)
            } else {
                epubBook.addSection(parentSection, title, chapterResource)
            }
        }
    }

    private fun getExportChapters(
        book: Book,
        chapters: List<BookChapter>,
        exportConfig: ExportConfig
    ): List<BookChapter> {
        if (chapters.isEmpty()) {
            throw NoStackTraceException(getString(R.string.export_no_available_chapters))
        }
        if (book.isPlainLocalBook) {
            return chapters
        }
        val plan = createBookExportPlan(
            chapters = chapters,
            policy = exportConfig.missingChapterPolicy,
            isContentChapter = { !it.isVolume },
            isAvailable = { BookHelp.getCachedContent(book, it) != null }
        )
        if (exportConfig.missingChapterPolicy == ExportMissingChapterPolicy.RequireComplete &&
            plan.missing.isNotEmpty()
        ) {
            throw NoStackTraceException(
                getString(R.string.export_missing_chapters, plan.missing.size)
            )
        }
        if (plan.selected.none { !it.isVolume }) {
            throw NoStackTraceException(getString(R.string.export_no_available_chapters))
        }
        return plan.selected
    }

    private fun getRequiredContent(book: Book, chapter: BookChapter): String {
        if (chapter.isVolume) {
            val content = if (book.isPlainLocalBook) {
                BookHelp.getContent(book, chapter)
            } else {
                BookHelp.getCachedContent(book, chapter)
            }
            return content ?: chapter.tag.orEmpty()
        }
        val content = if (book.isPlainLocalBook) {
            BookHelp.getContent(book, chapter)
        } else {
            BookHelp.getCachedContent(book, chapter)
        }
        return content ?: throw NoStackTraceException(
            getString(R.string.export_missing_chapters, 1)
        )
    }

    data class ExportChapter(
        val title: String,
        val chapterResource: Resource,
        val resources: ArrayList<Resource>,
        val chapter: BookChapter
    )

    private fun fixPic(
        book: Book,
        content: String,
        chapter: BookChapter
    ): Pair<String, ArrayList<Resource>> {
        val data = StringBuilder("")
        val resources = arrayListOf<Resource>()
        content.split("\n").forEach { text ->
            var text1 = text
            val matcher = HtmlFormatter.formatImagePattern.matcher(text)
            if (matcher.find()) {
                matcher.group(1)?.let { src ->
                    val originalHref =
                        "${MD5Utils.md5Encode16(src)}.${BookHelp.getImageSuffix(src)}"
                    val href =
                        "Images/${MD5Utils.md5Encode16(src)}.${BookHelp.getImageSuffix(src)}"
                    val vFile = BookHelp.getImage(book, src)
                    val fp = FileResourceProvider(vFile.parent)
                    if (vFile.exists()) {
                        val img = LazyResource(fp, href, originalHref)
                        resources.add(img)
                    }
                    text1 = text1.replace(src, "../${href}")
                }
            }
            data.append(text1).append("\n")
        }
        return data.toString() to resources
    }

    private fun setEpubMetadata(book: Book, epubBook: EpubBook) {
        val metadata = Metadata()
        metadata.titles.add(book.name)//书籍的名称
        metadata.authors.add(Author(book.getRealAuthor()))//书籍的作者
        metadata.language = "zh"//数据的语言
        metadata.dates.add(Date())//数据的创建日期
        metadata.publishers.add("Legado")//数据的创建者
        metadata.descriptions.add(book.getDisplayIntro())//书籍的简介
        //metadata.subjects.add("")//书籍的主题，在静读天下里面有使用这个分类书籍
        epubBook.metadata = metadata
    }

    //////end of EPUB

    //////start of custom exporter
    /**
     * 自定义Exporter
     * @param scope 导出范围
     * @param size epub 文件包含最大章节数
     */
    inner class CustomExporter(
        scopeStr: String,
        private val size: Int,
        private val exportConfig: ExportConfig
    ) {

        private var scope = parseExportChapterScope(scopeStr)
        private var selectedChapters: List<BookChapter> = emptyList()

        init {
            if (size <= 0) {
                throw NoStackTraceException("每个EPUB文件包含章节数必须大于0")
            }
        }

        /**
         * 导出Epub
         * @param path 导出的路径
         * @param book 书籍
         */
        suspend fun export(
            path: String,
            book: Book
        ) {
            exportProgress[book.bookUrl] = 0
            exportMsg.remove(book.bookUrl)
            postEvent(EventBus.EXPORT_BOOK, book.bookUrl)
            val currentTimeMillis = System.currentTimeMillis()
            val allChapters = appDb.bookChapterDao.getChapterList(book.bookUrl)
            scope = scope.filter { it < allChapters.size }.toHashSet()
            if (scope.isEmpty()) {
                throw NoStackTraceException("导出范围未匹配到章节")
            }
            val requestedChapters = allChapters.filterIndexed { index, _ ->
                scope.contains(index)
            }
            selectedChapters = getExportChapters(book, requestedChapters, exportConfig)

            val fileDoc = FileDoc.fromDir(path)

            val (contentModel, epubList) = createEpubs(book, fileDoc)
            var progressBar = 0.0
            try {
                epubList.forEachIndexed { index, ep ->
                    val (filename, epubBook) = ep
                    //设置正文
                    setEpubContent(
                        contentModel,
                        book,
                        epubBook,
                        index
                    ) { _, _ ->
                        // 将章节写入内存时更新进度条
                        postEvent(EventBus.EXPORT_BOOK, book.bookUrl)
                        progressBar +=
                            book.totalChapterNum.toDouble() / selectedChapters.size / 2
                        exportProgress[book.bookUrl] = progressBar.toInt()
                    }
                    save2Drive(filename, epubBook, fileDoc) { total, _ ->
                        //写入硬盘时更新进度条
                        progressBar +=
                            book.totalChapterNum.toDouble() / epubList.size / total / 2
                        postEvent(EventBus.EXPORT_BOOK, book.bookUrl)
                        exportProgress[book.bookUrl] = progressBar.toInt()
                    }
                }
            } catch (error: Throwable) {
                epubList.forEach { (filename, _) ->
                    fileDoc.find(filename)?.delete()
                }
                throw error
            }

            val elapsed = System.currentTimeMillis() - currentTimeMillis
            AppLog.put("分割导出书籍 ${book.name} 一共耗时 $elapsed")
        }


        /**
         * 设置epub正文
         *
         * @param contentModel 正文模板
         * @param book 书籍
         * @param epubBook 分割后的epub
         * @param epubBookIndex 分割后的epub序号
         */
        private suspend fun setEpubContent(
            contentModel: String,
            book: Book,
            epubBook: EpubBook,
            epubBookIndex: Int,
            updateProgress: (chapterList: List<BookChapter>, index: Int) -> Unit
        ) {
            //正文
            val useReplace = exportConfig.useReplace && book.getUseReplaceRule()
            val contentProcessor = ContentProcessor.get(book.name, book.origin)
            if (selectedChapters.isEmpty()) {
                throw RuntimeException("书籍<${book.name}>(${epubBookIndex + 1})未找到章节信息")
            }
            val chapterList = selectedChapters.subList(
                epubBookIndex * size,
                min(selectedChapters.size, (epubBookIndex + 1) * size)
            )
            chapterList.forEachIndexed { index, chapter ->
                currentCoroutineContext().ensureActive()
                updateProgress(chapterList, index)
                getRequiredContent(book, chapter).let { content ->
                    val (contentFix, resources) = fixPic(
                        book,
                        content,
                        chapter
                    )
                    epubBook.resources.addAll(resources)
                    val content1 = contentProcessor
                        .getContent(
                            book,
                            chapter,
                            contentFix,
                            includeTitle = false,
                            useReplace = useReplace,
                            chineseConvert = false,
                            reSegment = false
                        ).toString()
                    val title = chapter.run {
                        // 不导出vip标识
                        isVip = false
                        getDisplayTitle(
                            contentProcessor.getTitleReplaceRules(),
                            useReplace = useReplace,
                            chineseConvert = false
                        )
                    }
                    epubBook.addSection(
                        title,
                        ResourceUtil.createChapterResource(
                            title.replace("\uD83D\uDD12", ""),
                            content1,
                            contentModel,
                            "Text/chapter_${index}.html"
                        )
                    )
                }
            }
        }

        /**
         * 创建多个epub 对象
         *
         * 分割epub时，一个书籍需要创建多个epub对象
         * @param book 书籍
         * @param fileDoc 导出文件夹文档
         *
         * @return <内容模板字符串, <epub文件名, epub对象>>
         */
        private fun createEpubs(
            book: Book,
            fileDoc: FileDoc
        ): Pair<String, List<Pair<String, EpubBook>>> {
            val paresNumOfEpub = paresNumOfEpub(selectedChapters.size, size)
            val result: MutableList<Pair<String, EpubBook>> = ArrayList(paresNumOfEpub)
            var contentModel = ""
            for (i in 1..paresNumOfEpub) {
                val filename = book.getExportFileName("epub", i)
                fileDoc.find(filename)?.delete()

                val epubBook = EpubBook()
                epubBook.version = "2.0"
                //set metadata
                setEpubMetadata(book, epubBook)
                //set cover
                setCover(book, epubBook)
                //set css
                contentModel = setAssets(fileDoc, book, epubBook)

                // add epubBook
                result.add(Pair(filename, epubBook))
            }
            return Pair(contentModel, result)
        }

        /**
         * 保存文件到 设备
         */
        private suspend fun save2Drive(
            filename: String,
            epubBook: EpubBook,
            fileDoc: FileDoc,
            callback: (total: Int, progress: Int) -> Unit
        ) {
            val bookDoc = fileDoc.createFileIfNotExist(filename)
            try {
                bookDoc.openOutputStream().getOrThrow().buffered().use { bookOs ->
                    EpubWriter()
                        .setCallback(object : EpubWriterProcessor.Callback {
                            override fun onProgressing(total: Int, progress: Int) {
                                callback(total, progress)
                            }
                        })
                        .write(epubBook, bookOs)
                }
            } catch (error: Throwable) {
                bookDoc.delete()
                throw error
            }

            if (AppConfig.exportToWebDav) {
                // 导出到webdav
                AppWebDav.exportWebDav(bookDoc.uri, filename)
            }
        }

        /**
         * 解析 分割epub后的数量
         *
         * @param total 章节总数
         * @param size 每个epub文件包含多少章节
         */
        private fun paresNumOfEpub(total: Int, size: Int): Int {
            val i = total % size
            var result = total / size
            if (i > 0) {
                result++
            }
            return result
        }

    }
}
