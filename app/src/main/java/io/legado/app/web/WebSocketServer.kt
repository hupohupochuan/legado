package io.legado.app.web

import fi.iki.elonen.NanoWSD
import io.legado.app.service.WebService
import io.legado.app.web.socket.BookContentSearchWebSocket
import io.legado.app.web.socket.BookSearchWebSocket
import io.legado.app.web.socket.BookSourceDebugWebSocket

/**
 * WebSocket 服务，基于 NanoWSD。
 *
 * 路由:
 * - /bookSourceDebug → 书源调试交互
 * - /searchBook     → 书籍搜索流式推送
 * - /searchBookContent → 当前书籍正文搜索流式推送
 */
class WebSocketServer(port: Int) : NanoWSD(port) {

    override fun openWebSocket(handshake: IHTTPSession): WebSocket? {
        WebService.serve()
        return when (handshake.uri) {
            "/bookSourceDebug" -> {
                BookSourceDebugWebSocket(handshake)
            }
            "/searchBook" -> {
                BookSearchWebSocket(handshake)
            }
            "/searchBookContent" -> {
                BookContentSearchWebSocket(handshake)
            }
            else -> null
        }
    }
}
