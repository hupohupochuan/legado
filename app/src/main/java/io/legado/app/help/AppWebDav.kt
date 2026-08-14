package io.legado.app.help

import android.net.Uri
import android.os.SystemClock
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
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
import io.legado.app.lib.webdav.ObjectNotFoundException
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
import splitties.init.appCtx
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * webDav初始化会访问网络,不要放到主线程
 *
 * 初始化说明: 不在 [init] 块里 runBlocking 同步初始化, 避免单例首次被主线程访问时阻塞 UI.
 * 改为由调用方在协程中主动调用 [upConfig] 完成异步初始化 (App.onCreate 的 IO 协程会预热).
 * 在配置就绪前 [authorization] 为 null, 各读取点用 `?.let` / `?: throw` 安全降级, 不会崩溃.
 */
object AppWebDav : BookProgressSync {
    private const val defaultWebDavUrl = "https://dav.jianguoyun.com/dav/"
    private const val missingBookProgressCacheMillis = 10 * 60 * 1000L
    private val bookProgressUrl get() = "${rootWebDavUrl}bookProgress/"
    private val exportsWebDavUrl get() = "${rootWebDavUrl}books/"
    private val bgWebDavUrl get() = "${rootWebDavUrl}background/"

    var authorization: Authorization? = null
        private set

    var defaultBookWebDav: RemoteBookWebDav? = null

    val isOk get() = authorization != null

    val isJianGuoYun get() = rootWebDavUrl.startsWith(defaultWebDavUrl, true)

    private val configVersion = AtomicInteger()
    private val missingBookProgressFiles = ConcurrentHashMap<String, Long>()

    private enum class BookProgressCheckResult {
        CanApply,
        OutOfRange,
        LocalBookUnreadable
    }

    private sealed interface BookProgressDownloadResult {
        data class Success(val progress: BookProgress) : BookProgressDownloadResult
        data object InvalidJson : BookProgressDownloadResult
        data object IdentityMismatch : BookProgressDownloadResult
    }

    private data class DownloadedBookProgress(
        val fileName: String,
        val result: BookProgressDownloadResult
    )

    private data class PendingMigrationUploadProgress(
        val progress: BookProgress,
        val remoteProgressToApply: BookProgress? = null
    )

    private data class GeneratedBookProgressIdentity(
        val progressKey: String,
        val localFileKey: String? = null
    )

    private data class ResolvedBookProgressTarget(
        val target: BookProgressStorageTarget,
        val pendingIdentity: GeneratedBookProgressIdentity? = null
    )

    private data class PreparedBookProgress(
        val progress: BookProgress,
        val resolvedTarget: ResolvedBookProgressTarget,
        val persistedBook: Book? = null
    )

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
            missingBookProgressFiles.clear()
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
            missingBookProgressFiles.clear()
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

    /**
     * 上传书籍进度到 WebDAV（从 Book 构造 BookProgress）。
     */
    override suspend fun uploadBookProgress(
        book: Book,
        toast: Boolean,
        onSuccess: (() -> Unit)?
    ) {
        if (shouldSkipBookProgressUpload(book.name)) return
        val resolvedTarget = resolveBookProgressTarget(book)
        val target = resolvedTarget.target
        val fileName = target.identityFileName ?: target.candidates.firstOrNull()
            ?: return AppLog.putDebug(
                "WebDav uploadBookProgress skip reason=unavailableProgressIdentity book=${book.name}"
            )
        val migration = selectPendingMigrationUploadProgress(
            book = book,
            resolvedTarget = resolvedTarget,
            localProgress = BookProgress(book).copy(bookProgressKey = target.progressKey)
        ).onFailure {
            currentCoroutineContext().ensureActive()
            AppLog.put(
                "WebDav uploadBookProgress skip reason=migrationReadFailed book=${book.name}\n" +
                        it.localizedMessage,
                it
            )
        }.getOrNull() ?: return
        uploadBookProgressJson(
            progress = migration.progress,
            fileName = fileName,
            toast = toast,
            onSuccess = onSuccess,
            onProgressUpdate = { book.syncTime = System.currentTimeMillis() },
            onUploaded = {
                applyPendingMigrationRemoteProgress(book, migration.remoteProgressToApply)
                persistPendingBookProgressIdentity(book, resolvedTarget)
            }
        )
    }

