package io.legado.app.web.socket

import androidx.annotation.Keep
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import io.legado.app.help.book.BookContentSearchComplete
import io.legado.app.help.book.BookContentSearchListener
import io.legado.app.help.book.BookContentSearchProgress
import io.legado.app.help.book.BookContentSearchService
import io.legado.app.help.book.BookContentSearchStart
import io.legado.app.help.book.BookContentSearcher
import io.legado.app.help.book.WebBookContentSearchResult
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

@Keep
private data class BookContentSearchRequest(
    val bookUrl: String? = null,
    val query: String? = null,
    val maxResults: Int? = null
)

@Keep
private data class BookContentSearchStartMessage(
    val type: String = "start",
    val totalChapters: Int,
    val searchableChapters: Int,
    val isLocalBook: Boolean
)

@Keep
private data class BookContentSearchResultsMessage(
    val type: String = "results",
    val items: List<WebBookContentSearchResult>
)

@Keep
private data class BookContentSearchProgressMessage(
    val type: String = "progress",
    val scannedChapters: Int,
    val searchableChapters: Int,
    val matchCount: Int
)

@Keep
private data class BookContentSearchCompleteMessage(
    val type: String = "complete",
    val scannedChapters: Int,
    val matchCount: Int,
    val skippedUncachedChapters: Int,
    val truncated: Boolean
)

@Keep
private data class BookContentSearchErrorMessage(
    val type: String = "error",
    val message: String
)

