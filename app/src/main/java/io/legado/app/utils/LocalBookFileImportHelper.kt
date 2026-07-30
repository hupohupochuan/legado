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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
 * 核心安全约束：
 * 1. 跨 Provider 的 Uri 可能指向同一底层文件，必须先通过 dev/ino 判断，相同则跳过复制。
 * 2. 无法确认身份时，必须先把源文件完整复制到 App 临时文件，校验非空后再替换目标。
 * 3. 禁止在源、目标可能是同一文件时直接以 "wt" 打开目标并复制源流。
 * 4. 已有目标必须“备份成功并校验后才能写入”；写后校验大小/SHA-256；失败必须回滚；
 *    回滚失败向上传播并保留备份路径；新目标失败时删除。
 * 5. 所有复制阶段均为可取消分块复制；阶段间执行 ensureActive()；取消后的回滚清理放入
 *    NonCancellable，清理完成前不进入成功状态。
 */
object LocalBookFileImportHelper {

    /**
     * 导入阶段，用于进度回调区分当前工作。
     */
    enum class ImportStage {
        STAGING, BACKING_UP, WRITING, VERIFYING, ROLLING_BACK
    }

    /**
     * 进度回调。total 未知时为 -1。
     */
    typealias ImportProgressCallback = (stage: ImportStage, copied: Long, total: Long) -> Unit

    /**
     * 底层文件身份标识，通过只读文件描述符的 st_dev / st_ino 获得。
     */
    data class FileIdentity(
        val dev: Long,
        val ino: Long
    )

    /**
     * 复制结果，包含实际字节数和 SHA-256 摘要。
     */
    private data class CopyResult(
        val size: Long,
        val sha256: ByteArray
    )

    /**
     * 备份结果，包含备份文件、原始大小和摘要。
     */
    private data class BackupResult(
        val file: File,
        val size: Long,
        val sha256: ByteArray
    )