    suspend fun uploadBookProgress(book: Book) {
        uploadBookProgress(book, false, null)
    }

    suspend fun uploadBookProgress(book: Book, onSuccess: (() -> Unit)) {
        uploadBookProgress(book, false, onSuccess)
    }

    /**
     * 上传书籍进度到 WebDAV（直接使用 BookProgress 实例）。
     */
    override suspend fun uploadBookProgress(bookProgress: BookProgress, onSuccess: (() -> Unit)?) {
        if (shouldSkipBookProgressUpload(bookProgress.name)) return
        val prepared = prepareBookProgress(bookProgress)
            ?: return AppLog.putDebug(
                "WebDav uploadBookProgress skip reason=unavailableProgressIdentity book=${bookProgress.name}"
            )
        val target = prepared.resolvedTarget.target
        val fileName = target.identityFileName ?: target.candidates.firstOrNull()
            ?: return AppLog.putDebug(
                "WebDav uploadBookProgress skip reason=missingProgressTarget book=${bookProgress.name}"
            )
        val migration = prepared.persistedBook?.let { book ->
            val migrationProgress = selectPendingMigrationUploadProgress(
                book,
                prepared.resolvedTarget,
                prepared.progress
            )
                .onFailure {
                    currentCoroutineContext().ensureActive()
                    AppLog.put(
                        "WebDav uploadBookProgress skip reason=migrationReadFailed book=${book.name}\n" +
                                it.localizedMessage,
                        it
                    )
                }
            migrationProgress.getOrNull() ?: return
        } ?: PendingMigrationUploadProgress(prepared.progress)
        uploadBookProgressJson(
            progress = migration.progress,
            fileName = fileName,
            toast = false,
            onSuccess = onSuccess,
            onUploaded = {
                prepared.progress.bookUrl?.let(appDb.bookDao::getBook)?.let { book ->
                    applyPendingMigrationRemoteProgress(book, migration.remoteProgressToApply)
                    persistPendingBookProgressIdentity(book, prepared.resolvedTarget)
                }
            }
        )
    }

    suspend fun uploadBookProgress(bookProgress: BookProgress) {
        uploadBookProgress(bookProgress, null)
    }

    private fun shouldSkipBookProgressUpload(bookName: String): Boolean {
        if (authorization == null) {
            AppLog.putDebug("WebDav uploadBookProgress skip reason=noAuthorization book=${bookName}")
            return true
        }
        if (!AppConfig.syncBookProgress) {
            AppLog.putDebug("WebDav uploadBookProgress skip reason=syncDisabled book=${bookName}")
            return true
        }
        if (!NetworkUtils.isAvailable()) {
            AppLog.putDebug("WebDav uploadBookProgress skip reason=networkUnavailable book=${bookName}")
            return true
        }
        return false
    }

    /**
     * 公共上传逻辑：跳过判断 → 序列化 → WebDAV PUT → 回调。
     *
     * @param progress 进度对象
     * @param fileName WebDAV 上的文件名
     * @param toast 失败时是否弹 Toast
     * @param onProgressUpdate 上传成功后的额外回调（如更新 syncTime）
     */
    private suspend fun uploadBookProgressJson(
        progress: BookProgress,
        fileName: String,
        toast: Boolean,
        onSuccess: (() -> Unit)?,
        onProgressUpdate: (() -> Unit)? = null,
        onUploaded: (() -> Unit)? = null
    ) {
        val authorization = authorization ?: return AppLog.putDebug(
            "WebDav uploadBookProgress skip reason=noAuthorization file=${fileName}"
        )
        if (!AppConfig.syncBookProgress) return AppLog.putDebug(
            "WebDav uploadBookProgress skip reason=syncDisabled file=${fileName}"
        )
        if (!NetworkUtils.isAvailable()) return AppLog.putDebug(
            "WebDav uploadBookProgress skip reason=networkUnavailable file=${fileName}"
        )
        try {
            val json = GSON.toJson(progress)
            val url = bookProgressUrl + fileName
            WebDav(url, authorization).upload(json.toByteArray(), "application/json")
            missingBookProgressFiles.remove(fileName)
            onProgressUpdate?.invoke()
            onUploaded?.invoke()
            AppLog.putDebug(
                "WebDav uploadBookProgress success file=${fileName} " +
                        "chapter=${progress.durChapterIndex} pos=${progress.durChapterPos}"
            )
            onSuccess?.invoke()
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            AppLog.put("上传进度失败 file=${fileName}\n${e.localizedMessage}", e, toast)
        }
    }

