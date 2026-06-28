package io.legado.app.web

import android.graphics.Bitmap
import fi.iki.elonen.NanoHTTPD
import io.legado.app.api.ReturnData
import io.legado.app.api.controller.BookController
import io.legado.app.api.controller.BookSourceController
import io.legado.app.api.controller.ReplaceRuleController
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.service.WebService
import io.legado.app.utils.GSON
import io.legado.app.utils.LogUtils
import io.legado.app.utils.stackTraceStr
import io.legado.app.web.utils.AssetsWeb
import kotlinx.coroutines.runBlocking
import okio.Pipe
import okio.buffer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * 基于 NanoHTTPD 的嵌入式 HTTP 服务，为 Web 端提供 RESTful API 和静态资源。
 *
 * 路由约定：
 * - POST  → 数据处理（增/删/改），通过 `runBlocking` 调用 suspend 控制器
 * - GET   → 查询（查），直接返回值或 `runBlocking`
 * - OPTIONS → CORS 预检
 * - 其他  → 静态资源（web 前端构建产物）
 */
class HttpServer(port: Int) : NanoHTTPD(port) {
    private val assetsWeb = AssetsWeb("web")

    override fun serve(session: IHTTPSession): Response {
        // 通知 WebService 保持活跃（如刷新 WakeLock）
        WebService.serve()
        val ct = ContentType(session.headers["content-type"]).tryUTF8()
        session.headers["content-type"] = ct.contentTypeHeader
        var uri = session.uri

        val startAt = System.currentTimeMillis()
        LogUtils.d(TAG) {
            "${session.method.name} - $uri - ${session.queryParameterString} - Start($startAt)"
        }

        try {
            var returnData: ReturnData? = null
            when (session.method) {
                Method.OPTIONS -> return handleCorsPreflight(session)
                Method.POST   -> returnData = handlePost(session, uri)
                Method.GET    -> returnData = handleGet(session, uri)
                else -> Unit
            }

            // 未匹配路由 → 返回静态资源（SPA fallback）
            if (returnData == null) {
                if (uri.endsWith("/"))
                    uri += "index.html"
                return assetsWeb.getResponse(uri)
            }

            return buildResponse(returnData, session, startAt)
        } catch (e: Exception) {
            LogUtils.d(TAG) {
                "${session.method.name} - $uri - ${session.queryParameterString} - Error End($startAt)\n$e\n${e.stackTraceStr}"
            }
            return newFixedLengthResponse(e.message)
        }
    }

    /** CORS 预检请求 */
    private fun handleCorsPreflight(session: IHTTPSession): Response {
        val response = newFixedLengthResponse("")
        response.addHeader("Access-Control-Allow-Methods", "POST, GET, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "content-type")
        response.addHeader("Access-Control-Allow-Origin", session.headers["origin"])
        return response
    }

    /** POST 路由分发 */
    private fun handlePost(session: IHTTPSession, uri: String): ReturnData? {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val postData = files["postData"]

        return runBlocking {
            when (uri) {
                "/saveBookSource"    -> BookSourceController.saveSource(postData)
                "/saveBookSources"   -> BookSourceController.saveSources(postData)
                "/deleteBookSources" -> BookSourceController.deleteSources(postData)
                "/saveBook"          -> BookController.saveBook(postData)
                "/deleteBook"        -> BookController.deleteBook(postData)
                "/saveBookProgress"  -> BookController.saveBookProgress(postData)
                "/addLocalBook"      -> BookController.addLocalBook(session.parameters, files)
                "/saveReadConfig"    -> BookController.saveWebReadConfig(postData)
                "/saveReplaceRule"   -> ReplaceRuleController.saveRule(postData)
                "/deleteReplaceRule" -> ReplaceRuleController.delete(postData)
                "/testReplaceRule"   -> ReplaceRuleController.testRule(postData)
                else -> null
            }
        }
    }

    /** GET 路由分发 */
    private fun handleGet(session: IHTTPSession, uri: String): ReturnData? {
        val parameters = session.parameters
        return when (uri) {
            "/getBookSource"  -> BookSourceController.getSource(parameters)
            "/getBookSources" -> BookSourceController.sources
            "/getBookshelf"   -> BookController.getBooks(parameters)
            "/getGroups"      -> BookController.groups
            "/getChapterList" -> BookController.getChapterList(parameters)
            "/refreshToc"     -> BookController.refreshToc(parameters)
            "/getBookContent" -> BookController.getBookContent(parameters)
            "/cover"          -> BookController.getCover(parameters)
            "/image"          -> BookController.getImg(parameters)
            "/getReadConfig"  -> BookController.getWebReadConfig()
            "/getReplaceRules" -> ReplaceRuleController.allRules
            else -> null
        }
    }

    /** 构建 HTTP 响应（Bitmap / 大列表 chunked / 常规 JSON） */
    private fun buildResponse(
        returnData: ReturnData,
        session: IHTTPSession,
        startAt: Long
    ): Response {
        val response = if (returnData.data is Bitmap) {
            // 图片响应
            val bitmap = returnData.data as Bitmap
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            val byteArray = outputStream.toByteArray()
            outputStream.close()
            val inputStream = ByteArrayInputStream(byteArray)
            newFixedLengthResponse(
                Response.Status.OK,
                "image/png",
                inputStream,
                byteArray.size.toLong()
            )
        } else {
            val data = returnData.data
            if (data is List<*> && data.size > 3000) {
                // 大列表 → chunked streaming，避免 OOM
                val pipe = Pipe(16 * 1024)
                Coroutine.async {
                    pipe.sink.buffer().outputStream().bufferedWriter(Charsets.UTF_8).use {
                        GSON.toJson(returnData, it)
                    }
                }
                newChunkedResponse(
                    Response.Status.OK,
                    "application/json",
                    pipe.source.buffer().inputStream()
                )
            } else {
                newFixedLengthResponse(GSON.toJson(returnData))
            }
        }
        response.addHeader("Access-Control-Allow-Methods", "GET, POST")
        response.addHeader("Access-Control-Allow-Origin", session.headers["origin"])
        LogUtils.d(TAG) {
            "${session.method.name} - ${session.uri} - ${session.queryParameterString} - End($startAt)"
        }
        return response
    }

    companion object {
        private const val TAG = "HttpServer"
    }

}
