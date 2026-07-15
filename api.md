# 阅读 API

> 当前代码核对日期：2026-07-15。路由权威来源为 `web/HttpServer.kt`、`web/WebSocketServer.kt` 和 `api/ReaderProvider.kt`。
>
> RSS 已并入 `BookSource`。Web 服务不再提供 RSS 专用 HTTP/WebSocket 接口；RSS 类型书源使用 `bookSourceType=5` 调用统一书源接口。

## 启用与地址

先在 App 设置中启用“Web 服务”。HTTP 端口可配置，默认是 `1122`；WebSocket 固定使用 HTTP 端口加 1，默认是 `1123`。从其他设备访问时，把 `127.0.0.1` 换成手机局域网 IP。

普通 JSON 接口通常返回：

```json
{
  "isSuccess": true,
  "errorMsg": "",
  "data": {}
}
```

`/cover` 和 `/image` 成功时直接返回 PNG。以下接口是当前内置 Web 前端使用的实际路由，但项目尚未声明独立的稳定版本协议，外部调用方升级后应重新核对。

## HTTP 接口

### 书源

| 方法 | 路径 | 参数或请求体 |
| --- | --- | --- |
| POST | `/saveBookSource` | 单个 [`BookSource`](/app/src/main/java/io/legado/app/data/entities/BookSource.kt) JSON 对象 |
| POST | `/saveBookSources` | `BookSource` JSON 数组 |
| POST | `/deleteBookSources` | `BookSource` JSON 数组 |
| GET | `/getBookSource?url=...` | `url` 为书源地址 |
| GET | `/getBookSources` | 无 |

RSS 类型源也使用这些接口，且请求体必须是 `BookSource` 格式并明确带 `bookSourceType: 5`。旧 `sourceUrl` RSS JSON 的自动转换只存在于 App 导入流程，不适用于这里。

### 书籍、目录与正文

| 方法 | 路径 | 参数或请求体 |
| --- | --- | --- |
| POST | `/saveBook` | 单个 [`Book`](/app/src/main/java/io/legado/app/data/entities/Book.kt) JSON 对象 |
| POST | `/deleteBook` | 单个 `Book` JSON 对象 |
| GET | `/getBookshelf?groupId=...` | `groupId` 可省略；省略时返回全部书籍 |
| GET | `/getGroups` | 返回全部书籍分组 |
| GET | `/getChapterList?url=...` | 目录为空时会尝试刷新目录 |
| GET | `/refreshToc?url=...` | 强制重新获取并保存目录 |
| GET | `/getBookContent?url=...&index=0` | `index` 是从 0 开始的章节序号 |
| GET | `/cover?path=...` | 获取封面 PNG |
| GET | `/image?url=BOOK_URL&path=IMAGE_URL&width=640` | 获取正文图片 PNG；`width` 可省略 |

上传本地书使用 `multipart/form-data`：

```text
POST /addLocalBook
field fileName = 带扩展名的文件名
file  fileData = 文件内容
```

### 阅读进度

普通保存先更新手机 Room，再由手机端调度 WebDAV 上传；`flush=true` 用于切章、隐藏或离开页面时立即补交当前进度。

```text
POST /saveBookProgress
POST /saveBookProgress?flush=true
Content-Type: application/json
```

```json
{
  "bookUrl": "可选；同名同作者书较多时建议提供",
  "name": "书名",
  "author": "作者",
  "durChapterIndex": 10,
  "durChapterPos": 120,
  "durChapterTime": 1784040000000,
  "durChapterTitle": "章节名，可为 null"
}
```

打开单本书前拉取并比较 WebDAV/手机进度：

```text
POST /syncBookProgress
Body = { "bookUrl": "..." }
```

响应 `data` 包含 `progress`、`remoteApplied` 和可能为 `null` 的 `warning`。只有章节范围有效且远端位置更靠前时才会应用远端进度。

### Web 阅读配置与诊断日志

| 方法 | 路径 | 参数或请求体 |
| --- | --- | --- |
| GET | `/getReadConfig` | 返回 `data` 为已保存配置的 JSON 字符串；未配置时返回失败 |
| POST | `/saveReadConfig` | Web 阅读配置 JSON；服务端按原 JSON 字符串保存 |
| POST | `/saveReaderLog` | `{ "message": "..." }`，最多取 500 字符 |

`saveReaderLog` 是内置 Web 性能诊断接口。只有 App 的“记录日志”开关开启时，消息才会实际写入日志文件。

### 替换规则

| 方法 | 路径 | 参数或请求体 |
| --- | --- | --- |
| GET | `/getReplaceRules` | 返回 `data` 为规则数组的 JSON 字符串 |
| POST | `/saveReplaceRule` | 单个 [`ReplaceRule`](/app/src/main/java/io/legado/app/data/entities/ReplaceRule.kt) JSON 对象 |
| POST | `/deleteReplaceRule` | 单个 `ReplaceRule` JSON 对象 |
| POST | `/testReplaceRule` | `{ "rule": ReplaceRule对象, "text": "待测试文本" }` |

