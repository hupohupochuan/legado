package io.legado.app.help.http

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPOutputStream

class DecompressInterceptorTest {

    private val text = "第一章\n压缩正文 & 阅读"

    @Test
    fun gzipResponseIsDecodedAndCompressedHeadersAreRemoved() {
        val payload = ByteArrayOutputStream().apply {
            GZIPOutputStream(this).use { it.write(text.toByteArray()) }
        }.toByteArray()
        response("gzip", payload).use {
            assertNull(it.header("Content-Encoding"))
            assertNull(it.header("Content-Length"))
            assertEquals(text, it.body.string())
        }
    }

    @Test
    fun rawDeflateResponseIsDecoded() {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        val payload = try {
            ByteArrayOutputStream().apply {
                DeflaterOutputStream(this, deflater).use { it.write(text.toByteArray()) }
            }.toByteArray()
        } finally {
            deflater.end()
        }
        response("deflate", payload).use { assertEquals(text, it.body.string()) }
    }

    @Test
    fun rangeRequestsKeepTheOriginalBytesAndEncoding() {
        val payload = byteArrayOf(1, 2, 3, 4)
        response("gzip", payload, range = true).use {
            assertEquals("gzip", it.header("Content-Encoding"))
            assertEquals("4", it.header("Content-Length"))
            assertArrayEquals(payload, it.body.bytes())
        }
    }

    private fun response(encoding: String, payload: ByteArray, range: Boolean = false): Response {
        val client = OkHttpClient.Builder()
            .addInterceptor(DecompressInterceptor)
            .addInterceptor { chain ->
                assertEquals(if (range) null else "gzip, deflate", chain.request().header("Accept-Encoding"))
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(if (range) 206 else 200)
                    .message("OK")
                    .header("Content-Encoding", encoding)
                    .header("Content-Length", payload.size.toString())
                    .body(payload.toResponseBody())
                    .build()
            }
            .build()
        val request = Request.Builder().url("https://reader.example/chapter")
        if (range) request.header("Range", "bytes=0-3")
        return client.newCall(request.build()).execute()
    }
}
