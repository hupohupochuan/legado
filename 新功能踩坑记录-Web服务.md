# 新功能踩坑记录 - Web 服务

> 返回主题索引: [新功能踩坑记录.md](新功能踩坑记录.md)
> 当前复核状态（2026-08-26）：Web 阅读页新增按手机已保存章节索引计算的总进度，显示百分比和当前章/总章；不改进度保存、WebDAV 或数据库协议。
> 验证边界：2026-08-26 已通过进度计算测试、Web 既有定向测试、类型/ESLint、生产构建、assets 字节同步、`scripts/check-debug.sh`、Debug APK 组装及 APK 内 assets 字节检查；Headless Chrome 桌面/窄屏生产页显示通过，实体手机和真实移动浏览器仍待验收。2026-08-21 的 WebService/DNS、2026-08-16 的 WebDAV v2/404、2026-08-12 的权限黑盒和 2026-08-10 的方向键真实浏览器边界不因本次复核自动续期。

---

## Web 阅读页按章显示总进度

- 记录日期: 2026-08-26
- 适用环境: Web 阅读页连续滚动和书本翻页模式，桌面与窄屏布局
- 相关文件: `modules/web/src/views/BookChapter.vue`、`modules/web/src/utils/chapterReadingProgress.ts`、`modules/web/scripts/chapter-reading-progress.test.mjs`

**设计结论**:
- 可见总进度直接复用手机端持久化的零基章节索引，计算 `(durChapterIndex + 1) / 当前目录总章数`，显示一位小数百分比和“当前章/总章”。它只随章节变化，因此手机和 Web 的总进度口径一致。
- 不把 `chapterPos`、浏览器页码、字体、行高或视口尺寸纳入总进度，不新增独立持久化值，也不修改 Room、Web API 或 WebDAV 结构。目录为空时隐藏；失效或越界索引只在展示层安全钳制。
- 全文搜索结果只做临时预览时，总进度固定使用 `searchPreviewOrigin.chapterIndex`；用户确认保留当前位置后，原位置快照清除，才显示预览章节的进度。
- 进度提示固定在阅读页右下角；窄屏工具栏展开时上移，搜索预览时改到右上角，避免遮挡上一章/下一章工具栏和预览确认区。

**回归点**:
- 180 章示例中，第 1 章显示 `总进度 0.6%　1/180`，第 23 章显示 `总进度 12.8%　23/180`，末章显示 `总进度 100.0%　180/180`。
- 目录加载完成、正常切章和恢复手机端已保存章节时及时更新；仅滚动章内位置或改变浏览器排版时数值不变。
- 搜索预览、确认保留、取消返回原位置三条路径不能提前显示或遗留错误章节进度。
- 生产构建后必须同步 `dist/index.html` 到 APK asset，并继续验证桌面、窄屏隐藏工具栏和窄屏显示工具栏三种布局。

## Android 17 局域网权限门禁

