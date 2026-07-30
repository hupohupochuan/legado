package io.legado.app.utils

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.constant.AppConst
import io.legado.app.exception.EmptyFileException
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
 * 重点覆盖：
 * - 跨 scheme（file:// 与 content://）指向同一底层文件的识别
 * - 安全复制前后大小、SHA-256 一致
 * - 真实空文件与覆盖场景
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
    fun hasReadableContent_detectsUnknownLengthButReadable() {
        val file = File(testDir, "unknown.txt").apply { writeText("not empty") }
        // file:// 的 length 是精确的，此函数主要覆盖 DocumentFile.length 返回 0 的 content:// 场景
        assertTrue(Uri.fromFile(file).hasReadableContent(context))
    }

    @Test
    fun hasReadableContent_returnsFalseForEmptyFile() {
        val file = File(testDir, "real_empty.txt").apply { writeText("") }
        assertFalse(Uri.fromFile(file).hasReadableContent(context))
    }

    @Test
    fun fileIdentity_skipsMissingFile() {
        val missing = File(testDir, "missing.txt")
        assertNull(LocalBookFileImportHelper.fileIdentity(context, Uri.fromFile(missing)))
        assertFalse(
            LocalBookFileImportHelper.isSameFile(
                context,
                Uri.fromFile(missing),
                Uri.fromFile(missing)
            )
        )
    }

    @Suppress("SameParameterValue")
    private fun sha256(bytes: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").apply { update(bytes) }.digest()
    }
}