    /**
     * 判断两个 Uri 是否指向同一个底层文件。
     *
     * 契约：URI 相同但底层文件不存在或不是普通文件时返回 false；
     * 跨 Provider 通过只读文件描述符的 st_dev/st_ino 比较。
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
            if (sameId) return true
        }
        val leftId = fileIdentity(context, left) ?: return false
        val rightId = fileIdentity(context, right) ?: return false
        return leftId == rightId
    }

    /**
     * 获取 Uri 对应的底层文件身份。仅当文件存在、为普通文件且 inode 有效时返回非空。
     */
    fun fileIdentity(context: Context, uri: Uri): FileIdentity? {
        return runCatching {
            if (uri.isContentScheme()) {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    val stat = Os.fstat(pfd.fileDescriptor)
                    stat.toIdentityOrNull()
                }
            } else {
                val path = uri.path ?: return null
                val file = File(path)
                if (!file.exists() || !file.isFile) return null
                ParcelFileDescriptor.open(
                    file,
                    ParcelFileDescriptor.MODE_READ_ONLY
                ).use { pfd ->
                    val stat = Os.fstat(pfd.fileDescriptor)
                    stat.toIdentityOrNull()
                }
            }
        }.getOrNull()
    }

    private fun android.system.StructStat.toIdentityOrNull(): FileIdentity? {
        return if (st_mode and OsConstants.S_IFMT == OsConstants.S_IFREG && st_ino != 0L) {
            FileIdentity(st_dev, st_ino)
        } else null
    }

    /**
     * 安全复制源文件到目标 DocumentFile（已存在则覆盖）。
     *
     * 流程：
     * 1. 源完整复制到 App 临时文件并计算 SHA-256；
     * 2. 校验实际字节数，0 字节抛 [EmptyFileException]；
     * 3. 已有目标必须备份成功并校验后才能写入；
     * 4. 将临时文件写入目标；
     * 5. 重新读取目标并校验大小/SHA-256；
     * 6. 失败必须回滚；回滚失败向上传播并保留备份路径；
     * 7. 新目标失败时删除；清理完成前不进入成功状态。
     *
     * @return 写入后的目标 [FileDoc]
     */
    suspend fun copyToDocument(
        context: Context,
        sourceUri: Uri,
        targetDocUri: Uri,
        tempDir: File = context.externalCacheDir ?: context.cacheDir,
        onProgress: ImportProgressCallback? = null
    ): FileDoc {
        if (!targetDocUri.isContentScheme()) {
            val targetFile = File(
                targetDocUri.path ?: throw NoStackTraceException("无效目标路径")
            )
            copyToFile(context, sourceUri, targetFile, tempDir, onProgress)
            return FileDoc.fromFile(targetFile)
        }
        val tempFile = File(tempDir, "import_tmp_${System.currentTimeMillis()}")
        var backup: BackupResult? = null
        var isNewTarget = false
        try {
            // 1. 复制源到临时文件
            val copyResult = readSourceToTempCancellable(
                context, sourceUri, tempFile, ImportStage.STAGING, onProgress
            )
            if (copyResult.size == 0L) {
                throw EmptyFileException("Unexpected empty File")
            }

            // 2. 判断目标是否为新文件
            val targetDoc = DocumentFile.fromSingleUri(context, targetDocUri)
            isNewTarget = targetDoc?.exists() != true

            // 3. 已有目标必须备份成功并校验后才能写入
            if (!isNewTarget) {
                backup = backupDocumentCancellable(
                    context, targetDocUri, tempDir, onProgress
                ) ?: throw NoStackTraceException("目标文件备份失败，拒绝覆盖")
            }

            // 4. 写入目标
            writeTempToDocumentCancellable(
                context, tempFile, targetDocUri, ImportStage.WRITING, onProgress
            )

            // 5. 重新读取目标并校验大小/SHA-256
            onProgress?.invoke(ImportStage.VERIFYING, copyResult.size, copyResult.size)
            verifyDocumentContentCancellable(context, targetDocUri, copyResult.size, copyResult.sha256)

            // 6. 校验通过后清理备份
            backup?.file?.delete()
            return FileDoc.fromUri(targetDocUri, false)
        } catch (e: CancellationException) {
            // 取消：NonCancellable 中完成回滚与清理，然后继续抛出
            withContext(NonCancellable) {
                backup?.let {
                    onProgress?.invoke(ImportStage.ROLLING_BACK, 0, -1)
                    restoreDocumentBackupCancellable(context, it, targetDocUri)
                }
                if (isNewTarget) {
                    runCatching { DocumentFile.fromSingleUri(context, targetDocUri)?.delete() }
                }
                tempFile.delete()
            }
            throw e
        } catch (e: Exception) {
            // 失败：回滚；回滚失败向上传播并保留备份路径
            backup?.let {
                onProgress?.invoke(ImportStage.ROLLING_BACK, 0, -1)
                restoreDocumentBackupCancellable(context, it, targetDocUri)
            }
            if (isNewTarget) {
                runCatching { DocumentFile.fromSingleUri(context, targetDocUri)?.delete() }
            }
            throw e
        } finally {
            tempFile.delete()
        }
    }

    /**
     * 安全复制源文件到目标 [File]（已存在则覆盖）。逻辑同 [copyToDocument]。
     *
     * @return 写入后的目标 [File]
     */
    suspend fun copyToFile(
        context: Context,
        sourceUri: Uri,
        targetFile: File,
        tempDir: File = context.externalCacheDir ?: context.cacheDir,
        onProgress: ImportProgressCallback? = null
    ): File {
        val tempFile = File(tempDir, "import_tmp_${System.currentTimeMillis()}")
        var backup: BackupResult? = null
        var isNewTarget = false
        try {
            val copyResult = readSourceToTempCancellable(
                context, sourceUri, tempFile, ImportStage.STAGING, onProgress
            )
            if (copyResult.size == 0L) {
                throw EmptyFileException("Unexpected empty File")
            }
            isNewTarget = !targetFile.exists()
            if (!isNewTarget) {
                backup = backupFileCancellable(targetFile, tempDir, onProgress)
            }
            writeTempToFileCancellable(tempFile, targetFile, ImportStage.WRITING, onProgress)
            onProgress?.invoke(ImportStage.VERIFYING, copyResult.size, copyResult.size)
            verifyFileContentCancellable(targetFile, copyResult.size, copyResult.sha256)
            backup?.file?.delete()
            return targetFile
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                backup?.let {
                    onProgress?.invoke(ImportStage.ROLLING_BACK, 0, -1)
                    restoreFileBackupCancellable(it, targetFile)
                }
                if (isNewTarget) {
                    targetFile.delete()
                }
                tempFile.delete()
            }
            throw e
        } catch (e: Exception) {
            backup?.let {
                onProgress?.invoke(ImportStage.ROLLING_BACK, 0, -1)
                restoreFileBackupCancellable(it, targetFile)
            }
            if (isNewTarget) {
                targetFile.delete()
            }
            throw e
        } finally {
            tempFile.delete()
        }
    }

    /**
     * 把源文件完整复制到临时文件，返回实际字节数和 SHA-256。
     */
    private suspend fun readSourceToTempCancellable(
        context: Context,
        sourceUri: Uri,
        tempFile: File,
        stage: ImportStage,
        onProgress: ImportProgressCallback? = null
    ): CopyResult {
        return sourceUri.inputStream(context).getOrThrow().use { input ->
            FileOutputStream(tempFile).use { out ->
                copyStreamCancellable(input, out, stage, onProgress)
            }
        }
    }

    /**
     * 分块可取消流复制，同时计算 SHA-256。
     */
    private suspend fun copyStreamCancellable(
        input: InputStream,
        out: OutputStream,
        stage: ImportStage,
        onProgress: ImportProgressCallback? = null
    ): CopyResult {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        var total = 0L
        var read: Int
        while (true) {
            currentCoroutineContext().ensureActive()
            read = input.read(buffer)
            if (read < 0) break
            if (read > 0) {
                out.write(buffer, 0, read)
                digest.update(buffer, 0, read)
                total += read
                onProgress?.invoke(stage, total, -1L)
            }
        }
        out.flush()
        return CopyResult(total, digest.digest())
    }

    /**
     * 备份目标 DocumentFile 到临时目录并校验。目标不存在时返回 null；备份失败抛出异常。
     */
    private suspend fun backupDocumentCancellable(
        context: Context,
        targetDocUri: Uri,
        tempDir: File,
        onProgress: ImportProgressCallback? = null
    ): BackupResult? {
        val doc = DocumentFile.fromSingleUri(context, targetDocUri) ?: return null
        if (!doc.exists()) return null
        val backup = File(tempDir, "import_bak_${System.currentTimeMillis()}_${doc.name}")
        val result = context.contentResolver.openInputStream(targetDocUri)?.use { input ->
            FileOutputStream(backup).use { out ->
                copyStreamCancellable(input, out, ImportStage.BACKING_UP, onProgress)
            }
        } ?: throw NoStackTraceException("无法打开目标文件输入流")
        val expectedSize = doc.length()
        if (expectedSize >= 0 && result.size != expectedSize) {
            backup.delete()
            throw NoStackTraceException("备份文件大小与目标不一致")
        }
        return BackupResult(backup, result.size, result.sha256)
    }

    /**
     * 使用备份恢复目标 DocumentFile。回滚失败时保留备份并抛出异常。
     */
    private suspend fun restoreDocumentBackupCancellable(
        context: Context,
        backup: BackupResult,
        targetDocUri: Uri
    ) {
        withContext(NonCancellable) {
            runCatching {
                context.contentResolver.openOutputStream(targetDocUri, "wt")?.use { out ->
                    FileInputStream(backup.file).use { input ->
                        copyStreamCancellable(input, out, ImportStage.ROLLING_BACK, null)
                    }
                } ?: throw NoStackTraceException("无法打开目标文件输出流")
            }.onFailure { e ->
                throw NoStackTraceException("回滚失败，备份保留在: ${backup.file.absolutePath}").apply {
                    initCause(e)
                }
            }
        }
    }

    /**
     * 重新读取目标 DocumentFile 并校验大小/SHA-256。
     */
    private suspend fun verifyDocumentContentCancellable(
        context: Context,
        targetDocUri: Uri,
        expectedSize: Long,
        expectedSha256: ByteArray
    ) {
        val result = context.contentResolver.openInputStream(targetDocUri)?.use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            var total = 0L
            var read: Int
            while (true) {
                currentCoroutineContext().ensureActive()
                read = input.read(buffer)
                if (read < 0) break
                if (read > 0) {
                    digest.update(buffer, 0, read)
                    total += read
                }
            }
            CopyResult(total, digest.digest())
        } ?: throw NoStackTraceException("无法打开目标文件输入流进行校验")
        if (result.size != expectedSize || !result.sha256.contentEquals(expectedSha256)) {
            throw NoStackTraceException("目标文件校验失败：大小或摘要不一致")
        }
    }

    /**
     * 将临时文件写入目标 DocumentFile。
     */
    private suspend fun writeTempToDocumentCancellable(
        context: Context,
        tempFile: File,
        targetDocUri: Uri,
        stage: ImportStage,
        onProgress: ImportProgressCallback? = null
    ) {
        context.contentResolver.openOutputStream(targetDocUri, "wt")?.use { out ->
            FileInputStream(tempFile).use { input ->
                copyStreamCancellable(input, out, stage, onProgress)
            }
        } ?: throw NoStackTraceException("无法打开目标文件输出流")
    }

    /**
     * 备份目标文件到临时目录。
     */
    private suspend fun backupFileCancellable(
        targetFile: File,
        tempDir: File,
        onProgress: ImportProgressCallback? = null
    ): BackupResult {
        val backup = File(tempDir, "import_bak_${System.currentTimeMillis()}_${targetFile.name}")
        val result = FileInputStream(targetFile).use { input ->
            FileOutputStream(backup).use { out ->
                copyStreamCancellable(input, out, ImportStage.BACKING_UP, onProgress)
            }
        }
        if (result.size != targetFile.length()) {
            backup.delete()
            throw NoStackTraceException("备份文件大小与目标不一致")
        }
        return BackupResult(backup, result.size, result.sha256)
    }

    /**
     * 使用备份恢复目标文件。回滚失败时保留备份并抛出异常。
     */
    private suspend fun restoreFileBackupCancellable(backup: BackupResult, targetFile: File) {
        withContext(NonCancellable) {
            runCatching {
                FileUtils.createFileWithReplace(targetFile.absolutePath)
                FileInputStream(backup.file).use { input ->
                    FileOutputStream(targetFile).use { out ->
                        copyStreamCancellable(input, out, ImportStage.ROLLING_BACK, null)
                    }
                }
            }.onFailure { e ->
                throw NoStackTraceException("回滚失败，备份保留在: ${backup.file.absolutePath}").apply {
                    initCause(e)
                }
            }
        }
    }

    /**
     * 将临时文件写入目标文件。
     */
    private suspend fun writeTempToFileCancellable(
        tempFile: File,
        targetFile: File,
        stage: ImportStage,
        onProgress: ImportProgressCallback? = null
    ) {
        FileUtils.createFileWithReplace(targetFile.absolutePath)
        FileInputStream(tempFile).use { input ->
            FileOutputStream(targetFile).use { out ->
                copyStreamCancellable(input, out, stage, onProgress)
            }
        }
    }

    /**
     * 重新读取目标文件并校验大小/SHA-256。
     */
    private suspend fun verifyFileContentCancellable(
        targetFile: File,
        expectedSize: Long,
        expectedSha256: ByteArray
    ) {
        val result = FileInputStream(targetFile).use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            var total = 0L
            var read: Int
            while (true) {
                currentCoroutineContext().ensureActive()
                read = input.read(buffer)
                if (read < 0) break
                if (read > 0) {
                    digest.update(buffer, 0, read)
                    total += read
                }
            }
            CopyResult(total, digest.digest())
        }
        if (result.size != expectedSize || !result.sha256.contentEquals(expectedSha256)) {
            throw NoStackTraceException("目标文件校验失败：大小或摘要不一致")
        }
    }
}

/**
 * 实际读取源文件至少一字节，确认 DocumentFile.length() 返回 0 是“真实空文件”
 * 还是“长度未知”。
 *
 * 只有成功读取到 EOF 才返回 false；权限和 I/O 异常原样向上传播。
 */
@Throws(Exception::class)
fun Uri.hasReadableContent(context: Context = appCtx): Boolean {
    return inputStream(context).getOrThrow().use { it.read() != -1 }
}
