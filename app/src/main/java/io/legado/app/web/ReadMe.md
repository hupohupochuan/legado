# Web 服务

- `HttpServer.kt`：基于 NanoHTTPD 的 HTTP API、CORS 和 SPA 静态资源入口。
- `WebSocketServer.kt`：书源调试、在线搜书和书籍正文搜索的 WebSocket 路由。
- `socket/`：三个 WebSocket 处理器。
- `utils/AssetsWeb.kt`：响应 `app/src/main/assets/web/` 中的构建产物。

HTTP 业务控制器位于相邻的 `api/controller/`，不在本目录中。对外路由以 `HttpServer.kt` 和 `WebSocketServer.kt` 的分发表为准。