    private fun getLegacyProgressFileName(name: String, author: String): String {
        return UrlUtil.replaceReservedChar("${name}_${author}".normalizeFileName()) + ".json"
    }

    private suspend fun resolveBookProgressTarget(book: Book): ResolvedBookProgressTarget {
        val legacyFileName = getLegacyProgressFileName(book.name, book.author)
        val existingProgressKey = book.bookProgressKey?.takeIf { it.isNotBlank() }
        if (existingProgressKey != null) {
            return ResolvedBookProgressTarget(
                BookProgressStorageTarget.forBook(
                    progressKey = existingProgressKey,
                    legacyFileName = legacyFileName,
                    sameNameBookCount = 1
                )
            )
        }
        val sameNameBooks = appDb.bookDao.getBooksByNameAndAuthor(book.name, book.author)
        val sameNameBookCount = sameNameBooks.size +
                if (sameNameBooks.any { it.bookUrl == book.bookUrl }) 0 else 1
        val isLocalFileBook = book.isLocal && !book.bookUrl.startsWith(BookType.webDavTag)
        if (isLocalFileBook) {
            val generatedIdentity = generateMissingBookProgressIdentity(book)
                ?: return ResolvedBookProgressTarget(
                    BookProgressStorageTarget(
                        progressKey = null,
                        legacyFileName = legacyFileName,
                        allowLegacyFallback = false
                    )
                )
            if (sameNameBookCount > 1) {
                persistBookProgressIdentity(book, generatedIdentity)
                return ResolvedBookProgressTarget(
                    BookProgressStorageTarget.forBook(
                        progressKey = generatedIdentity.progressKey,
                        legacyFileName = legacyFileName,
                        sameNameBookCount = sameNameBookCount
                    )
                )
            }
            return ResolvedBookProgressTarget(
                target = BookProgressStorageTarget.forBook(
                    progressKey = generatedIdentity.progressKey,
                    legacyFileName = legacyFileName,
                    sameNameBookCount = sameNameBookCount,
                    allowLegacyMigration = true
                ),
                pendingIdentity = generatedIdentity
            )
        }
        val generatedIdentity = if (sameNameBookCount > 1) {
            generateMissingBookProgressIdentity(book)?.also {
                persistBookProgressIdentity(book, it)
            }
        } else {
            null
        }
        return ResolvedBookProgressTarget(
            BookProgressStorageTarget.forBook(
                progressKey = generatedIdentity?.progressKey,
                legacyFileName = getLegacyProgressFileName(book.name, book.author),
                sameNameBookCount = sameNameBookCount
            )
        )
    }

    private suspend fun generateMissingBookProgressIdentity(
        book: Book
    ): GeneratedBookProgressIdentity? {
        val isLocalFileBook = book.isLocal && !book.bookUrl.startsWith(BookType.webDavTag)
        return try {
            if (book.isLocal && !book.bookUrl.startsWith(BookType.webDavTag)) {
                val identities = FileBook.createLocalBookIdentities(book)
                GeneratedBookProgressIdentity(
                    progressKey = identities.bookProgressKey,
                    localFileKey = identities.localFileKey
                )
            } else {
                val progressKey = BookProgressIdentity.fromBookUrl(book.bookUrl) ?: return null
                GeneratedBookProgressIdentity(progressKey)
            }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            val reason = if (isLocalFileBook) "localBookUnreadable" else "identityUnavailable"
            AppLog.put(
                "WebDav progressIdentity skip reason=$reason book=${book.name}\n" +
                        e.localizedMessage,
                e
            )
            null
        }
    }

