package io.legado.app.utils

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.system.Os
import android.system.OsConstants
import androidx.documentfile.provider.DocumentFile
import io.legado.app.exception.EmptyFileException
import io.legado.app.exception.NoStackTraceException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * 本地书籍文件安全导入辅助类。
 *
 * 所有导入都必须先暂存源文件，再备份旧目标，最后覆盖并校验。事务持有全局互斥锁，
 * 避免多个文件关联 Activity 同时修改同一目标。任何失败或取消都会在
 * [NonCancellable] 中完成回滚和临时文件清理。
 */
object LocalBookFileImportHelper {

    enum class ImportStage {
        STAGING, BACKING_UP, WRITING, VERIFYING, ROLLING_BACK
    }

    typealias ImportProgressCallback = (stage: ImportStage, copied: Long, total: Long) -> Unit

    data class FileIdentity(
        val dev: Long,
        val ino: Long
    )

    private data class CopyResult(
        val size: Long,
        val sha256: ByteArray
    )

    private data class BackupResult(
        val file: File,
        val size: Long,
        val sha256: ByteArray
    )

    private const val BUFFER_SIZE = 8192
    private const val PROGRESS_STEP_BYTES = 1024L * 1024L
    private val importMutex = Mutex()

    /**
     * 判断两个 Uri 是否指向同一个存在的普通文件。
     */
    fun isSameFile(context: Context, left: Uri, right: Uri): Boolean {
        if (left == right) {
            return fileIdentity(context, left) != null
        }
        if (left.isContentScheme() && right.isContentScheme()
            && left.authority == right.authority
        ) {
            val sameId = runCatching {
                DocumentsContract.getDocumentId(left) == DocumentsContract.getDocumentId(right)
            }.getOrDefault(false)
            if (sameId) {
                val leftId = fileIdentity(context, left) ?: return false
                return leftId == fileIdentity(context, right)
            }
        }
        val leftId = fileIdentity(context, left) ?: return false
        val rightId = fileIdentity(context, right) ?: return false
        return leftId == rightId
    }

