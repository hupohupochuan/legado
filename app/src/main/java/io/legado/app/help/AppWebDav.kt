package io.legado.app.help

import android.net.Uri
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookProgress
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.help.config.AppConfig
import io.legado.app.help.storage.Backup
import io.legado.app.help.storage.Restore
import io.legado.app.lib.webdav.Authorization
import io.legado.app.lib.webdav.WebDav
import io.legado.app.lib.webdav.WebDavException
import io.legado.app.lib.webdav.WebDavFile
import io.legado.app.model.fileBook.FileBook
import io.legado.app.model.remote.RemoteBookWebDav
import io.legado.app.utils.AlphanumComparator
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.UrlUtil
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getPrefString
import io.legado.app.utils.isJson
import io.legado.app.utils.normalizeFileName
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import splitties.init.appCtx
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * webDav初始化会访问网络,不要放到主线程
 */
object AppWebDav {
    private const val defaultWebDavUrl = "https://dav.jianguoyun.com/dav/"
    private val bookProgressUrl get() = "${rootWebDavUrl}bookProgress/"
    private val exportsWebDavUrl get() = "${rootWebDavUrl}books/"
    private val bgWebDavUrl get() = "${rootWebDavUrl}background/"

    var authorization: Authorization? = null
        private set

    var defaultBookWebDav: RemoteBookWebDav? = null

    val isOk get() = authorization != null

    val isJianGuoYun get() = rootWebDavUrl.startsWith(defaultWebDavUrl, true)

    private val configVersion = AtomicInteger()

    init {
        runBlocking {
            upConfig()
        }
    }

    private val serverWebDavUrl: String
        get() {
            val configUrl = appCtx.getPrefString(PreferKey.webDavUrl)?.trim()
            var url = if (configUrl.isNullOrEmpty()) defaultWebDavUrl else configUrl
            if (!url.endsWith("/")) url = "${url}/"
            return url
        }

    private val rootWebDavUrl: String
        get() {
            var url = serverWebDavUrl
            AppConfig.webDavDir?.trim()?.trim('/')?.let {
                if (it.isNotEmpty()) {
                    url = "$url$it/"
                }
            }
            return url
        }

    suspend fun upConfig() {
        val version = configVersion.incrementAndGet()
        val account = appCtx.getPrefString(PreferKey.webDavAccount)?.trim()
        val password = appCtx.getPrefString(PreferKey.webDavPassword)?.trim()

        if (account.isNullOrEmpty() || password.isNullOrEmpty()) {
            authorization = null
            defaultBookWebDav = null
            AppLog.put("WebDav upConfig: 账号或密码为空，已清空运行时配置")
            return
        }

        val mAuthorization = Authorization(account, password)
        kotlin.runCatching {
            AppLog.putDebug(
                "WebDav upConfig start version=${version} " +
                        "serverUrl=${WebDav.safeLogUrl(serverWebDavUrl)} " +
                        "rootUrl=${WebDav.safeLogUrl(rootWebDavUrl)}"
            )
            checkAuthorization(mAuthorization, version)
            if (version != configVersion.get()) {
                AppLog.put("WebDav upConfig 版本过期(1) url=${WebDav.safeLogUrl(serverWebDavUrl)}")
                return@runCatching
            }
            WebDav(rootWebDavUrl, mAuthorization).makeAsDir()
            WebDav(bookProgressUrl, mAuthorization).makeAsDir()
            WebDav(exportsWebDavUrl, mAuthorization).makeAsDir()
            WebDav(bgWebDavUrl, mAuthorization).makeAsDir()
            if (version != configVersion.get()) {
                AppLog.put("WebDav upConfig 版本过期(2) url=${WebDav.safeLogUrl(rootWebDavUrl)}")
                return@runCatching
            }
            val rootBooksUrl = "${rootWebDavUrl}books/"
            defaultBookWebDav = RemoteBookWebDav(rootBooksUrl, mAuthorization)
            authorization = mAuthorization
            AppLog.put("WebDav配置更新成功 url=${WebDav.safeLogUrl(rootWebDavUrl)}")
        }.onFailure {
            AppLog.put(
                "WebDav认证失败 serverUrl=${WebDav.safeLogUrl(serverWebDavUrl)} " +
                        "rootUrl=${WebDav.safeLogUrl(rootWebDavUrl)} ${it.localizedMessage}"
            )
        }
    }