    private fun persistBookProgressIdentity(
        book: Book,
        identity: GeneratedBookProgressIdentity
    ) {
        book.bookProgressKey = identity.progressKey
        if (book.localFileKey == null && identity.localFileKey != null) {
            book.localFileKey = identity.localFileKey
        }
        appDb.bookDao.updateBookProgressIdentity(
            book.bookUrl,
            identity.progressKey,
            identity.localFileKey
        )
    }

    private fun persistPendingBookProgressIdentity(
        book: Book,
        resolvedTarget: ResolvedBookProgressTarget
    ) {
        resolvedTarget.pendingIdentity?.let {
            persistBookProgressIdentity(book, it)
        }
    }

    private suspend fun prepareBookProgress(bookProgress: BookProgress): PreparedBookProgress? {
        val persistedBook = bookProgress.bookUrl?.let(appDb.bookDao::getBook)
            ?: appDb.bookDao.getBooksByNameAndAuthor(bookProgress.name, bookProgress.author)
                .singleOrNull()
        if (persistedBook != null) {
            val resolvedTarget = resolveBookProgressTarget(persistedBook)
            val target = resolvedTarget.target
            if (target.candidates.isEmpty()) return null
            return PreparedBookProgress(
                progress = bookProgress.copy(
                    name = persistedBook.name,
                    author = persistedBook.author,
                    bookProgressKey = target.progressKey,
                    bookUrl = persistedBook.bookUrl
                ),
                resolvedTarget = resolvedTarget,
                persistedBook = persistedBook
            )
        }
        if (bookProgress.bookProgressKey == null && bookProgress.bookUrl != null) return null
        val target = BookProgressStorageTarget(
            progressKey = bookProgress.bookProgressKey,
            legacyFileName = getLegacyProgressFileName(bookProgress.name, bookProgress.author),
            allowLegacyFallback = bookProgress.bookProgressKey == null
        )
        return PreparedBookProgress(
            bookProgress,
            ResolvedBookProgressTarget(target)
        )
    }

    /**
     * 获取书籍进度
     */
    override suspend fun getBookProgress(book: Book): BookProgress? {
        return getBookProgressResult(book)
            .onFailure {
                currentCoroutineContext().ensureActive()
                AppLog.put(
                    "获取书籍进度失败 book=${book.name}\n${it.localizedMessage}",
                    it
                )
            }.getOrNull()
    }

    override suspend fun getBookProgressResult(book: Book): Result<BookProgress?> {
        val authorization = authorization ?: run {
            AppLog.putDebug("WebDav getBookProgress skip reason=noAuthorization book=${book.name}")
            return Result.success(null)
        }
        val resolvedTarget = resolveBookProgressTarget(book)
        val target = resolvedTarget.target
        if (target.candidates.isEmpty()) {
            AppLog.putDebug(
                "WebDav getBookProgress skip reason=unavailableProgressIdentity book=${book.name}"
            )
            return Result.success(null)
        }
        return kotlin.runCatching {
            for (progressFileName in target.candidates) {
                if (isMissingBookProgressCached(progressFileName)) {
                    AppLog.putDebug(
                        "WebDav getBookProgress skip reason=remoteMissingCached file=${progressFileName}"
                    )
                    continue
                }
                try {
                    val progress = when (
                        val downloaded = downloadBookProgress(target, progressFileName, authorization)
                    ) {
                        BookProgressDownloadResult.InvalidJson -> {
                            AppLog.put(
                                "WebDav getBookProgress skip reason=invalidJson file=${progressFileName}"
                            )
                            if (resolvedTarget.pendingIdentity != null) continue
                            return@runCatching null
                        }

                        BookProgressDownloadResult.IdentityMismatch -> {
                            AppLog.put(
                                "WebDav getBookProgress skip reason=identityMismatch file=${progressFileName}"
                            )
                            if (resolvedTarget.pendingIdentity != null) continue
                            return@runCatching null
                        }

                        is BookProgressDownloadResult.Success -> downloaded.progress
                    }
                    if (resolvedTarget.pendingIdentity != null) {
                        if (!isBookProgressInRange(book, progress)) {
                            AppLog.put(
                                "WebDav getBookProgress skip reason=outOfRange file=${progressFileName} " +
                                        "book=${book.name}"
                            )
                            continue
                        }
                        // A valid v2 appears first and returns before legacy is considered.
                    }
                    establishPendingBookProgressIdentity(
                        book,
                        resolvedTarget,
                        progressFileName,
                        progress
                    )
                    missingBookProgressFiles.remove(progressFileName)
                    AppLog.putDebug(
                        "WebDav getBookProgress success file=${progressFileName} " +
                                "chapter=${progress.durChapterIndex} pos=${progress.durChapterPos}"
                    )
                    return@runCatching progress
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    if (!e.isBookProgressNotFound()) throw e
                    markMissingBookProgress(progressFileName)
                    AppLog.putDebug(
                        "WebDav getBookProgress skip reason=remoteMissing file=${progressFileName}"
                    )
                }
            }
            null
        }
    }

