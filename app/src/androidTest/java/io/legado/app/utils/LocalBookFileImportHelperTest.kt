package io.legado.app.utils

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.constant.AppConst
import io.legado.app.exception.EmptyFileException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/**
 * 本地书籍文件安全导入辅助类回归测试。
 *
 * 覆盖：跨 scheme 同一文件识别、安全复制、空文件、取消回滚、并发临时文件。
 * 注意：真实跨 authority（如 MediaStore vs ExternalStorageProvider）需要设备环境，
 * 当前测试以 file:// + FileProvider 模拟同一物理文件。
 */
@RunWith(AndroidJUnit4::class)
class LocalBookFileImportHelperTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var testDir: File

    @Before
    fun setup() {
        testDir = File(context.externalCacheDir, "local_book_import_test")
        testDir.mkdirs()
    }

    @After
    fun tearDown() {
        testDir.deleteRecursively()
    }

    @Test
    fun fileIdentity_detectsSamePhysicalFileAcrossSchemes() {
        val file = File(testDir, "same.txt").apply { writeText("cross scheme identity") }
        val fileUri = Uri.fromFile(file)
        val contentUri = FileProvider.getUriForFile(context, AppConst.authority, file)

        val id1 = LocalBookFileImportHelper.fileIdentity(context, fileUri)
        val id2 = LocalBookFileImportHelper.fileIdentity(context, contentUri)

        assertNotNull(id1)
        assertNotNull(id2)
        assertEquals(id1, id2)
        assertTrue(LocalBookFileImportHelper.isSameFile(context, fileUri, contentUri))
    }

    @Test
    fun isSameFile_returnsFalseForDifferentFiles() {
        val fileA = File(testDir, "a.txt").apply { writeText("a") }
        val fileB = File(testDir, "b.txt").apply { writeText("b") }
        assertFalse(
            LocalBookFileImportHelper.isSameFile(
                context,
                Uri.fromFile(fileA),
                Uri.fromFile(fileB)
            )
        )
    }

    @Test
    fun isSameFile_returnsFalseForSameUriButMissingFile() {
        val missing = File(testDir, "missing.txt")
        val uri = Uri.fromFile(missing)
        assertFalse(LocalBookFileImportHelper.isSameFile(context, uri, uri))
    }

    @Test
    fun fileIdentity_skipsMissingFile() {
        val missing = File(testDir, "missing.txt")
        assertNull(LocalBookFileImportHelper.fileIdentity(context, Uri.fromFile(missing)))
    }

    @Test
    fun copyToFile_preservesSizeAndHash() = runBlocking {
        val content = "《测试》作者：测试者\n第一章\n正文内容".toByteArray()
        val src = File(testDir, "src.txt").apply { writeBytes(content) }
        val dst = File(testDir, "dst.txt")

        LocalBookFileImportHelper.copyToFile(context, Uri.fromFile(src), dst)

        assertEquals(src.length(), dst.length())
        assertArrayEquals(content, dst.readBytes())
        assertArrayEquals(sha256(content), sha256(dst.readBytes()))
    }

    @Test
    fun copyToDocument_preservesSizeAndHash() = runBlocking {
        val content = "content for document target".toByteArray()
        val src = File(testDir, "src_doc.txt").apply { writeBytes(content) }
        val dst = File(testDir, "dst_doc.txt")

        // file:// 目标会回退到 copyToFile 逻辑，此处验证统一入口不报错
        val result = LocalBookFileImportHelper.copyToDocument(
            context,
            Uri.fromFile(src),
            Uri.fromFile(dst)
        )

        assertTrue(result.uri.isFileScheme())
        assertEquals(content.size.toLong(), result.size)
        assertArrayEquals(content, dst.readBytes())
    }

    @Test
    fun copyToFile_overwritesDifferentContent() = runBlocking {
        val src = File(testDir, "src2.txt").apply { writeText("new content") }
        val dst = File(testDir, "dst2.txt").apply { writeText("old content") }

        LocalBookFileImportHelper.copyToFile(context, Uri.fromFile(src), dst)

        assertEquals("new content", dst.readText())
    }

    @Test
    fun copyToFile_throwsForEmptyFile() = runBlocking {
        val src = File(testDir, "empty.txt").apply { writeText("") }
        val dst = File(testDir, "dst_empty.txt")

        try {
            LocalBookFileImportHelper.copyToFile(context, Uri.fromFile(src), dst)
            fail("Expected EmptyFileException")
        } catch (e: EmptyFileException) {
            // expected
        }
        assertFalse("空文件不应创建目标", dst.exists())
    }

    @Test
    fun copyToFile_rollbackOnFailure() = runBlocking {
        val src = File(testDir, "src_fail.txt").apply { writeText("new") }
        val dst = File(testDir, "dst_fail.txt").apply { writeText("original") }
        val originalBytes = dst.readBytes()

        // 通过把目标父目录设为只读制造写入失败
        val originalWritable = testDir.canWrite()
        testDir.setWritable(false)
        try {
            try {
                LocalBookFileImportHelper.copyToFile(context, Uri.fromFile(src), dst)
                fail("Expected IOException or SecurityException")
            } catch (e: Exception) {
                // expected
            }
        } finally {
            testDir.setWritable(originalWritable)
        }

        // 目标应保持原内容
        assertArrayEquals(originalBytes, dst.readBytes())
    }

    @Test
    fun copyToFile_cancelDuringCopy_rollsBack() = runBlocking {
        val largeContent = ByteArray(1024 * 1024) { it.toByte() }
        val src = File(testDir, "src_cancel.txt").apply { writeBytes(largeContent) }
        val dst = File(testDir, "dst_cancel.txt").apply { writeText("original") }
        val originalBytes = dst.readBytes()

        val job = async {
            LocalBookFileImportHelper.copyToFile(context, Uri.fromFile(src), dst)
        }
        // 立即取消，让复制在写入目标前停止
        job.cancelAndJoin()

        try {
            job.await()
            fail("Expected CancellationException")
        } catch (e: CancellationException) {
            // expected
        }

        // 目标应保持原内容
        assertArrayEquals(originalBytes, dst.readBytes())
        // 临时文件应被清理
        val leftovers = testDir.listFiles { file -> file.name.startsWith("import_tmp_") }
        assertTrue("临时文件应被清理", leftovers.isNullOrEmpty())
    }

    @Test
    fun copyToFile_concurrentTempFilesDoNotConflict() = runBlocking {
        val content = "concurrent test".toByteArray()
        val src = File(testDir, "src_concurrent.txt").apply { writeBytes(content) }
        val results = (1..5).map { index ->
            async {
                val dst = File(testDir, "dst_concurrent_$index.txt")
                LocalBookFileImportHelper.copyToFile(context, Uri.fromFile(src), dst)
                dst
            }
        }
        val targets = results.map { it.await() }
        targets.forEach { dst ->
            assertArrayEquals(content, dst.readBytes())
        }
        // 临时文件应被清理
        val leftovers = testDir.listFiles { file -> file.name.startsWith("import_tmp_") }
        assertTrue("临时文件应被清理", leftovers.isNullOrEmpty())
    }

    @Test
    fun hasReadableContent_detectsUnknownLengthButReadable() {
        val file = File(testDir, "unknown.txt").apply { writeText("not empty") }
        assertTrue(Uri.fromFile(file).hasReadableContent(context))
    }

    @Test
    fun hasReadableContent_returnsFalseForEmptyFile() {
        val file = File(testDir, "real_empty.txt").apply { writeText("") }
        assertFalse(Uri.fromFile(file).hasReadableContent(context))
    }

    @Test
    fun hasReadableContent_throwsForMissingFile() {
        val missing = File(testDir, "missing.txt")
        try {
            Uri.fromFile(missing).hasReadableContent(context)
            fail("Expected exception for missing file")
        } catch (e: Exception) {
            // expected：权限和 I/O 异常应向上传播
        }
    }

    @Suppress("SameParameterValue")
    private fun sha256(bytes: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").apply { update(bytes) }.digest()
    }
}
