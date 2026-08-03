# 阅读 Web 端

本目录是 Vue 3 单页应用，统一包含欢迎页、书架、网页传书、连续滚动/书本翻页阅读页、书源编辑和替换规则编辑。唯一生产入口是 `modules/web/index.html` → `src/main.ts`；旧的 `src/pages/bookshelf`、`src/pages/source` 独立入口已于 2026-07-15 删除。Android App 实际加载 `app/src/main/assets/web/index.html`。

## Hash 路由

- `/#/`：欢迎页。
- `/#/shelf`：书架。
- `/#/uploadBook`：网页传书，支持多文件选择/拖放、逐个上传、真实结果和失败重试。
- `/#/chapter?bookUrl=...`：阅读页。
- `/#/bookSource`：书源编辑。
- `/#/replaceRule`：替换规则编辑。

历史直达地址 `/uploadBook/index.html` 只保留到 `/#/uploadBook` 的兼容跳转；不要在该静态文件中维护第二套上传逻辑。手机端接口对业务失败也返回 HTTP 200，上传页面必须继续检查 JSON 的 `isSuccess` 并展示 `errorMsg`。

## 构建目标

Vite 当前编译目标为 ES2020、Edge 88、Firefox 78、Chrome 87 和 Safari 14。它表示构建产物的语法目标，不等同于这些版本已完成全部功能实测。

## 本地开发与 APK 同步

需要先在手机端启动 Web 服务。本机通过 App 提供页面时默认使用当前 origin；独立启动 Vite 时，可在 `modules/web/.env.development` 中设置 `VITE_API=http://手机IP:Web服务端口`，也可在书架页填写后端地址。

```bash
cd modules/web
pnpm install --frozen-lockfile
pnpm dev

# 提交生产改动前
pnpm run test:read-time
pnpm run type-check
pnpm run build-only
cd ../..
cp modules/web/dist/index.html app/src/main/assets/web/index.html
cp modules/web/dist/favicon.ico app/src/main/assets/web/favicon.ico
cmp -s modules/web/dist/index.html app/src/main/assets/web/index.html
cmp -s modules/web/dist/favicon.ico app/src/main/assets/web/favicon.ico
scripts/check-web-assets-sync.sh
```

构建启用了 `vite-plugin-singlefile`，业务 JS/CSS 会内联进 `dist/index.html`，独立产物只有 `index.html` 与 `favicon.ico`。本地 `pnpm build` 最后的 `scripts/sync.js` 只会在 GitHub Actions 环境复制产物，因此本地仍须手动同步和比较这两个文件。`pnpm run test:read-time` 覆盖 Web 阅读时长的切章、空闲和清理状态；代码检查和格式化分别使用 `pnpm lint:fix` 与 `pnpm format`。

*Last updated: 2026-08-03*