    override fun canApplyBookProgress(
        book: Book,
        bookProgress: BookProgress,
        logPrefix: String,
        mode: ProgressCheckMode
    ): Boolean {
        val expectedProgressKey = book.bookProgressKey
        if (expectedProgressKey != null && bookProgress.bookProgressKey != null &&
            bookProgress.bookProgressKey != expectedProgressKey
        ) {
            AppLog.put("$logPrefix skip reason=identityMismatch book=${book.name}")
            return false
        }
        return checkBookProgress(book, bookProgress, logPrefix, mode) == BookProgressCheckResult.CanApply
    }

    private fun isMissingBookProgressCached(progressFileName: String): Boolean {
        val expireAt = missingBookProgressFiles[progressFileName] ?: return false
        if (expireAt > SystemClock.uptimeMillis()) {
            return true
        }
        missingBookProgressFiles.remove(progressFileName, expireAt)
        return false
    }

    private fun markMissingBookProgress(progressFileName: String) {
        missingBookProgressFiles[progressFileName] =
            SystemClock.uptimeMillis() + missingBookProgressCacheMillis
    }

    private fun Throwable.isBookProgressNotFound(): Boolean {
        if (this is ObjectNotFoundException) {
            return true
        }
        val detail = localizedMessage ?: message ?: return false
        return this is WebDavException &&
                (detail.contains("\n404:") || detail.contains("404:Not Found", ignoreCase = true))
    }

    private fun checkBookProgress(
        book: Book,
        bookProgress: BookProgress,
        logPrefix: String,
        mode: ProgressCheckMode
    ): BookProgressCheckResult {
        val maxChapterIndex = book.simulatedTotalChapterNum()
        if (maxChapterIndex <= 0 || bookProgress.durChapterIndex !in 0 until maxChapterIndex) {
            AppLog.put(
                "$logPrefix skip reason=outOfRange " +
                        "book=${book.name} remoteChapter=${bookProgress.durChapterIndex} " +
                        "maxChapter=${maxChapterIndex}"
            )
            return BookProgressCheckResult.OutOfRange
        }
        if (mode == ProgressCheckMode.ReadableRequired && book.isLocal) {
            kotlin.runCatching {
                FileBook.checkBookReadable(book)
            }.onFailure {
                AppLog.put(
                    "$logPrefix skip reason=localBookUnreadable " +
                            "book=${book.name}\n${it.localizedMessage}", it
                )
                return BookProgressCheckResult.LocalBookUnreadable
            }
        }
        return BookProgressCheckResult.CanApply
    }

    private suspend fun downloadBookProgress(
        progressFileName: String,
        authorization: Authorization
    ): BookProgress? {
        val url = bookProgressUrl + progressFileName
        return WebDav(url, authorization).download().let { byteArray ->
            val json = String(byteArray)
            if (json.isJson()) {
                return@let GSON.fromJsonObject<BookProgress>(json).getOrNull()
            }
            null
        }
    }

    private suspend fun downloadBookProgress(
        target: BookProgressStorageTarget,
        progressFileName: String,
        authorization: Authorization
    ): BookProgressDownloadResult {
        val progress = downloadBookProgress(progressFileName, authorization)
            ?: return BookProgressDownloadResult.InvalidJson
        if (!target.accepts(progressFileName, progress)) {
            return BookProgressDownloadResult.IdentityMismatch
        }
        return BookProgressDownloadResult.Success(progress)
    }

