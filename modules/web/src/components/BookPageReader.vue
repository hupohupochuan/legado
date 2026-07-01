<template>
  <div
    class="book-page-reader"
    ref="rootRef"
    :style="rootStyle"
    :data-effect="pageTurnEffectComputed"
    :class="{ night: isNight, day: !isNight }"
    @touchstart="onTouchStart"
    @touchmove="onTouchMove"
    @touchend="onTouchEnd"
  >
    <div
      class="bp-stage"
      ref="stageRef"
      :class="stageClass"
    >
      <div
        v-if="currentPage"
        class="bp-page bp-page-current"
        :key="'current-' + currentPageKey"
        :style="pageStyle(currentPage)"
      >
        <div class="bp-page-inner" v-html="renderBlocks(currentPage.blocks)"></div>
        <div class="bp-page-shade" aria-hidden="true"></div>
      </div>
      <div
        v-if="animating && targetPage"
        class="bp-page bp-page-target"
        :key="'target-' + targetPageKey"
        :style="pageStyle(targetPage)"
      >
        <div class="bp-page-inner" v-html="renderBlocks(targetPage.blocks)"></div>
        <div class="bp-page-shade" aria-hidden="true"></div>
      </div>
    </div>

    <div class="bp-hint" v-if="paginating">分页中…</div>

    <!-- 隐藏测量容器：宽高与可见书页一致，用来做分页 DOM 测量 -->
    <div
      class="bp-measure"
      ref="measureRef"
      :style="measureStyle"
      aria-hidden="true"
    ></div>

    </div>
</template>

<script setup lang="ts">
import API from '@api'
import { isLegadoUrl } from '@/utils/utils'
import { toast } from '@/utils/toast'
import {
  buildBlocks,
  findPageIndexByPos,
  paginateBlocks,
  type BookPage,
  type PageBlock,
} from '@/utils/bookPagination'
import type { webReadConfig } from '@/web'

const store = useBookStore()

const props = defineProps<{
  chapterIndex: number
  contents: string[]
  title: string
  spacing: webReadConfig['spacing']
  fontFamily: string
  fontSize: string
  readWidth: number
  pageTurnEffect?: 'slide' | 'book'
  initialChapterPos: number
}>()

const emit = defineEmits<{
  (e: 'progressChange', index: number, pos: number): void
  (e: 'requestNextChapter'): void
  (e: 'requestPrevChapter'): void
  (e: 'pageReady'): void
  (e: 'fallbackToScroll'): void
}>()

const isNight = computed(() => store.isNight)
const reducedMotion = computed(
  () =>
    typeof window !== 'undefined' &&
    window.matchMedia?.('(prefers-reduced-motion: reduce)').matches,
)

const rootRef = ref<HTMLElement>()
const stageRef = ref<HTMLElement>()
const measureRef = ref<HTMLElement>()

const pages = ref<BookPage[]>([])
const paginating = ref(false)
const currentPageIndex = ref(0)
const targetPageIndex = ref<number | null>(null)
const animating = ref(false)
let initialized = false
let fallbackEmitted = false

const currentPage = computed(() => pages.value[currentPageIndex.value])
const targetPage = computed(() =>
  targetPageIndex.value === null ? null : pages.value[targetPageIndex.value],
)
const currentPageKey = computed(
  () => `${props.chapterIndex}:${currentPageIndex.value}:${pages.value.length}`,
)
const targetPageKey = computed(
  () => `${props.chapterIndex}:${targetPageIndex.value ?? 'none'}:${pages.value.length}`,
)

// 翻页方向：next/prev 决定 enter/leave 动画朝向，避免前进后退看起来一样
const flipDirection = ref<'next' | 'prev'>('next')
const pageTurnEffectComputed = computed(
  () => props.pageTurnEffect ?? 'book',
)
const stageClass = computed(() => {
  if (!animating.value || reducedMotion.value) return {}
  return {
    'bp-animating': true,
    [`bp-${pageTurnEffectComputed.value}-${flipDirection.value}`]: true,
  }
})

