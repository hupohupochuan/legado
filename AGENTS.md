# Agents Instructions

> 本项目为 **Legado (阅读)** Android 电子书阅读器
>
> **必读文件**（按优先级排序）：
> 1. `AI编辑必看架构图.md` — 项目架构、目录结构、编码规范、常见陷阱
> 2. `适配踩坑记录.md` — Android API 迁移踩坑记录、编译问题、运行时 Bug
> 3. `新功能踩坑记录.md` — 新功能开发踩坑记录、设计取舍、回归测试点
> 4. `README.md` — 项目简介（人类可读）

---

## 项目信息

- **Language**: Kotlin (primary), Java (legacy)
- **Build System**: Gradle 9.4.1 + AGP 9.2.1 + Version Catalog (`gradle/libs.versions.toml`)
- **targetSdk**: 36 | **compileSdk**: 36 | **minSdk**: 26
- **Architecture**: MVVM + Repository Pattern + Room Database
- **UI**: ViewBinding + ViewPager2 + Material Design Components
- **DI**: None (manual dependency injection via `appDb`, `AppConfig` singletons)
- **Async**: Kotlin Coroutines + Flow
- **Modules**: `app` (main), `modules/book`, `modules/rhino`, `modules/web` (Vue web UI source)

---

## 构建

```bash
# 编译验证（Debug Kotlin）
./gradlew :app:compileAppDebugKotlin --no-daemon

# Debug APK
./gradlew :app:assembleDebug --no-daemon

# Release APK (需要签名配置)
./gradlew :app:assembleRelease --no-daemon
```

**重要**:
- `gradle-wrapper.properties` 当前使用 `https://services.gradle.org/distributions/gradle-9.4.1-bin.zip`。
- AGP 9 已移除 `applicationVariants`；APK 输出命名必须走 `androidComponents`。
- ABI split 输出里 `filterType` 是 enum，比较时用 `it.filterType.name() == "ABI"`，否则所有 split 都会回退成 `legado_all.apk` 并在打包阶段冲突。
- `modules/web/src` 是 Vue 源码；App 实际加载的是 `app/src/main/assets/web/index.html`。没有运行 web 构建同步时，Web UI 逻辑修复必须同步到 assets，否则 APK 不生效。

---

## 代码风格

- **Indent**: 4 spaces
- **Max Line Length**: 120 (soft limit)
- **Naming**: CamelCase for classes, camelCase for functions/variables
- **Base Classes**:
  - Activity → `VMBaseActivity<Binding, ViewModel>`
  - Fragment → `BaseFragment()` or `BaseDialogFragment()`
  - Service → `BaseService`
- **Extensions**: 通用工具放 `utils/` 目录，用 Kotlin 扩展函数
- **Database**: Room, 访问入口 `appDb.xxxDao()`, 所有 DB 操作在协程中
- **Deprecated APIs**: 必须按 API level 分支处理，旧 API 加 `@Suppress("DEPRECATION")`

---

## 目录约定

| 目录 | 用途 |
|------|------|
| `app/src/main/java/io/legado/app/base/` | 基类 (Activity/Fragment/Service/Dialog) |
| `app/src/main/java/io/legado/app/ui/` | 所有界面，按功能分子目录 |
| `app/src/main/java/io/legado/app/service/` | 前台服务 (朗读、缓存、Web服务) |
| `app/src/main/java/io/legado/app/model/` | 全局业务状态 (ReadBook, AudioPlay) |
| `app/src/main/java/io/legado/app/data/` | Room 数据库 (entities, dao, AppDatabase) |
| `app/src/main/java/io/legado/app/help/` | 配置/辅助类 (主题、书籍处理、ContentProcessor) |
| `app/src/main/java/io/legado/app/utils/` | 通用 Kotlin 扩展函数 |
| `app/src/main/java/io/legado/app/lib/` | 第三方库封装和自定义 View |
| `app/src/main/java/io/legado/app/receiver/` | BroadcastReceiver |
| `app/src/main/java/io/legado/app/constant/` | 常量 (EventBus, PreferKey, AppConst) |
| `modules/web/src/` | Web 服务浏览器端 UI 源码 |
| `app/src/main/assets/web/` | APK 内实际 Web UI 资源 |

---

## 文档维护规则

