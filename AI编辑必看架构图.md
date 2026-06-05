# AI 编辑必看架构图

> **必读文件**：本文件 + `适配踩坑记录.md` + `新功能踩坑记录.md`
> 
> 修改代码前，先通读本文了解项目架构；遇到编译/运行问题，查 `适配踩坑记录.md`；遇到新功能设计、交互、性能或回归问题，查并维护 `新功能踩坑记录.md`。
>
> **维护要求**：每次代码更改后，必须同步检查本文是否需要更新；兼容性/API/构建坑写入 `适配踩坑记录.md`，新功能相关坑写入 `新功能踩坑记录.md`。
>
> **文档拆分**：如果 `.md` 文件过大，按主题拆分为 `xxx-1.md`、`xxx-2.md`、`xxx-3.md`、`xxx-4.md`，原文件保留索引和最新有效结论。
>
> **时间有效性**：时间敏感结论必须写明记录日期、验证日期、适用 SDK/依赖/系统版本；超过 30 天或环境变化后，先复核再引用。

---

## 一、项目概览

```
Legado (阅读) — Android 电子书阅读器
├── targetSdk=36, compileSdk=36, minSdk=26
├── Kotlin (~790 文件) + Java (10 文件)
├── Gradle Version Catalog (libs.versions.toml)
└── 模块结构：app + modules/book + modules/rhino + modules/web
```

### 模块依赖图

```
┌─────────────┐
│   modules   │
│   /rhino    │ ──→ JavaScript 引擎 (Rhino)
└──────┬──────┘
       │
┌──────┴──────┐        ┌──────────────┐
│   modules   │◄───────│     app      │
│   /book     │───────►│  (主模块)    │
│ (书籍解析)   │        │              │
└─────────────┘        └──────────────┘
                              │
                    ┌─────────┼─────────┬──────────────┐
                    ▼         ▼         ▼              ▼
               ┌────────┐ ┌──────┐ ┌──────┐     ┌────────────┐
               │  data  │ │  help│ │  lib  │     │ assets/web │
               │ (数据库)│ │(配置)│ │(第三方)│     │ Web UI产物 │
               └────────┘ └──────┘ └──────┘     └────────────┘
                                                        ▲
                                                        │
                                               ┌────────────────┐
                                               │ modules/web/src│
                                               │ Vue Web UI源码 │
                                               └────────────────┘
```

---

## 二、app 模块目录结构

