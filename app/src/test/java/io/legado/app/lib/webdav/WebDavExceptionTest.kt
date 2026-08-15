package io.legado.app.lib.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavExceptionTest {

    @Test
    fun `http 404 is object not found even without a response message`() {
        val error = webDavStatusException(
            safePath = "https://example.com/bookProgress/v2-progress.json",
            statusCode = 404
        )

        assertTrue(error is ObjectNotFoundException)
        assertEquals(
            "https://example.com/bookProgress/v2-progress.json doesn't exist. code:404",
            error?.message
        )
    }

    @Test
    fun `non missing http status keeps existing error handling`() {
        assertNull(webDavStatusException("https://example.com/bookProgress/", 401))
        assertNull(webDavStatusException("https://example.com/bookProgress/", 500))
    }
}