    private suspend fun downloadAvailableBookProgress(
        book: Book,
        target: BookProgressStorageTarget,
        availableFileNames: Set<String>,
        authorization: Authorization,
        allowLegacyMigrationFallback: Boolean
    ): DownloadedBookProgress? {
        var fallback: DownloadedBookProgress? = null
        for (progressFileName in target.candidates) {
            if (progressFileName !in availableFileNames) continue
            val downloaded = DownloadedBookProgress(
                progressFileName,
                downloadBookProgress(target, progressFileName, authorization)
            )
            when (val result = downloaded.result) {
                is BookProgressDownloadResult.Success -> {
                    if (!allowLegacyMigrationFallback) {
                        return downloaded
                    }
                    if (!isBookProgressInRange(book, result.progress)) {
                        if (fallback == null) fallback = downloaded
                        continue
                    }
                    // A valid v2 appears first and returns before legacy is considered.
                    return downloaded
                }

                BookProgressDownloadResult.InvalidJson,
                BookProgressDownloadResult.IdentityMismatch -> {
                    if (fallback == null) fallback = downloaded
                }
            }
            if (!allowLegacyMigrationFallback) return downloaded
        }
        return fallback
    }

    private suspend fun establishPendingBookProgressIdentity(
        book: Book,
        resolvedTarget: ResolvedBookProgressTarget,
        progressFileName: String,
        progress: BookProgress,
        preferRemoteProgress: Boolean = false
    ) {
        val pendingIdentity = resolvedTarget.pendingIdentity ?: return
        val target = resolvedTarget.target
        if (!isBookProgressInRange(book, progress)) return
        val currentProgress = BookProgress(book)
        val progressToMigrate = when (progressFileName) {
            target.identityFileName -> when {
                BookProgressMigration.isMoreRecentThan(progress, currentProgress) -> return
                BookProgressMigration.isMoreRecentThan(currentProgress, progress) -> currentProgress
                else -> {
                    persistBookProgressIdentity(book, pendingIdentity)
                    return
                }
            }

            target.legacyFileName -> if (preferRemoteProgress ||
                !BookProgressMigration.isMoreRecentThan(currentProgress, progress)
            ) {
                progress
            } else {
                currentProgress
            }

            else -> return
        }.copy(
            name = book.name,
            author = book.author,
            bookProgressKey = pendingIdentity.progressKey,
            bookUrl = book.bookUrl
        )
        uploadBookProgressJson(
            progress = progressToMigrate,
            fileName = checkNotNull(target.identityFileName),
            toast = false,
            onSuccess = null,
            onUploaded = {
                if (!BookProgressMigration.isMoreRecentThan(progressToMigrate, BookProgress(book))) {
                    persistBookProgressIdentity(book, pendingIdentity)
                }
            }
        )
    }