```
app/src/main/java/io/legado/app/
│
├── base/                    ← 【基础类】所有 Activity/Fragment/Service 的基类
│   ├── BaseActivity.kt      ← VMBaseActivity 继承它，处理主题、权限、返回键
│   ├── BaseFragment.kt      
│   ├── BaseDialogFragment.kt ← 已修复: 使用 commitAllowingStateLoss()
│   ├── BaseService.kt
│   └── VMBaseActivity.kt    ← 带 ViewModel 的基类，绝大多数 Activity 继承它
│
├── ui/                      ← 【界面层】按功能分目录
│   ├── main/                ← 主界面 (书架、发现、订阅、我的)
│   ├── book/                ← 书籍相关
│   │   ├── read/            ← 阅读器核心 (ReadBookActivity, ReadView, 翻页动画)
│   │   ├── toc/             ← 目录 (TocActivity, ViewPager2 + FragmentStateAdapter)
│   │   ├── audio/           ← 音频播放
│   │   ├── manga/           ← 漫画阅读
│   │   └── ...
│   ├── config/              ← 设置界面 (Theme, ReadStyle, Backup等)
│   └── about/               ← 关于、日志、记录
│
├── service/                 ← 【后台服务】
│   ├── AudioPlayService.kt  ← 音频播放前台服务 (FOREGROUND_SERVICE_MEDIA_PLAYBACK)
│   ├── BaseReadAloudService.kt ← 朗读服务基类
│   ├── CacheBookService.kt  ← 缓存书籍 (FOREGROUND_SERVICE_DATA_SYNC)
│   └── WebService.kt        ← Web 服务 (WiFi传书)
│   ⚠️ 所有前台服务已改用 ServiceCompat.startForeground(this@Service, ...)
│
├── receiver/                ← 【广播接收器】
│   └── MediaButtonReceiver.kt ← 耳机键监听 (已改用 IntentCompat.getParcelableExtra)
│
├── model/                   ← 【业务逻辑/全局状态】
│   ├── ReadBook.kt          ← 阅读器全局状态 (当前书、章节、页码)
│   ├── AudioPlay.kt         ← 音频播放全局状态
│   └── ReadAloud.kt         ← 朗读全局控制
│   ⚠️ 修改前检查空集合: 用 firstOrNull() 替代 get(0)
│
├── data/                    ← 【数据层】
│   ├── appDb/               ← Room 数据库访问入口
│   ├── entities/            ← 数据实体 (Book, BookChapter, BookSource等)
│   └── dao/                 ← DAO 接口
│
├── help/                    ← 【配置/工具类】
│   ├── config/              ← AppConfig, ReadBookConfig (SharedPreferences封装)
│   ├── book/                ← 书籍相关工具 (ContentProcessor, BookHelp)
│   └── theme/               ← 主题颜色/夜间模式
│
├── utils/                   ← 【通用工具】
│   ├── ActivityExtensions.kt  ← Activity 扩展 (沉浸模式、Dialog显示)
│   ├── ContextExtensions.kt  ← Context 扩展 (PendingIntent已改FLAG_IMMUTABLE)
│   ├── NetworkUtils.kt        ← 网络状态 (已改用 NetworkCapabilities)
│   ├── ViewExtensions.kt      ← View 扩展 (SHOW_FORCED 已修复)
│   └── WebSettingsExtensions.kt ← WebView 设置 (暗黑模式已清理)
│   ⚠️ 扩展函数优先放这里，不要在业务类里重复造轮子
│
├── lib/                     ← 【第三方/自定义库封装】
│   ├── dialogs/             ← 对话框封装 (AndroidDialogs.kt — ProgressDialog已替换)
│   ├── theme/               ← 主题 View (ThemeTextView, ThemeEditText等)
│   ├── cronet/              ← Cronet 网络库封装
│   └── permission/          ← 权限请求封装 (PermissionActivity.kt)
│
└── constant/                ← 【常量】
    ├── AppConst.kt          ← App 常量
    ├── PreferKey.kt         ← SharedPreferences Key
    └── EventBus.kt          ← EventBus 事件定义
```

### Web 前端资源边界

- `modules/web/src/` 是 Web 服务浏览器端 UI 源码。
- `app/src/main/assets/web/` 是 APK 内实际加载资源。
- 如果没有执行 `modules/web` 构建同步，修改 Web 阅读页时必须同步维护 `app/src/main/assets/web/index.html`，否则 APK 里不会包含修复。
- Web 阅读页核心文件: `modules/web/src/views/BookChapter.vue`, `modules/web/src/store/bookStore.ts`。

---

## 三、核心数据流

### 阅读器打开一本书的流程

```
ReadBookActivity
    │
    ├─→ ReadBookViewModel.initData(intent)
    │       ├─→ 从 Intent/数据库获取 Book
    │       ├─→ ReadBook.initData(book)           ← 初始化全局状态
    │       │       ├─→ 检查 chapterList 是否匹配 (⚠️ firstOrNull()!)
    │       │       └─→ 加载章节列表
    │       └─→ 触发 loadContent() / loadOrUpContent()
    │
    ├─→ ReadBook.loadContent()                    ← 加载当前章节文本
    │       ├─→ ChapterProvider.getTextChapter()   ← 解析 HTML → 分页
    │       │       ├─→ ContentProcessor.get()     ← 内容处理(替换规则等)
    │       │       └─→ TextChapterLayout.kt        ← 排版引擎 (StaticLayout.Builder)
    │       └─→ 生成 TextPage 列表
    │
    └─→ ReadView.drawPage()                         ← Canvas 绘制页面
            └─→ ContentTextView.onDraw()            ← 绘制文字/图片
```

### WebDAV 同步流程

```
用户点击"WebDAV同步"
    │
    ├─→ WebDavViewModel.sync()                    ← 上传/下载进度
    │       ├─→ 上传: bookProgress.json, 阅读记录
    │       └─→ 下载: 合并远程数据到本地
    │
    └─→ 同步完成后重新打开书籍
            └─→ ReadBook.initData(book)
                    ⚠️ 此时 chapterList 可能为空 (WebDAV 同步 bug 已修复)
                    └─→ 用 firstOrNull() 安全访问章节列表
```

---

## 四、编码规范

### 1. Kotlin 风格

