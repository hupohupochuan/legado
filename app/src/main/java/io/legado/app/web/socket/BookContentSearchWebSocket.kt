package io.legado.app.web.socket

import androidx.annotation.Keep
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import io.legado.app.help.book.BookContentSearchComplete
import io.legado.app.help.book.BookContentSearchCursor
import io.legado.app.help.book.BookContentSearchListener
import io.legado.app.help.book.BookContentSearchProgress
import io.legado.app.help.book.BookContentSearchService
import io.legado.app.help.book.BookContentSearchStart
import io.legado.app.help.book.BookContentSearcher
import io.legado.app.help.book.WebBookContentSearchResult
import io.legado.app.help.config.AppConfig
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
import java.security.MessageDigest

@Keep
private data class BookContentSearchRequest(
    val bookUrl: String? = null,
    val query: String? = null,
    val maxResults: Int? = null,
    val cursor: String? = null
)

@Keep
private data class BookContentSearchStartMessage(
    val type: String = "start",
    val totalChapters: Int,
    val searchableChapters: Int,
    val isLocalBook: Boolean,
    val resultOffset: Int
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
    val truncated: Boolean,
    val hasMore: Boolean,
    val nextCursor: String?,
    val resultStart: Int,
    val resultEnd: Int
)

@Keep
private data class BookContentSearchErrorMessage(
    val type: String = "error",
    val message: String
)

/** Web 阅读页手机端全文搜索 WebSocket。 */
class BookContentSearchWebSocket(
    handshakeRequest: NanoHTTPD.IHTTPSession,
    private val searchService: BookContentSearchService = BookContentSearchService(
        searchConcurrency = AppConfig.threadCount
    )
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
            val cursor = request.cursor?.takeIf { it.isNotBlank() }?.let {
                decodeCursor(it, bookUrl, query)
            }

            searchService.search(
                bookUrl = bookUrl,
                query = query,
                maxResults = maxResults,
                cursor = cursor,
                listener = socketListener(requestId, bookUrl, query)
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            if (isCurrentRequest(requestId)) {
                val message = if (exception is IllegalArgumentException &&
                    exception.message?.startsWith("搜索位置") == true
                ) {
                    "搜索位置已失效，请重新搜索"
                } else {
                    "搜索失败"
                }
                sendError(requestId, message)
            }
        }
    }

    private fun socketListener(
        requestId: Long,
        bookUrl: String,
        query: String
    ) = object : BookContentSearchListener {
        override suspend fun onStart(start: BookContentSearchStart) {
            sendEnvelope(
                requestId,
                BookContentSearchStartMessage(
                    totalChapters = start.totalChapters,
                    searchableChapters = start.searchableChapters,
                    isLocalBook = start.isLocalBook,
                    resultOffset = start.resultOffset
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
            val nextCursor = complete.nextCursor?.let { encodeCursor(it, bookUrl, query) }
            sendEnvelope(
                requestId,
                BookContentSearchCompleteMessage(
                    scannedChapters = complete.scannedChapters,
                    matchCount = complete.matchCount,
                    skippedUncachedChapters = complete.skippedUncachedChapters,
                    truncated = complete.truncated,
                    hasMore = nextCursor != null,
                    nextCursor = nextCursor,
                    resultStart = if (complete.matchCount == 0) {
                        0
                    } else {
                        complete.resultOffset + 1
                    },
                    resultEnd = complete.resultOffset + complete.matchCount
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
        const val CURSOR_VERSION = "v1"
        const val MAX_CURSOR_LENGTH = 256
        // WebService 的 accepted socket 读超时为 15 秒，必须更频繁地 ping/pong 续活。
        const val PING_INTERVAL_MILLIS = 10_000L
        val PING_PAYLOAD = "ping".toByteArray()
        val HEX_CHARS = "0123456789abcdef".toCharArray()

        fun encodeCursor(
            cursor: BookContentSearchCursor,
            bookUrl: String,
            query: String
        ): String {
            val payload = listOf(
                CURSOR_VERSION,
                cursor.chapterPosition,
                cursor.chapterIndex,
                cursor.fromIndex,
                cursor.resultOffset
            ).joinToString(".")
            return "$payload.${cursorChecksum(payload, bookUrl, query)}"
        }

        fun decodeCursor(
            encoded: String,
            bookUrl: String,
            query: String
        ): BookContentSearchCursor {
            require(encoded.length <= MAX_CURSOR_LENGTH) { "搜索位置已失效" }
            val parts = encoded.split('.')
            require(parts.size == 6 && parts[0] == CURSOR_VERSION) { "搜索位置已失效" }
            val chapterPosition = parts[1].toIntOrNull()
            val chapterIndex = parts[2].toIntOrNull()
            val fromIndex = parts[3].toIntOrNull()
            val resultOffset = parts[4].toIntOrNull()
            require(
                chapterPosition != null && chapterPosition >= 0 &&
                    chapterIndex != null && chapterIndex >= 0 &&
                    fromIndex != null && fromIndex >= 0 &&
                    resultOffset != null && resultOffset >= 0
            ) { "搜索位置已失效" }
            val payload = parts.take(5).joinToString(".")
            require(parts[5] == cursorChecksum(payload, bookUrl, query)) { "搜索位置已失效" }
            return BookContentSearchCursor(
                chapterPosition = chapterPosition,
                chapterIndex = chapterIndex,
                fromIndex = fromIndex,
                resultOffset = resultOffset
            )
        }

        private fun cursorChecksum(payload: String, bookUrl: String, query: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("$bookUrl\u0000$query\u0000$payload".toByteArray(Charsets.UTF_8))
            val result = CharArray(digest.size * 2)
            digest.forEachIndexed { index, byte ->
                val value = byte.toInt() and 0xff
                result[index * 2] = HEX_CHARS[value ushr 4]
                result[index * 2 + 1] = HEX_CHARS[value and 0x0f]
            }
            return String(result)
        }
    }
}