    /**
     * 旧唯一本地书首次上传 v2 前，优先验证 v2；只有 v2 不可用时才读取 legacy，
     * 再将可信远端与本地较新的有效位置写入 v2。
     */
    private suspend fun selectPendingMigrationUploadProgress(
        book: Book,
        resolvedTarget: ResolvedBookProgressTarget,
        localProgress: BookProgress
    ): Result<PendingMigrationUploadProgress> {
        if (resolvedTarget.pendingIdentity == null) {
            return Result.success(PendingMigrationUploadProgress(localProgress))
        }
        val authorization = authorization
            ?: return Result.failure(NoStackTraceException("webDav没有配置"))
        val target = resolvedTarget.target
        var remoteProgress: BookProgress? = null
        for (progressFileName in target.candidates) {
            val downloaded = try {
                downloadBookProgress(target, progressFileName, authorization)
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                if (e.isBookProgressNotFound()) continue
                return Result.failure(e)
            }
            val candidate = when (downloaded) {
                BookProgressDownloadResult.InvalidJson -> {
                    AppLog.put(
                        "WebDav uploadBookProgress skip reason=invalidJson file=$progressFileName"
                    )
                    continue
                }

                BookProgressDownloadResult.IdentityMismatch -> {
                    AppLog.put(
                        "WebDav uploadBookProgress skip reason=identityMismatch file=$progressFileName"
                    )
                    continue
                }

                is BookProgressDownloadResult.Success -> downloaded.progress
            }
            if (!isBookProgressInRange(book, candidate)) {
                AppLog.put(
                    "WebDav uploadBookProgress skip reason=outOfRange file=$progressFileName " +
                            "book=${book.name}"
                )
                continue
            }
            // A valid v2 appears first and prevents any legacy read.
            remoteProgress = candidate
            break
        }
        val remoteProgressToApply = remoteProgress
            ?.takeIf { BookProgressMigration.isMoreRecentThan(it, localProgress) }
        val progressToUpload = remoteProgressToApply ?: localProgress
        return Result.success(
            PendingMigrationUploadProgress(
                progress = progressToUpload.copy(
                    name = book.name,
                    author = book.author,
                    bookProgressKey = target.progressKey,
                    bookUrl = book.bookUrl
                ),
                remoteProgressToApply = remoteProgressToApply
            )
        )
    }

    private fun isBookProgressInRange(book: Book, progress: BookProgress): Boolean {
        val maxChapterIndex = book.simulatedTotalChapterNum()
        return maxChapterIndex > 0 && progress.durChapterIndex in 0 until maxChapterIndex
    }

    private fun applyPendingMigrationRemoteProgress(
        book: Book,
        remoteProgress: BookProgress?
    ) {
        remoteProgress ?: return
        book.durChapterIndex = remoteProgress.durChapterIndex
        book.durChapterPos = remoteProgress.durChapterPos
        book.durChapterTitle = remoteProgress.durChapterTitle
        book.durChapterTime = remoteProgress.durChapterTime
        book.syncTime = System.currentTimeMillis()
        appDb.bookDao.update(book)
    }

