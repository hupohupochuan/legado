# AI 编辑必看架构图

> 最新修订: 2026-08-10 CST
> 最近三次修订:
> - 2026-08-10 CST: Web 阅读页左右方向键不再受上下翻屏锁误伤，书本翻页动画期间缓存一次最新左右键意图，避免章末切换需要重复按键。
> - 2026-08-07 CST: Web 全文搜索保留单批 500 条资源边界，通过不透明游标分批继续到整本书结束，不再把 500 当作总结果上限。
> - 2026-08-07 CST: Web 全文搜索按 `bookUrl` 的实际正文定位区分本地与远程；本地书上传 WebDAV 后仍搜索原文件，且搜索失败不得回源下载。

> 本文件是第一入口；同时按 `AGENTS.md` 要求读完其余三个必读文档，再按具体任务打开 `AI编辑必看/` 子文件。

---

## 先读规则

- 具体 Android/API/构建坑: 读 `适配踩坑记录.md`。该文件最多保留 15 条，满 15 条后整体改名归档到 `文档归档/` 并重新开新文件记录，不再来回挪动单条。
- 新功能设计、交互、性能、回归点: 先读 `新功能踩坑记录.md` 索引，再按主题读取同名前缀分卷。
- 项目结构、数据流、编码规则不要都塞回本文件；详细内容放 `AI编辑必看/` 子目录。
- 每次代码修改后检查文档是否需要同步；更新日志写 `app/src/main/assets/updateLog.md`，只直接展示最近 5 个日期，其余收入 `more`。每个日期最多 2 句话，只保留用户需要知道的变化，不当作完整改动清单。

---

## 项目概览

- 项目: Legado (阅读) Android 电子书阅读器
- 构建: Gradle 9.6.1, AGP 9.3.0, Kotlin 2.4.10, JDK 17
- SDK: `app` minSdk 26 / targetSdk 37；`modules/book` minSdk 21 / targetSdk 36；`modules/rhino` minSdk 26 / targetSdk 36；三个 Android 模块均 compileSdk 37
- 技术栈: Kotlin + 少量 Java, MVVM + Room, ViewBinding, Coroutines/Flow
- 模块: `settings.gradle` 只包含 `app`、`modules/book`、`modules/rhino`；`modules/web/src` 是含网页传书在内的 Web 服务浏览器端 Vue 源码，不是 Gradle 子模块
- 资源边界: `modules/web/src/` 是 Web 源码，`app/src/main/assets/web/` 是 APK 实际加载资源
- 外部接口边界: `ReaderProvider` 保持导出但受 `${applicationId}.permission.READ_WRITE` 签名级权限保护；外部调用方必须声明对应变体权限并使用同一签名证书
- Cronet 版本边界: 构建 API 与下载兜底由 `gradle/libs.versions.toml` 固定为 `143.7445.0`；运行时优先使用系统/GMS 外部 Provider，实际引擎版本可能随设备变化

---

## 按需读取

| 任务 | 读取 |
|------|------|
| 找目录、模块职责、常见修改入口 | `AI编辑必看/项目地图.md` |
| 改阅读页、本地书、WebDAV、Web 前端 | `AI编辑必看/核心数据流.md` |
| 查编码规范、构建命令、文档维护规则 | `AI编辑必看/开发规则.md` |
| Android API/兼容/构建坑 | `适配踩坑记录.md` |
| 新功能设计和回归点 | `新功能踩坑记录.md`，再按索引读取主题分卷 |

---

## 必守红线

