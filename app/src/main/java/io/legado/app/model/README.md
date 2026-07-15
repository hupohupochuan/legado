# 业务模型与解析

顶层的 `ReadBook`、`AudioPlay`、`CacheBook` 等维护阅读、音频、缓存和朗读等运行时业务状态。

- `analyzeRule/`：书源规则解析。
- `fileBook/`：本地、远程及归档书籍文件解析。
- `remote/`：远程书库列举、下载、上传和删除。
- `webBook/`：网络书籍信息、目录与正文获取。

独立 `rss/` 解析目录已删除；RSS 类型内容通过 `BookSource`（`bookSourceType=5`）和 `ui/book/rss/` 处理。
