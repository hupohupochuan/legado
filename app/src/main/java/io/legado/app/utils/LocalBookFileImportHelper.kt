package io.legado.app.utils

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.system.Os
import androidx.documentfile.provider.DocumentFile
import io.legado.app.exception.EmptyFileException
import io.legado.app.exception.NoStackTraceException
import kotlinx.coroutines.yield
import splitties.init.appCtx
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * 本地书籍文件安全导入辅助类。
 *
 * 核心安全约束：
 * 1. 跨 Provider 的 Uri 可能指向同一底层文件，必须先通过 dev/ino 判断，相同则跳过复制。
 * 2. 无法确认身份时，必须先把源文件完整复制到 App 临时文件，校验非空后再替换目标。
 * 3. 禁止在源、目标可能是同一文件时直接以 "wt" 打开目标并复制源流。
 * 4. 覆盖已有目标前必须先备份，写入失败自动回滚。
 */
object LocalBookFileImportHelper {

    /**
     * 底层文件身份标识，通过只读文件描述符的 st_dev / st_ino 获得。
     */
    data class FileIdentity(
        val dev: Long,
        val ino: Long
    )

    /**
     * 判断两个 Uri 是否指向同一个底层文件。
     *
     * 先比较 content Uri 的 authority + documentId；若不同再尝试通过 [fileIdentity]
     * 比较底层 dev/ino。该方法可识别“media/external/file/xxx”与“com.android.externalstorage/...
     * /document/xxx”指向同一物理文件的场景。
     */
    fun isSameFile(context: Context, left: Uri, right: Uri): Boolean {
        if (left == right) return true
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
     * 获取 Uri 对应的底层文件身份。content:// 通过 [ContentResolver.openFileDescriptor] 获取；
     * file:// 直接打开只读 [ParcelFileDescriptor]。失败或无法获取时返回 null。
     */
    fun fileIdentity(context: Context, uri: Uri): FileIdentity? {
        return runCatching {
            if (uri.isContentScheme()) {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    val stat = Os.fstat(pfd.fileDescriptor)
                    FileIdentity(stat.st_dev, stat.st_ino)
                }
            } else {
                val path = uri.path ?: return null
                val file = File(path)
                if (!file.exists()) return null
                ParcelFileDescriptor.open(
                    file,
                    ParcelFileDescriptor.MODE_READ_ONLY
                ).use { pfd ->
                    val stat = Os.fstat(pfd.fileDescriptor)
                    FileIdentity(stat.st_dev, stat.st_ino)
                }
            }
        }.getOrNull()
    }

