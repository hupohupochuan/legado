package io.legado.app.lib.cronet

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class CronetRedirectTest {

    @Test
    fun postChangesToGetOn301302303() {
        for (code in listOf(301, 302, 303)) {
            assertChangesToGet("POST", code)
        }
    }

    @Test
    fun temporaryAndPermanentRedirectsKeepMethodAndBody() {
        for (method in listOf("POST", "PUT", "QUERY", "PROPFIND")) {
            for (code in listOf(307, 308)) {
                assertKeepsBody(method, code)
            }
        }
    }

    @Test
    fun webDavPropfindKeepsItsBodyOnAllRedirects() {
        for (code in listOf(301, 302, 303, 307, 308)) {
            assertKeepsBody("PROPFIND", code)
        }
    }

    @Test
    fun queryKeepsBodyOn301302ButChangesToGetOn303() {
        assertKeepsBody("QUERY", 301)
        assertKeepsBody("QUERY", 302)
        assertChangesToGet("QUERY", 303)
    }

    @Test
    fun getAndHeadRemainBodyless() {
        for (method in listOf("GET", "HEAD")) {
            val original = Request.Builder().url("https://reader.example/old").method(method, null).build()
            for (code in listOf(301, 302, 303, 307, 308)) {
                val redirected = redirect(original, code)
                assertEquals(method, redirected.method)
                assertNull(redirected.body)
            }
        }
    }

    private fun assertChangesToGet(method: String, code: Int) {
        val redirected = redirect(requestWithBody(method), code)
        assertEquals("$method $code", "GET", redirected.method)
        assertNull(redirected.body)
        for (header in listOf("Content-Type", "Content-Length", "Transfer-Encoding")) {
            assertNull(redirected.header(header))
        }
    }

    private fun assertKeepsBody(method: String, code: Int) {
        val original = requestWithBody(method)
        val redirected = redirect(original, code)
        assertEquals("$method $code", method, redirected.method)
        assertSame(original.body, redirected.body)
        for (header in listOf("Content-Type", "Content-Length", "Transfer-Encoding")) {
            assertEquals(original.header(header), redirected.header(header))
        }
    }

    private fun requestWithBody(method: String): Request = Request.Builder()
        .url("https://reader.example/old")
        .method(method, "body".toRequestBody())
        .header("Content-Type", "text/plain")
        .header("Content-Length", "4")
        .header("Transfer-Encoding", "chunked")
        .build()

    private fun redirect(request: Request, code: Int): Request {
        val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(code).message("Redirect").build()
        return AbsCallBack.buildRedirectRequest(response, request.method, "https://reader.example/new").also {
            assertEquals("https://reader.example/new", it.url.toString())
        }
    }
}
