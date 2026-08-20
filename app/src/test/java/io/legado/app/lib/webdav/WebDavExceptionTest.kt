package io.legado.app.lib.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

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

    @Test
    fun `unknown host is a dns resolution failure`() {
        assertTrue(UnknownHostException("dav.example.com").isWebDavDnsResolutionFailure())
    }

    @Test
    fun `cronet name not resolved message is a dns resolution failure`() {
        val error = IOException("Exception in CronetUrlRequest: net::ERR_NAME_NOT_RESOLVED")

        assertTrue(error.isWebDavDnsResolutionFailure())
    }

    @Test
    fun `wrapped dns resolution failure is recognized`() {
        val error = IllegalStateException("restore failed", UnknownHostException("dav.example.com"))

        assertTrue(error.isWebDavDnsResolutionFailure())
    }

    @Test
    fun `unrelated network error keeps existing handling`() {
        assertFalse(IOException("timeout").isWebDavDnsResolutionFailure())
    }
}
