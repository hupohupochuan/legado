# 历史书源编辑分入口

`src/pages/source/` 是旧的独立入口，不参与当前 `modules/web/index.html` → `src/main.ts` 生产构建；其中 `main.js` 还保留已不在当前依赖中的 Element Plus 引用，不应作为开发入口运行。

当前书源和替换规则编辑已并入统一 SPA：

- `/#/bookSource`
- `/#/replaceRule`

开发、`VITE_API` 配置、生产构建及 APK assets 同步以 [`modules/web/README.md`](../../../README.md) 为准。