    /**
     * 安全复制源文件到目标 DocumentFile（已存在则覆盖）。
     *
     * 流程：
     * 1. 源完整复制到 App 临时文件；
     * 2. 校验实际字节数，0 字节抛 [EmptyFileException]；
     * 3. 备份已有目标；
     * 4. 将临时文件写入目标；
     * 5. 写入失败时使用备份回滚；
     * 6. 清理临时文件与备份。
     *
     * @return 写入后的目标 [FileDoc]
     */
    suspend fun copyToDocument(
        context: Context,
        sourceUri: Uri,
        targetDocUri: Uri,
        tempDir: File = context.externalCacheDir ?: context.cacheDir,
        onProgress: ((copied: Long, total: Long) -> Unit)? = null
    ): FileDoc {
        if (!targetDocUri.isContentScheme()) {
            val targetFile = File(
                targetDocUri.path ?: throw NoStackTraceException("无效目标路径")
            )
            copyToFile(context, sourceUri, targetFile, tempDir, onProgress)
            return FileDoc.fromFile(targetFile)
        }
        val tempFile = File(tempDir, "import_tmp_${System.currentTimeMillis()}")
        var backupFile: File? = null
        try {
            val copiedSize = readSourceToTemp(context, sourceUri, tempFile, onProgress)
            if (copiedSize == 0L) {
                throw EmptyFileException("Unexpected empty File")
            }
            backupFile = backupDocument(context, targetDocUri, tempDir)
            writeTempToDocument(context, tempFile, targetDocUri)
            backupFile?.delete()
            return FileDoc.fromUri(targetDocUri, false)
        } catch (e: Exception) {
            backupFile?.let { restoreDocumentBackup(context, it, targetDocUri) }
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
        onProgress: ((copied: Long, total: Long) -> Unit)? = null
    ): File {
        val tempFile = File(tempDir, "import_tmp_${System.currentTimeMillis()}")
        var backupFile: File? = null
        try {
            val copiedSize = readSourceToTemp(context, sourceUri, tempFile, onProgress)
            if (copiedSize == 0L) {
                throw EmptyFileException("Unexpected empty File")
            }
            backupFile = if (targetFile.exists()) backupFile(targetFile, tempDir) else null
            FileUtils.createFileWithReplace(targetFile.absolutePath)
            FileInputStream(tempFile).use { input ->
                FileOutputStream(targetFile).use { out ->
                    input.copyTo(out)
                }
            }
            backupFile?.delete()
            return targetFile
        } catch (e: Exception) {
            backupFile?.let { restoreBackup(it, targetFile) }
            throw e
        } finally {
            tempFile.delete()
        }
    }

    /**
     * 把源文件完整复制到临时文件，返回实际字节数。
     */
    private suspend fun readSourceToTemp(
        context: Context,
        sourceUri: Uri,
        tempFile: File,
        onProgress: ((copied: Long, total: Long) -> Unit)? = null
    ): Long {
        return sourceUri.inputStream(context).getOrThrow().use { input ->
            FileOutputStream(tempFile).use { out ->
                copyWithProgress(input, out, onProgress)
            }
        }
    }

    /**
     * 带进度回调的流复制。总大小未知时 total 固定为 -1。
     */
    private suspend fun copyWithProgress(
        input: InputStream,
        out: OutputStream,
        onProgress: ((copied: Long, total: Long) -> Unit)? = null
    ): Long {
        val buffer = ByteArray(8192)
        var total = 0L
        var read: Int
        while (input.read(buffer).also { read = it } >= 0) {
            if (read > 0) {
                out.write(buffer, 0, read)
                total += read
                onProgress?.invoke(total, -1L)
            }
            yield()
        }
        out.flush()
        return total
    }

    /**
     * 备份目标 DocumentFile 到临时目录。目标不存在时返回 null。
     */
    private fun backupDocument(context: Context, targetDocUri: Uri, tempDir: File): File? {
        val doc = DocumentFile.fromSingleUri(context, targetDocUri) ?: return null
        if (doc.exists() != true) return null
        val backup = File(tempDir, "import_bak_${System.currentTimeMillis()}_${doc.name}")
        return runCatching {
            context.contentResolver.openInputStream(targetDocUri)?.use { input ->
                FileOutputStream(backup).use { out ->
                    input.copyTo(out)
                }
            }
            backup
        }.getOrNull()
    }

    /**
     * 使用备份文件恢复目标 DocumentFile。
     */
    private fun restoreDocumentBackup(context: Context, backup: File, targetDocUri: Uri) {
        runCatching {
            context.contentResolver.openOutputStream(targetDocUri, "wt")?.use { out ->
                FileInputStream(backup).use { input ->
                    input.copyTo(out)
                }
            }
        }
    }

    /**
     * 备份目标文件到临时目录。
     */
    private fun backupFile(targetFile: File, tempDir: File): File {
        val backup = File(tempDir, "import_bak_${System.currentTimeMillis()}_${targetFile.name}")
        FileInputStream(targetFile).use { input ->
            FileOutputStream(backup).use { out ->
                input.copyTo(out)
            }
        }
        return backup
    }

    /**
     * 使用备份文件恢复目标文件。
     */
    private fun restoreBackup(backup: File, targetFile: File) {
        FileUtils.createFileWithReplace(targetFile.absolutePath)
        FileInputStream(backup).use { input ->
            FileOutputStream(targetFile).use { out ->
                input.copyTo(out)
            }
        }
    }

    /**
     * 将临时文件写入目标 DocumentFile。
     */
    private fun writeTempToDocument(context: Context, tempFile: File, targetDocUri: Uri) {
        context.contentResolver.openOutputStream(targetDocUri, "wt")?.use { out ->
            FileInputStream(tempFile).use { input ->
                input.copyTo(out)
            }
        } ?: throw NoStackTraceException("无法打开目标文件输出流")
    }
}

/**
 * 实际读取源文件至少一字节，确认 DocumentFile.length() 返回 0 是“真实空文件”
 * 还是“长度未知”。
 */
fun Uri.hasReadableContent(context: Context = appCtx): Boolean {
    return runCatching {
        inputStream(context).getOrThrow().use { it.read() != -1 }
    }.getOrDefault(false)
}