    @Throws(WebDavException::class)
    private suspend fun checkAuthorization(authorization: Authorization, version: Int) {
        val serverAuthorized = WebDav(serverWebDavUrl, authorization).check()
        val rootAuthorized = serverAuthorized
            || serverWebDavUrl.equals(rootWebDavUrl, ignoreCase = true)
            || WebDav(rootWebDavUrl, authorization).check()
        AppLog.putDebug(
            "WebDav checkAuthorization version=${version} " +
                    "serverAuthorized=${serverAuthorized} rootAuthorized=${rootAuthorized} " +
                    "serverUrl=${WebDav.safeLogUrl(serverWebDavUrl)} " +
                    "rootUrl=${WebDav.safeLogUrl(rootWebDavUrl)}"
        )
        if (!rootAuthorized) {
            if (version == configVersion.get()) {
                appCtx.toastOnUi(R.string.webdav_application_authorization_error)
            }
            throw WebDavException(appCtx.getString(R.string.webdav_application_authorization_error))
        }
    }

    @Throws(Exception::class)
    suspend fun getBackupNames(): ArrayList<String> {
        val names = arrayListOf<String>()
        authorization?.let {
            var files = WebDav(rootWebDavUrl, it).listFiles()
            files = files.sortedWith { o1, o2 ->
                AlphanumComparator.compare(o1.displayName, o2.displayName)
            }.reversed()
            files.forEach { webDav ->
                val name = webDav.displayName
                if (name.startsWith("backup")) {
                    names.add(name)
                }
            }
        } ?: throw NoStackTraceException("webDav没有配置")
        return names
    }

    @Throws(WebDavException::class)
    suspend fun restoreWebDav(name: String) {
        authorization?.let {
            val webDav = WebDav(rootWebDavUrl + name, it)
            webDav.downloadTo(Backup.zipFilePath, true)
            FileUtils.delete(Backup.backupPath)
            ZipUtils.unZipToPath(File(Backup.zipFilePath), Backup.backupPath)
            Restore.restoreLocked(Backup.backupPath)
        }
    }

    suspend fun hasBackUp(backUpName: String): Boolean {
        authorization?.let {
            val url = "$rootWebDavUrl${backUpName}"
            return WebDav(url, it).exists()
        }
        return false
    }

    suspend fun lastBackUp(): Result<WebDavFile?> {
        return kotlin.runCatching {
            authorization?.let {
                var lastBackupFile: WebDavFile? = null
                WebDav(rootWebDavUrl, it).listFiles().reversed().forEach { webDavFile ->
                    if (webDavFile.displayName.startsWith("backup")) {
                        if (lastBackupFile == null
                            || webDavFile.lastModify > lastBackupFile.lastModify
                        ) {
                            lastBackupFile = webDavFile
                        }
                    }
                }
                lastBackupFile
            }
        }
    }

    /**
     * webDav备份
     * @param fileName 备份文件名
     */
    @Throws(Exception::class)
    suspend fun backUpWebDav(fileName: String) {
        if (!NetworkUtils.isAvailable()) return
        authorization?.let {
            val putUrl = "$rootWebDavUrl$fileName"
            WebDav(putUrl, it).upload(Backup.zipFilePath)
        }
    }

    /**
     * 获取云端所有背景名称
     */
    private suspend fun getAllBgWebDavFiles(): Result<List<WebDavFile>> {
        return kotlin.runCatching {
            if (!NetworkUtils.isAvailable())
                throw NoStackTraceException("网络未连接")
            authorization.let {
                it ?: throw NoStackTraceException("webDav未配置")
                WebDav(bgWebDavUrl, it).listFiles()
            }
        }
    }

