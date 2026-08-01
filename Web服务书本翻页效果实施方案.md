# Web 服务书本翻页效果实施方案

> 记录日期: 2026-07-01 CST
> 适用范围: `modules/web` Vue 3 Web 服务阅读页；APK 实际生效文件为 `app/src/main/assets/web/index.html`
> 目标读者: 后续接手实现的 AI / 开发者
>
> **状态复核（2026-08-01）**：这是实施前的历史方案，不是当前实现说明。功能已在 `bookPagination.ts` + `BookPageReader.vue` 中完成并继续演进；建议中的 `PagedChapterContent.vue` 没有成为当前文件，整页左右点击热区已取消，纸质书效果也从 `rotateY` 方案改为同一舞台上的固定 `clip-path` 裁切/边缘/阴影动画。维护当前代码时以 `新功能踩坑记录-Web服务.md` 的 Web 书本模式后续条目和实际源码为准。

---

## 1. 结论

可以做。该功能应作为 Web 服务阅读页的一个可选阅读模式接入，不直接替换当前连续滚动模式。

推荐路线:

1. 第一版先实现稳定的分页阅读模式，并配一个轻量 CSS 3D 书本翻页动画。
2. 如果希望直接获得更成熟的纸张翻动视觉，可评估引入 GitHub 项目 `Nodlik/StPageFlip` 对应 npm 包 `page-flip`，但仍然必须自己完成文本分页、进度映射和 Vue 生命周期封装。
3. 不建议直接引入 `turn.js`，它依赖较老的 jQuery 使用方式，且许可包含非商业限制，不适合直接进入本项目。

核心判断: 这个需求最难的不是动画，而是“把当前滚动 DOM 阅读改造成可保存进度的分页阅读”。动画库只能解决翻页视觉，不能替代分页和阅读进度逻辑。

本方案的最高优先级约束:

- 翻页功能必须独立于现有滚动阅读实现。
- 默认模式必须仍然是滚动阅读。
- 翻页模式出错、分页失败、浏览器能力不足或组件初始化失败时，必须能回退到滚动阅读。
- 不允许为了实现翻页大面积重写 `ChapterContent.vue` 的滚动阅读逻辑。
- 不允许影响现有无限滚动、键盘滚动、目录切章、阅读进度保存。

---

## 2. 当前 Web 阅读页结构

关键文件:

- `modules/web/src/views/BookChapter.vue`: Web 阅读页主入口；负责加载目录、获取章节、保存进度、键盘翻屏、无限滚动、工具栏。
- `modules/web/src/components/ChapterContent.vue`: 当前章节内容 DOM 渲染；用 `IntersectionObserver` 按段落位置更新 `chapterPos`。
- `modules/web/src/components/ReadSettings.vue`: 阅读设置面板。
- `modules/web/src/store/bookStore.ts`: Web 阅读配置、当前阅读书籍、阅读进度保存。
- `modules/web/src/web.d.ts`: `webReadConfig` 类型。
- `app/src/main/assets/web/index.html`: APK 内实际加载的 Web UI 产物。

现状约束:

- 当前阅读模式是连续滚动。
- 进度保存依赖段落进入顶部区域时触发 `readedLengthChange`。
- 无限滚动追加章节不能使用全局 loading mask。
- 正常阅读进度保存走普通 POST；页面隐藏/离开时才用 `sendBeacon`。
- 修改 `modules/web/src` 后必须同步构建产物到 `app/src/main/assets/web/index.html`。

---

## 3. 功能边界

### 必做

- 设置里新增阅读模式: `滚动` / `书本翻页`。
- 默认仍为 `滚动`，保持现有行为不变。
- 书本翻页相关代码必须放在新组件或新 composable 中，和滚动阅读路径分离。
- 书本翻页初始化失败时自动切回滚动模式，并只提示一次错误。
- 书本翻页模式支持:
  - 点击左右区域翻页。
  - 键盘左右键翻页。
  - 移动端横向滑动翻页。
  - 当前章第一页向前翻时进入上一章最后一页。
  - 当前章最后一页向后翻时进入下一章第一页。
  - 翻页完成后保存 `chapterIndex/chapterPos`。
  - 字体、字号、阅读宽度、行距、段距、窗口尺寸变化后重新分页。

