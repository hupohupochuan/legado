package io.legado.app.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

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
    fun isJson_falseForShortNonJson() {
        ByteArrayInputStream("abc".toByteArray()).use {
            assertFalse(it.isJson())
        }
    }
}