// 视口（书页）尺寸：书本翻页每页都是全新内容、不滚动、不重复，所以比滚动
// 模式给页更大的宽度（几乎吃满 readWidth）和更高的高度（只保留很小的上下留白）。
const pageWidth = computed(() => {
  if (store.miniInterface) return window.innerWidth - 40
  // 滚动模式正文宽 = readWidth - 130；书本模式少扣两侧留白，页面更宽
  return Math.max(props.readWidth - 40, 240)
})
const pageHeight = ref(0)

const rootStyle = computed(() => ({
  height: pageHeight.value + 'px',
  '--bp-page-width': pageWidth.value + 'px',
  '--bp-page-height': pageHeight.value + 'px',
  '--bp-font-size': props.fontSize,
  '--bp-font-family': props.fontFamily,
  '--bp-letter':
    'calc(' + (props.spacing.letter ?? 0) + ' * 1em)',
  '--bp-line':
    'calc(1 + ' + (props.spacing.line ?? 0) + ')',
  '--bp-paragraph':
    'calc(' + (props.spacing.paragraph ?? 0) + ' * 1em)',
}))

const measureStyle = rootStyle

const pageStyle = (_page: BookPage) => ({
  width: 'var(--bp-page-width)',
  height: 'var(--bp-page-height)',
})