- **命名**: 驼峰命名，`ViewModel` 后缀，Activity 和 Fragment 不用后缀
  ```kotlin
  class ReadBookActivity    ✓
  class ReadBookViewModel   ✓
  class ReadBookFragment    ✓ (但 Fragment 名通常直接用功能名)
  ```

- **可空性**: 优先用非空类型；nullable 必须处理
  ```kotlin
  val chapterList: List<BookChapter>? = null   ✓ (可为空)
  chapterList?.firstOrNull()?.title            ✓ (安全访问)
  chapterList!![0]                            ✗ (绝不强转)
  chapterList?.get(0)?.title                 ✗ (已废弃，get(0) 不安全)
  ```

- **协程**: 使用 `lifecycleScope` / `viewModelScope`，不要用 GlobalScope
  ```kotlin
  lifecycleScope.launch { ... }           ✓
  lifecycleScope.launch(Dispatchers.IO) { ... }  ✓ (IO操作)
  viewModelScope.launch { ... }          ✓ (ViewModel中)
  ```

- **扩展函数**: 通用逻辑放 `utils/`，业务相关放对应模块

### 2. UI 层规范

- **ViewBinding**: 使用 `viewBinding` 委托，不用 findViewById
  ```kotlin
  override val binding by viewBinding(ActivityReadBookBinding::inflate)
  ```

- **ViewModel**: 业务数据用 `LiveData` / `Flow`，不在 Activity 中直接操作数据库

- **主题**: 颜色通过 `AppConfig.isNightTheme` + `accentColor` / `primaryTextColor` 获取

### 3. 服务层规范

- **前台服务**: 必须使用 `ServiceCompat.startForeground(this@Service, id, notification, type)`
- **通知**: 构造后用 `ServiceCompat.startForeground()`，不要直接调用 `startForeground()`
- **生命周期**: Service `onCreate()` 中初始化，`onDestroy()` 中清理

### 4. 数据库操作

- **Room**: 使用 `appDb.xxxDao()` 访问，所有查询在协程中执行
- **Flow**: 查询返回 `Flow` 时，collect 在 `lifecycleScope` 中

---

## 五、常见陷阱 / 绝对不要做的事

### ❌ 危险操作

| 陷阱 | 后果 | 正确做法 |
|------|------|----------|
| `chapterList?.get(0)` | 空列表崩溃: `Index 0 out of bounds` | `chapterList?.firstOrNull()` |
| `bundle.getParcelable("key")` | API 33+ 废弃，类型不安全 | `BundleCompat.getParcelable(bundle, "key", Class::class.java)` |
| `intent.getParcelableExtra<KeyEvent>(...)` | API 33+ 废弃 | `IntentCompat.getParcelableExtra(...)` |
| `T::class.java.newInstance()` | 反射 API 废弃 | `T::class.java.getDeclaredConstructor().newInstance()` |
| `overridePendingTransition()` | API 34+ 废弃 | `overrideActivityTransition()` (API 34+) + fallback |
| `ProgressDialog(this)` | API 26+ 废弃 | `AlertDialog` + `ProgressBar` |
| `FLAG_MUTABLE` in PendingIntent | Android 12+ 安全问题 | `FLAG_IMMUTABLE` |
| `startForeground(id, notification)` | Android 14+ 需传 foregroundServiceType | `ServiceCompat.startForeground(this@Service, id, notification, type)` |
| `StaticLayout(text, paint, width, ...)` | API 28+ 废弃 | `StaticLayout.Builder.obtain(...)` |
| `windowManager.defaultDisplay.getRealMetrics()` | API 30+ 废弃 | `windowManager.currentWindowMetrics.bounds` (API 30+) |
| `InputMethodManager.SHOW_FORCED` | API 33+ 废弃 | `WindowInsetsController.show(Type.ime())` (API 30+) |
| `FragmentPagerAdapter` | 内存泄漏风险 | `FragmentStateAdapter` + `ViewPager2` |
| `screenOrientation="behind"` | API 33+ 废弃 | `screenOrientation="unspecified"` |
| `requestLegacyExternalStorage="true"` | API 30+ 无效 | 删除该属性，使用 Scoped Storage 或 MediaStore |

### ⚠️ 容易遗漏的检查清单