- 不要用 `chapterList?.get(0)`、`list[0]` 访问可能为空的列表，用 `firstOrNull()` / `getOrNull()`。
- 不要把 WebDAV 账号、密码、Authorization、URL query/fragment 写入日志。
- 不要把本地 SAF 路径跨设备自动替换；`content://.../tree/...` 失效时必须提示用户重新授权。
- 本地书不得以书名、作者或文件名作为唯一身份；`bookUrl` 保留可读 URI/路径，`localFileKey` 使用 `SHA1(URI/路径 + 内容 SHA1)`。文件列表的“已在书架”和点击打开必须先按精确 URI 定位，不得回退到同文件名后改写另一本书的路径。
- Web 全文搜索判断正文能否从手机原文件读取时必须看 `bookUrl`，不能只看 `origin`：上传 WebDAV 会把本地书 `origin` 改成 `webDav::`，但不会移走本地 `bookUrl`。只有实际远程定位或在线书才限制为已有缓存；本地文件读取失败时仍不得借 `origin` 回源下载。
- Web 全文搜索单批最多 500 条，更多结果必须使用手机端返回的不透明游标精确续搜；不得直接放大单批上限、从第一章按 offset 重扫，或在浏览器循环 `/getBookContent`。旧 `truncated` 字段继续兼容，新增客户端以 `hasMore + nextCursor` 判断下一批。
- 不要只改 `modules/web/src` 而不同步 `app/src/main/assets/web`。
- 网页传书必须用浏览器原生 `FormData` 交给浏览器生成 multipart boundary，并检查 `ReturnData.isSuccess`；`addLocalBook` 的业务失败同样返回 HTTP 200。`app/src/main/assets/web/uploadBook/index.html` 只允许作为旧地址到 `/#/uploadBook` 的兼容跳转，不得恢复第二套上传实现。
- Web 阅读时长空闲超过 10 分钟后，本章作废状态必须保持到切章，恢复可见或再次交互不能清除；浏览器只上报 5 秒至 24 小时的章节经过时长，结束时间和自然日归属由手机端确定。
- Web 阅读页连续滚动的 `canJump` 只允许锁住上下翻屏，不能吞掉左右切章；书本翻页动画锁期间只缓存一次最新左右键意图，动画结束后执行，取消或跨章重挂时必须清空，不能累积成多次切章。
- 不要恢复 AGP 旧 `applicationVariants`；APK 输出命名走 `androidComponents`。
- Gson 反射 DTO 不能只依赖 `-keepattributes Signature`；R8 full mode 还要求显式 keep/稳定字段注解。系统 TTS 持久化配置统一走 `TtsEngineSelection` 的 JSON 树编解码，不要恢复 `fromJsonObject<SelectItem<String>>`；Debug 通过后仍须验证 Release/R8 成品。
- App 全局主题继承 `Theme.AppCompat`，布局不得直接使用会强制校验 `Theme.MaterialComponents` 的 `MaterialButton`；按钮优先使用 `AppCompatButton` 或项目现有控件。迁移主题前必须保留 `AppThemeLayoutCompatibilityTest` 构建门禁。
- `.github/workflows/test.yml` 的发布 job 必须保留最小的 `contents: write` 权限；APK 编译后的 beta Release 创建、更新和追加产物都依赖该权限。
- compileSdk 从 `libs.versions.compileSdk` 读取，不要恢复跨项目隐式查找根项目 `ext` 属性；Android 模块使用 AGP 内置 Kotlin，不要重新应用 `kotlin-android`。
- UI 协程必须优先传入 `lifecycleScope`/`viewModelScope`/现有组件 scope；`Coroutine.async` 默认 scope 只用于明确需要存活到进程结束的任务。取消异常不得进入普通错误回调。
- 在线书导出只允许读取已经落盘的章节缓存；导出前必须明确选择“要求完整”或“仅已有缓存”，缓存缺失或导出中途丢失时不得写入 `null` 等占位正文。
- 缓存完成通知只允许自然收尾路径发布；收到用户停止/移除请求或服务开始销毁时，必须先关闭完成通知门闩并取消下载协程。详见 [`新功能踩坑记录-基础设施.md`](新功能踩坑记录-基础设施.md)。
- 前台服务（WebService 等）销毁时必须显式 `ServiceCompat.stopForeground(...STOP_FOREGROUND_REMOVE)` 并 `notificationManager.cancel(id)` 移除通知，同时设置 `isStopping` 门闩短路所有网络回调、后台重启检查和通知刷新；不得依赖系统自动清理前台通知，也不得在停机后再 `notify()`。详见 [`适配踩坑记录.md` 0.5](适配踩坑记录.md#05-webservice-关闭后前台通知残留)。
- `values/strings.xml` 新增可翻译界面字符串时，必须同步越南语、巴西葡萄牙语、日语、西班牙语和三个中文资源目录，并保持格式占位符一致。详见 [`适配踩坑记录.md` 0.3](适配踩坑记录.md#03-新增缓存导出字符串未同步全部语言导致-lint-错误增加)。
- API 30+ 系统栏显隐和图标明暗统一走 `WindowInsetsControllerCompat`；API 26-29 才保留旧 `systemUiVisibility` 分支。Android 16+、宽度不小于 600dp 的大屏不再强制阅读页方向。历史细节见 [`适配踩坑记录-2026-07-15.md` 0.3](文档归档/适配踩坑记录-2026-07-15.md#03-链式协程回调竞态与系统栏兼容-api-迁移)。
- 原生文本阅读页用普通 `scaledTouchSlop` 取消长按和启动文本扩选，用 `scaledPagingTouchSlop`（或用户显式配置）判定整页拖动；不要再用普通阈值直接否决抬手单击。动画中止可以消费翻页等非菜单动作，但不得吞掉已配置的菜单动作。详见 [`适配踩坑记录.md` 0.2](适配踩坑记录.md#02-阅读页点击阈值死区在-android-81-老平板表现为点击失效)。
- 本地 SDK、IDE、签名和产物文件不得提交；完整拦截清单以 `scripts/check-staged-local-files.sh` 为准，尤其不要提交 `.sdk/`、`local.properties`、`app/gradle.properties`、`app/signing/`、`app/app/`、`release签名/`、证书/keystore、`AGENTS.md` 和 `opencode.json`。

---

## 最小验证

```bash
scripts/check-debug.sh
```

修改 `modules/web` 的运行时源码、依赖或构建配置时，还必须执行 Web 验证并同步 APK 产物：

```bash
cd modules/web
pnpm run type-check
pnpm run build-only
cd ../..
cp modules/web/dist/index.html app/src/main/assets/web/index.html
cp modules/web/dist/favicon.ico app/src/main/assets/web/favicon.ico
cmp -s modules/web/dist/index.html app/src/main/assets/web/index.html
cmp -s modules/web/dist/favicon.ico app/src/main/assets/web/favicon.ico
scripts/check-web-assets-sync.sh
```

生成 APK 时再跑:

```bash
./gradlew :app:assembleDebug --no-daemon
```

最终交付可直接安装的个人签名 Release APK 时，必须先提交本轮修改并保持工作树干净，再运行:

```bash
scripts/build-signed-release.sh
```

脚本会强制使用 `app/gradle.properties` 中的个人签名配置和 `RELEASE_CERT_SHA256`，完成 Release/R8、五个 ABI APK、证书/包名/版本校验，再把同一批 APK、`.idsig`、metadata 原子同步到 `app/app/release/`。`app/gradle.properties` 与 keystore 必须仅所有者可读写（脚本接受 `400` 或 `600`，通常使用 `600`）。Android 64 位真机优先安装 `app/app/release/legado_arm64.apk`；不得直接复制单个 APK，也不得在签名配置缺失时接受 Gradle 的 Debug 签名回退。

*文档版本: 2026-08-10*