## WebSocket 接口

下例使用默认 WebSocket 端口 `1123`；如果 HTTP 端口改为 `N`，这里使用 `N+1`。

### 书源调试

```text
URL = ws://127.0.0.1:1123/bookSourceDebug
Message = { "tag": "书源 URL", "key": "搜索关键词" }
```

### 在线搜书

```text
URL = ws://127.0.0.1:1123/searchBook
Message = { "key": "搜索关键词" }
```

服务端分批发送 `SearchBook` JSON 数组，完成后关闭连接。若要继续通过 Web API 获取目录/正文，应先用 `/saveBook` 保存选中的书籍，不需要时再用 `/deleteBook` 删除。

### 单本书正文搜索

```text
URL = ws://127.0.0.1:1123/searchBookContent
Message = { "bookUrl": "...", "query": "关键词", "maxResults": 500 }
```

`query` 最长 100 字符，`maxResults` 会限制在 1～500。服务端以 `type` 区分 `start`、`results`、`progress`、`complete`、`error` 消息；新请求会取消同一连接上的旧搜索。

## Content Provider

Provider authority 为 `${applicationId}.readerProvider`。当前个人版示例：

- Release：`shutiao.reader.release.readerProvider`
- Debug：`shutiao.reader.debug.readerProvider`

### 签名权限

`ReaderProvider` 保持 `exported=true` 以支持外部集成，但所有读写操作均受 `${applicationId}.permission.READ_WRITE` 保护，该自定义权限的 `protectionLevel` 为 `signature`。当前个人版的实际权限名为：

- Release：`shutiao.reader.release.permission.READ_WRITE`
- Debug：`shutiao.reader.debug.permission.READ_WRITE`

调用方需要在自己的 Manifest 中声明与目标变体对应的权限，例如调用个人版 Release：

```xml
<uses-permission android:name="shutiao.reader.release.permission.READ_WRITE" />
```

调用方还必须与目标 App 使用同一签名证书；仅声明权限但签名不同仍会被系统拒绝，并可能收到 `SecurityException`。权限名随 `applicationId` 变化，因此 Debug 和 Release 不能混用。旧权限名 `io.legado.READ_WRITE` 不再有效。

这是 2026-07-15 起的有意安全收紧：此前依赖无权限访问或使用不同证书的第三方客户端需要改为同签名构建，否则无法继续调用 Provider。

### 调用约定

- `insert`：用 `ContentValues` 的 `json` 字段传 JSON。当前实现始终返回 `null`，结果通过数据库副作用体现。
- `delete`：JSON 数组放在 `ContentProvider.delete(uri, selection, selectionArgs)` 的 `selection` 参数中；返回值当前始终为 `0`。
- `query`：返回一行一列的 Cursor，用 `cursor.getString(0)` 取得完整 `ReturnData` JSON。
- 不支持或格式错误的写入可能只返回 `null`/`0`，外部调用方应在操作后查询确认。
- 签名权限拒绝发生在进入 `ReaderProvider` 业务代码之前，会抛出系统 `SecurityException`；它与 Provider 内部参数错误返回的 `null`/`0` 不是同一种失败。

### 书源 URI

| 操作 | URI |
| --- | --- |
| 插入单个 | `content://providerHost/bookSource/insert` |
| 插入多个 | `content://providerHost/bookSources/insert` |
| 删除多个 | `content://providerHost/bookSources/delete` |
| 查询单个 | `content://providerHost/bookSource/query?url=...` |
| 查询全部 | `content://providerHost/bookSources/query` |

以下旧 RSS URI 仍作为完全相同的 `BookSource` 路由别名保留：

```text
content://providerHost/rssSource/insert
content://providerHost/rssSources/insert
content://providerHost/rssSources/delete
content://providerHost/rssSource/query?url=...
content://providerHost/rssSources/query
```

这些别名不会自动把旧 RSS JSON 转成 `BookSource`，不会自动补 `bookSourceType=5`，`rssSources/query` 也不会过滤 RSS 类型；调用方必须提交当前 `BookSource` 格式，并自行按 `bookSourceType` 区分结果。

### 书籍 URI

| 操作 | URI |
| --- | --- |
| 插入书籍 | `content://providerHost/book/insert` |
| 查询全部书籍 | `content://providerHost/books/query` |
| 刷新目录 | `content://providerHost/book/refreshToc/query?url=...` |
| 查询目录 | `content://providerHost/book/chapter/query?url=...` |
| 查询正文 | `content://providerHost/book/content/query?url=...&index=0` |
| 查询封面 | `content://providerHost/book/cover/query?path=...` |

当前 Provider 没有暴露删除书籍或保存阅读进度的可匹配 URI；不要根据 `ReaderProvider` 内部未映射的枚举项推断接口存在。