// ---------- 图片代理（与 ChapterContent.vue 一致） ----------
const imgTagPattern = /<img[^>]*src=['"]([^'"]*(?:['"][^>]+\})?)['"][^>]*>/g
const bookUrl = computed(() => store.readingBook.bookUrl)
const fontSizeNum = computed(() => store.config.fontSize)

const proxyInlineImages = (content: string) => {
  return content.replace(imgTagPattern, (match, src) => {
    if (isLegadoUrl(src)) {
      const proxySrc = API.getProxyImageUrl(
        bookUrl.value,
        src,
        fontSizeNum.value * 2,
      )
      return match.replace(src, proxySrc)
    }
    return match
  })
}

// ---------- 渲染每页块为 HTML ----------
const renderBlock = (block: PageBlock): string => {
  if (block.type === 'title') {
    return `<div class="bp-title">${escapeHtml(block.text)}</div>`
  }
  if (block.type === 'image') {
    const src = proxyInlineImages(block.html)
    return `<div class="bp-img-wrapper">${src}</div>`
  }
  // paragraph
  return `<p>${proxyInlineImages(block.html)}</p>`
}

const renderBlocks = (blocks: PageBlock[]): string => {
  return blocks.map(renderBlock).join('')
}

function escapeHtml(s: string) {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
}

// ---------- 测量与分页 ----------
let appendFrag: ChildNode[] = []
let pendingLastBlock: ChildNode[] | null = null

const createBlockNode = (block: PageBlock): HTMLElement => {
  const wrapper = document.createElement('div')
  wrapper.innerHTML = renderBlock(block)
  return wrapper.firstElementChild as HTMLElement
}

const measureApi = {
  append: (block: PageBlock): boolean => {
    const measureEl = measureRef.value
    if (!measureEl) return false
    const node = createBlockNode(block)
    measureEl.appendChild(node)
    appendFrag.push(node)
    const overflow = measureEl.scrollHeight - measureEl.clientHeight > 1
    // 暂存这次追加，便于 rollback
    pendingLastBlock = appendFrag.slice(-1)
    return overflow
  },
  rollback: () => {
    if (pendingLastBlock) {
      pendingLastBlock.forEach(n => n.parentNode?.removeChild(n))
      const idx = appendFrag.indexOf(pendingLastBlock[0])
      if (idx > -1) appendFrag.length = idx
    }
    pendingLastBlock = null
  },
  reset: () => {
    const measureEl = measureRef.value
    if (measureEl) measureEl.innerHTML = ''
    appendFrag = []
    pendingLastBlock = null
  },
  splitParagraph: (
    block: Extract<PageBlock, { type: 'paragraph' }>,
  ): PageBlock[] => {
    const measureEl = measureRef.value
    if (!measureEl) return [block]
    // 测量容器还没拿到真实尺寸（首挂载同步分页/样式未 flush）时，单字符
    // 都会判定为溢出，递归对 1 字符 chunk 再次切分会无限递归直至栈溢出。
    // 此时直接放弃切分，让外层 place 把段落到独立页，布局就绪后重分页修正。
    if (measureEl.clientHeight <= 0 || measureEl.clientWidth <= 0) return [block]
    // 取纯文本，按字符二分；图片标签会丢失（仅在不正常超长段触发）
    const text = stripHtml(block.html)
    if (!text.length) return [block]
    const chunks: PageBlock[] = []
    const tryChunk = (start: number): number => {
      // 二分 max length 使 substring(start, end) 单独放入空 measure 不溢出
      let lo = 1
      let hi = text.length - start
      let best = 1
      measureEl.innerHTML = ''
      while (lo <= hi) {
        const mid = (lo + hi) >> 1
        const probe = text.slice(start, start + mid)
        const p = document.createElement('p')
        p.textContent = probe
        measureEl.appendChild(p)
        const overflow = measureEl.scrollHeight - measureEl.clientHeight > 1
        measureEl.removeChild(p)
        if (!overflow) {
          best = mid
          lo = mid + 1
        } else {
          hi = mid - 1
        }
      }
      return best
    }
    let cursor = 0
    while (cursor < text.length) {
      const len = tryChunk(cursor)
      if (len <= 0) {
        // 兜底：单字符都放不下（页面极窄），把剩余文本独占一页
        chunks.push({
          type: 'paragraph',
          html: text.slice(cursor),
          topPos: block.topPos + cursor,
        })
        break
      }
      // 每个 chunk 的 topPos = 段落起点 + 该 chunk 在段内的起始字符偏移，
      // 这样翻页进度会随长段落切分前进，刷新后也能回到对应 chunk 页。
      chunks.push({
        type: 'paragraph',
        html: text.slice(cursor, cursor + len),
        topPos: block.topPos + cursor,
      })
      cursor += len
    }
    measureEl.innerHTML = ''
    return chunks.length ? chunks : [block]
  },
}

function stripHtml(html: string): string {
  const tmp = document.createElement('div')
  tmp.innerHTML = html
  return tmp.textContent || tmp.innerText || ''
}

const doPaginate = () => {
  paginating.value = true
  try {
    const blocks = buildBlocks({
      contents: props.contents,
      title: props.title,
      spacing: props.spacing,
      fontFamily: props.fontFamily,
      fontSize: props.fontSize,
    })
    measureApi.reset()
    pages.value = paginateBlocks(blocks, measureApi)
    // 测量容器没有拿到真实尺寸时，递归 place/splitParagraph 会无限切分
    // 单字符块直至栈溢出；此时不要判定为初始化失败，等布局就绪后由
    // ResizeObserver / resize / fonts.ready 触发的重分页修正即可。
    if (pages.value.length === 0) {
      console.warn('[BookPageReader] paginate produced 0 pages, layout not ready')
    }
  } catch (e) {
    console.error('[BookPageReader] paginate failed', e)
    if (!fallbackEmitted) {
      fallbackEmitted = true
      toast.warning('书本翻页初始化失败，已切换为滚动阅读')
      emit('fallbackToScroll')
    }
    return
  } finally {
    paginating.value = false
  }
}

const restoreIndex = () => {
  if (pages.value.length === 0) return
  currentPageIndex.value = findPageIndexByPos(pages.value, props.initialChapterPos)
  emit('progressChange', props.chapterIndex, currentPage.value?.startPos ?? 0)
}

let paginateTimer: ReturnType<typeof setTimeout> | null = null
const scheduleRepaginate = (delay = 200) => {
  if (paginateTimer) clearTimeout(paginateTimer)
  paginateTimer = setTimeout(() => {
    cancelFlipAnimation()
    const prevPos = currentPage.value?.startPos ?? props.initialChapterPos
    doPaginate()
    if (pages.value.length === 0) return
    currentPageIndex.value = findPageIndexByPos(pages.value, prevPos)
  }, delay)
}
const onWindowResize = () => scheduleRepaginate()

// ---------- 高度同步：按视口 + 顶/底工具栏预留 ----------
const updateHeight = () => {
  // 书本翻页每页内容独立、不滚动也不重复，去掉滚动模式那 2x64px 占位的
  // 富余，只留小上下边距让页面更长，单页可容纳更多文本。
  pageHeight.value = Math.max(160, window.innerHeight - 40)
}

// ---------- 翻页 ----------
let flipLock = false
let flipTimer: ReturnType<typeof setTimeout> | null = null

const flipDuration = () => {
  if (reducedMotion.value) return 0
  return pageTurnEffectComputed.value === 'book' ? 480 : 260
}

const cancelFlipAnimation = () => {
  if (flipTimer) clearTimeout(flipTimer)
  flipTimer = null
  targetPageIndex.value = null
  animating.value = false
  flipLock = false
}

const finishFlip = (nextIndex: number) => {
  currentPageIndex.value = nextIndex
  targetPageIndex.value = null
  animating.value = false
  flipLock = false
  emit('progressChange', props.chapterIndex, currentPage.value?.startPos ?? 0)
}

const startFlip = (nextIndex: number, direction: 'next' | 'prev') => {
  if (flipLock || paginating.value || pages.value.length === 0) return
  flipDirection.value = direction
  if (reducedMotion.value) {
    finishFlip(nextIndex)
    return
  }
  flipLock = true
  targetPageIndex.value = nextIndex
  animating.value = true
  if (flipTimer) clearTimeout(flipTimer)
  flipTimer = setTimeout(() => {
    flipTimer = null
    finishFlip(nextIndex)
  }, flipDuration())
}

const flipNext = () => {
  if (flipLock || paginating.value || pages.value.length === 0) return
  if (currentPageIndex.value >= pages.value.length - 1) {
    emit('requestNextChapter')
    return
  }
  startFlip(currentPageIndex.value + 1, 'next')
}

const flipPrev = () => {
  if (flipLock || paginating.value || pages.value.length === 0) return
  if (currentPageIndex.value <= 0) {
    emit('requestPrevChapter')
    return
  }
  startFlip(currentPageIndex.value - 1, 'prev')
}

// ---------- 交互 ----------
// 点击翻页热区不再覆盖整页：保持与改动前一致，仅底部 read-bar 的
// 上一章/下一章按钮处可点击翻页（由父组件 BookChapter 调用 flipPrev/flipNext）。
// 整页点击交给父级 .chapter-wrapper 的 @click 切换工具栏，沿用滚动模式行为。

// 触摸横向滑动
let touchStartX = 0
let touchStartY = 0
let touchActive = false
const onTouchStart = (e: TouchEvent) => {
  if (e.touches.length !== 1) {
    touchActive = false
    return
  }
  const t = e.touches[0]
  touchStartX = t.clientX
  touchStartY = t.clientY
  touchActive = true
}
const onTouchMove = (e: TouchEvent) => {
  if (!touchActive) return
  const t = e.touches[0]
  const dx = t.clientX - touchStartX
  const dy = t.clientY - touchStartY
  // 横向滑动占主导时阻止滚动
  if (Math.abs(dx) > Math.abs(dy) && Math.abs(dx) > 6) {
    e.preventDefault?.()
  }
}
const onTouchEnd = (e: TouchEvent) => {
  if (!touchActive) return
  touchActive = false
  const t = e.changedTouches[0]
  const dx = t.clientX - touchStartX
  const dy = t.clientY - touchStartY
  if (Math.abs(dx) > 40 && Math.abs(dx) > Math.abs(dy)) {
    if (dx < 0) flipNext()
    else flipPrev()
  }
}

// 键盘
const onKey = (e: KeyboardEvent) => {
  if (!initialized) return
  if (e.key === 'ArrowRight') {
    e.stopPropagation()
    e.preventDefault()
    flipNext()
  } else if (e.key === 'ArrowLeft') {
    e.stopPropagation()
    e.preventDefault()
    flipPrev()
  }
}

// ---------- 生命周期 ----------
let resizeObserver: ResizeObserver | null = null

// 等待测量容器拿到真实尺寸：首挂载时 pageHeight 初始为 0，rootStyle 的
// CSS 变量要等 Vue 下一帧才 flush 到 DOM，同步立即分页会让 0 高度容器
// 把所有块判为溢出、splitParagraph 把段落切成 1 字符块再无限递归栈溢出。
const waitForLayout = () =>
  new Promise<void>(resolve => {
    const start = performance.now()
    const check = () => {
      const el = measureRef.value
      if (el && el.clientHeight > 0 && el.clientWidth > 0) return resolve()
      if (performance.now() - start > 2000) return resolve()
      requestAnimationFrame(check)
    }
    check()
  })

const init = async () => {
  updateHeight()
  await waitForLayout()
  doPaginate()
  if (pages.value.length === 0) return
  initialized = true
  restoreIndex()
  emit('pageReady')
}

onMounted(() => {
  init().catch(e => {
    console.error('[BookPageReader] init failed', e)
    if (!fallbackEmitted) {
      fallbackEmitted = true
      toast.warning('书本翻页初始化失败，已切换为滚动阅读')
      emit('fallbackToScroll')
    }
  })
  window.addEventListener('keydown', onKey)
  window.addEventListener('resize', onWindowResize)
  if (typeof ResizeObserver !== 'undefined' && stageRef.value) {
    resizeObserver = new ResizeObserver(() => scheduleRepaginate())
    resizeObserver.observe(stageRef.value)
  }
  // 字体加载后重分页一次
  document.fonts?.ready.then(() => scheduleRepaginate(60)).catch(() => undefined)
  // 图片加载后重分页
  document.addEventListener('load', onAnyLoad, true)
})

const onAnyLoad = (e: Event) => {
  const t = e.target as HTMLElement
  if (t && t.tagName === 'IMG') scheduleRepaginate()
}

onBeforeUpdate(() => {
  // 防止测量容器残留影响渲染
})

watch(
  () => [props.contents, props.title, props.fontSize, props.fontFamily],
  () => scheduleRepaginate(),
  { deep: true },
)
watch(
  () => [props.spacing.letter, props.spacing.line, props.spacing.paragraph],
  () => scheduleRepaginate(),
)
watch(
  () => pageWidth.value,
  () => scheduleRepaginate(),
)
watch(
  () => props.chapterIndex,
  () => {
    // 章节切换由父组件重新挂载或更新 contents 触发，这里复位页索引
    currentPageIndex.value = 0
  },
)

onUnmounted(() => {
  window.removeEventListener('keydown', onKey)
  window.removeEventListener('resize', onWindowResize)
  document.removeEventListener('load', onAnyLoad, true)
  resizeObserver?.disconnect()
  resizeObserver = null
  if (flipTimer) clearTimeout(flipTimer)
  if (paginateTimer) clearTimeout(paginateTimer)
  measureApi.reset()
})

defineExpose({ flipNext, flipPrev, currentPageIndex, pages })
</script>

<style lang="scss" scoped>
.book-page-reader {
  position: relative;
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  user-select: none;

  .bp-stage {
    position: relative;
    width: var(--bp-page-width);
    height: var(--bp-page-height);
    perspective: 1800px;
    transform-style: preserve-3d;
    isolation: isolate;
  }

  .bp-page {
    position: absolute;
    inset: 0;
    box-sizing: border-box;
    padding: 0;
    overflow: hidden;
    background: inherit;
    backface-visibility: hidden;
    -webkit-backface-visibility: hidden;
    transform-style: preserve-3d;
    will-change: transform, opacity;
    z-index: 1;

    .bp-page-inner {
      font-size: var(--bp-font-size);
      line-height: var(--bp-line);
      font-family: var(--bp-font-family);
    }

    .bp-page-shade {
      position: absolute;
      inset: 0;
      pointer-events: none;
      opacity: 0;
    }
  }

  .bp-page-current {
    z-index: 2;
  }

  .bp-page-target {
    z-index: 1;
  }

  .bp-stage.bp-animating {
    pointer-events: none;
  }

  // 固定纸质书效果：不跟随手指，只在触发后播放预设 3D 翻页。
  .bp-stage.bp-book-next {
    .bp-page-current {
      z-index: 3;
      transform-origin: left center;
      animation: bp-book-current-next 0.48s cubic-bezier(0.2, 0.68, 0.18, 1) forwards;

      .bp-page-shade {
        background:
          linear-gradient(90deg, rgba(0, 0, 0, 0.26), rgba(0, 0, 0, 0.08) 38%, transparent 72%),
          linear-gradient(270deg, rgba(255, 255, 255, 0.2), transparent 46%);
        animation: bp-book-current-shade 0.48s ease-in-out forwards;
      }
    }

    .bp-page-target {
      z-index: 1;
      animation: bp-book-target-next 0.48s ease-out forwards;

      .bp-page-shade {
        background: linear-gradient(90deg, rgba(0, 0, 0, 0.18), transparent 48%);
        animation: bp-book-target-shade 0.48s ease-out forwards;
      }
    }
  }

  .bp-stage.bp-book-prev {
    .bp-page-current {
      z-index: 1;
      animation: bp-book-current-prev 0.48s ease-out forwards;

      .bp-page-shade {
        background: linear-gradient(270deg, rgba(0, 0, 0, 0.16), transparent 52%);
        animation: bp-book-target-shade 0.48s ease-out forwards;
      }
    }

    .bp-page-target {
      z-index: 3;
      transform-origin: left center;
      animation: bp-book-target-prev 0.48s cubic-bezier(0.2, 0.68, 0.18, 1) forwards;

      .bp-page-shade {
        background:
          linear-gradient(90deg, rgba(0, 0, 0, 0.26), rgba(0, 0, 0, 0.08) 38%, transparent 72%),
          linear-gradient(270deg, rgba(255, 255, 255, 0.2), transparent 46%);
        animation: bp-book-current-shade 0.48s ease-in-out reverse forwards;
      }
    }
  }

  // 固定滑动效果：触发后直接左右移入/移出，不做跟手位移。
  .bp-stage.bp-slide-next {
    .bp-page-current {
      z-index: 2;
      animation: bp-slide-current-next 0.26s ease-out forwards;
    }

    .bp-page-target {
      z-index: 3;
      animation: bp-slide-target-next 0.26s ease-out forwards;
    }
  }

  .bp-stage.bp-slide-prev {
    .bp-page-current {
      z-index: 2;
      animation: bp-slide-current-prev 0.26s ease-out forwards;
    }

    .bp-page-target {
      z-index: 3;
      animation: bp-slide-target-prev 0.26s ease-out forwards;
    }
  }

  .bp-measure {
    position: absolute;
    left: -99999px;
    top: -99999px;
    width: var(--bp-page-width);
    height: var(--bp-page-height);
    padding: 0;
    box-sizing: border-box;
    overflow: hidden;
    font-size: var(--bp-font-size);
    line-height: var(--bp-line);
    font-family: var(--bp-font-family);
    visibility: hidden;
    pointer-events: none;
  }
  .bp-measure :deep(.bp-title) {
    font: 24px / 32px PingFangSC-Regular, HelveticaNeue-Light,
      'Helvetica Neue Light', 'Microsoft YaHei', sans-serif;
    margin-bottom: 57px;
  }
  .bp-measure :deep(p) {
    display: block;
    word-wrap: break-word;
    letter-spacing: var(--bp-letter);
    line-height: var(--bp-line);
    margin: var(--bp-paragraph) 0;
    img {
      height: 1em;
    }
  }
  .bp-measure :deep(.bp-img-wrapper) {
    width: 100%;
    img {
      display: block;
      width: 100%;
      max-height: 100%;
    }
  }

  .bp-hint {
    position: absolute;
    left: 50%;
    bottom: 12%;
    transform: translateX(-50%);
    z-index: 6;
    font-size: 13px;
    opacity: 0.7;
  }
}

// 可见页块样式
.bp-page :deep(.bp-title) {
  font: 24px / 32px PingFangSC-Regular, HelveticaNeue-Light,
    'Helvetica Neue Light', 'Microsoft YaHei', sans-serif;
  margin-bottom: 57px;
}
.bp-page :deep(p) {
  display: block;
  word-wrap: break-word;
  letter-spacing: var(--bp-letter);
  line-height: var(--bp-line);
  margin: var(--bp-paragraph) 0;
  img {
    height: 1em;
  }
}
.bp-page :deep(.bp-img-wrapper) {
  width: 100%;
  img {
    display: block;
    width: 100%;
    max-height: 100%;
  }
}

.day .bp-page {
  border: 1px solid #d8d8d8;
  color: #262626;
}
.night .bp-page {
  border: 1px solid #444;
  color: #666;
}

@keyframes bp-book-current-next {
  0% {
    opacity: 1;
    transform: rotateY(0deg) translateZ(2px);
    box-shadow: none;
  }
  52% {
    opacity: 0.94;
    transform: rotateY(-64deg) translateZ(18px);
    box-shadow: -18px 0 28px rgba(0, 0, 0, 0.18);
  }
  100% {
    opacity: 0;
    transform: rotateY(-108deg) translateZ(4px);
    box-shadow: -28px 0 34px rgba(0, 0, 0, 0.08);
  }
}

@keyframes bp-book-target-next {
  0% {
    opacity: 0.72;
    transform: translateX(14px) scale(0.996);
  }
  100% {
    opacity: 1;
    transform: translateX(0) scale(1);
  }
}

@keyframes bp-book-current-prev {
  0% {
    opacity: 0.86;
    transform: translateX(-10px) scale(0.998);
  }
  100% {
    opacity: 1;
    transform: translateX(0) scale(1);
  }
}

@keyframes bp-book-target-prev {
  0% {
    opacity: 0;
    transform: rotateY(-108deg) translateZ(4px);
    box-shadow: -28px 0 34px rgba(0, 0, 0, 0.08);
  }
  52% {
    opacity: 0.94;
    transform: rotateY(-64deg) translateZ(18px);
    box-shadow: -18px 0 28px rgba(0, 0, 0, 0.18);
  }
  100% {
    opacity: 1;
    transform: rotateY(0deg) translateZ(2px);
    box-shadow: none;
  }
}

@keyframes bp-book-current-shade {
  0% {
    opacity: 0;
  }
  46% {
    opacity: 0.72;
  }
  100% {
    opacity: 0.18;
  }
}

@keyframes bp-book-target-shade {
  0% {
    opacity: 0.42;
  }
  100% {
    opacity: 0;
  }
}

@keyframes bp-slide-current-next {
  0% {
    transform: translateX(0);
  }
  100% {
    transform: translateX(-100%);
  }
}

@keyframes bp-slide-target-next {
  0% {
    transform: translateX(100%);
  }
  100% {
    transform: translateX(0);
  }
}

@keyframes bp-slide-current-prev {
  0% {
    transform: translateX(0);
  }
  100% {
    transform: translateX(100%);
  }
}

@keyframes bp-slide-target-prev {
  0% {
    transform: translateX(-100%);
  }
  100% {
    transform: translateX(0);
  }
}
</style>