    /**
     * 获取 Uri 对应的底层文件身份。仅普通文件且 inode 有效时返回结果。
     */
    fun fileIdentity(context: Context, uri: Uri): FileIdentity? {
        return runCatching {
            if (uri.isContentScheme()) {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    Os.fstat(pfd.fileDescriptor).toIdentityOrNull()
                }
            } else {
                val path = uri.path ?: return null
                val file = File(path)
                if (!file.exists() || !file.isFile) return null
                ParcelFileDescriptor.open(
                    file,
                    ParcelFileDescriptor.MODE_READ_ONLY
                ).use { pfd ->
                    Os.fstat(pfd.fileDescriptor).toIdentityOrNull()
                }
            }
        }.getOrNull()
    }

    private fun android.system.StructStat.toIdentityOrNull(): FileIdentity? {
        return if (st_mode and OsConstants.S_IFMT == OsConstants.S_IFREG && st_ino != 0L) {
            FileIdentity(st_dev, st_ino)
        } else {
            null
        }
    }

    /**
     * 在互斥事务内查找或创建 SAF 目标，确保“导入前是否存在”的状态不会丢失。
     */
    suspend fun copyToDocumentTree(
        context: Context,
        sourceUri: Uri,
        treeUri: Uri,
        displayName: String,
        mimeType: String,
        tempDir: File = context.externalCacheDir ?: context.cacheDir,
        onProgress: ImportProgressCallback? = null
    ): FileDoc = importMutex.withLock {
        currentCoroutineContext().ensureActive()
        val treeDoc = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw NoStackTraceException("无法读取书籍保存目录")
        val existingDoc = treeDoc.findFile(displayName)
        if (existingDoc != null && !existingDoc.isFile) {
            throw NoStackTraceException("书籍保存目录存在同名文件夹")
        }
        if (existingDoc != null && isSameFile(context, sourceUri, existingDoc.uri)) {
            return@withLock FileDoc.fromUri(existingDoc.uri, false)
        }
        val targetExisted = existingDoc != null
        val targetDoc = existingDoc ?: treeDoc.createFile(mimeType, displayName)
        ?: throw NoStackTraceException("无法创建目标文件")
        if (!targetExisted && isSameFile(context, sourceUri, targetDoc.uri)) {
            return@withLock FileDoc.fromUri(targetDoc.uri, false)
        }
        copyToDocumentLocked(
            context = context,
            sourceUri = sourceUri,
            targetDocUri = targetDoc.uri,
            targetExisted = targetExisted,
            tempDir = tempDir,
            onProgress = onProgress
        )
    }

    /**
     * 安全复制到一个已知的目标 Uri。调用方创建了新目标时必须显式传
     * `targetExisted = false`。
     */
    suspend fun copyToDocument(
        context: Context,
        sourceUri: Uri,
        targetDocUri: Uri,
        tempDir: File = context.externalCacheDir ?: context.cacheDir,
        targetExisted: Boolean = true,
        onProgress: ImportProgressCallback? = null
    ): FileDoc {
        if (!targetDocUri.isContentScheme()) {
            val targetFile = File(
                targetDocUri.path ?: throw NoStackTraceException("无效目标路径")
            )
            copyToFile(context, sourceUri, targetFile, tempDir, onProgress)
            return FileDoc.fromFile(targetFile)
        }
        try {
            return importMutex.withLock {
                currentCoroutineContext().ensureActive()
                if (targetExisted && isSameFile(context, sourceUri, targetDocUri)) {
                    return@withLock FileDoc.fromUri(targetDocUri, false)
                }
                copyToDocumentLocked(
                    context = context,
                    sourceUri = sourceUri,
                    targetDocUri = targetDocUri,
                    targetExisted = targetExisted,
                    tempDir = tempDir,
                    onProgress = onProgress
                )
            }
        } catch (cause: Throwable) {
            if (!targetExisted) {
                val cleanupFailure = withContext(NonCancellable) {
                    runCatching {
                        deleteNewDocument(context, targetDocUri)
                    }.exceptionOrNull()
                }
                if (cleanupFailure != null) {
                    cleanupFailure.addSuppressed(cause)
                    throw cleanupFailure
                }
            }
            throw cause
        }
    }

    /**
     * 安全复制到普通文件。所有调用同样经过全局导入互斥锁。
     */
    suspend fun copyToFile(
        context: Context,
        sourceUri: Uri,
        targetFile: File,
        tempDir: File = context.externalCacheDir ?: context.cacheDir,
        onProgress: ImportProgressCallback? = null
    ): File = importMutex.withLock {
        currentCoroutineContext().ensureActive()
        if (isSameFile(context, sourceUri, Uri.fromFile(targetFile))) {
            return@withLock targetFile
        }
        copyToFileLocked(context, sourceUri, targetFile, tempDir, onProgress)
    }

    private suspend fun copyToDocumentLocked(
        context: Context,
        sourceUri: Uri,
        targetDocUri: Uri,
        targetExisted: Boolean,
        tempDir: File,
        onProgress: ImportProgressCallback?
    ): FileDoc {
        var tempFile: File? = null
        var backup: BackupResult? = null
        var completed = false
        try {
            val stagingFile = createImportTempFile(tempDir, "import_tmp_")
            tempFile = stagingFile
            val copyResult = readSourceToTempCancellable(
                context, sourceUri, stagingFile, onProgress
            )
            if (copyResult.size == 0L) {
                throw EmptyFileException("Unexpected empty File")
            }
            currentCoroutineContext().ensureActive()
            if (targetExisted) {
                backup = backupDocumentCancellable(
                    context, targetDocUri, tempDir, onProgress
                )
            }
            currentCoroutineContext().ensureActive()
            writeTempToDocumentCancellable(context, stagingFile, targetDocUri, onProgress)
            verifyDocumentWithProgress(
                context, targetDocUri, copyResult, onProgress
            )
            val result = FileDoc.fromUri(targetDocUri, false)
            completed = true
            backup?.file?.delete()
            return result
        } catch (cause: Throwable) {
            val rollbackFailure = withContext(NonCancellable) {
                rollbackDocument(
                    context = context,
                    targetDocUri = targetDocUri,
                    targetExisted = targetExisted,
                    backup = backup,
                    onProgress = onProgress
                )
            }
            if (rollbackFailure != null) {
                rollbackFailure.addSuppressed(cause)
                throw rollbackFailure
            }
            throw cause
        } finally {
            withContext(NonCancellable) {
                tempFile?.delete()
                if (completed) {
                    backup?.file?.delete()
                }
            }
        }
    }

    private suspend fun copyToFileLocked(
        context: Context,
        sourceUri: Uri,
        targetFile: File,
        tempDir: File,
        onProgress: ImportProgressCallback?
    ): File {
        val targetExisted = targetFile.exists()
        var tempFile: File? = null
        var backup: BackupResult? = null
        var completed = false
        try {
            val stagingFile = createImportTempFile(tempDir, "import_tmp_")
            tempFile = stagingFile
            val copyResult = readSourceToTempCancellable(
                context, sourceUri, stagingFile, onProgress
            )
            if (copyResult.size == 0L) {
                throw EmptyFileException("Unexpected empty File")
            }
            currentCoroutineContext().ensureActive()
            if (targetExisted) {
                backup = backupFileCancellable(targetFile, tempDir, onProgress)
            }
            currentCoroutineContext().ensureActive()
            writeTempToFileCancellable(stagingFile, targetFile, onProgress)
            verifyFileWithProgress(targetFile, copyResult, onProgress)
            completed = true
            backup?.file?.delete()
            return targetFile
        } catch (cause: Throwable) {
            val rollbackFailure = withContext(NonCancellable) {
                rollbackFile(
                    targetFile = targetFile,
                    targetExisted = targetExisted,
                    backup = backup,
                    onProgress = onProgress
                )
            }
            if (rollbackFailure != null) {
                rollbackFailure.addSuppressed(cause)
                throw rollbackFailure
            }
            throw cause
        } finally {
            withContext(NonCancellable) {
                tempFile?.delete()
                if (completed) {
                    backup?.file?.delete()
                }
            }
        }
    }

    private fun createImportTempFile(tempDir: File, prefix: String): File {
        if (!tempDir.exists() && !tempDir.mkdirs()) {
            throw NoStackTraceException("无法创建导入临时目录")
        }
        return File.createTempFile(prefix, ".part", tempDir)
    }

    private suspend fun readSourceToTempCancellable(
        context: Context,
        sourceUri: Uri,
        tempFile: File,
        onProgress: ImportProgressCallback?
    ): CopyResult {
        val sizeHint = sourceSizeHint(context, sourceUri)
        return sourceUri.inputStream(context).getOrThrow().use { input ->
            FileOutputStream(tempFile).use { out ->
                copyStreamCancellable(
                    input = input,
                    out = out,
                    stage = ImportStage.STAGING,
                    totalHint = sizeHint,
                    onProgress = onProgress,
                    reportIntermediate = true
                )
            }
        }
    }

    private fun sourceSizeHint(context: Context, sourceUri: Uri): Long {
        val size = runCatching {
            if (sourceUri.isContentScheme()) {
                FileDoc.fromUri(sourceUri, false).size
            } else {
                sourceUri.path?.let(::File)?.length() ?: -1L
            }
        }.getOrDefault(-1L)
        return if (size > 0L) size else -1L
    }

    private suspend fun copyStreamCancellable(
        input: InputStream,
        out: OutputStream,
        stage: ImportStage,
        totalHint: Long,
        onProgress: ImportProgressCallback?,
        reportIntermediate: Boolean,
        emitStart: Boolean = true
    ): CopyResult {
        val coroutineContext = currentCoroutineContext()
        coroutineContext.ensureActive()
        if (emitStart) {
            onProgress?.invoke(stage, 0L, totalHint)
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        var lastReported = 0L
        while (true) {
            coroutineContext.ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            coroutineContext.ensureActive()
            out.write(buffer, 0, read)
            digest.update(buffer, 0, read)
            total += read
            if (reportIntermediate && total - lastReported >= PROGRESS_STEP_BYTES) {
                onProgress?.invoke(stage, total, totalHint)
                lastReported = total
            }
        }
        coroutineContext.ensureActive()
        out.flush()
        if (total != lastReported || total == 0L) {
            onProgress?.invoke(stage, total, totalHint)
        }
        return CopyResult(total, digest.digest())
    }

    private suspend fun backupDocumentCancellable(
        context: Context,
        targetDocUri: Uri,
        tempDir: File,
        onProgress: ImportProgressCallback?
    ): BackupResult {
        val doc = DocumentFile.fromSingleUri(context, targetDocUri)
            ?: throw NoStackTraceException("无法读取目标文件")
        if (!doc.exists() || !doc.isFile) {
            throw NoStackTraceException("目标文件不存在或不是普通文件")
        }
        val backupFile = createImportTempFile(tempDir, "import_bak_")
        try {
            val sizeHint = doc.length().takeIf { it > 0L } ?: -1L
            val copyResult = context.contentResolver.openInputStream(targetDocUri)?.use { input ->
                FileOutputStream(backupFile).use { out ->
                    copyStreamCancellable(
                        input = input,
                        out = out,
                        stage = ImportStage.BACKING_UP,
                        totalHint = sizeHint,
                        onProgress = onProgress,
                        reportIntermediate = false
                    )
                }
            } ?: throw NoStackTraceException("无法打开目标文件输入流")
            if (sizeHint > 0L && copyResult.size != sizeHint) {
                throw NoStackTraceException("备份文件大小与目标不一致")
            }
            verifyFileContentCancellable(
                backupFile, copyResult.size, copyResult.sha256
            )
            return BackupResult(backupFile, copyResult.size, copyResult.sha256)
        } catch (cause: Throwable) {
            backupFile.delete()
            throw cause
        }
    }

    private suspend fun backupFileCancellable(
        targetFile: File,
        tempDir: File,
        onProgress: ImportProgressCallback?
    ): BackupResult {
        val backupFile = createImportTempFile(tempDir, "import_bak_")
        try {
            val expectedSize = targetFile.length()
            val copyResult = FileInputStream(targetFile).use { input ->
                FileOutputStream(backupFile).use { out ->
                    copyStreamCancellable(
                        input = input,
                        out = out,
                        stage = ImportStage.BACKING_UP,
                        totalHint = expectedSize,
                        onProgress = onProgress,
                        reportIntermediate = false
                    )
                }
            }
            if (copyResult.size != expectedSize) {
                throw NoStackTraceException("备份文件大小与目标不一致")
            }
            verifyFileContentCancellable(
                backupFile, copyResult.size, copyResult.sha256
            )
            return BackupResult(backupFile, copyResult.size, copyResult.sha256)
        } catch (cause: Throwable) {
            backupFile.delete()
            throw cause
        }
    }

    private suspend fun writeTempToDocumentCancellable(
        context: Context,
        tempFile: File,
        targetDocUri: Uri,
        onProgress: ImportProgressCallback?
    ) {
        val coroutineContext = currentCoroutineContext()
        coroutineContext.ensureActive()
        onProgress?.invoke(ImportStage.WRITING, 0L, tempFile.length())
        coroutineContext.ensureActive()
        context.contentResolver.openOutputStream(targetDocUri, "wt")?.use { out ->
            FileInputStream(tempFile).use { input ->
                copyStreamCancellable(
                    input = input,
                    out = out,
                    stage = ImportStage.WRITING,
                    totalHint = tempFile.length(),
                    onProgress = onProgress,
                    reportIntermediate = false,
                    emitStart = false
                )
            }
        } ?: throw NoStackTraceException("无法打开目标文件输出流")
    }

    private suspend fun writeTempToFileCancellable(
        tempFile: File,
        targetFile: File,
        onProgress: ImportProgressCallback?
    ) {
        val coroutineContext = currentCoroutineContext()
        coroutineContext.ensureActive()
        onProgress?.invoke(ImportStage.WRITING, 0L, tempFile.length())
        coroutineContext.ensureActive()
        FileUtils.createFileWithReplace(targetFile.absolutePath)
        coroutineContext.ensureActive()
        FileInputStream(tempFile).use { input ->
            FileOutputStream(targetFile).use { out ->
                copyStreamCancellable(
                    input = input,
                    out = out,
                    stage = ImportStage.WRITING,
                    totalHint = tempFile.length(),
                    onProgress = onProgress,
                    reportIntermediate = false,
                    emitStart = false
                )
            }
        }
    }

    private suspend fun verifyDocumentWithProgress(
        context: Context,
        targetDocUri: Uri,
        expected: CopyResult,
        onProgress: ImportProgressCallback?
    ) {
        onProgress?.invoke(ImportStage.VERIFYING, 0L, expected.size)
        verifyDocumentContentCancellable(
            context, targetDocUri, expected.size, expected.sha256
        )
        onProgress?.invoke(ImportStage.VERIFYING, expected.size, expected.size)
    }

    private suspend fun verifyFileWithProgress(
        targetFile: File,
        expected: CopyResult,
        onProgress: ImportProgressCallback?
    ) {
        onProgress?.invoke(ImportStage.VERIFYING, 0L, expected.size)
        verifyFileContentCancellable(targetFile, expected.size, expected.sha256)
        onProgress?.invoke(ImportStage.VERIFYING, expected.size, expected.size)
    }

    private suspend fun verifyDocumentContentCancellable(
        context: Context,
        targetDocUri: Uri,
        expectedSize: Long,
        expectedSha256: ByteArray
    ) {
        val result = context.contentResolver.openInputStream(targetDocUri)?.use {
            digestInputCancellable(it)
        } ?: throw NoStackTraceException("无法打开目标文件输入流进行校验")
        if (result.size != expectedSize || !result.sha256.contentEquals(expectedSha256)) {
            throw NoStackTraceException("目标文件校验失败：大小或摘要不一致")
        }
    }

    private suspend fun verifyFileContentCancellable(
        targetFile: File,
        expectedSize: Long,
        expectedSha256: ByteArray
    ) {
        val result = FileInputStream(targetFile).use {
            digestInputCancellable(it)
        }
        if (result.size != expectedSize || !result.sha256.contentEquals(expectedSha256)) {
            throw NoStackTraceException("目标文件校验失败：大小或摘要不一致")
        }
    }

    private suspend fun digestInputCancellable(input: InputStream): CopyResult {
        val coroutineContext = currentCoroutineContext()
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            coroutineContext.ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) {
                digest.update(buffer, 0, read)
                total += read
            }
        }
        return CopyResult(total, digest.digest())
    }

    private suspend fun rollbackDocument(
        context: Context,
        targetDocUri: Uri,
        targetExisted: Boolean,
        backup: BackupResult?,
        onProgress: ImportProgressCallback?
    ): Throwable? {
        return runCatching {
            runCatching {
                onProgress?.invoke(ImportStage.ROLLING_BACK, 0L, backup?.size ?: -1L)
            }
            if (targetExisted) {
                val requiredBackup = backup
                    ?: return@runCatching
                restoreDocumentBackup(context, requiredBackup, targetDocUri)
                requiredBackup.file.delete()
            } else {
                deleteNewDocument(context, targetDocUri)
            }
            runCatching {
                onProgress?.invoke(
                    ImportStage.ROLLING_BACK,
                    backup?.size ?: 0L,
                    backup?.size ?: -1L
                )
            }
        }.exceptionOrNull()
    }

    private suspend fun rollbackFile(
        targetFile: File,
        targetExisted: Boolean,
        backup: BackupResult?,
        onProgress: ImportProgressCallback?
    ): Throwable? {
        return runCatching {
            runCatching {
                onProgress?.invoke(ImportStage.ROLLING_BACK, 0L, backup?.size ?: -1L)
            }
            if (targetExisted) {
                val requiredBackup = backup
                    ?: return@runCatching
                restoreFileBackup(requiredBackup, targetFile)
                requiredBackup.file.delete()
            } else {
                deleteNewFile(targetFile)
            }
            runCatching {
                onProgress?.invoke(
                    ImportStage.ROLLING_BACK,
                    backup?.size ?: 0L,
                    backup?.size ?: -1L
                )
            }
        }.exceptionOrNull()
    }

    private suspend fun restoreDocumentBackup(
        context: Context,
        backup: BackupResult,
        targetDocUri: Uri
    ) {
        try {
            context.contentResolver.openOutputStream(targetDocUri, "wt")?.use { out ->
                FileInputStream(backup.file).use { input ->
                    copyStreamCancellable(
                        input = input,
                        out = out,
                        stage = ImportStage.ROLLING_BACK,
                        totalHint = backup.size,
                        onProgress = null,
                        reportIntermediate = false
                    )
                }
            } ?: throw NoStackTraceException("无法打开目标文件输出流")
            verifyDocumentContentCancellable(
                context, targetDocUri, backup.size, backup.sha256
            )
        } catch (cause: Throwable) {
            throw rollbackException(backup.file, cause)
        }
    }

    private suspend fun restoreFileBackup(
        backup: BackupResult,
        targetFile: File
    ) {
        try {
            FileUtils.createFileWithReplace(targetFile.absolutePath)
            FileInputStream(backup.file).use { input ->
                FileOutputStream(targetFile).use { out ->
                    copyStreamCancellable(
                        input = input,
                        out = out,
                        stage = ImportStage.ROLLING_BACK,
                        totalHint = backup.size,
                        onProgress = null,
                        reportIntermediate = false
                    )
                }
            }
            verifyFileContentCancellable(targetFile, backup.size, backup.sha256)
        } catch (cause: Throwable) {
            throw rollbackException(backup.file, cause)
        }
    }

    private fun rollbackException(backupFile: File, cause: Throwable): Throwable {
        return NoStackTraceException(
            "回滚失败，备份保留在: ${backupFile.absolutePath}"
        ).apply {
            initCause(cause)
        }
    }

    private fun deleteNewDocument(context: Context, targetDocUri: Uri) {
        val targetDoc = DocumentFile.fromSingleUri(context, targetDocUri)
            ?: throw NoStackTraceException("无法定位待清理的新目标文件")
        val deleted = targetDoc.delete()
        if (!deleted && targetDoc.exists()) {
            throw NoStackTraceException("新目标文件删除失败: $targetDocUri")
        }
    }

    private fun deleteNewFile(targetFile: File) {
        if (targetFile.exists() && !targetFile.delete()) {
            throw NoStackTraceException("新目标文件删除失败: ${targetFile.absolutePath}")
        }
    }
}

/**
 * 只有成功读取到 EOF 才判定为空；权限和 I/O 异常原样向上传播。
 */
@Throws(Exception::class)
fun Uri.hasReadableContent(context: Context = appCtx): Boolean {
    return inputStream(context).getOrThrow().use { it.read() != -1 }
}
