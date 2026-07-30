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
    fun isJson_worksWhenAvailableAndSkipThrow() {
        // 模拟某些 Provider 的 InputStream：available() 返回 0，skip() 抛异常
        val bytes = "{\"a\":1}".toByteArray()
        val stream = object : InputStream() {
            private val delegate = ByteArrayInputStream(bytes)
            override fun read(): Int = delegate.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
            override fun available(): Int = 0
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
}
