# Android 服务

- `AudioPlayService`：音频播放。
- `CacheBookService`：书籍离线缓存；页面和通知共用本次任务进度，完成通知可返回缓存页导出。
- `CheckSourceService`：书源检测。
- `DownloadService`：通过系统 `DownloadManager` 下载普通文件。
- `ExportBookService`：导出 TXT/EPUB；在线书和 WebDAV 来源书只读已落盘缓存，按“要求完整/仅已有缓存”策略处理缺章。
- `HttpReadAloudService`：HTTP 在线朗读。
- `TTSReadAloudService`：系统 TTS 朗读。
- `WebService`：局域网 Web 服务。
- `WebTileService`：Web 服务快捷设置磁贴。

`BaseReadAloudService` 是两个朗读服务共用的基类，不是独立注册的服务。具体注册项和前台服务类型以 `AndroidManifest.xml` 为准。
