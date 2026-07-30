package io.legado.app.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class InputStreamExtensionsTest {

    @Test
    fun isJson_trueForObject() {
        ByteArrayInputStream("{\"a\":1}".toByteArray()).use {
            assertTrue(it.isJson())
        }
    }

    @Test
    fun isJson_trueForArray() {
        ByteArrayInputStream("[1,2,3]".toByteArray()).use {
            assertTrue(it.isJson())
        }
    }

    @Test
    fun isJson_falseForText() {
        ByteArrayInputStream("第一章 正文".toByteArray()).use {
            assertFalse(it.isJson())
        }
    }

    @Test
    fun isJson_falseForEmptyStream() {
        ByteArrayInputStream(ByteArray(0)).use {
            assertFalse(it.isJson())
        }
    }

    @Test
    fun isJson_detectsLongJsonWithoutAvailable() {
        // 超过 128 字节，确保依赖 available() 的旧实现会失败
        val json = buildString {
            append("{")
            repeat(50) { append("\"key$it\":\"value$it\",") }
            append("\"end\":true}")
        }
        ByteArrayInputStream(json.toByteArray()).use {
            assertTrue(it.isJson())
        }
    }

    @Test
    fun isJson_detectsVeryLongJsonAcrossMultipleBuffers() {
        // 超过多个 8192 缓冲区，验证尾部缓冲不越界且结果正确
        val json = buildString {
            append("{")
            repeat(2000) { append("\"key$it\":\"value$it\",") }
            append("\"end\":true}")
        }
        val bytes = json.toByteArray()
        assertTrue(bytes.size > 8192 * 3)
        ByteArrayInputStream(bytes).use {
            assertTrue(it.isJson())
        }
    }

    @Test
    fun isJson_detectsJsonWhenFinalChunkHasOneByte() {
        val bytes = ByteArray(128 + 8192 + 1) { ' '.code.toByte() }
        bytes[0] = '{'.code.toByte()
        bytes[bytes.lastIndex] = '}'.code.toByte()

        ByteArrayInputStream(bytes).use {
            assertTrue(it.isJson())
        }
    }

    @Test
    fun isJson_detectsJsonFromRepeatedSmallChunks() {
        val bytes = buildString {
            append('{')
            repeat(600) { append(' ') }
            append('}')
        }.toByteArray()

        listOf(1, 50, 127).forEach { chunkSize ->
            ChunkedInputStream(bytes, chunkSize).use {
                assertTrue("chunkSize=$chunkSize", it.isJson())
            }
        }
    }

    @Test
    fun isJson_worksWhenAvailableAndSkipThrow() {
        // 模拟某些 Provider 的 InputStream：available()/skip() 都不支持
        val bytes = "{\"a\":1}".toByteArray()
        val stream = object : InputStream() {
            private val delegate = ByteArrayInputStream(bytes)
            override fun read(): Int = delegate.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
            override fun available(): Int = throw UnsupportedOperationException("available not supported")
            override fun skip(n: Long): Long = throw UnsupportedOperationException("skip not supported")
        }
        stream.use {
            assertTrue(it.isJson())
        }
    }

    @Test
    fun isJson_falseForShortNonJson() {
        ByteArrayInputStream("abc".toByteArray()).use {
            assertFalse(it.isJson())
        }
    }

    private class ChunkedInputStream(
        bytes: ByteArray,
        private val chunkSize: Int
    ) : InputStream() {

        private val delegate = ByteArrayInputStream(bytes)

        override fun read(): Int = delegate.read()

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            return delegate.read(buffer, offset, minOf(length, chunkSize))
        }
    }
}