### 暂不做

- 不改 Android 原生阅读器翻页。
- 不改漫画阅读。
- 不做 EPUB 原版排版还原。
- 不做复杂纸张卷曲物理仿真。
- 不让书本翻页模式和无限滚动同时工作。

---

## 4. 配置设计

在 `webReadConfig` 增加字段:

```ts
pageMode: 'scroll' | 'book'
pageTurnEffect: 'slide' | 'book'
```

默认值:

```ts
pageMode: 'scroll'
pageTurnEffect: 'book'
```

兼容旧配置:

- `bookStore.setConfig(config)` 仍用 `Object.assign` 合并默认值即可。
- 旧服务端配置没有新字段时自动使用默认滚动模式。

设置面板:

- `ReadSettings.vue` 增加模式切换。
- 当 `pageMode === 'book'` 时，禁用或隐藏“无限滚动”开关，并提示性地在逻辑层强制不启用无限滚动。

---

## 5. 推荐组件拆分

新增组件:

- `modules/web/src/components/PagedChapterContent.vue`
  - 输入单章内容、标题、字体、字号、间距、容器尺寸。
  - 输出分页结果和每页起始 `chapterPos`。

- `modules/web/src/components/BookPageReader.vue`
  - 承载分页阅读 UI。
  - 管理当前页、上一页/下一页预渲染、CSS 3D 翻页动画、手势。
  - 向父组件 emit:
    - `progressChange(index, pos)`
    - `requestPrevChapter()`
    - `requestNextChapter()`
    - `pageReady()`

保留组件:

- `ChapterContent.vue` 继续服务滚动模式，不要在第一版里大改。

强制隔离原则:

- `ChapterContent.vue` 只允许做必要的小型兼容改动，例如抽出复用的图片代理函数；不要把分页逻辑塞进去。
- `BookChapter.vue` 只做模式分发、章节加载、进度保存协调；不要把翻页动画细节写进主页面。
- 新增分页/翻页组件如果异常，应 emit `fallbackToScroll`，由 `BookChapter.vue` 切回滚动渲染。

`BookChapter.vue` 调整:

- 根据 `store.config.pageMode` 切换渲染:

```vue
<chapter-content v-if="store.config.pageMode === 'scroll'" />
<book-page-reader v-else />
```

- 滚动模式继续保留当前 `IntersectionObserver` 和无限滚动逻辑。
- 书本模式关闭滚动 observer，不调用无限滚动追加章节。

建议增加运行时守护:

```ts
const activePageMode = ref(store.config.pageMode || 'scroll')

const fallbackToScroll = () => {
  activePageMode.value = 'scroll'
  toast.warning('书本翻页初始化失败，已切换为滚动阅读')
}
```

渲染判断使用 `activePageMode`，不要直接用配置字段强绑渲染，避免失败后反复进入异常组件。

---

## 6. 分页算法

第一版建议使用 DOM 测量分页，不要用纯字数估算。

基本流程:

1. 创建隐藏测量容器，宽高等于实际书页可用区域。
2. 按段落逐步插入内容，检测 `scrollHeight > clientHeight`。
3. 段落级溢出时:
   - 普通文本段落用二分查找按字符切分。
   - 图片段落单独占页；如果图片超过页高，按 `max-height: 100%` 缩放。
4. 生成页面数组:

```ts
type BookPage = {
  chapterIndex: number
  startPos: number
  endPos: number
  title?: string
  blocks: PageBlock[]
}

type PageBlock =
  | { type: 'title'; text: string }
  | { type: 'paragraph'; html: string; startOffset: number; endOffset: number }
  | { type: 'image'; html: string; startOffset: number; endOffset: number }
```

5. 当前页翻动完成后，用 `page.startPos` 更新 `store.readingBook.chapterPos`。

