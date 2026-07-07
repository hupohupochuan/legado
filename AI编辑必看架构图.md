# AI 编辑必看架构图

> 最新修订: 2026-06-26 00:00 CST
> 最近三次修订:
> - 2026-06-26 00:00 CST: compileSdk/targetSdk 抬到 37 (Android 17) 前置闸门验证通过；AGP 9.2.1 无需升级；主文件 SDK 指标同步更新为 targetSdk/compileSdk=37。
> - 2026-06-14 12:38 CST: 恢复并确认主从结构；主文件只保留入口、路由、红线，防止被误恢复为旧版长文。
> - 2026-06-14: 将从文件移动到 `AI编辑必看/`，适配归档移动到 `文档归档/`，降低根目录噪声。
> - 2026-06-14: 将 AI 必读架构文档拆为主从结构，降低默认读取 token。

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

*文档版本: 2026-06-14*