    /**
     * 上传背景图片
     */
    suspend fun upBgs(files: Array<File>) {
        val authorization = authorization ?: return
        if (!NetworkUtils.isAvailable()) return
        val bgWebDavFiles = getAllBgWebDavFiles().getOrThrow()
            .map { it.displayName }
            .toSet()
        files.forEach {
            if (!bgWebDavFiles.contains(it.name) && it.exists()) {
                WebDav("$bgWebDavUrl${it.name}", authorization)
                    .upload(it)
            }
        }
    }

    @Suppress("unused")
    suspend fun exportWebDav(byteArray: ByteArray, fileName: String) {
        if (!NetworkUtils.isAvailable()) return
        try {
            authorization?.let {
                // 如果导出的本地文件存在,开始上传
                val putUrl = exportsWebDavUrl + fileName
                WebDav(putUrl, it).upload(byteArray, "text/plain")
            }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            AppLog.put("WebDav导出失败\n${e.localizedMessage}", e, true)
        }
    }

    suspend fun exportWebDav(uri: Uri, fileName: String) {
        if (!NetworkUtils.isAvailable()) return
        try {
            authorization?.let {
                // 如果导出的本地文件存在,开始上传
                val putUrl = exportsWebDavUrl + fileName
                WebDav(putUrl, it).upload(uri, "text/plain")
            }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            AppLog.put("WebDav导出失败\n${e.localizedMessage}", e, true)
        }
    }

    suspend fun uploadBookProgress(
        book: Book,
        toast: Boolean = false,
        onSuccess: (() -> Unit)? = null
    ) {
        val progressFileName = getProgressFileName(book.name, book.author)
        val authorization = authorization ?: return AppLog.putDebug(
            "WebDav uploadBookProgress skip reason=noAuthorization file=${progressFileName}"
        )
        if (!AppConfig.syncBookProgress) return AppLog.putDebug(
            "WebDav uploadBookProgress skip reason=syncDisabled file=${progressFileName}"
        )
        if (!NetworkUtils.isAvailable()) return AppLog.putDebug(
            "WebDav uploadBookProgress skip reason=networkUnavailable file=${progressFileName}"
        )
        try {
            val bookProgress = BookProgress(book)
            val json = GSON.toJson(bookProgress)
            val url = bookProgressUrl + progressFileName
            WebDav(url, authorization).upload(json.toByteArray(), "application/json")
            book.syncTime = System.currentTimeMillis()
            AppLog.putDebug(
                "WebDav uploadBookProgress success file=${progressFileName} " +
                        "chapter=${bookProgress.durChapterIndex} pos=${bookProgress.durChapterPos}"
            )
            onSuccess?.invoke()
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            AppLog.put("上传进度失败 file=${progressFileName}\n${e.localizedMessage}", e, toast)
        }
    }

    suspend fun uploadBookProgress(bookProgress: BookProgress, onSuccess: (() -> Unit)? = null) {
        val progressFileName = getProgressFileName(bookProgress.name, bookProgress.author)
        try {
            val authorization = authorization ?: return AppLog.putDebug(
                "WebDav uploadBookProgress skip reason=noAuthorization file=${progressFileName}"
            )
            if (!AppConfig.syncBookProgress) return AppLog.putDebug(
                "WebDav uploadBookProgress skip reason=syncDisabled file=${progressFileName}"
            )
            if (!NetworkUtils.isAvailable()) return AppLog.putDebug(
                "WebDav uploadBookProgress skip reason=networkUnavailable file=${progressFileName}"
            )
            val json = GSON.toJson(bookProgress)
            val url = bookProgressUrl + progressFileName
            WebDav(url, authorization).upload(json.toByteArray(), "application/json")
            AppLog.putDebug(
                "WebDav uploadBookProgress success file=${progressFileName} " +
                        "chapter=${bookProgress.durChapterIndex} pos=${bookProgress.durChapterPos}"
            )
            onSuccess?.invoke()
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            AppLog.put("上传进度失败 file=${progressFileName}\n${e.localizedMessage}", e)
        }
    }

    private fun getProgressFileName(name: String, author: String): String {
        return UrlUtil.replaceReservedChar("${name}_${author}".normalizeFileName()) + ".json"
    }