注意:

- `chapterPos` 当前语义接近“章节内字符位置”，不要改成页码持久化。
- 页面重分页后，应按旧 `chapterPos` 找到第一个 `endPos >= chapterPos` 的页。
- 字体加载可能导致第一次分页不准，`document.fonts?.ready` 后需要重分页一次。

---

## 7. 翻页动画方案

### 方案 A: 自研 CSS 3D 动画

推荐第一版使用。

优点:

- 不新增依赖。
- Vue 状态可控，不需要外部库克隆 DOM。
- 更容易绑定 `chapterPos`、章节边界和保存逻辑。

实现要点:

- 容器固定高度为视口高度，禁止页面自然滚动。
- 只渲染当前页、目标页和必要的阴影层。
- 下一页动画:
  - 当前页绕右边缘 `rotateY(-180deg)`。
  - 目标页在背后等待。
  - 动画结束后提交页索引和进度。
- 上一页动画反向处理。
- `prefers-reduced-motion: reduce` 时禁用动画，直接切页。

### 方案 B: 引入 `page-flip` / `StPageFlip`

适合追求更成熟纸张视觉时使用。

参考项目:

- GitHub: https://github.com/Nodlik/StPageFlip
- npm: `page-flip`

已确认信息:

- MIT License。
- 包名 `page-flip`，`package.json` 声明无运行时 dependencies。
- 支持 HTML block 页面、移动端、横竖屏、软/硬页、事件和命令式翻页方法。
- 支持 `loadFromHtml` / `updateFromHtml`，可用生成后的 page DOM 作为输入。

集成建议:

1. 只在 `BookPageReader.vue` 内部封装 `PageFlip`，不要把库对象泄漏到 `BookChapter.vue`。
2. 由本项目自己生成 `BookPage[]` 和 page DOM。
3. `PageFlip` 只负责动画与翻页交互。
4. 监听 `flip` 事件，将库的 page index 映射回 `BookPage.startPos`。
5. 重分页时调用 `updateFromHtml`，并用 `turnToPage` 恢复当前阅读位置。
6. 组件卸载时必须调用 `destroy()`。

风险:

- 该库会接管并克隆/移动 HTML page，和 Vue 响应式 DOM 生命周期有潜在冲突。
- 长章节如果一次性生成大量 page DOM，内存压力较大；需要限制只渲染当前章节，跨章时重建。

### 不推荐: `turn.js`

参考项目:

- GitHub: https://github.com/blasten/turn.js

不推荐原因:

- 许可文本限制 redistribution/use/modification 只能用于 personal benefit，不能用于商业或 monetary gain。
- 项目使用方式偏旧，通常依赖 jQuery 生态。
- 本项目是现代 Vue/Vite/TypeScript 前端，引入成本和许可风险都高于收益。

---

## 8. 章节边界设计

书本模式只维护当前章节页面，跨章时重建分页。

下一页:

1. 如果 `currentPageIndex < pages.length - 1`，翻到下一页。
2. 否则调用父组件加载下一章。
3. 下一章加载成功后分页，跳到第 0 页。
4. 保存进度为下一章 `chapterPos = 0`。

上一页:

1. 如果 `currentPageIndex > 0`，翻到上一页。
2. 否则调用父组件加载上一章。
3. 上一章加载成功后分页，跳到最后一页。
4. 保存进度为上一章最后页 `startPos`。

失败处理:

- 下一章/上一章加载失败时，不改变当前页和当前进度。
- toast 沿用当前 `BookChapter.vue` 的错误提示风格。

---

## 9. 进度保存

滚动模式:

- 保持现状，继续由 `ChapterContent.vue` 的 `IntersectionObserver` 驱动。

书本模式:

- 翻页完成或跨章成功后调用:

```ts
saveReadingBookProgressToBrowser(chapterIndex, page.startPos)
saveBookProgressThrottle()
```

- `visibilitychange=hidden`、`onBeforeRouteLeave`、`onUnmounted` 仍调用现有 flush 和 beacon 逻辑。