- [ ] 修改 Kotlin 后是否编译通过 (`compileAppDebugKotlin`)
- [ ] 修改 Manifest 后是否声明了对应权限/服务类型
- [ ] 新 API (API 30+) 是否有 `Build.VERSION.SDK_INT >=` 分支保护旧设备
- [ ] Lambda 中的 `this` 是否指向正确对象（尤其是 Service/Activity）
- [ ] 新 import 是否已添加
- [ ] `init` lambda 的 receiver 类型是否与实现一致（如 AlertDialog.Builder vs AlertDialog）

---

## 六、重要文件速查表

| 功能 | 文件 | 备注 |
|------|------|------|
| **Android 清单** | `app/src/main/AndroidManifest.xml` | 权限、Activity、Service声明 |
| **构建配置** | `app/build.gradle` | signingConfigs, dependencies |
| **版本目录** | `gradle/libs.versions.toml` | 依赖版本集中管理 |
| **主题/样式** | `app/src/main/res/values/styles.xml` | Base.AppTheme, enableOnBackInvokedCallback |
| **阅读器核心** | `ReadBookActivity.kt` + `ReadView.kt` | 阅读界面 + 页面绘制 |
| **翻页动画** | `delegate/*PageDelegate.kt` | `HorizontalPageDelegate` 已改用 `postInvalidateOnAnimation()` 对齐 vsync |
| **排版引擎** | `TextChapterLayout.kt` | StaticLayout.Builder 分页 |
| **全局阅读状态** | `model/ReadBook.kt` | 当前书、章节、位置 |
| **数据库** | `data/AppDatabase.kt` | Room 数据库定义 |
| **内容处理** | `help/book/ContentProcessor.kt` | 替换规则、净化 |
| **网络请求** | `lib/cronet/` + `utils/NetworkUtils.kt` | 网络状态 + 下载 |
| **权限请求** | `lib/permission/PermissionActivity.kt` | 权限对话框逻辑 |
| **通知构造** | `help/NotificationManager.kt` | 前台服务通知 |
| **Js 引擎** | `modules/rhino/` | 书源解析用 JavaScript |

---

## 七、编译/构建流程

### Debug 编译（验证代码）
```bash
# 1. 临时改 Gradle Wrapper 为本地路径
distributionUrl=file:///home/liu/Documents/legado-master/.gradle/gradle-8.13-bin.zip

# 2. 编译
ANDROID_HOME=/home/liu/Documents/legado-master/.sdk \
./gradlew :app:compileAppDebugKotlin --no-daemon

# 3. 恢复 Gradle Wrapper 路径
distributionUrl=https\://services.gradle.org/distributions/gradle-8.13-bin.zip
```

### Release APK
```bash
# 同上，但命令换为:
ANDROID_HOME=/home/liu/Documents/legado-master/.sdk \
./gradlew :app:assembleAppRelease --no-daemon

# 输出: app/build/outputs/apk/app/release/legado_arm64.apk (推荐)
```

---

## 八、适配踩坑记录关联

本文档是**"地图"**，告诉你项目结构、代码规范和哪里能改/不能改。

`适配踩坑记录.md` 是**"攻略"**，告诉你 Android API 迁移时的具体坑和解决方案。

**修改代码时，两个文件都要看。**

| 问题类型 | 查本文档 | 查适配踩坑记录 |
|----------|----------|----------------|
| 这个类是干嘛的？ | ✓ 目录结构/核心数据流 | |
| 该把代码写在哪里？ | ✓ 编码规范 | |
| API 被废弃了怎么替换？ | ✓ 常见陷阱表 | ✓ API 适配要点 |
| 编译报错怎么解决？ | ✓ 常见陷阱表 | ✓ Kotlin 编译错误 |
| 构建 APK 有什么要注意？ | ✓ 构建流程 | ✓ 构建 Release APK 注意事项 |
| WebDAV 同步 Bug 怎么修？ | | ✓ 运行时 Bug |

---

## 九、快速上手：修改一个功能的正确姿势

```
1. 读本文档 → 定位功能所在目录
2. 读源码 → 理解现有逻辑
3. 检查是否需要 API 迁移 → 查适配踩坑记录
4. 写代码 → 遵循编码规范
5. 编译验证 → compileAppDebugKotlin
6. 测试 → 真机/模拟器运行
7. 提交 → git commit
```

---

*文档版本: 2026-05-31 (翻页动画 vsync 优化已补充)*  
*关联文件: `适配踩坑记录.md`, `README.md`, `AGENTS.md`*
