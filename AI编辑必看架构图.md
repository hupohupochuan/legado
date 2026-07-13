# AI 编辑必看架构图

> 最新修订: 2026-07-14 03:20 CST
> 最近三次修订:
> - 2026-07-14 03:20 CST: Kotlin/Gradle 迁移后 compileSdk 改由 Version Catalog 提供，移除未使用的 kotlin-android 插件声明。
> - 2026-07-14 02:30 CST: 补充链式协程回调/取消语义、系统栏 API 分支及 Android 16+ 大屏方向限制红线。
> - 2026-07-11 00:00 CST: 补充 Web 单书按需拉取、5 秒 Room 合并保存、60 秒 WebDAV 上传与生命周期补交的数据流入口。

> 主入口文件。AI 每次只需要先读这里；按任务需要再打开子文件。

---

## 先读规则

- 具体 Android/API/构建坑: 读 `适配踩坑记录.md`。该文件最多保留 15 条，满 15 条后整体改名归档到 `文档归档/` 并重新开新文件记录，不再来回挪动单条。
- 新功能设计、交互、性能、回归点: 读 `新功能踩坑记录.md`。
- 项目结构、数据流、编码规则不要都塞回本文件；详细内容放 `AI编辑必看/` 子目录。
- 每次代码修改后检查文档是否需要同步；更新日志写 `app/src/main/assets/updateLog.md`，只写最短用户可读摘要，不写实现细节。

---

## 项目概览

- 项目: Legado (阅读) Android 电子书阅读器
- SDK: minSdk 26, targetSdk 37, compileSdk 37
- 技术栈: Kotlin + 少量 Java, MVVM + Room, ViewBinding, Coroutines/Flow
- 模块: `app`, `modules/book`, `modules/rhino`, `modules/web`
- 资源边界: `modules/web/src/` 是 Web 源码，`app/src/main/assets/web/` 是 APK 实际加载资源

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
- API 30+ 系统栏显隐和图标明暗统一走 `WindowInsetsControllerCompat`；API 26-29 才保留旧 `systemUiVisibility` 分支。Android 16+、宽度不小于 600dp 的大屏不再强制阅读页方向。
- 不要提交 `.sdk/`, `local.properties`, `app/gradle.properties`, `app/signing/`, `AGENTS.md`, `opencode.json`。

---

## 最小验证

```bash
scripts/check-debug.sh
```

生成 APK 时再跑:

```bash
./gradlew :app:assembleDebug --no-daemon
```

*文档版本: 2026-07-14*