防坑:

- 动画进行中不要保存目标页进度，避免取消手势后进度提前。
- 页面隐藏时如果动画进行中，应先完成或回滚到稳定页，再保存。

---

## 10. 性能策略

- 一次只分页当前章节。
- 章节内容变化、阅读宽度变化、字体配置变化、窗口 resize 后防抖重分页。
- 长章节分页可能耗时，使用 `requestIdleCallback` 或分批 `requestAnimationFrame`，避免卡住主线程。
- 图片加载完成后触发局部或整章重分页。
- 移动端书本模式应禁用文档自然滚动，避免触摸翻页和页面滚动冲突。

---

## 11. 实施步骤

### 阶段 1: 配置与 UI

- 修改 `modules/web/src/web.d.ts`。
- 修改 `modules/web/src/store/bookStore.ts` 默认配置。
- 修改 `modules/web/src/components/ReadSettings.vue` 增加阅读模式切换。
- 验证旧配置加载不报错。
- 只保存用户主动选择的模式；运行时失败回退不要覆盖用户配置，避免临时错误永久改变设置。

### 阶段 2: 分页组件

- 新增 `PagedChapterContent.vue` 或分页 composable。
- 实现 DOM 测量分页。
- 覆盖文本、标题、图片、空段落。
- 能按 `chapterPos` 定位到对应页。

### 阶段 3: 书本阅读器组件

- 新增 `BookPageReader.vue`。
- 实现页容器、页切换状态、点击/键盘/触摸翻页。
- 第一版用 CSS 3D 动画。
- 暴露跨章请求事件。
- 捕获分页和动画初始化异常，统一 emit `fallbackToScroll`。

### 阶段 4: 接入 `BookChapter.vue`

- 按 `pageMode` 切换滚动/书本模式。
- 书本模式禁用 infinite loading observer。
- 复用现有 `fetchChapterData`、`getContent`、进度保存和目录跳转。
- 确保目录切章后书本模式能跳到传入 `chapterPos`。
- 验证切回滚动模式后，当前章节内容、目录切章、阅读进度仍正常。

### 阶段 5: 可选引入 `page-flip`

- 如果第一版视觉不够，再添加依赖 `page-flip`。
- 将 CSS 3D 动画替换/封装为 `PageFlip` 后端。
- 不要在第一版同时做分页和第三方库集成，避免问题难以定位。

### 阶段 6: 构建同步

```bash
cd modules/web
pnpm run type-check
pnpm run build
```

本地构建后如果脚本没有自动同步，需要手动同步:

```bash
cp modules/web/dist/index.html app/src/main/assets/web/index.html
cp modules/web/dist/favicon.ico app/src/main/assets/web/favicon.ico
```

提交前更新:

- `新功能踩坑记录-Web服务.md`（并同步 `新功能踩坑记录.md` 索引）
- `app/src/main/assets/updateLog.md`

---

## 12. 验收清单

- 默认配置仍进入滚动阅读，现有无限滚动不回归。
- 设置切到书本翻页后，页面不再自然长滚动。
- 点击右侧/左侧能下一页/上一页。
- 键盘左右键能翻页。
- 手机横向滑动能翻页。
- 第一页上一页、最后一页下一页能正确跨章。
- 翻页完成后刷新页面，能恢复到最近页。
- 页面隐藏/关闭前能保存最近进度。
- 字号、行距、段距、阅读宽度变化后重分页，并尽量保持原进度。
- 图片页不撑破版面。
- 长章节分页不出现明显长时间白屏。
- 夜间主题、各阅读主题下页背景和文字颜色正确。
- `pnpm run type-check` 通过。
- `pnpm run build` 通过。
- `app/src/main/assets/web/index.html` 已同步。

---

## 13. 参考资料

- StPageFlip: https://github.com/Nodlik/StPageFlip
- React wrapper for StPageFlip: https://github.com/Nodlik/react-pageflip
- turn.js: https://github.com/blasten/turn.js
