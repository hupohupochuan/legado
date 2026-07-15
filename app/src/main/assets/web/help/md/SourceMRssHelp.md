# RSS 类型书源兼容说明

独立“订阅源管理”界面和 RSS 专用数据表已经移除。RSS 源现已合并到统一的“书源管理”，以 `bookSourceType=5` 区分；旧 RSS JSON、备份和 `legado://import/rssSource` 深链导入仍会转换到 `BookSource`，因此本文件仅保留为旧入口的兼容说明。

当前操作路径：

1. 打开“我的” → “书源管理”。
2. 新建或编辑书源时选择 RSS 类型，并填写 RSS 内容规则。
3. 导入、分组、启用、禁用、导出等操作统一使用书源管理界面。

RSS 内容阅读界面位于 `ui/book/rss/`；外部 Web API 也统一使用书源接口，不再提供 RSS 专用 HTTP 路由。Content Provider 的旧 `rssSource` URI 只是 `BookSource` 路由别名，不会替调用方转换旧 JSON 或自动补类型 5。
