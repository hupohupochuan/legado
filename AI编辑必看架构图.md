# AI 编辑必看架构图

> 最新修订: 2026-07-29 CST
> 最近三次修订:
> - 2026-07-29 CST: 缓存服务主动取消时禁止发布完成通知；新增界面字符串必须同步全部受支持语言。
> - 2026-07-28 CST: 离线缓存增加任务进度；在线书导出必须先校验缓存边界，缺章不得写入占位正文。
> - 2026-07-16 CST: 原生文本阅读页分离普通触摸与整页滑动阈值；动画中止后仍允许已配置的菜单动作响应。

> 本文件是第一入口；同时按 `AGENTS.md` 要求读完其余三个必读文档，再按具体任务打开 `AI编辑必看/` 子文件。

---

## 先读规则

- 具体 Android/API/构建坑: 读 `适配踩坑记录.md`。该文件最多保留 15 条，满 15 条后整体改名归档到 `文档归档/` 并重新开新文件记录，不再来回挪动单条。
- 新功能设计、交互、性能、回归点: 读 `新功能踩坑记录.md`。
- 项目结构、数据流、编码规则不要都塞回本文件；详细内容放 `AI编辑必看/` 子目录。
- 每次代码修改后检查文档是否需要同步；更新日志写 `app/src/main/assets/updateLog.md`，只直接展示最近 5 个日期，其余收入 `more`。每个日期最多 2 句话，只保留用户需要知道的变化，不当作完整改动清单。

---

## 项目概览

- 项目: Legado (阅读) Android 电子书阅读器
- 构建: Gradle 9.6.1, AGP 9.3.0, Kotlin 2.4.10, JDK 17
- SDK: `app` minSdk 26 / targetSdk 37；`modules/book` minSdk 21 / targetSdk 36；`modules/rhino` minSdk 26 / targetSdk 36；三个 Android 模块均 compileSdk 37
- 技术栈: Kotlin + 少量 Java, MVVM + Room, ViewBinding, Coroutines/Flow
- 模块: `settings.gradle` 只包含 `app`、`modules/book`、`modules/rhino`；`modules/web/src` 是 Web 服务浏览器端 Vue 源码，不是 Gradle 子模块
- 资源边界: `modules/web/src/` 是 Web 源码，`app/src/main/assets/web/` 是 APK 实际加载资源
- 外部接口边界: `ReaderProvider` 保持导出但受 `${applicationId}.permission.READ_WRITE` 签名级权限保护；外部调用方必须声明对应变体权限并使用同一签名证书

---

## 按需读取

| 任务 | 读取 |
|------|------|
| 找目录、模块职责、常见修改入口 | `AI编辑必看/项目地图.md` |
| 改阅读页、本地书、WebDAV、Web 前端 | `AI编辑必看/核心数据流.md` |
| 查编码规范、构建命令、文档维护规则 | `AI编辑必看/开发规则.md` |
| Android API/兼容/构建坑 | `适配踩坑记录.md` |
| 新功能设计和回归点 | `新功能踩坑记录.md` |

---

## 必守红线

- 不要用 `chapterList?.get(0)`、`list[0]` 访问可能为空的列表，用 `firstOrNull()` / `getOrNull()`。
- 不要把 WebDAV 账号、密码、Authorization、URL query/fragment 写入日志。
- 不要把本地 SAF 路径跨设备自动替换；`content://.../tree/...` 失效时必须提示用户重新授权。
- 不要只改 `modules/web/src` 而不同步 `app/src/main/assets/web`。
- 不要恢复 AGP 旧 `applicationVariants`；APK 输出命名走 `androidComponents`。
- compileSdk 从 `libs.versions.compileSdk` 读取，不要恢复跨项目隐式查找根项目 `ext` 属性；Android 模块使用 AGP 内置 Kotlin，不要重新应用 `kotlin-android`。
- UI 协程必须优先传入 `lifecycleScope`/`viewModelScope`/现有组件 scope；`Coroutine.async` 默认 scope 只用于明确需要存活到进程结束的任务。取消异常不得进入普通错误回调。
- 在线书导出只允许读取已经落盘的章节缓存；导出前必须明确选择“要求完整”或“仅已有缓存”，缓存缺失或导出中途丢失时不得写入 `null` 等占位正文。
- 缓存完成通知只允许自然收尾路径发布；收到用户停止/移除请求或服务开始销毁时，必须先关闭完成通知门闩并取消下载协程。
- `values/strings.xml` 新增可翻译界面字符串时，必须同步越南语、巴西葡萄牙语、日语、西班牙语和三个中文资源目录，并保持格式占位符一致。
- API 30+ 系统栏显隐和图标明暗统一走 `WindowInsetsControllerCompat`；API 26-29 才保留旧 `systemUiVisibility` 分支。Android 16+、宽度不小于 600dp 的大屏不再强制阅读页方向。
- 原生文本阅读页用普通 `scaledTouchSlop` 取消长按和启动文本扩选，用 `scaledPagingTouchSlop`（或用户显式配置）判定整页拖动；不要再用普通阈值直接否决抬手单击。动画中止可以消费翻页等非菜单动作，但不得吞掉已配置的菜单动作。
- 本地 SDK、IDE、签名和产物文件不得提交；完整拦截清单以 `scripts/check-staged-local-files.sh` 为准，尤其不要提交 `.sdk/`、`local.properties`、`app/gradle.properties`、`app/signing/`、`app/app/`、`release签名/`、证书/keystore、`AGENTS.md` 和 `opencode.json`。

---

## 最小验证

```bash
scripts/check-debug.sh
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

*文档版本: 2026-07-29*