/** Web 阅读页手机端全文搜索 WebSocket。 */
class BookContentSearchWebSocket(
    handshakeRequest: NanoHTTPD.IHTTPSession,
    private val searchService: BookContentSearchService = BookContentSearchService()
) : NanoWSD.WebSocket(handshakeRequest) {

    private val socketScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateLock = Any()
    private val sendLock = Any()
    private var activeSearchJob: Job? = null
    private var activeRequestId = 0L

    override fun onOpen() {
        socketScope.launch {
            try {
                while (isActive && isOpen) {
                    sendPing()
                    delay(PING_INTERVAL_MILLIS)
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                cancelConnection()
            }
        }
    }

    override fun onClose(
        code: NanoWSD.WebSocketFrame.CloseCode,
        reason: String,
        initiatedByRemote: Boolean
    ) {
        cancelConnection()
    }

    override fun onMessage(message: NanoWSD.WebSocketFrame) {
        startRequest(message.textPayload)
    }

    override fun onPong(pong: NanoWSD.WebSocketFrame) = Unit

    override fun onException(exception: IOException) {
        cancelConnection()
    }

    private fun startRequest(payload: String) {
        var requestId = 0L
        var previousJob: Job? = null
        lateinit var requestJob: Job
        synchronized(stateLock) {
            previousJob = activeSearchJob
            previousJob?.cancel()
            activeRequestId++
            requestId = activeRequestId
            requestJob = socketScope.launch(start = CoroutineStart.LAZY) {
                // BookHelp/FileBook 的同步读取本身不可中断；等旧 Job 真正结束后再开始，
                // 避免快速连续搜索时并发读取同一个 EPUB/缓存文件。等待屏障本身不能
                // 被第三次请求打断，否则第三次请求可能越过仍在同步读取的第一条任务。
                withContext(NonCancellable) {
                    previousJob?.join()
                }
                coroutineContext.ensureActive()
                handleRequest(requestId, payload)
            }
            activeSearchJob = requestJob
        }
        requestJob.invokeOnCompletion {
            synchronized(stateLock) {
                if (activeRequestId == requestId && activeSearchJob === requestJob) {
                    activeSearchJob = null
                }
            }
        }
        requestJob.start()
    }

    private suspend fun handleRequest(requestId: Long, payload: String) {
        try {
            val request = GSON.fromJsonObject<BookContentSearchRequest>(payload).getOrNull()
            if (request == null) {
                sendError(requestId, "请求格式错误")
                return
            }
            val bookUrl = request.bookUrl?.trim().orEmpty()
            val query = request.query?.trim().orEmpty()
            if (bookUrl.isEmpty()) {
                sendError(requestId, "书籍地址不能为空")
                return
            }
            if (query.isEmpty()) {
                sendError(requestId, "搜索关键词不能为空")
                return
            }
            if (query.length > MAX_QUERY_LENGTH) {
                sendError(requestId, "搜索关键词不能超过 ${MAX_QUERY_LENGTH} 个字符")
                return
            }
            val maxResults = (request.maxResults ?: BookContentSearcher.MAX_RESULTS)
                .coerceIn(1, BookContentSearcher.MAX_RESULTS)

            searchService.search(
                bookUrl = bookUrl,
                query = query,
                maxResults = maxResults,
                listener = socketListener(requestId)
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            if (isCurrentRequest(requestId)) {
                sendError(requestId, "搜索失败")
            }
        }
    }

    private fun socketListener(requestId: Long) = object : BookContentSearchListener {
        override suspend fun onStart(start: BookContentSearchStart) {
            sendEnvelope(
                requestId,
                BookContentSearchStartMessage(
                    totalChapters = start.totalChapters,
                    searchableChapters = start.searchableChapters,
                    isLocalBook = start.isLocalBook
                )
            )
        }

        override suspend fun onResults(items: List<WebBookContentSearchResult>) {
            sendEnvelope(requestId, BookContentSearchResultsMessage(items = items))
        }

        override suspend fun onProgress(progress: BookContentSearchProgress) {
            sendEnvelope(
                requestId,
                BookContentSearchProgressMessage(
                    scannedChapters = progress.scannedChapters,
                    searchableChapters = progress.searchableChapters,
                    matchCount = progress.matchCount
                )
            )
        }

        override suspend fun onComplete(complete: BookContentSearchComplete) {
            sendEnvelope(
                requestId,
                BookContentSearchCompleteMessage(
                    scannedChapters = complete.scannedChapters,
                    matchCount = complete.matchCount,
                    skippedUncachedChapters = complete.skippedUncachedChapters,
                    truncated = complete.truncated
                )
            )
        }
    }

    private fun sendError(requestId: Long, message: String) {
        sendEnvelope(requestId, BookContentSearchErrorMessage(message = message))
    }

    /**
     * 请求代际校验和 send 放在同一个状态锁内：新请求一旦接管连接，旧请求就不能再发送帧。
     * send 失败会取消整个连接作用域，确保手机端搜索不会脱离 WebSocket 继续运行。
     */
    private fun sendEnvelope(requestId: Long, message: Any) {
        val json = GSON.toJson(message)
        try {
            synchronized(stateLock) {
                if (activeRequestId != requestId || !isOpen) {
                    throw CancellationException("搜索请求已失效")
                }
                synchronized(sendLock) {
                    send(json)
                }
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            cancelConnection()
            throw CancellationException("WebSocket 发送失败").also {
                it.initCause(exception)
            }
        }
    }

    private fun sendPing() {
        synchronized(sendLock) {
            if (!isOpen) throw IOException("WebSocket 已关闭")
            ping(PING_PAYLOAD)
        }
    }

    private fun isCurrentRequest(requestId: Long): Boolean = synchronized(stateLock) {
        activeRequestId == requestId && activeSearchJob?.isActive == true && isOpen
    }

    private fun cancelConnection() {
        synchronized(stateLock) {
            activeRequestId++
            activeSearchJob?.cancel()
            activeSearchJob = null
        }
        socketScope.cancel()
    }

    private companion object {
        const val MAX_QUERY_LENGTH = 100
        // WebService 的 accepted socket 读超时为 15 秒，必须更频繁地 ping/pong 续活。
        const val PING_INTERVAL_MILLIS = 10_000L
        val PING_PAYLOAD = "ping".toByteArray()
    }
}