- 记录日期: 2026-08-12
- 适用环境: Android 17/API 37+ 且 App targetSdk 37；WebService HTTP 端口与相邻 WebSocket 端口
- 相关文件: `AndroidManifest.xml`、`WebServiceLocalNetworkAccess.kt`、`WebServicePermissionActivity.kt`、`WebService.kt`、`WebTileService.kt`、`OtherConfigFragment.kt`
- 兼容细节和测试陷阱: [适配踩坑记录-2026-09-05.md 0.12](文档归档/适配踩坑记录-2026-09-05.md#012-android-17-局域网权限阻断-webservice-入站连接)

**当前设计**:
- `WebService.start()` 是设置开关和普通启动的统一门禁；Android 17/target 37 缺少精确 `ACCESS_LOCAL_NETWORK` 时只打开非导出的透明权限 Activity，不创建 WebService。API 26-36 直接沿用原启动路径。
- 权限 Activity 先解释同一局域网浏览器访问用途，再请求系统权限；拒绝后提供“去应用设置/取消”，拒绝、取消和设置页返回仍缺权都会写回关闭。Permission/Settings/拒绝阶段保存在实例状态中，旋转或进程重建后仍重新检查精确权限。
- Quick Settings Tile 缺权时通过 `startActivityAndCollapse(PendingIntent)` 进入同一权限 Activity；已授权时继续保留可见 Tile 交互内的 FGS 启动路径。授权只表示可以启动，`isRun` 与 Tile 激活必须等 HTTP、WebSocket 都启动成功后再提交。
- 服务端在创建、命令、网络变化、keep-alive、通知和定时/章节触发重建时再次检查权限。拒绝或撤销统一复用 `requestStop()/tearDown()`；同 UID 绕过 UI 直接启动 FGS 时，先用瞬时最小通知完成 foreground promotion，再立即停止并移除 ID 105，避免系统超时崩溃。
- 端口修改使用保留启用意图的 `restart()`；Preference listener 识别正在启动状态，避免授权成功写回开关后重复发送启动命令。

**回归点**:
- 清数据后的设置开关与 Tile 分别覆盖用途说明、系统 Allow/Don’t allow、拒绝页取消、去设置后允许；只有允许后才出现 1122/1123 监听与活动 Tile。
- 运行中从系统设置撤销权限后，原进程、两个端口和通知必须消失；若系统尝试 sticky 重启，缺权兜底只能启动一次并立即销毁，不能循环崩溃或重新显示运行。
- 模拟器入站必须用 emulator console `redir`：授权后 HTTP 返回 200、WebSocket 握手返回 101，拒权时超时。`adb forward`、设备内 loopback 或访问设备自身 IP 在拒权时仍可能成功，不能作为权限验收。
- 最终发布仍需实体 Android 17 手机与另一台同 Wi-Fi 设备访问手机 IP，覆盖 Web 根页面、网页传书、Web 阅读与 WebSocket 管理；模拟器 NAT 通过不等同于真实 Wi-Fi/厂商 ROM 通过。

## Web 后端地址探测与后台刷新一致性

- 记录日期: 2026-07-20
- 验证日期: 2026-07-20（ESLint、Web 类型检查、生产构建、请求地址定向测试、Headless Chrome 白天主题目录弹窗、Web assets 同步检查及 Debug Kotlin 编译通过）
- 适用环境: `modules/web` 书架、阅读页目录和自定义后端地址切换
- 相关文件: `modules/web/src/api/axios.ts`、`modules/web/src/api/index.ts`、`modules/web/src/store/bookStore.ts`、`modules/web/src/components/PopCatalog.vue`、`modules/web/src/views/BookShelf.vue`、`modules/web/src/views/BookChapter.vue`

**实现约束**:
- 自定义后端地址必须先通过单次请求的 `baseURL` 实际访问新地址；验证成功后才能更新全局 API/WebSocket 入口和 `localStorage.remoteUrl`。验证失败时继续保留旧连接，不能静默请求旧地址并把新地址误判为可用。
- 请求级配置必须与浏览器 `RequestInit` 分离，不能把 `baseURL` 当作 `fetch()` 选项透传；未指定请求级地址时继续使用 `ajax.defaults.baseURL`。
- 书架和目录保留 stale-while-revalidate：缓存命中立即返回，同时后台刷新。后台失败由连接拦截器提示并收口 Promise；分组切换后的书架旧响应和被新目录请求取代的迟到响应不得覆盖当前状态。
- 目录弹窗必须使用 `bookStore.isNight` 判断昼夜主题，不能把非零主题编号当作夜间模式；虚拟列表滚动层保持透明并继承弹窗的主题背景与文字色，避免白天主题的目录中段被错误渲染成深色。
- `isSearchBook` 只作为前端运行时名称；已有 `sessionStorage.isSeachBook` 和 `localStorage.readingRecent.isSeachBook` 属于持久化兼容边界，继续保留旧拼写。

**回归点**:
- 输入可用的新后端地址时，探测请求必须命中新地址，成功后书架请求和连接状态切换到新地址。
- 输入不可访问或返回格式错误的地址时应明确报错，旧 API 入口、WebSocket 入口和已保存地址不变。
- 同一分组/书籍命中缓存时页面立即可用，后台刷新失败不产生未处理 rejection；快速切换分组或书籍时迟到响应不能覆盖当前内容。
- 逐一切换全部阅读主题并打开目录，标题区、滚动列表和底部留白应使用连续一致的背景；只有夜间主题使用夜间文字和分隔线。
- Web 构建后必须手动同步 `dist/index.html` 到 APK asset，并做字节一致性检查。

---

---

## Web 单入口收敛

- 记录日期: 2026-07-15
- 验证日期: 2026-07-15（`pnpm run type-check`、`pnpm run build-only`、Debug APK 组装通过，`dist`、源码 asset 与 APK 内 asset 的 SHA-256/字节一致）
- 适用环境: Vue 3.5.39, Vite 8.1.4，Android 内置 Web 服务
- 相关文件: `modules/web/index.html`、`modules/web/src/main.ts`、`modules/web/src/router/bookRouter.ts`、`modules/web/src/router/sourceRouter.ts`、`modules/web/README.md`、`app/src/main/assets/web/index.html`

**设计结论**:
- 欢迎页、书架、阅读页、书源和替换规则统一由 `modules/web/index.html` 加载 `src/main.ts`，再通过 Hash Router 分发；不要恢复功能重复的独立 HTML/JS 入口。
- 已删除的 `src/pages/bookshelf`、`src/pages/source` 从未进入当前 Vite 生产模块图，还引用了依赖清单中已不存在的 Element Plus；保留它们只会误导维护者并产生不可运行的入口。
- `bookRouter.ts`、`sourceRouter.ts` 只保留统一 Router 使用的命名路由数组，删除仅供旧入口导入的默认 Router 构造，避免继续创建无消费者的路由实例。
- 支撑代码清理会改变生产 bundle，必须把重建后的 `dist/index.html` 同步到 APK asset；后续同类入口收敛也要先沿 import 图区分“完全未构建文件”和“被共享模块带入的遗留初始化”。

**回归点**:
- `/#/`、`/#/shelf`、`/#/chapter`、`/#/bookSource`、`/#/replaceRule` 必须继续由统一 SPA 路由进入。
- Web 构建配置不得再依赖 `src/pages/*` 或 Element Plus；文档只能把它们作为已删除的历史边界说明，不能当作现存入口；类型检查和生产构建必须保持通过。

## Web 运行时依赖迁移

### 1. VueUse core/shared 11 → 14

- 记录日期: 2026-07-14
- 验证日期: 2026-07-14（frozen 安装、类型检查、生产构建、日期格式定向测试、Headless Chrome 路由加载和 Web assets 同步通过）。
- 适用环境: Vue 3.5.39, VueUse 14.3.0, Vite 8.1.4, Node 22.22.1, Google Chrome Headless。
- 相关文件: `modules/web/package.json`、`modules/web/pnpm-lock.yaml`、`modules/web/src/utils/utils.ts`、`app/src/main/assets/web/index.html`。

**迁移结论**:
- `@vueuse/core` 与 `@vueuse/shared` 必须同批从 11.3.0 升到 14.3.0，避免 core/shared 内部协议错配；VueUse 14 要求 Vue 3.5，本项目已满足。
- 当前业务源码只直接使用 shared 的 `formatDate`，用于书架“最后检查时间”超过 30 天时显示 `YYYY-MM-DD`；14.3.0 仍保留原函数名和该格式语义，定向测试输出 `2026-07-14`。
- VueUse 12 对 Vue 3 行为等价于 11.3；13 改为 ESM-only；14 改用新打包结构并移除部分废弃别名。本项目 Vite 配置为 ESM，且未使用被移除别名、`useThrottleFn`、`computedAsync`、`createSharedComposable` 等行为变化 API，无需业务代码适配。
- 生产 bundle 会因 VueUse 打包实现变化而改变，必须同步 APK asset；不能以“仅日期工具”为由跳过 `app/src/main/assets/web/index.html`。

**回归点**:
- 书架路由必须渲染并保留“基本设定”，30 天以上的最后检查时间按本地时区显示 `YYYY-MM-DD`；刚刚/秒/分/时/天的项目自有相对时间逻辑不受 VueUse 影响。
- `/shelf` 与带测试 bookUrl 的 `/chapter` 通过 HTTP 直接刷新后均能挂载，阅读页目录/设置入口存在；真实书籍取章、设置交互与进度保存仍需连接 Legado 后端做浏览器/真机回归。
- 类型检查和单文件生产构建必须完整覆盖书架、阅读页、设置组件、路由与进度保存代码；`dist/index.html` 必须与 APK asset 字节一致。

### 2. Pinia 2 → 3

- 记录日期: 2026-07-14
- 验证日期: 2026-07-14（frozen 安装、类型检查、生产构建、Headless Chrome 书架/阅读路由加载和 Web assets 同步通过）。
- 适用环境: Vue 3.5.39, Pinia 3.0.4, TypeScript 5.9.3, Vite 8.1.4。
- 相关文件: `modules/web/package.json`、`modules/web/pnpm-lock.yaml`、`modules/web/src/store/`、`app/src/main/assets/web/index.html`。

**迁移结论**:
- Pinia `2.3.1 -> 3.0.4`；官方迁移说明表明 v3 主要移除废弃 API并升级依赖，本项目已是 Vue 3/TypeScript 5，满足运行条件。
- 四个 store 全部使用 `defineStore('id', options)`，没有使用已废弃并删除的 `defineStore({ id })` 签名；没有 `PiniaStorePlugin` 类型、自定义插件或 Vue 2 兼容代码需要迁移。
- `createPinia()`、`storeToRefs()` 与 auto-import 声明在 v3 下类型检查通过；book/source/replaceRule/connection store 的 state、getter 和 action 不需要为了主版本升级重写。
- Pinia 3 会改变生产 bundle 和 devtools 依赖，即使 store 源码无差异也必须重建并同步 APK asset。

**回归点**:
- 书架连接状态、分组与列表状态，阅读页目录/正文/配置状态，以及替换规则/书源编辑状态都必须继续由各自 store 隔离维护；返回和路由刷新后不得出现 active Pinia 缺失。
- 进度保存仍由 book store 的 latest-wins/flush 路径驱动，Pinia 升级不能改变 5 秒合并、切章/离开立即补交或 Beacon 语义；真实请求顺序需在连接手机后回归。
- Headless Chrome 直接刷新 `/shelf` 与 `/chapter` 均成功挂载并显示书架基本设定、阅读目录和设置入口；类型检查、142 模块生产构建及 APK assets 字节同步必须通过。

### 3. Vue Router 4 → 5

- 记录日期: 2026-07-14
- 验证日期: 2026-07-14（frozen 安装、类型检查、生产构建、HTTP 直接刷新与 Headless Chrome hash 路由挂载、Web assets 同步通过）。
- 适用环境: Vue 3.5.39, Vue Router 5.1.0, Pinia 3.0.4, Vite 8.1.4。
- 相关文件: `modules/web/package.json`、`modules/web/pnpm-lock.yaml`、`modules/web/src/router/`、`modules/web/src/views/BookShelf.vue`、`modules/web/src/views/BookChapter.vue`、`app/src/main/assets/web/index.html`。

**迁移结论**:
- Vue Router `4.6.4 -> 5.1.0`；官方说明 v5 对未使用 file-based routing/unplugin-vue-router 的 v4 项目没有破坏性 API 变化，本项目继续使用手写 routes 和 `createWebHashHistory()`，无需引入路由生成插件。
- 现有 welcome/shelf/chapter/bookSource/replaceRule 路由、`router.push()`、`useRouter()`、`afterEach()` 和 `onBeforeRouteLeave()` API 均保留；阅读页离开守卫继续等待 flush 保存与搜索书入架确认后再 `next()`。
- v5.1.0 的 peer 组合要求 Vue 3.5.34+、Pinia 3.0.4、Vite 7/8，本项目已在前置独立批次满足；不能把 Router 5 提前于 Pinia/Vite 升级。
- 项目必须保留 hash history：APK asset、局域网 Web 服务和直接刷新都只请求根 `index.html`，不能在没有服务端 fallback 的情况下改为 HTML5 history。

**回归点**:
- HTTP 根资源返回 200，直接刷新 `/#/shelf` 与 `/#/chapter?bookUrl=...` 后必须分别挂载书架和阅读页，不能出现空白页、重复编码 query 或找不到路由。
- 从书架打开书籍、阅读页返回书架、打开设置/目录、replaceRule/bookSource 跳转都应保持原 hash；真实书籍离开时必须确认 `/saveBookProgress?flush=true` 完成或被捕获后才放行。
- 自动检查覆盖类型、134 模块生产构建、书架/阅读路由 DOM 挂载和 APK assets 字节同步；进度请求次序与浏览器前进/后退仍需连接 Legado 后端回归。

### 4. hotkeys-js 3 → 4

- 记录日期: 2026-07-14
- 验证日期: 2026-07-14（frozen 安装、类型检查、生产构建、Chrome DevTools Protocol 快捷键录制/保存/触发定向回归、书架/阅读路由挂载和 Web assets 同步通过）。
- 适用环境: hotkeys-js 4.0.4, Vue 3.5.39, Vite 8.1.4, Google Chrome Headless。
- 相关文件: `modules/web/package.json`、`modules/web/pnpm-lock.yaml`、`modules/web/src/components/ToolBar.vue`、`app/src/main/assets/web/index.html`。

**迁移结论**:
- hotkeys-js `3.13.15 -> 4.0.4`；v4 修复 ESM default import、全屏切换卡键和拉丁键盘布局归一化，本项目原有 `import hotkeys from 'hotkeys-js'` 可直接使用。
- 工具栏调用的 `hotkeys()`、`unbind()`、`unbind('*')`、`filter` 和 `getPressedKeyString()` 在 v4 类型与运行时均保留，不需要改写快捷键配置结构。
- 定向浏览器回归从空 localStorage 打开书源编辑页，确认快捷键弹窗挂载；点击首项“编辑”后录入 A，保存到 localStorage，再发送 A 触发“推送源”空列表动作并显示“空空如也”，覆盖录制、解绑、重绑和执行完整链路。
- v4 的布局归一化可能把录制显示规范化为大写 `A`；配置匹配不区分这一展示差异，不要为了显示大小写改写历史配置。

**回归点**:
- 首次进入书源编辑页应显示快捷键设置；已有合法配置时应关闭弹窗并绑定，非法 JSON 应清理并提示，不能让页面挂载失败。
- 录制期间屏蔽业务快捷键，ESC/点击遮罩结束录制；保存后必须先解绑通配监听再按配置重绑，重复打开弹窗不得叠加 handler。
- 书架、阅读页、设置、hash 刷新和进度保存不直接依赖 hotkeys-js，但仍需通过类型检查、134 模块生产构建、两条路由挂载与 APK assets 字节同步，真实手机键盘布局留待浏览器/真机回归。

---

## Web 服务阅读页断连提示

### 1. 问题范围

- 记录日期: 2026-07-09
- 验证日期: 2026-07-09（`pnpm run type-check`、`pnpm run build-only`、assets 同步检查）。
- 适用环境: Vue 3.5 + Vite 5 + Pinia，Web 服务阅读页 `BookChapter.vue`。

### 2. 现象

- 手机端 Web 服务或网络连接中断后，网页端阅读页点击下一章/上一章，页面没有切换动作，但仍先弹出“下一章”或“上一章”。
- 无限滚动追加下一章失败时，提示“获取下一章内容失败！”过于笼统，不能明确说明网页与手机已断开联系。

### 3. 修复要点

- `src/api/axios.ts` 的 `fetch` 封装必须在 `fetch()` 或 `response.json()` 失败时执行 response error interceptor，否则连接状态不会切到“连接异常”。
- API 层统一标记后端连接失败错误，并用“网络异常，与手机断开联系”作为断连提示。
- 阅读页切换上一章/下一章时，不要在请求前先弹“上一章/下一章”；成功拿到章节内容后再提示，失败时显示真实错误。
- 整章切换失败且已有旧章节内容时，恢复旧章节内容和旧进度，避免断连后把阅读状态提前切到目标章节。
- 无限滚动追加章节失败时只提示错误，不应清空当前阅读内容。

### 4. 回归测试点

- 手机端 Web 服务停止或网络断开时，网页端点击下一章/上一章应提示“网络异常，与手机断开联系”。
- 断连失败后当前章节内容和阅读进度应保持在原章节。
- 后端正常返回章节业务错误时，仍显示后端 `errorMsg`，不要误报为断连。
- 构建后必须同步 `modules/web/dist/index.html` 到 `app/src/main/assets/web/index.html`，否则 APK 内 Web UI 不生效。

---

## Web 服务补齐与网页传书收口

### 1. 功能范围

- 欢迎页和书架统一进入 `/#/uploadBook`，支持多文件选择/拖放、逐个上传、进度、失败原因和重试；历史 `/uploadBook/index.html` 只保留无业务逻辑的兼容跳转。
- 网页传书接入后端 `addLocalBook` 接口；TXT、EPUB、UMD、PDF、MOBI、AZW3、AZW、CBZ 可直接上传，ZIP、RAR、7Z 由手机端在含受支持书籍时解包导入。
- 阅读页目录弹窗新增"刷新"按钮，接入后端 `refreshToc` 接口。
- 新增 `#/replaceRule` 替换规则管理页面，接入后端 `getReplaceRules` / `saveReplaceRule` / `deleteReplaceRule` / `testReplaceRule` 四个接口。
- RSS 源编辑本次未做：后端 `HttpServer.kt` 无 RSS 专用接口，数据库层 `rssSources` 已迁移合并到 `bookSources`（`bookSourceType=5`），README 中 `#/rssSource` 已删除。

### 2. 关键实现点

- `addLocalBook` 必须走原生 `XMLHttpRequest` + `FormData`（或等价的原生 fetch/FormData），**不能**复用会注入 JSON 请求头的 `ajax` 实例：
  - `src/api/axios.ts` 的 `_request` 默认注入 `Content-Type: application/json`；
  - multipart 上传需要浏览器自动生成 `boundary`，手动设置 `multipart/form-data` 会缺失 boundary 导致 NanoHTTPD 解析失败。
- `HttpServer.buildResponse()` 对 `ReturnData` 业务失败仍返回 HTTP 200；上传端必须校验响应结构并以 `isSuccess` 为最终结果，失败展示 `errorMsg`。旧页只检查状态码，导致保存目录失效或导入异常时仍画绿色成功，这是本轮故障根因。
- 多文件串行上传，避免手机端同时执行多次保存、摘要和解析；每项独立保留进度/结果，失败项可重试，成功前不得显示“已导入手机书架”。
- `refreshToc` 后端实现为 `runBlocking { WebBook.getChapterListAwait(...) }`，网络书源刷新可能耗时数秒，前端按钮需置 `disabled`/loading 态，避免重复点击。
- 替换规则新建时 `order` 初始化为 `-2147483648`（对应后端 `Int.MIN_VALUE`），后端 `ReplaceRuleController.saveRule` 会自动分配新排序。
- 替换规则测试接口接收 `{ rule, text }`，返回替换后的文本；前端测试区直接展示结果字符串，不渲染富文本。
- `ReplaceRuleController.testRule` 检测到空 `pattern` 后必须立即返回错误，不能只设置 `errorMsg` 后继续替换，否则后续 `setData` 会把失败响应覆盖为成功；定向 JVM 测试需同时断言 `isSuccess=false`、错误文案和 `data=null`。

### 3. 构建与同步

- `pnpm build` 会执行 `vue-tsc --build --force` 类型检查 + `vite build` + `node ./scripts/sync.js`。
- `sync.js` 只在 `process.env.GITHUB_ENV` 存在时复制产物；**本地构建后必须手动复制**：
  - `modules/web/dist/index.html` → `app/src/main/assets/web/index.html`
  - `modules/web/dist/favicon.ico` → `app/src/main/assets/web/favicon.ico`
- 未同步则 APK 内 Web UI 不生效。
- `/uploadBook/index.html` 兼容跳转是 Android asset 中的独立小文件，Vite 不重建它；不得把上传业务复制回该文件。

### 4. 时间有效性

- 记录日期: 2026-06-28；网页传书收口: 2026-08-03
- 验证日期: 2026-08-03（Vue 类型检查、ESLint、Prettier、既有阅读时长 6 项测试、Vite 生产构建、桌面/390px Headless Chrome 路由渲染、旧 URL 跳转、HTTP 200 业务失败不误报/重试后成功的浏览器级模拟接口回归、APK Web asset 字节同步、`scripts/check-debug.sh`、Debug APK 组装及包内两个 Web 文件反查通过；连接真实手机上传待验证）。
- 适用环境: Vue 3.5.39 + Vite 8.1.4 + Pinia 3.0.4，Google Chrome Headless，web 模块 node>=20/pnpm>=9。
- 复核条件: 升级 NanoHTTPD、修改 `HttpServer.kt` 路由、调整 `ReplaceRule` 实体字段、修改 web 构建流程时需重新验证。

---

---

## 二、Web 服务浏览器阅读页

### 0.8 左右方向键切章需要按两次或更多

- 记录日期: 2026-08-10
- 验证日期: 2026-08-10（方向键缓冲 4 项测试、阅读时长 6 项测试、ESLint、Web 类型检查、生产构建、APK assets 字节同步及 Debug Kotlin 编译通过；真实浏览器键盘操作待回归）
- 适用环境: `modules/web` Vue 前端连续滚动与书本翻页模式；纸质书和滑动两种翻页效果
- 相关文件:
  - `modules/web/src/views/BookChapter.vue`
  - `modules/web/src/components/BookPageReader.vue`
  - `modules/web/src/utils/pageTurnKeyBuffer.ts`
  - `modules/web/scripts/page-turn-key-buffer.test.mjs`
  - `app/src/main/assets/web/index.html`

**现象与根因**:
- 连续滚动模式的 `canJump` 本来只用于防止上下键翻屏动画重入，却在方向键分支前统一返回；上下翻屏尚未回调解锁时按右键，下一章动作会被直接丢弃，只能等动画结束后再按。
- 书本翻页模式在 260/440ms 动画期间用 `flipLock` 拒绝所有新翻页；快速到达章节末页后继续按右键时，跨章意图不会保留，因此可能需要再按一次或多次。

**修复约束**:
- `canJump` 只约束 `ArrowUp` / `ArrowDown` 的翻屏动画，`ArrowLeft` / `ArrowRight` 始终按当前运行时模式处理，不能再被垂直滚动状态误伤。
- 书本翻页动画锁期间只保留最后一次键盘左右翻页意图；章内动画结束后执行一次，使到达末页时已按下的右键继续触发下一章，但不把多次按键累积成多章跳转。
- 跨章动画完成、动画取消和组件卸载时清空缓存意图，避免旧章节的按键泄漏到新章节；触摸手势和底部翻页按钮继续沿用现有锁语义。

**回归测试点**:
- 连续滚动模式在上下键翻屏动画尚未结束时按一次右键，应立即发起下一章；左键同理，且上下键自身仍不得重入。
- 纸质书和滑动效果在章内动画尚未结束时按右键，动画结束后只补一次翻页；若当前目标是末页，应继续进入下一章，不需重复按键。
- 动画锁期间交替按左右键只执行最后一次方向；跨章重挂、取消或离开页面后不得执行旧缓存按键。
- 搜索输入区继续阻止全局方向键；Web 产物必须同步 APK assets，真实浏览器需复测单击、快速连续和长按方向键。

### 0.7 书本翻页模式上下键误触发滚动提示

- 记录日期: 2026-08-01
- 验证日期: 2026-08-01（Web 类型检查、生产构建和 APK assets 字节同步检查通过；真实浏览器键盘操作待回归）
- 适用环境: `modules/web` Vue 前端书本翻页模式；纸质书和滑动两种翻页效果
- 相关文件:
  - `modules/web/src/views/BookChapter.vue`
  - `modules/web/src/components/BookPageReader.vue`
  - `app/src/main/assets/web/index.html`

**现象与根因**:
- `BookChapter.handleKeyPress` 是连续滚动模式遗留的全局 `keyup` 处理器，上下方向键会执行一屏滚动，并在边界显示“已到达页面顶部/底部”。
- 引入书本翻页模式时只给左右方向键增加 `activeBookMode` 分流，由 `BookPageReader` 在 `keydown` 阶段接管翻页；上下方向键没有按运行时模式隔离，因此固定分页页面处于文档顶部时，每次松开上键都会误弹顶部提示。

**修复约束**:
- 键盘行为按运行时 `activePageMode` 分流，不能直接按持久化的 `config.pageMode` 或单独的 `pageTurnEffect` 判断；初始化失败临时回退滚动后，上下键必须自动恢复滚动翻屏。
- 书本翻页模式下，上下方向键只消费事件并保持无动作，不滚动、不翻页、不显示边界提示；该约束同时覆盖纸质书和滑动效果。
- 保留 `BookPageReader` 对左右方向键的章内/跨章翻页处理，也保留 `BookChapter` 的 `keydown` 默认滚动拦截，避免浏览器原生滚动绕过模式分流。
- 连续滚动模式继续使用上下键翻屏和边界提示、左右键切章，不改变原有 `jumpDuration` 与进度更新路径。

**回归测试点**:
- 纸质书和滑动两种书本效果下，单次、连续和长按上下方向键均不改变页面、页码或阅读进度，也不显示顶部/底部提示。
- 书本模式左右方向键的章内翻页、首尾页跨章和动画锁保持正常。
- 切回滚动模式或因分页失败运行时回退滚动后，上下键恢复一屏滚动，并仅在真实边界显示提示。
- 搜索框等已阻止键盘事件冒泡的输入区域不触发全局阅读快捷键；构建产物必须同步 APK assets。

### 0.6 书本翻页模式正文选择与复制

- 记录日期: 2026-07-15
- 验证日期: 2026-07-15（Web 类型检查、生产构建、Headless Chrome 正文拖选回归和 APK assets 字节同步检查通过）
- 适用环境: `modules/web` Vue 前端书本翻页模式；桌面端拖选与移动端长按选词
- 相关文件:
  - `modules/web/src/components/BookPageReader.vue`
  - `app/src/main/assets/web/index.html`

**根因与修复约束**:
- `BookPageReader` 根节点设置 `user-select: none` 后，当前页正文也无法进入浏览器原生选区，桌面端不能拖选复制。
- 书页正文必须显式允许 `user-select: text` 和 `-webkit-user-select: text`，不能为了避免误触翻页而全局禁用选择。
- 移动端长按或已有非折叠选区时，触摸事件必须交还浏览器；只有短时间内明确的横向滑动才能阻止默认行为并触发翻页。
- 完成文字选择产生的 click 不向父级冒泡，避免选词后顺带切换工具栏；不改变底部按钮、键盘和普通快速横滑翻页。

**回归测试点**:
- 书本翻页模式下，桌面端拖选正文后可通过右键或快捷键复制，选择操作不切换工具栏。
- 移动端长按正文可选词并拖动选择手柄，不能误翻页；已有选区时触摸正文也不能触发翻页。
- 未选中文字时，快速左右横滑、左右方向键和底部上一页/下一页按钮仍正常翻页。
- 连续滚动模式、跨章动画、分页和阅读进度保存保持原行为；构建产物必须同步 APK assets。

### 0.5 纸质书固定翻页轨迹平顺化

- 记录日期: 2026-07-11
- 验证日期: 2026-07-11（`pnpm run type-check`、`pnpm run build-only`、Web assets 同步检查通过）
- 适用环境: `modules/web` Vue 前端纸质书翻页效果；章内与跨章共用同一动画舞台
- 相关文件:
  - `modules/web/src/components/BookPageReader.vue`
  - `app/src/main/assets/web/index.html`

**问题与优化**:
- 原裁切动画在约一半进度处额外设置关键帧，缓动曲线会分成两段，中点附近容易产生先减速再加速的顿挫。
- 前进和后退都改为起点到终点的单段连续裁切；固定 440ms 时长、固定方向、阴影轨迹和动画结束后提交进度的语义保持不变。
- `clip-path` 和滑动 `transform` 仅在动画期间声明 `will-change`，舞台增加布局/绘制隔离；移除纸质动画不再使用的 3D 图层属性，减少常态合成层负担。
- 新旧页继续使用互补裁切和不透明主题背景，不能以重影或页面重叠换取流畅度。

**回归测试点**:
- 纸质书前进/后退的轨迹应连续，动画中点不应出现明显停顿或突然加速。
- 章内和跨章翻页都应保持 440ms 固定动画，结束后页码和阅读进度正确。
- 动画全程未翻区域只显示旧页、已翻区域只显示目标页，不能出现重影、接缝或透明背景。
- 滑动效果、连续滚动模式、`prefers-reduced-motion` 和动画期间延后重分页逻辑保持原行为。

### 0.4 跨章翻页动画被重分页打断

- 记录日期: 2026-07-03
- 验证日期: 2026-07-03（`pnpm run type-check`、`pnpm run build-only` 通过；assets 已由 `modules/web/dist/index.html` 手动同步）
- 适用环境: `modules/web` Vue 前端；App 实际加载 `app/src/main/assets/web/index.html`
- 相关文件:
  - `modules/web/src/components/BookPageReader.vue`
  - `app/src/main/assets/web/index.html`

**现象**:
- 0.3 已实现跨章动画，但实际阅读时前进/后退跨章都没有动画，直接停在旧章或瞬间切到新章。book/slide 两种效果都失效。

**根因**:
- `scheduleRepaginate` 的定时回调里无条件调用 `cancelFlipAnimation()`。
- 跨章动画由 `flipToChapter` 启动：它先用测量容器分页目标章，再把目标章首页/末页作为 `targetExternalPage` 渲染进舞台，动画时长 book=440ms / slide=260ms。
- 目标章首页 `v-html` 中的图片在动画期间陆续 `load`，触发 `onAnyLoad` → `scheduleRepaginate(200)`；`document.fonts.ready` 也可能在此窗口内 resolve 触发 `scheduleRepaginate(60)`。
- 这些定时器在跨章动画启动后 60~200ms 到期，回调直接 `cancelFlipAnimation()` 把 440ms 的跨章动画取消，`onCancel` 只解锁按钮不切章，于是停在旧章，表现为跨章无动画。章内翻页动画（同样经 `startFlip` 启动）也会被同样机制打断，只是章内翻页频率高、不易察觉。

**修复**:
- `scheduleRepaginate` 入口与定时回调增加 `animating.value || flipLock` 校验：动画进行中只置 `pendingRepaginateAfterFlip = true`，不安排也不触发会取消动画的重分页定时器。
- `finishFlip`（章内动画结束）：若有挂起请求，补一次 `scheduleRepaginate(0)`，把当前进度恢复到对应页。
- `finishExternalFlip` / `cancelFlipAnimation`（跨章动画结束/取消）：清除 `pendingRepaginateAfterFlip`，因为跨章后父组件 `switchBookChapter` 会重挂本组件重新分页，无需在旧章上下文补分页。

**回归测试清单**:
- 书本翻页 book 效果：末页下一页/右滑、首页上一页/右滑，应播放完整跨章动画后进入新章。
- 书本翻页 slide 效果：同上，260ms 滑动跨章动画应完整播放。
- 跨章动画期间目标章图片加载、字体就绪不应打断动画。
- 章内翻页期间图片加载/字体就绪不应打断动画，动画结束后按当前进度重分页。
- 字号/字体/行距/阅读宽度变化在非动画期触发重分页仍正常。
- `prefers-reduced-motion: reduce` 时跨章直接切换并保存正确进度（不受影响，原本就走 `finishFlip`/回调直连）。

### 0.3 书本翻页跨章节动画

- 记录日期: 2026-07-02
- 验证日期: 2026-07-02（`pnpm run type-check`、`pnpm run build-only` 通过；assets 已由 `modules/web/dist/index.html` 手动同步）
- 适用环境: `modules/web` Vue 前端；App 实际加载 `app/src/main/assets/web/index.html`
- 相关文件:
  - `modules/web/src/components/BookPageReader.vue`
  - `modules/web/src/views/BookChapter.vue`
  - `app/src/main/assets/web/index.html`

**现象**:
- Web 服务书本翻页模式下，同一章节内上一页/下一页有纸质书或滑动动画；到章节边界后切换上一章/下一章时直接重挂组件，动画没有生效。

**根因**:
- 原实现的动画目标页只来自当前章节 `pages[targetPageIndex]`。
- 页末/页首跨章时 `BookPageReader` 直接 emit `requestNextChapter` / `requestPrevChapter`，父组件随后替换 `chapterData`、更新 `chapterIndex` 并重挂组件。旧章和目标章从未同时处于同一个动画舞台，CSS 动画没有目标页可渲染。

**修复约束**:
- 跨章前先拿到目标章内容，并在当前 `BookPageReader` 内用同一测量容器分页目标章。
- 动画期间当前页和目标章首页/末页同时渲染；动画结束后再提交 `readingBook.chapterIndex/chapterPos` 并重挂组件。
- 上一章进入目标章最后一页时，哨兵值只作为分页定位输入，不写入 `readingBook.chapterPos`；动画完成后写入真实页起始位置。
- 目标章拉取或分页失败时保留直接切章兜底，不把阅读页锁死。

**回归测试清单**:
- 书本翻页模式下，章节最后一页点下一页/右方向键/左滑，应播放当前效果的跨章动画后进入下一章第一页。
- 章节第一页点上一页/左方向键/右滑，应播放跨章动画后进入上一章最后一页，并保存真实末页位置。
- 纸质书和滑动两种效果都应覆盖跨章路径。
- 目标章未预取时，等待拉取后仍应播放动画；拉取失败时不应跳章或锁住按钮。
- `prefers-reduced-motion: reduce` 时跨章直接切换并保存正确进度。

### 0.2 书本翻页固定动画优化

- 记录日期: 2026-07-02
- 验证日期: 2026-07-02（`pnpm run type-check`、`pnpm run build`、`scripts/check-web-assets-sync.sh` 通过；assets 已由 `modules/web/dist/index.html` 手动同步）
- 适用环境: `modules/web` Vue 前端；App 实际加载 `app/src/main/assets/web/index.html`
- 相关文件:
  - `modules/web/src/components/BookPageReader.vue`
  - `modules/web/src/components/ReadSettings.vue`
  - `app/src/main/assets/web/index.html`

**设计结论**:
- PC 版和移动端 Web 阅读页都不做跟随手指的纸张弯曲；触发翻页后播放固定时长、固定轨迹的预设动画。
- 纸质书效果不使用整页 3D 旋转；目标页固定在底层，翻动页用 `clip-path` 固定裁切轨迹收起/展开，并让页边阴影跟随移动。整页 3D 旋转会让页面在透视空间里左右漂移，PC 浏览器上容易出现抖动且不像手机阅读器的固定仿真翻页。
- 纸质书动画期间会同时渲染当前页和目标页；页层必须显式使用当前阅读主题的 `content` 背景作为不透明背景。只写 `background: inherit` 时无法从父级 `.chapter` 继承到背景，底层目标页文字会透出形成重影。
- 旧页和目标页必须用互补 `clip-path` 分区显示：未翻页区域只显示旧页，已翻页区域只显示目标页，不能靠目标页整页铺底，否则会出现两页内容重叠或边界不清。
- 滑动效果只做当前页/目标页横向移入移出，不叠加淡入淡出，优先保证清晰和低卡顿。
- 页码和阅读进度在动画结束后提交；动画中锁定翻页，避免连点导致进度和可见页不同步。
- `prefers-reduced-motion: reduce` 时跳过动画直接切页，保留可访问性。

**回归测试清单**:
- PC 端左右方向键、底部上一页/下一页按钮能触发纸质书和滑动两种固定动画。
- 手机浏览器左右滑动只作为触发手势，松手后播放固定动画；竖向滑动不应误触翻页。
- 快速连续翻页时不应跳页、错页或保存到动画前进度。
- 字号、字体、行距、段距、阅读宽度变化后重分页，当前页应按旧进度恢复。
- 第一页上一页、最后一页下一页仍走跨章逻辑；相邻章节预取命中时不出现全局 loading mask。
- 页面隐藏/路由离开前阅读进度仍保存到浏览器 storage 和后端。

### 0. 键盘翻屏滚动卡顿优化

- 记录日期: 2026-06-27
- 验证日期: 2026-06-27（`pnpm run type-check`、`pnpm run build` 通过；assets 已由 `modules/web/dist/index.html` 手动同步）
- 适用环境: `modules/web` Vue 前端；App 实际加载 `app/src/main/assets/web/index.html`
- 相关文件:
  - `modules/web/src/views/BookChapter.vue`
  - `modules/web/src/components/ChapterContent.vue`
  - `app/src/main/assets/web/index.html`

**现象**:
- 电脑端浏览器访问 Web 服务阅读页，使用键盘上/下键翻屏滚动时偶发掉帧、卡顿。
- 长章节、段落多、无限滚动接近章节末尾时更容易复现。

**根因判断**:
- 上/下键被拦截后使用 `jump()` 自定义 `requestAnimationFrame` 动画滚动一屏，滚动期间主线程帧预算较紧。
- 每个段落都挂 `IntersectionObserver`，段落进入顶部观察区时会立即更新阅读进度。
- 进度更新触发 `store.readingBook` deep watch，同步写 `localStorage/sessionStorage`；这些同步写入会和滚动动画争抢主线程。
- 无限滚动到底部时原先需要同时等待下一章网络/解析并追加整章 DOM，长章节会放大末尾卡顿。

**修复约束**:
- 阅读进度状态仍要实时更新，页面隐藏/离开前必须 flush 到浏览器 storage，并继续使用 beacon 保存服务端进度。
- 当前保存分为两级：浏览器向手机使用固定 5 秒 latest-wins 窗口；手机收到后先写 Room，再以固定 60 秒窗口上传 WebDAV。段落变化不应逐次发请求。
- 无限滚动采用“提前请求正文、到底部再挂 DOM”：预取只缓存正文数组，不提前挂载段落节点，避免提前创建大量 DOM 和 observer。
- 预取缓存只保留 1-2 章；切章、关闭无限滚动、组件卸载时清空，并用 generation 防止旧请求晚返回污染缓存。
- 预取失败静默降级，到底部追加时仍走原有 `getContent(index + 1, false)` 兜底。

**回归测试清单**:
- 电脑端按上/下键连续翻屏，滚动期间不应频繁卡顿。
- 长章节段落多时，进度仍能正确保存到刷新后的阅读位置。
- 页面隐藏/关闭/路由离开前，最近进度仍写入浏览器 storage，并通过 beacon 尝试保存到服务端。
- 无限滚动开启时，接近章节末尾会提前请求下一章；真正到底部追加章节时不显示全局 loading mask。
- 预取失败、服务暂停或断网时，到底部追加仍提示失败且不插入错误章节。
- 切换目录章节后，旧预取缓存不应污染新章节追加。

### 0.1 中英文混排字体链

- 记录日期: 2026-06-27
- 验证日期: 2026-06-27（`pnpm run type-check`、`pnpm run build` 通过；assets 已由 `modules/web/dist/index.html` 手动同步）
- 适用环境: `modules/web` Vue 前端；App 实际加载 `app/src/main/assets/web/index.html`
- 相关文件:
  - `modules/web/src/config/themeConfig.ts`
  - `app/src/main/assets/web/index.html`

**设计结论**:
- 不内置中文字体文件，避免显著增加 APK 体积和 Web 首屏字体加载成本。
- 通过 CSS `font-family` fallback 顺序实现中英文混排：英文字母/数字优先命中西文字体，中文再回落到中文字体。
- 默认字体链使用系统无衬线西文字体优先，中文回落到苹方/微软雅黑/Noto Sans CJK。
- 宋体、楷体选项使用 Georgia/Times 优先处理英文数字，再回落到 Songti/SimSun 或 Kaiti/KaiTi/STKaiti/LXGW WenKai。

**回归测试清单**:
- 电脑端和手机端分别测试默认/宋体/楷体三种选项。
- 中英混排段落中英文和数字应更接近西文字体形态，中文仍保持对应中文字体风格。
- 未安装某个中文字体的系统应正常 fallback，不应出现方块或空白。

### 1. 无限滚动追加章节不能使用全局 Loading

- 记录日期: 2026-06-05
- 验证日期: 2026-06-05（源码检查 + assets 内 module JS 语法检查）
- 适用环境: `modules/web` Vue 前端；App 实际加载 `app/src/main/assets/web/index.html`
- 相关文件:
  - `modules/web/src/views/BookChapter.vue`
  - `modules/web/src/store/bookStore.ts`
  - `app/src/main/assets/web/index.html`

**现象**:
- 设置里打开 Web 服务，通过浏览器阅读时，每章末尾触发无限滚动加载下一章会出现一层白色遮罩。
- 手机锁屏或 App 未打开时，浏览器端无限滚动和阅读进度更新表现不稳定。

**根因**:
- `getContent(index + 1, false)` 追加下一章时复用了 `loadingWrapper()`，而 `useLoading()` 会在章节容器上插入 `.web-loading-mask`，视觉上覆盖阅读区域。
- 追加请求期间只用 `isLoading` 去防重，会和全局 loading 状态混在一起。
- 阅读进度保存原先始终使用 `navigator.sendBeacon()`。Beacon 适合页面隐藏/卸载，但正常阅读中没有可等待的响应，失败不可感知。

**修复约束**:
- 整章跳转、目录切章、上一章/下一章仍可使用全局 loading。
- 无限滚动追加章节必须使用独立的 `isAppendingChapter` 防重，不显示全局遮罩。
- 追加下一章失败时只提示错误，不要把“获取失败”伪章节插入 `chapterData`，避免后续章节 index 被错误推进。
- 正常阅读进度保存走普通 POST；`visibilitychange=hidden` 时才用 beacon。

**构建同步注意**:
- 只改 `modules/web/src` 不会自动进入 APK。
- 本地没有运行 web 构建同步时，必须同步改 `app/src/main/assets/web/index.html`。
- 同步压缩后的 assets 文件时必须检查 module JS 语法，避免 minified 变量名冲突。
- 提交前 `scripts/check-web-assets-sync.sh` 会检查暂存区：如果改了 `modules/web` 运行时源码/配置但没有暂存 `app/src/main/assets/web/`，pre-commit 会阻止提交。

**回归测试清单**:
- 浏览器打开 Web 服务阅读页，关闭无限滚动，普通上一章/下一章正常。
- 开启无限滚动，滚到章节末尾时不出现白色遮罩。
- 下一章成功追加后继续滚动能继续追加后续章节。
- 断网或服务暂停时追加下一章只提示失败，不应把错误内容当作下一章显示。
- 阅读进度在正常阅读过程中能更新；切到后台/锁屏前触发隐藏事件时仍能保存最近进度。
- 如果要求锁屏后 App 仍持续服务，应优先引导开启设置里的 WebService 唤醒锁，不要默认强开。

### 2. Web 源码变更必须同步 APK assets

- 记录日期: 2026-06-06
- 验证日期: 2026-06-06（`scripts/check-web-assets-sync.sh` 和 `.githooks/pre-commit` 手动验证）
- 适用环境: `modules/web` Vue 前端；App 实际加载 `app/src/main/assets/web/`
- 相关文件:
  - `.githooks/pre-commit`
  - `scripts/check-web-assets-sync.sh`

**约束**:
- 暂存区中如果包含 `modules/web/src/`、`modules/web/public/`、`modules/web/index.html`、`modules/web/package.json`、`vite.config.ts`、`tsconfig*.json` 等运行时源码/配置变更，必须同时暂存 `app/src/main/assets/web/` 变更。
- 该脚本只做一致性拦截，不执行 Node/pnpm 构建，不验证 minified JS 语义。

**维护注意**:
- 如果新增会影响 Web 产物的配置文件，必须加入 `scripts/check-web-assets-sync.sh` 的源码匹配列表。
- 只改 `modules/web/README.md`、`LICENSE`、`scripts/sync.js` 等非运行时文件时，不要求 assets 同步。

### 3. WebService 长时运行自动重启

- 记录日期: 2026-06-28
- 验证日期: 2026-06-28（`./gradlew :app:compileAppDebugKotlin` 编译通过）
- 适用环境: WebService 运行超过 3 小时后偶发不可访问（NanoHTTPD 长时运行连接泄漏/死锁、WiFi 切换后旧 IP 绑定、Doze CPU 休眠）
- 相关文件: `app/src/main/java/io/legado/app/service/WebService.kt`

**问题**:
- WebService 启动后长时间运行，偶发浏览器报"网页不可用"。
- 根因：NanoHTTPD 实例长时间运行后可能连接泄漏/死锁；WiFi 切换后 server 仍绑定旧 IP；锁屏 Doze 下 CPU 休眠工作线程被挂起。
- `BaseService.onTimeout`（API 35+）在 dataSync 前台服务超时后直接 `stopSelf()`，Android 16+ 每 24h 累计 6h 上限触发后服务关闭。

**方案**:
- WebService 监听 `EventBus.SAVE_CONTENT`（新章节下载保存完成事件，由 `BookHelp.saveContent` 和 `CacheBook` 发出）。
- 记录服务启动时间 `startTimeMs`，当运行超过 `RESTART_INTERVAL_MS`（3 小时）且有新章节加载完成时，调用 `upWebServer()` 重建 httpServer/webSocketServer 实例。
- `upWebServer()` 内部成功 start 后统一重置 `startTimeMs` 重新计时；重启失败（端口占用/无网络）走 IOException 分支 `stopSelf()`。
- 不 `stopSelf` + `startService` 重启整个 Service，避免触发系统 Service 重启冷却期，保留前台服务状态和通知连续性。

**设计取舍**:
- 选择 `SAVE_CONTENT` 事件而非 `contentLoadFinish` 回调：前者是 EventBus 可跨组件监听，且只在下载新章节时触发（缓存命中不触发），频率适中；后者是 callback 接口不适合 Service 监听。
- 不改 `webServiceWakeLock` 默认值（AGENTS.md 约束）：唤醒锁默认 false，定期重启 server 实例已能缓解大部分长时运行问题。
- 不规避 Android 16+ dataSync 6h/24h 系统限制：定期重建 server 实例解决的是应用层问题，系统级累计计时重置需用户手动重启服务。
- `upWebServer()` 先 stop 旧 server 再 start 新 server，重启瞬间正在连接的客户端会断开需重连，可接受。

**优化补强 (2026-06-28)**:
- **兜底定时器**: 仅靠 `SAVE_CONTENT` 事件触发存在覆盖盲区——用户开 Web 服务后长时间不读任何新章节（典型：开一整夜白天再用，或只读已缓存章节）时事件不触发，重启不生效。新增 `startRestartChecker()`：`lifecycleScope` 协程每 `RESTART_CHECK_INTERVAL_MS`（30 分钟）轮询一次 `tryRestartOnChapterLoaded()`，作为事件触发之外的兜底，覆盖静默场景。`onDestroy` 取消该协程。
- **重入保护**: `upWebServer()` stop+start 期间非幂等，被并发调用会出问题。虽然 `observeEvent` 回调（`lifecycleScope + repeatOnLifecycle(CREATED)`）和 `onStartCommand`/`onNetworkChanged` 均在主线程实际串行，但加 `@Volatile isRestarting` + `synchronized(restartGuard)` 显式保护，防止后续有人把调用挪到后台线程。
- **计时统一**: 把 `startTimeMs` 重置从 `tryRestartOnChapterLoaded` 下移到 `upWebServer()` 成功分支内部，所有调用路径（首次启动 / 事件触发重启 / 定时器触发重启）统一在 server 成功 start 后重置计时，语义一致。

**回归测试点**:
- Web 服务启动后正常访问，3 小时内阅读新章节不触发重启（日志无"触发重启"）。
- 模拟运行超过 3 小时（可临时调小 `RESTART_INTERVAL_MS` 测试），阅读新章节后日志出现"触发重启"和"(重新)启动成功"，`httpServer` 新实例 isAlive。
- 模拟运行超过 3 小时且**不读任何新章节**，等待 30 分钟（`RESTART_CHECK_INTERVAL_MS`）后日志出现兜底定时器触发的重启。
- 重启后浏览器能正常访问 Web 服务阅读页。
- 重启时端口被占用（模拟）→ `upWebServer()` 走 IOException 分支 `stopSelf()`，不会残留半启动状态，`isRestarting` 在 finally 复位。
- 并发触发重启（事件 + 定时器同时到期）→ `synchronized` 保证只执行一次，第二次调用直接 return。
- 网络切换（WiFi↔移动网络）后 `networkChangedListener` 更新 hostAddress；该路径不调 `upWebServer()` 故不重置计时（server 实例未变，语义正确）。

### 4. WebService 熄屏首次访问短时唤醒租约

- 记录日期: 2026-08-21
- 验证日期: 2026-08-21（应用日志时序、源码链路检查、全量 173 项 JVM 测试及 `scripts/check-debug.sh` 通过；问题手机真机回归待验证）
- 适用环境: WebService 已启动、手机熄屏后从浏览器首次或再次访问
- 相关文件: `app/src/main/java/io/legado/app/service/WebService.kt`, `HttpServer.kt`, `AssetsWeb.kt`

**日志结论**:
- 最近一次异常中，`SCREEN_OFF` 后根页面请求已到达，但 `/getReadConfig` 晚约 23.781 秒；紧接的 `/getBookshelf` 约 9 ms 返回，未出现数据库空结果或 WebService 停机证据。
- 同次熄屏期间稍后刷新只需约 0.389 秒；历史同型离群达到约 573.226 秒，而已统计样本中位数约 1.160 秒。故问题集中在首屏 asset 响应到浏览器开始初始化接口之间，不是稳定的后端查询慢。
- 结合熄屏状态，当前最符合 CPU/Wi-Fi 省电造成的首次传输停滞；由于没有内核电源日志或抓包，不将其写成特定 ROM/驱动的已证实故障。

**实现约束**:
- 服务初始化完成后立即取得 CPU `PARTIAL_WAKE_LOCK` 和 Wi-Fi 高性能锁，默认保持 90 秒，覆盖首次打开。
- `HttpServer` 每个请求及 WebSocket 握手会调用既有 `WebService.serve()`；`serve` action 只续期同一个释放任务，以最后一次入站访问为起点重新计时 90 秒。
- 两把锁均保持非引用计数，并在取得、释放前检查 `isHeld`；连续请求不累计 acquire/release 次数。统一停机先取消释放任务，再释放仍持有的锁。
- `webServiceWakeLock=true` 时继续沿用服务全生命周期保活，短时释放任务不启动；默认值不变，也不新增 Web 心跳、前台服务类型或 `onTaskRemoved()` 行为。

**效果边界与回归**:
- 本机制只能在请求已经到达 WebService 后续期；若系统已让手机断开 Wi-Fi、路由器隔离客户端或浏览器本身被冻结，仍可能无法建立首次请求。
- 永久锁关闭：验证启动后 90 秒内熄屏首次打开、连续访问不断续期、最后访问后 90 秒释放，以及超时后下一次已到达的请求重新取得锁。
- 永久锁开启：验证超过 90 秒仍持锁；关闭、端口重启、权限撤销和系统超时统一停机后均无锁或协程残留。
- 真机重点对比“刚启动即熄屏首次打开”和“熄屏空闲超过 90 秒再打开”；只有前一条由启动租约完整覆盖，后一条依赖请求能先到达服务。

---

---

## 十、WebDAV 仅恢复阅读进度

### 1. 跨设备恢复不能复用 SAF URI

- 记录日期: 2026-06-16
- 验证日期: 2026-06-16（源码检查；编译验证见当前修改记录）
- 适用环境: WebDAV 在线恢复；Android 16 与 Android 8.1 等本地书目录名/授权根不一致设备
- 相关文件:
  - `app/src/main/java/io/legado/app/help/AppWebDav.kt`
  - `app/src/main/java/io/legado/app/ui/main/MainActivity.kt`
  - `app/src/main/java/io/legado/app/ui/config/BackupConfigFragment.kt`
  - `app/src/main/java/io/legado/app/ui/main/MainViewModel.kt`
  - `app/src/main/java/io/legado/app/ui/config/ConfigViewModel.kt`

**设计约束**:
- WebDAV 在线恢复入口必须让用户选择“仅恢复阅读进度”或“完整恢复备份”。
- “仅恢复阅读进度”只读取 WebDAV `bookProgress/*.json`，不下载备份 zip，不调用 `Restore.restore(...)`，不恢复 `bookshelf.json`。
- v2 进度按 `bookProgressKey` 匹配：本地书用不含设备路径的内容 SHA-1；同名冲突的在线书惰性使用 `bookUrl` SHA-256。匹配不到时跳过，不创建本地书。
- keyless 唯一本地旧书首次恢复先验证 v2，只有 v2 缺失、无效、身份错或越界时才读取旧书名作者文件；v2 写入当前有效位置后才持久化 key，有 key 后只读 v2。同名旧记录无法生成 key 时跳过，不能猜测映射。
- WebDAV GET 返回 HTTP 404 时不依赖 response message 或 XML body，统一视为远端文件缺失；这既保证“仅恢复”可安全跳过不存在的进度，也让单书同步能继续上传本地位置并建立首个 v2。旧版只识别 legacy，参与同步的所有设备必须升级到支持 v2 的版本。
- 写入字段仅限 `durChapterIndex`、`durChapterPos`、`durChapterTitle`、`durChapterTime`、`syncTime`。
- 写入前继续校验章节范围；仅恢复阅读进度使用 `ProgressCheckMode.RangeOnly`，不得因为本地 SAF URI 当前不可读而跳过。只有真正加载本地书内容的 `ReadableRequired` 路径才调用 `FileBook.checkBookReadable()`。
- 不要把 `primary:yuedu` 之类 SAF URI 字符串替换成另一个目录名。

**回归点**:
- 自动检测到新 WebDAV 备份后，确认恢复应弹出恢复模式选择。
- 备份设置页手动选择 WebDAV 备份文件后，应弹出恢复模式选择。
- 跨设备场景选择“仅恢复阅读进度”时，不修改本地书 `bookUrl`。
- 选择“完整恢复备份”时，仍走原 `restoreWebDav(name)` 完整恢复行为。
- 已有 `bookProgressKey` 的本地书即使 SAF URI 暂不可读，仍可恢复范围有效的进度；keyless 唯一旧书必须先读取精确文件补齐身份，失权则安全跳过。章节越界或身份不匹配同样跳过，不误报为 WebDAV 配置失败。

### 2. DNS 解析失败只改用户提示

- 记录/验证日期: 2026-08-21（用户日志与调用链复核；错误分类定向测试、全量 177 项 JVM 测试和 `scripts/check-debug.sh` 通过；真机弹窗待验收）
- 适用环境: 书架页与备份设置页的“仅恢复阅读进度”入口
- 相关文件: `WebDavException.kt`、`MainViewModel.kt`、`ConfigViewModel.kt`、各语言 `strings.xml`

**当前结论**:
- 2026-08-20 11:58:39 日志记录了多次 Cronet `ERR_NAME_NOT_RESOLVED`，11:59:28 手动恢复失败；当时手机亮屏，且 12:28 WebDAV GET/PUT 已恢复，应解释为临时网络或 DNS 故障，不归因于休眠、WebService 或永久配置错误。
- 仅异常链中出现 `UnknownHostException` 或消息含 `ERR_NAME_NOT_RESOLVED` 时，用户看到“暂时无法连接 WebDAV，请检查网络或 DNS 后重试，本地阅读进度未受影响。”超时、鉴权和未配置等其他错误继续显示原异常，不扩大归类。
- 这是用户可读性改动，不是隐藏故障：`AppLog.put` 仍保留完整异常和堆栈，不改 WebDAV 请求、本地进度应用顺序，不加自动重试。

**回归点**:
- 直接、被 cause 包装的 `UnknownHostException` 及 Cronet 文本都要命中；无关的 `IOException` 必须不命中。
- 两个手动入口使用同一多语言资源，日志仍可用原始错误排查；用户手动重试前不产生新请求。


---

## 十一、Web 服务书本翻页阅读模式

> 本节演进说明：本节主体按日期记录第一版及后续修复。纸质书效果已不再使用第一版整页 `rotateY` 方案，而是在 `BookPageReader.vue` 的同一舞台中用固定 `clip-path` 裁切、边缘和阴影关键帧完成；滑动效果仍为固定平移。当前结论优先参考本章后续评审记录以及前面的 0.2～0.6 小节。

### 1. 功能范围与设计取舍

- 记录日期: 2026-07-01
- 验证日期: 2026-07-01（`pnpm run type-check`、`pnpm run build` 通过；`app/src/main/assets/web/index.html` 已手动同步）
- 适用环境: `modules/web` Vue 3.5 前端；APK 实际加载 `app/src/main/assets/web/index.html`
- 相关文件:
  - `modules/web/src/web.d.ts`（新增 `pageMode` / `pageTurnEffect` 配置字段）
  - `modules/web/src/store/bookStore.ts`（默认配置 + `activePageMode` 运行时态 + `fallbackToScroll`/`syncActivePageMode`/`setActivePageMode`）
  - `modules/web/src/components/ReadSettings.vue`（阅读模式/翻页效果切换、书本模式禁用无限滚动开关）
  - `modules/web/src/utils/bookPagination.ts`（分页模型与 DOM 测量分页算法，复用滚动模式 chapterPos 语义）
  - `modules/web/src/components/BookPageReader.vue`（书本翻页 UI、CSS Transition 翻页动画、点击/键盘/触摸、跨章、fallback）
  - `modules/web/src/views/BookChapter.vue`（按 `activePageMode` 分发滚动/书本、书本模式禁用无限滚动观察器、复用 `getContent`/进度保存）
  - `app/src/main/assets/web/index.html`（构建产物同步）

**最高优先级约束（方案 §1）**:
- 默认仍为连续滚动模式，行为完全不变。
- 书本翻页相关代码全部放在新组件/新 composable，和滚动路径分离。
- 初始化失败/分页失败时自动回退滚动模式，且只 `store.fallbackToScroll()`（不回写 `config.pageMode`），避免临时错误永久改变用户配置。
- 不允许大面积重写 `ChapterContent.vue` 的滚动逻辑；本变更对它完全零改动。

### 2. 关键实现点与陷阱

- **配置兼容**：`webReadConfig` 新增 `pageMode: 'scroll' | 'book'`（默认 `'scroll'`）与 `pageTurnEffect: 'slide' | 'book'`（默认 `'book'`）；`bookStore.setConfig` 仍用 `Object.assign({}, this.config, config)` 合并，旧服务端配置无新字段时自动用默认滚动，不报错。
- **运行态 vs 持久态分离**：`store.activePageMode` 是运行时态，用户主动切换调 `syncActivePageMode()` 同步；`BookPageReader` 失败时只改 `activePageMode`，绝不改 `config.pageMode`，避免临时回退污染用户设置。`BookChapter.vue` 一律以 `store.activePageMode` 决定渲染，不直接绑配置字段，避免失败后反复进入异常组件。
- **进度语义复用**：`buildBlocks` 的 block `topPos` 严格复用 `ChapterContent.vue` 的累计字符公式（图片标签按 1 字符计、每段 +1 换行、标题固定 0），所以书本模式保存的 `chapterPos` 切回滚动模式时 `toChapterPos` 可正确定位、跨模式进度互转不丢失。
- **分页算法**：`paginateBlocks` 用递归 `place(block)`：先试放入当前页，溢出则换页再试，仍放不下则段落按字符二分切分（标题/图片强制独占页）。`splitParagraph` 取纯文二分；只有当整段落单独一页都放不下才触发，正常段落不切分，因此内嵌图片只在极端超长段触发切分时丢失（可接受）。
- **翻页动画（历史第一版）**：第一版不引入第三方库，用 Vue `<Transition>` + CSS 实现；当时的 `bp-book` 使用 `rotateY`，`bp-slide` 使用横向平移。该实现已被本章后续固定轨迹方案替代，不能再作为当前 CSS 结构说明；`prefers-reduced-motion` 禁用动效和防连点约束仍保留。
- **跨章定位**：下一章传 `chapterPos=0` 落第一页；上一章传 `Number.MAX_SAFE_INTEGER`，`findPageIndexByPos` 兜底返回最后一页。`getContent` 在 reloadChapter 且 `activeBookMode` 时同步 `bookInitialPos = chapterPos`（参数），保证目录切章传入的 `chapterPos` 能正确定位页。
- **组件重挂**：`BookPageReader` `:key="bp-${index}-${seed}"`，章节变化时重挂载重新分页；`bookReaderSeed` 在 `fallbackToScroll`/切回书本复用同章节时自增以强制重分页。
- **图片代理**：`BookPageReader.proxyInlineImages` 复用 `API.getProxyImageUrl(bookUrl, src, fontSize*2)`，与 `ChapterContent.replaceImage` 一致；图片 `load` 事件冒泡到 document 时触发防抖重分页。
- **DOM 测量容器**：`.bp-measure` 用 `position:absolute; left/top:-99999px; visibility:hidden` 脱离布局但保持同尺寸，宽高/字号/行距/字距/段距/边距与可见页完全一致；测量在隐藏容器进行，可见页用 `v-html` 渲染单页块，避免和 Vue 响应式 DOM 争夺渲染权。
- **键盘去重**：`BookChapter.handleKeyPress` 监听 `keyup`，`BookPageReader.onKey` 监听 `keydown` 且 `stopPropagation+preventDefault`；`BookChapter` 在 `activeBookMode` 时对 ArrowLeft/ArrowRight 不再切章，避免翻页和切章双触发。
- **无限滚动隔离**：`watchEffect` 中 `!infiniteLoading || activeBookMode` 即断开并清空 `scrollObserver/prefetchObserver` 和预取缓存；书本模式 `.loading` 元素 `v-if` 不渲染，避免 `observe(undefined)`。

### 3. 构建与同步

- `pnpm build` 走 `type-check` + `vite build` + `scripts/sync.js`；`sync.js` 仅在 `GITHUB_ENV` 存在时复制产物，**本地构建后必须手动** `cp modules/web/dist/index.html app/src/main/assets/web/index.html` 和 `favicon.ico`，未同步则 APK 内 Web UI 不生效。
- `BookPageReader` 在 `components.d.ts` 手动登记，让 `vue-tsc` 识别 `<book-page-reader>`（构建时 unplugin-vue-components 会重新生成该文件）。

### 4. 第一版暂不做 / 已知限制

- 不引入 `page-flip` / `StPageFlip` 等第三方动画库，第一版只做稳定 CSS 过渡；如需更纸张物理视觉再评估。
- 分页只对当前章；跨章时重新分页，不做整书预分页。
- 段落二分切分会丢失该段内嵌图片（仅在整段单独一页放不下时才触发，正常情况不切分）。
- 不处理 EPUB 原版排版还原、不做纸张卷曲物理仿真。
- 书本模式与无限滚动互斥（已通过禁用观察器保证）。

### 5. 回归测试清单

- 默认配置进入阅读页仍是滚动模式，无限滚动/键盘翻屏/目录切章/进度保存均不回归。
- 设置切到"书本翻页"后，页面不再自然长滚动，分页后正确显示当前章首页。
- 点击右侧/左侧、键盘左/右键、手机横向滑动均能翻下一页/上一页。
- 当前章第一页上一页 → 加载上一章并落到最后一页；当前章最后一页下一页 → 加载下一章落到第一页。
- 翻页后刷新页面，能恢复到最近页（通过 `chapterPos` 反查 `findPageIndexByPos`）。
- 切回滚动模式后仍在原阅读位置（顶部段落对齐到保存的 `chapterPos`）。
- 字号/行距/段距/阅读宽度变化后防抖重分页并尽量保持原进度；窗口 resize 后重分页。
- 图片页不撑破版面（`max-height:100%` 缩放）。
- 夜间主题、各阅读主题下页背景和文字颜色正确（`bp-page` `background:inherit` 继承章节主题色）。
- 长章节分页不出现明显长时间白屏（DOM 测量是同步的，超长章节偶有顿挫属预期）。
- `prefers-reduced-motion` 开启时翻页无动画直接切页。
- 模拟分页/初始化异常 → `fallbackToScroll` 切回滚动并 toast 提示一次，且不覆盖用户配置（再次打开设置仍显示原选的"书本翻页"）。

### 6. 评审修复 (2026-07-01)

- 验证日期: 2026-07-01（`pnpm run build` 通过；`modules/web/dist/index.html` 与 `app/src/main/assets/web/index.html`/`favicon.ico` 一致）
- **滚动定位代码在书本模式抛错**：`getContent` reload 后无条件调用 `toChapterPos`，但书本模式模板不渲染 `chapter-content`，`chapterRef.value` 为 `undefined`，`chapterRef.value.length` 会抛未捕获异常。修复：`toChapterPos` 在 `activeBookMode` 直接 return，并对 `chapterRef.value` 判空。
- **哨兵值污染阅读进度**：上一章用 `Number.MAX_SAFE_INTEGER` 作为"末页"哨兵，原实现把它作为 `getContent` 第 3 参 `chapterPos` 写入 `readingBook.chapterPos` 并立即 `saveBookProgress()`，请求慢/失败/页面隐藏都会把哨兵保存到本地/后端。修复：`getContent` 新增第 4 参 `initialPos`（仅作为 `BookPageReader` 初始定位参数，不进入 `readingBook`）；上一章持久化 `chapterPos=0`、`initialPos=哨兵`；书本模式跨章不再立即 `saveBookProgress`，改由 `BookPageReader` 分页后 emit `progressChange` → `onReadedLengthChange` 在章节 index 变化时立即保存（绕过 60s 节流），保证保存的是真实末页 `startPos`。
- **超长段落切分页进度不可区分**：`splitParagraph` 切出的每个 chunk 都沿用同一 `block.topPos`，导致同一长段落拆多页时翻页进度不前进、刷新后只回到第一页。修复：每个 chunk 的 `topPos = block.topPos + 该 chunk 在段内的起始字符偏移 cursor`，使 `findPageIndexByPos` 能区分各 chunk 页、翻页进度随长段切分前进。

### 7. 首挂载误触发 fallback toast (2026-07-01)

- 记录日期: 2026-07-01
- 验证日期: 2026-07-01（`pnpm run type-check`、`pnpm run build-only` 通过；`app/src/main/assets/web/index.html` 已同步）
- **现象**：选择书本翻页模式后打开章节，几乎必现弹 toast "书本翻页初始化失败，已切换为滚动阅读" 并被强制切回滚动，但实际内容与配置都正常。
- **根因**：`BookPageReader` 首挂载时 `pageHeight` 初始为 `0`，`rootStyle` 的 `--bp-page-height` CSS 变量要等 Vue 下一帧才 flush 到 DOM；而 `onMounted` 里 `init()` 同步调用 `updateHeight()` 后立即 `doPaginate()`，此时 `.bp-measure` 容器 `clientHeight` 仍为 `0`。0 高度下 `paginateBlocks` 把**所有**块判为溢出，`splitParagraph` 对段落二分只能得到 1 字符 chunk，1 字符 chunk 在 0 高度容器里仍然溢出，外层 `place` 又对它调用 `splitParagraph` → 对 1 字符 chunk 二分再得 1 字符 chunk → 无限递归 → 栈溢出 → 被 `doPaginate` try/catch 捕获 → 误 emit `fallbackToScroll` → toast 弹出。
- **修复**：
  1. `BookPageReader.vue` `init()` 改为 async，`updateHeight()` 后 `await waitForLayout()`（`requestAnimationFrame` 轮询直到 `measureRef` 的 `clientWidth/clientHeight > 0`，2s 超时兜底）再 `doPaginate()`，保证首次分页拿到真实尺寸。
  2. `measureApi.splitParagraph` 在 `clientWidth/clientHeight <= 0` 时直接返回 `[block]` 不切分，作为布局未就绪时的双保险，彻底切断"1 字符 chunk 仍溢出 → 再次切分"的无限递归链路。
  3. `doPaginate` 不再把 `pages.length === 0` 视作初始化失败 toast 触发点，仅 `console.warn`，避免布局未就绪的瞬时态被误报为永久失败。
  4. `onMounted` 改用 `init().catch(...)` 形式兜底真正的异步异常。
- **回归点**：默认滚动模式行为不变；书本模式首屏正确分页、不再弹 fallback toast；当 measure 容器尺寸异常（极端窄/0 高）时长段落会被落到独立页，由 ResizeObserver/resize/fonts.ready 触发的重分页修正，不再栈溢出。

### 8. 书本翻页动画方向 / 页面尺寸 / 点击热区 (2026-07-01)

- 记录日期: 2026-07-01
- 验证日期: 2026-07-01（`pnpm run type-check`、`pnpm run build-only` 通过；`app/src/main/assets/web/index.html` 已同步）
- **动画前进后退相同**：`<Transition>` 用单一 `name`，enter-from/leave-to 是固定 CSS，无论 `flipNext` 还是 `flipPrev`，新页都从同一侧进入、旧页都向同一侧离开，看起来前进后退完全一样。修复：新增 `flipDirection: 'next' | 'prev'`，`flipNext/flipPrev` 在改 `currentPageIndex` 前先置方向；`transitionName` computed 按效果+方向选 `bp-book-next/prev`、`bp-slide-next/prev`；CSS 为四个方向各写 enter/leave，前进新页从右进旧页左出、后退反之（book 模式 rotateY 符号取反，slide 模式 translateX 符号取反）。
- **页面偏小**：书本模式每页独立、不滚动、不重复，但 `pageWidth=readWidth-130`、`pageHeight=innerHeight-128` 沿用滚动模式的占位（65px 横向 padding + 2×64px 上下 bar），单页偏窄偏矮。修复：`BookChapter.vue` `.chapter` 在 `activeBookMode` 时宽度吃满 `store.config.readWidth`、`padding: 0 20px`；`.content.book-mode .top-bar/.bottom-bar` 高度由 64px 降到 20px；`BookPageReader.pageWidth=readWidth-40`、`pageHeight=innerHeight-40`，单页更宽更高且不产生整体滚动（总高 = 20+reader+20 ≈ innerHeight）。
- **点击翻页热区过大**：原实现用 `.bp-tap--left/center/right` 三个绝对定位热区覆盖整页（左 28%/中 44%/右 28% 全高），点击任意位置即翻页，而改动前滚动模式只有底部 `.read-bar` 的 上一章/下一章 按钮处可点击。修复：删除三个 `bp-tap` 热区及对应 CSS 与 `onTap*/onContainerClick`；`BookChapter` 底部 `.read-bar` 的 上一章/下一章 按钮在 `activeBookMode` 时改调 `bookReaderRef.flipPrev/flipNext`（跨章仍由 `BookPageReader` emit `requestPrev/NextChapter`），mini 模式文案随之显示"上一页/下一页"。整页点击回归 `.chapter-wrapper @click` 切换工具栏，与滚动模式一致。
- **回归点**：默认滚动模式布局/动效零改动；书本模式前进后退动画方向相反、单页加宽加高、翻页点击仅在底部按钮、整页点击切换工具栏行为不变。

### 9. 书本翻页跨章每次弹 loading mask (2026-07-01)

- 记录日期: 2026-07-01
- 验证日期: 2026-07-01（`pnpm run type-check`、`pnpm run build-only` 通过；`app/src/main/assets/web/index.html` 已同步）
- **现象**：书本模式翻到首/末页再翻跨章时，几乎每次都弹"正在获取信息"加载遮罩，比滚动无限模式明显多出一次加载等待。
- **根因**：`onBookRequestNextChapter/PrevChapter` 一律调 `getContent(index, true, …)`，`reloadChapter=true` 会 `setShowContent(false)` + `loadingWrapper`（显示遮罩）+ `clearPrefetchedChapters()`（丢弃已预取的相邻章）+ 重新 `fetchChapterData`。而书本模式原本依赖 `infiniteLoading` 才预取，切换书本模式时 `infiniteLoading` 被强制为 `false`，导致 `prefetchChapter` 从不触发；`watchEffect` 里 `!infiniteLoading || activeBookMode` 分支又在每次响应式触发时 `clearPrefetchedChapters()`，即使有点缓存也被清空。结果跨章永远走全屏遮罩网络重载。
- **修复**：
  1. `watchEffect` 分支拆分：`activeBookMode` 时只断开滚动观察器，**不再清空预取缓存**；仅"滚动模式 + 未开无限滚动"时才 `clearPrefetchedChapters()`。
  2. 新增 `prefetchBookAdjacent(index)`，在 `getContent` 成功且 `reloadChapter` 时对书本模式预取前后相邻章；`switchBookChapter` 命中后也补预取，供下一次跨章即时用。
  3. 新增 `switchBookChapter(targetIndex, initialPos, safePos)`：优先从 `prefetchedChapters` 或已加载 `chapterData` 取目标章，命中则直接 push（限制 `chapterData` 保留 ≤3 章避免内存增长）、设 `bookInitialPos`、更新持久化进度（`safePos`，非哨兵）、`bookReaderSeed++` 强制重挂 `BookPageReader` 重新分页、立即 `saveBookProgress`、补预取相邻章，**全程不 `setShowContent(false)`、不显示 loading mask**。
  4. `onBookRequestNextChapter/PrevChapter` 改为先 `switchBookChapter`，命中即返回，未命中才回退到原 `getContent` 走网络加载遮罩。
- **回归点**：默认滚动模式预取/加载逻辑零改动；书本模式首次进入章节（无缓存）仍走一次正常遮罩加载；相邻章预取完成后向前/向后翻跨章即时切换无遮罩；`chapterData` 最多保留 3 章避免内存增长；预取缓存不在书本模式响应式触发时被清空。

## 10. Web 阅读页阅读时长记录

- 记录日期: 2026-08-01
- 复核日期: 2026-08-02（接管审查发现空闲状态可被恢复事件清除、键盘续活缺失，以及后端信任客户端时间戳且未限制上界；修复后 `pnpm run test:read-time` 6 项、定向 JVM 测试 5 项、全量 JVM 测试 133 项、Web 类型/ESLint/生产构建、assets 字节同步、Debug APK、Release/R8 及 `scripts/check-debug.sh` 通过；浏览器/真机阅读计时仍待执行）
- 适用环境: Web 服务阅读页、阅读记录热力图、跨天统计
- 相关文件:
  - Web 前端: `modules/web/src/utils/readTimeTracker.ts`、`modules/web/src/utils/readTimeTrackerCore.ts`、`modules/web/src/views/BookChapter.vue`、`modules/web/src/api/api.ts`
  - Web 后端: `app/src/main/java/io/legado/app/web/HttpServer.kt`、`app/src/main/java/io/legado/app/api/controller/ReadTimeController.kt`
  - 原生复用: `app/src/main/java/io/legado/app/model/ReadTimeRecorder.kt`、`app/src/main/java/io/legado/app/model/WebReadTimeSession.kt`
  - 生产产物: `app/src/main/assets/web/index.html`

**设计结论**:
- Web 端只做"感知和上报"，不做持久化计时状态：进入章节开始计时，切章时把上一章时长发到后端，由后端统一写入 `readRecord`。
- 开关统一受手机端 `AppConfig.enableReadRecord` 控制，后端 `ReadTimeController.saveReadTime` 保存前再校验；Web 端无需独立开关。
- 防挂机：章节内任一无交互间隔超过 10 分钟，本章计时即保持作废；恢复可见或再次交互只能记录新活跃时间，不能清除已经发生的超时。
- 防抖动：不足 5 秒的章节不计入；后端复用 `ReadTimeRecorder.recordWebSession` 及跨天拆分逻辑。
- 省电：不发心跳、不常驻连接，仅在切章产生一次 HTTP POST；页面可见性恢复时只更新活跃时间。
- 不上报未切章：页面关闭、切后台、直接离开均不提交当前未完成的章节，避免跨章/跨书边界争议。
- 多设备/多标签：同一时刻多 Web 会话会分别写入，可能重复；一期以"用户通常只看一个屏"为前提，不额外做设备/标签去重。

**实现约束**:
- `readTimeTrackerCore` 维护当前书名、章节开始时间、最后活跃时间和粘性的 `idleExpired`；`watch(chapterIndex)` 监听切章，提交上一章后才为新章重置全部状态。
- 事件监听限制在 `scroll` / `click` / `touchstart` / `keydown` / `keyup` / `visibilitychange=visible`，只先检查空闲间隔再更新 `lastActiveTime`，不触发网络请求；滚动与触摸监听使用 passive 模式。
- 后端 `ReadTimeController.saveReadTime` 只接收 `{bookName, durationMs}`；`durationMs` 必须为整数且在 5 秒至 24 小时内，结束时间取手机端 `System.currentTimeMillis()`，再调用 `ReadTimeRecorder.recordWebSession` 在 IO 协程中按自然日同步落盘后返回。
- 新增 `/saveReadTime` POST 路由注册到 `HttpServer.handlePost`；响应只返回成功/失败，不暴露数据库细节。
- `pnpm run test:read-time` 覆盖 5 秒下界、正常续活、空闲超时粘性、切章重置和清理不提交；Web 修改后仍须生产构建、手动同步两个 `dist` 产物并做字节一致性校验。

**回归点**:
- Web 端开启一本书阅读，每切一章，手机端阅读记录当日新增对应时长。
- 同一章内超过 10 分钟无操作后，即使恢复页面、滚动或按键再切章，该章时长也不写入。
- 不足 5 秒的章（快速划过）不计入。
- 手机端关闭阅读记录开关后，Web 端切章不再新增记录。
- 跨天阅读（如 23:50 进入、00:10 切章）以手机端收到请求的时间为结束点拆分两天；伪造客户端时间戳或超过 24 小时的单次时长不能写入。
- 页面关闭/切后台/直接返回书架不触发未切章的异常写入。

## 11. Web 阅读页全文搜索章节并行化

- 记录日期: 2026-08-04
- 复核日期: 2026-08-07（`BookContentSearcherTest` 27 项及 `scripts/check-debug.sh` 通过，新增 WebDAV 关联本地书来源边界；2026-08-04 的 Debug APK 与模拟器 WebSocket 结果未自动续期）
- 适用环境: Web 阅读页全文搜索（`BookContentSearch.vue` → WebSocket → `BookContentSearchService`）
- 相关文件:
  - 编排: `app/src/main/java/io/legado/app/help/book/BookContentSearcher.kt`（`BookContentSearchService.search`）、`app/src/main/java/io/legado/app/web/socket/BookContentSearchWebSocket.kt`
  - 线程安全: `app/src/main/java/io/legado/app/help/book/ContentProcessor.kt`
  - 单测: `app/src/test/java/io/legado/app/help/book/BookContentSearcherTest.kt`
  - Web 前端契约不变: `modules/web/src/components/BookContentSearch.vue`（零改动，无需同步 assets）

**设计结论**:
- 原实现按章节串行读+净化+匹配，长书耗时≈各章之和。现改为固定 worker 消费章节任务、完成结果写入带章节位置的 Channel，协调端用索引缓冲按 `chapterIndex` 有序上报；调度窗口限制为并发数的两倍，前序章节完成后立即滑动补位，不再等待固定分块全部结束，也不会在单个慢章前堆积整本书结果。严格结果顺序仍意味着后章结果可能暂存在窗口内等待慢前章，文档不得再宣称完全不受慢章影响。
- 并发度 = `min(searchConcurrency, 可搜索章节数, MAX_SEARCH_CONCURRENCY=16).coerceAtLeast(1)`；`BookContentSearchWebSocket` 注入 `AppConfig.threadCount`。即便用户把 threadCount 调到 999，并行也限到 16，避免同时打开过多文件句柄/同步锁竞争导致 IO 抖动与内存峰值。
- 保留全部可观察契约：结果按章有序流式上报、每章一次 `onProgress`、任一章异常会通过结构化并发取消其余任务并由 WebSocket 报“搜索失败”。达到 `maxResults=500` 本身不再提前跳过后续章节；协调端继续按章确认，只有发现第 501 条或单章搜索明确还有更多结果时才置 `truncated=true` 并取消剩余任务，正好 500 条且后续无命中则扫描完整本书并返回 `false`。Web 前端契约不变，无需改 `modules/web/src` 或同步 assets。
- WebDAV 的 `origin` 只表示来源/同步关系，不能直接代表正文是远程文件：本地书上传后仍保留本地 `bookUrl`，搜索应扫描全部章节；只有在线书或 `bookUrl` 本身为 `webDav::` 的远程书才走缓存快照。WebDAV 关联本地书读取时使用禁用远程回源的副本，文件失效只能读取失败，不能因搜索触发下载。
- 提速主要落在在线书（每章独立缓存文件 `file.readText()`，并行无锁）收益最大；本地书因各格式 handler（`TextFile`/`EpubFile`/`PdfFile`/`UmdFile`/`MobiFile`/`CbzFile`）的 `getContent`/zip 读取多带 `@Synchronized`/`synchronized(pfd)`，IO 仍串行，仅 CPU（ContentProcessor 正则、匹配）并行 → 中等收益。
- 线程安全：`ContentProcessor.processors` 使用 `ConcurrentHashMap.compute` 原子复用或创建同一本书的处理器，避免并发“查询—创建—写入”产生多个实例；替换规则继续使用 `CopyOnWriteArrayList`，动态增删的重复标题缓存改为 `ConcurrentHashMap.newKeySet()`，搜索读取与阅读页切换设置可以并发执行。
- 模拟器合成大文本为 22 MB、433 个可搜索章节、无命中关键词，各配置跑 5 轮：单线程中位数 `1043.22 ms`，16 线程中位数 `639.11 ms`，约 `1.63x`、耗时下降 `38.7%`。该数字只代表本轮 Android 模拟器本地 TXT 路径，不外推为实体机、EPUB 或在线缓存书的固定收益。

**实现约束**:
- 构造默认 `searchConcurrency = DEFAULT_SEARCH_CONCURRENCY = 16`（纯 JVM 常量，不触碰 `AppConfig`），保证纯 JVM 单测不触发 Android 依赖加载；生产路径必须由 `BookContentSearchWebSocket` 显式注入 `AppConfig.threadCount`。
- `search()` 内不得直接调用 `ContentProcessor.get(...)`：会让纯 JVM 单测加载依赖 Android 的 `ContentProcessor` 触发 `ClassNotFoundException`；并发安全已由 `ConcurrentHashMap` 兜底。
- worker 只负责独立章节读取、净化和匹配，不读写全局命中状态；`matchCount`、`truncated`、批次结果和进度只允许协调协程修改，避免用原子变量近似计算每章剩余额度。
- 任务与结果 Channel、索引缓冲必须保持有界；当前窗口为 `min(可搜索章节数, concurrency * 2)`。结果上报必须以章节在排序后列表中的位置重排，不能依赖 worker 完成顺序。
- 单章搜索最多保留全局 `resultLimit` 条；协调端达到上限后仍须检查后续章节是否存在额外命中，不能仅因 `matchCount == resultLimit` 就把未搜索章节计入 `scannedChapters` 或返回 `truncated=false`。

**回归点**:
- `BookContentSearcherTest`：真实固定线程池下并发数不超过配置、慢章未完成时窗口继续补位、乱序完成仍按章有序、`searchConcurrency=1` 等价旧串行、上限内截断、正好达到上限后有/无额外命中的两条边界、取消后无 `onResults/onComplete`、普通本地书和 WebDAV 关联本地书全章读取、实际远程 WebDAV 书仅缓存读取。
- 模拟器：64 章合成书实际含 501 个 `needle`，整书搜索只返回前 500 条且在扫描到第 41 章确认额外命中后返回 `truncated=true`；单独搜索第 41 章仍可找到该结果。结果顺序、进度和无命中全书完成均通过真实 `/searchBookContent` WebSocket 检查。
- 待验证：实体浏览器确认 500 条提示和点击跳转；在线缓存长书及本地 EPUB 对比；实体机性能、取消和内存峰值。

> 本节记录 2026-08-04 的“总结果最多 500 条”历史契约；当前分批续搜契约以第 12 节为准。

## 12. Web 全文搜索每 500 条游标续搜

- 记录日期: 2026-08-07
- 验证日期: 2026-08-07（31 项定向搜索测试、156 项全量 JVM、Web 类型检查/生产构建/阅读时长 6 项测试、assets 字节同步、`scripts/check-debug.sh`、Debug APK、Release/R8 及 Android 17 模拟器通过）
- 适用环境: `BookContentSearch.vue` → `/searchBookContent` → `BookContentSearchService`

**设计结论**:
- 不直接把单次结果上限放大到 1000/5000。手机端继续把 `maxResults` 限制为 `1..500`，但其含义改为单批上限；确认存在首条未返回命中时，`complete` 返回 `hasMore=true` 与 `nextCursor`，下一请求从第 501、1001 条继续，直到整本书搜索完成。
- 内部游标记录可搜索章节位置、章节索引、归一化正文中的首条未返回命中和累计结果数；WebSocket 编码为带当前 `bookUrl + query` 校验摘要的不透明字符串，不把明文书籍地址或关键词放入游标。损坏游标、跨关键词复用和章节快照错位都必须失败，不能回退到从头搜索。
- 服务端不保留跨连接 session，完成消息后仍关闭当前 WebSocket；因此用户停留在结果页不会占用手机搜索 Job。续搜不会重扫此前章节，只有分页边界所在章节需要重新读取、净化并从章内位置继续。
- `truncated` 继续输出并与 `hasMore` 同义，保证旧客户端仍看到原 500 条提示；新 Web 页面只在拿到有效 `nextCursor` 后启用下一批。`resultStart/resultEnd` 明确当前范围。
- Web 当前只展示一批 500 条，虚拟列表仍只保留 16 个 DOM 节点；用 LRU 缓存最近 3 批，更早批次通过页首游标重取。新批首个结果到达前保留旧批次，避免换批时列表闪空。

**实现约束**:
- `BookContentSearcher` 必须额外探测首条未返回命中；正好达到 500 且无第 501 条时 `nextCursor=null`。章内游标使用图片归一化后的 UTF-16 坐标，Web 跳转结果仍使用原正文 `queryIndexInChapter/chapterPos`，两套坐标不得混用。
- 结果仍按章节、章内位置有序，每 20 条上报；固定 worker、两倍并发窗口、并发硬上限 16、停止/重搜/断连取消和本地/缓存正文来源边界全部保持不变。
- 页面缓存、页首游标和在途请求按书籍与已提交关键词隔离；输入框文字变化但未重新提交时，上一批/下一批仍属于原已提交关键词。
- Gson 请求与完成 DTO 新字段必须保留 `@Keep`；Web 运行时修改后同步 `modules/web/dist/index.html` 与 `favicon.ico` 到 APK assets，并做 Release/R8 验证。

**回归点**:
- 499/500 条：只有一批且 `hasMore=false`；501 条：第一批 500、第二批 1；1000 条：两批各 500 且第二批结束；1001/1501 条继续类推。
- 把全部批次拼接后，`chapterIndex + queryIndexInChapter` 无重复、无遗漏、严格有序；同一章跨批次、图片标签前后命中和 emoji 摘要不能错位。
- 损坏游标、把 A 关键词游标用于 B 关键词、缓存章节集合变化、停止、快速重搜、上一批缓存命中/淘汰重取分别验证。
- 模拟器真实 WebSocket 后还需用实体浏览器检查 PC/手机宽度、日间/夜间、滚动位置、结果点击预览和恢复/保留进度；在线缓存书与本地 EPUB 继续单列待验证，不能被合成 TXT 替代。

**2026-08-07 实测结果**:
- Android 17 模拟器导入 64 章合成 TXT，真实 WebSocket 实测 1501 条为 `500/500/500/1`、1001 条为 `500/500/1`、500 条为 `500`、1000 条为 `500/500`；批次范围、最后一批 `hasMore=false`、全量无重复且严格有序均通过。
- 损坏游标和跨关键词复用均返回“搜索位置已失效，请重新搜索”；结果流首批 20 条时断开连接后，紧接的完整搜索仍能正常返回 500 条和下一批位置。
- 桌面 Headless Chrome 通过模拟器 WebService 实测 `1–500 → 501–1000 → 1001–1500`，提示与下一批按钮范围正确，“上一批”命中缓存后立即恢复；无 WebService 或应用崩溃日志。

*Last updated: 2026-08-26*