    /**
     * 获取书籍进度
     */
    suspend fun getBookProgress(book: Book): BookProgress? {
        return getBookProgressResult(book)
            .onFailure {
                currentCoroutineContext().ensureActive()
                AppLog.put(
                    "获取书籍进度失败 file=${getProgressFileName(book.name, book.author)}\n${it.localizedMessage}",
                    it
                )
            }.getOrNull()
    }

    suspend fun getBookProgressResult(book: Book): Result<BookProgress?> {
        val progressFileName = getProgressFileName(book.name, book.author)
        val url = bookProgressUrl + progressFileName
        val authorization = authorization ?: run {
            AppLog.putDebug("WebDav getBookProgress skip reason=noAuthorization file=${progressFileName}")
            return Result.success(null)
        }
        return kotlin.runCatching {
            WebDav(url, authorization).download().let { byteArray ->
                val json = String(byteArray)
                if (json.isJson()) {
                    return@runCatching GSON.fromJsonObject<BookProgress>(json).getOrNull()?.also {
                        AppLog.putDebug(
                            "WebDav getBookProgress success file=${progressFileName} " +
                                    "chapter=${it.durChapterIndex} pos=${it.durChapterPos}"
                        )
                    }
                }
                null
            }
        }
    }

    fun canApplyBookProgress(book: Book, bookProgress: BookProgress, logPrefix: String): Boolean {
        val maxChapterIndex = book.simulatedTotalChapterNum()
        if (maxChapterIndex <= 0 || bookProgress.durChapterIndex !in 0 until maxChapterIndex) {
            AppLog.put(
                "$logPrefix skip reason=outOfRange " +
                        "book=${book.name} remoteChapter=${bookProgress.durChapterIndex} " +
                        "maxChapter=${maxChapterIndex}"
            )
            return false
        }
        if (book.isLocal) {
            kotlin.runCatching {
                FileBook.checkBookReadable(book)
            }.onFailure {
                AppLog.put(
                    "$logPrefix skip reason=localBookUnreadable " +
                            "book=${book.name}\n${it.localizedMessage}", it
                )
                return false
            }
        }
        return true
    }

    suspend fun downloadAllBookProgress() {
        val authorization = authorization ?: return AppLog.putDebug(
            "WebDav downloadAllBookProgress skip reason=noAuthorization"
        )
        if (!NetworkUtils.isAvailable()) return AppLog.putDebug(
            "WebDav downloadAllBookProgress skip reason=networkUnavailable"
        )
        kotlin.runCatching {
            val bookProgressFiles = WebDav(bookProgressUrl, authorization).listFiles()
            val map = hashMapOf<String, WebDavFile>()
            bookProgressFiles.forEach {
                map[it.displayName] = it
            }
            var matchedCount = 0
            var updatedCount = 0
            appDb.bookDao.all.forEach { book ->
                val progressFileName = getProgressFileName(book.name, book.author)
                val webDavFile = map[progressFileName] ?: return@forEach
                matchedCount++
                if (webDavFile.lastModify <= book.syncTime) {
                    //本地同步时间大于上传时间不用同步
                    return@forEach
                }
                getBookProgress(book)?.let { bookProgress ->
                    if (!canApplyBookProgress(book, bookProgress, "WebDav downloadAllBookProgress")) {
                        return@forEach
                    }
                    if (bookProgress.durChapterIndex > book.durChapterIndex
                        || (bookProgress.durChapterIndex == book.durChapterIndex
                            && bookProgress.durChapterPos > book.durChapterPos)
                    ) {
                        book.durChapterIndex = bookProgress.durChapterIndex
                        book.durChapterPos = bookProgress.durChapterPos
                        book.durChapterTitle = bookProgress.durChapterTitle
                        book.durChapterTime = bookProgress.durChapterTime
                        book.syncTime = System.currentTimeMillis()
                        appDb.bookDao.update(book)
                        updatedCount++
                    }
                }
            }
            AppLog.putDebug(
                "WebDav downloadAllBookProgress success " +
                        "remoteFiles=${bookProgressFiles.size} matched=${matchedCount} updated=${updatedCount}"
            )
        }.onFailure {
            currentCoroutineContext().ensureActive()
            AppLog.put("WebDav全量同步阅读进度失败\n${it.localizedMessage}", it)
        }
    }

}
