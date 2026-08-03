package io.legado.app.model.fileBook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class LocalBookIdentityTest {

    @Test
    fun `same path and content produce stable identity`() {
        val first = identity("file:///books/a.txt", "正文")
        val second = identity("file:///books/a.txt", "正文")

        assertEquals(first, second)
        assertTrue(first.matches(Regex("sha1:[0-9a-f]{40}")))
    }

    @Test
    fun `path and content both participate in identity`() {
        val original = identity("file:///books/a.txt", "正文")

        assertNotEquals(original, identity("file:///other/a.txt", "正文"))
        assertNotEquals(original, identity("file:///books/a.txt", "不同正文"))
    }

    private fun identity(path: String, content: String): String {
        return ByteArrayInputStream(content.toByteArray()).use {
            LocalBookIdentity.create(path, it)
        }
    }
}