- 每次完成代码修改后，必须同步检查并维护 `AI编辑必看架构图.md`、`适配踩坑记录.md`、`新功能踩坑记录.md`。
- 兼容性/API/构建/运行时适配问题写入 `适配踩坑记录.md`；新功能设计、交互、性能、回归测试问题写入 `新功能踩坑记录.md`。
- 如果某个 `.md` 文件过大，按主题拆分为 `xxx-1.md`、`xxx-2.md`、`xxx-3.md`、`xxx-4.md`，并在原文件顶部保留索引和最新有效结论。
- 文档中的时间敏感结论必须带上记录日期、验证日期、适用版本或环境；超过 30 天、target/compile SDK 改变、核心依赖升级、系统规则变化时，必须重新验证后再引用。

---

## 常见修改场景

### 1. 添加/修改阅读器功能
- 阅读界面: `ui/book/read/` → `ReadBookActivity.kt`, `ReadView.kt`
- 排版分页: `ui/book/read/page/provider/TextChapterLayout.kt`
- 全局状态: `model/ReadBook.kt`
- 配置项: `help/config/ReadBookConfig.kt`
- 翻页动画: `ui/book/read/page/delegate/`, `constant/PageAnim.kt`, `ui/book/read/config/ReadStyleDialog.kt`
- 翻页动画第一版范围: 只覆盖文本阅读页，不碰漫画翻页和滚动翻页

### 2. 修改书源解析
- JS 引擎: `modules/rhino/`
- 内容处理: `help/book/ContentProcessor.kt`
- 书源编辑: `ui/book/source/edit/`

### 3. 修改主题/外观
- 主题配置: `ui/config/ThemeConfigFragment.kt`
- 颜色定义: `help/config/ThemeConfig.kt`
- 样式: `res/values/styles.xml`, `res/values-night/`

### 4. 添加后台服务
- 继承 `BaseService`
- 在 `AndroidManifest.xml` 注册并声明 `android:foregroundServiceType`
- 使用 `ServiceCompat.startForeground(this@Service, id, notification, type)`

### 5. 修改数据库
- Entity: `data/entities/`
- DAO: `data/dao/`
- Migration: `data/AppDatabase.kt`
- 需要 KSP 重新生成: `./gradlew kspAppDebugKotlin`

### 6. 修改 Web 服务浏览器端阅读页
- 源码: `modules/web/src/views/BookChapter.vue`, `modules/web/src/store/bookStore.ts`
- APK 生效文件: `app/src/main/assets/web/index.html`
- 无限滚动追加章节不能走全局 loading mask，否则章节末尾会出现白色遮罩。
- 正常阅读进度保存走普通 POST；页面隐藏/离开时才用 `sendBeacon`。
- WebService 锁屏保活已有 `PreferKey.webServiceWakeLock` 配置，默认强开有耗电和部分 ROM 杀后台风险，不要无评估修改默认值。

---

## 测试

- **Unit Tests**: `src/test/java/` (JUnit 4)
- **Android Tests**: `src/androidTest/java/` (Espresso)
- **手动测试重点**: 阅读器翻页、WebDAV 同步、书源导入、朗读功能、主题切换

---

## 提交规范

- 每完成一轮修改就 `git commit` 一次
- Commit message 格式: `<type>: <subject>`
  - `refactor:` — 重构/适配
  - `fix:` — Bug 修复
  - `feat:` — 新功能
  - `build:` — 构建配置
- 不要提交: `.sdk/`, `app/signing/`, `app/gradle.properties`, `local.properties`

---

## 注意事项

1. **minSdk=26** — 永远不要引入 API 26 以下的 API，旧设备会崩溃
2. **targetSdk=36** — Android 14+ 行为变更已适配，但 Edge-to-Edge 尚未全局启用
3. **前台服务** — Android 14+ 严格限制，必须传 `foregroundServiceType`
4. **存储权限** — 已使用 `MANAGE_EXTERNAL_STORAGE`，未迁移到 Scoped Storage（风险过高）
5. **WebView** — 暗黑模式已清理废弃 API，使用 `setAlgorithmicDarkeningAllowed()`
6. **兼容性代码** — API 26-29 分支保留旧 API，加 `@Suppress("DEPRECATION")`，不要删除

---

## 外部依赖

- **Gradle**: wrapper `gradle-9.4.1-bin.zip`
- **Android SDK**: 本地 `.sdk/` (platform-36, build-tools 35.0.0 & 37.0.0)
- **代理**: `127.0.0.1:7897` (http/socks5)，用于 Gradle/Google 下载

---

## 相关文档

- `AI编辑必看架构图.md` — 架构图、目录说明、编码规范、数据流
- `适配踩坑记录.md` — API 迁移记录、编译错误、运行时 Bug、构建注意事项
- `新功能踩坑记录.md` — 新功能开发记录、踩坑、回归验证清单
- `release签名` — Release 构建说明（签名位置、产物位置）

---

*Last updated: 2026-06-05*
