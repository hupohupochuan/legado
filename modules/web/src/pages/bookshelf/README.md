# 历史书架分入口

`src/pages/bookshelf/` 是旧的独立入口，不参与当前 `modules/web/index.html` → `src/main.ts` 生产构建；其中 `main.js` 还保留已不在当前依赖中的 Element Plus 引用，不应作为开发入口运行。

当前书架和阅读页已并入统一 SPA，路由分别为 `/#/shelf` 和 `/#/chapter?bookUrl=...`。书架页可保存自定义后端 URL；最近阅读信息使用浏览器存储，阅读配置通过 App 的 `/getReadConfig` 与 `/saveReadConfig` 接口读写，不能笼统视为全部只存本地。

开发、生产构建及 APK assets 同步以 [`modules/web/README.md`](../../../README.md) 为准。