    override suspend fun downloadAllBookProgress() {
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
            var skippedIdentity = 0
            appDb.bookDao.all.forEach { book ->
                val resolvedTarget = resolveBookProgressTarget(book)
                val target = resolvedTarget.target
                val progressFileName = target.selectAvailable(map.keys)
                if (progressFileName == null) {
                    if (target.candidates.isEmpty()) skippedIdentity++
                    return@forEach
                }
                WebBookProgressSyncCoordinator.withBook(
                    target.progressKey ?: book.bookUrl
                ) bookLock@{
                    val selectedWebDavFile = map[progressFileName] ?: return@bookLock
                    matchedCount++
                    if (resolvedTarget.pendingIdentity == null &&
                        selectedWebDavFile.lastModify <= book.syncTime
                    ) {
                        //本地同步时间大于上传时间不用同步
                        return@bookLock
                    }
                    val downloaded = downloadAvailableBookProgress(
                        book = book,
                        target = target,
                        availableFileNames = map.keys,
                        authorization = authorization,
                        allowLegacyMigrationFallback = resolvedTarget.pendingIdentity != null
                    ) ?: return@bookLock
                    when (val result = downloaded.result) {
                        BookProgressDownloadResult.InvalidJson -> {
                            AppLog.put(
                                "WebDav downloadAllBookProgress skip reason=invalidJson " +
                                        "file=${downloaded.fileName}"
                            )
                        }

                        BookProgressDownloadResult.IdentityMismatch -> {
                            skippedIdentity++
                            AppLog.put(
                                "WebDav downloadAllBookProgress skip reason=identityMismatch " +
                                        "file=${downloaded.fileName}"
                            )
                        }

                        is BookProgressDownloadResult.Success -> {
                            val bookProgress = result.progress
                            if (!canApplyBookProgress(
                                    book,
                                    bookProgress,
                                    "WebDav downloadAllBookProgress",
                                    ProgressCheckMode.RangeOnly
                                )
                            ) {
                                return@bookLock
                            }
                            if (bookProgress.compareReadPosition(book) > 0) {
                                book.durChapterIndex = bookProgress.durChapterIndex
                                book.durChapterPos = bookProgress.readChapterPos
                                book.durChapterTitle = bookProgress.durChapterTitle
                                book.durChapterTime = bookProgress.durChapterTime
                                book.syncTime = System.currentTimeMillis()
                                appDb.bookDao.update(book)
                                updatedCount++
                            }
                            establishPendingBookProgressIdentity(
                                book,
                                resolvedTarget,
                                downloaded.fileName,
                                bookProgress
                            )
                        }
                    }
                }
            }
            AppLog.putDebug(
                "WebDav downloadAllBookProgress success " +
                        "remoteFiles=${bookProgressFiles.size} matched=${matchedCount} " +
                        "updated=${updatedCount} skippedIdentity=${skippedIdentity}"
            )
        }.onFailure {
            currentCoroutineContext().ensureActive()
            AppLog.put("WebDav全量同步阅读进度失败\n${it.localizedMessage}", it)
        }
    }

    override suspend fun restoreBookProgressOnly() {
        val authorization = authorization ?: throw NoStackTraceException("webDav没有配置")
        if (!NetworkUtils.isAvailable()) throw NoStackTraceException("网络未连接")
        kotlin.runCatching {
            val bookProgressFiles = WebDav(bookProgressUrl, authorization).listFiles()
            val map = hashMapOf<String, WebDavFile>()
            bookProgressFiles.forEach {
                map[it.displayName] = it
            }
            var matchedCount = 0
            var updatedCount = 0
            var skippedInvalid = 0
            var skippedIdentity = 0
            var skippedUnreadable = 0
            var skippedOutOfRange = 0
            appDb.bookDao.all.forEach { book ->
                val resolvedTarget = resolveBookProgressTarget(book)
                val target = resolvedTarget.target
                val progressFileName = target.selectAvailable(map.keys)
                if (progressFileName == null) {
                    if (target.candidates.isEmpty()) skippedIdentity++
                    return@forEach
                }
                matchedCount++
                val downloaded = downloadAvailableBookProgress(
                    book = book,
                    target = target,
                    availableFileNames = map.keys,
                    authorization = authorization,
                    allowLegacyMigrationFallback = resolvedTarget.pendingIdentity != null
                ) ?: return@forEach
                val bookProgress = when (val result = downloaded.result) {
                    BookProgressDownloadResult.InvalidJson -> {
                        skippedInvalid++
                        return@forEach
                    }

                    BookProgressDownloadResult.IdentityMismatch -> {
                        skippedIdentity++
                        return@forEach
                    }

                    is BookProgressDownloadResult.Success -> result.progress
                }
                when (
                    checkBookProgress(
                        book,
                        bookProgress,
                        "WebDav restoreProgressOnly",
                        ProgressCheckMode.RangeOnly
                    )
                    ) {
                    BookProgressCheckResult.CanApply -> {
                        book.durChapterIndex = bookProgress.durChapterIndex
                        book.durChapterPos = bookProgress.durChapterPos
                        book.durChapterTitle = bookProgress.durChapterTitle
                        book.durChapterTime = bookProgress.durChapterTime
                        book.syncTime = System.currentTimeMillis()
                        appDb.bookDao.update(book)
                        updatedCount++
                        establishPendingBookProgressIdentity(
                            book,
                            resolvedTarget,
                            downloaded.fileName,
                            bookProgress,
                            preferRemoteProgress = true
                        )
                    }

                    BookProgressCheckResult.OutOfRange -> skippedOutOfRange++
                    BookProgressCheckResult.LocalBookUnreadable -> skippedUnreadable++
                }
            }
            AppLog.putDebug(
                "WebDav restoreProgressOnly success " +
                        "remoteFiles=${bookProgressFiles.size} matched=${matchedCount} " +
                        "updated=${updatedCount} skippedUnreadable=${skippedUnreadable} " +
                        "skippedOutOfRange=${skippedOutOfRange} skippedInvalid=${skippedInvalid} " +
                        "skippedIdentity=${skippedIdentity}"
            )
        }.onFailure {
            currentCoroutineContext().ensureActive()
            AppLog.put("WebDav仅恢复阅读进度失败\n${it.localizedMessage}", it)
        }.getOrThrow()
    }

}
