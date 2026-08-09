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
    @touchcancel="onTouchCancel"
    @click="onReaderClick"
  >
    <div class="bp-stage" ref="stageRef" :class="stageClass">
      <div
        v-if="currentPage"
        class="bp-page bp-page-current"
        :key="'current-' + currentPageKey"
        :style="pageStyle()"
      >
        <div
          class="bp-page-inner"
          v-html="renderBlocks(currentPage.blocks)"
        ></div>
        <div class="bp-page-shade" aria-hidden="true"></div>
      </div>
      <div
        v-if="animating && targetPage"
        class="bp-page bp-page-target"
        :key="'target-' + targetPageKey"
        :style="pageStyle()"
      >
        <div
          class="bp-page-inner"
          v-html="renderBlocks(targetPage.blocks)"
        ></div>
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
import settings from '@/config/themeConfig'
import { isLegadoUrl } from '@/utils/utils'
import { finishReaderPerf, startReaderPerf } from '@/utils/readerPerformance'
import { toast } from '@/utils/toast'
import {
  buildBlocks,
  findPageIndexByPos,
  paginateBlocks,
  type BookPage,
  type PageBlock,
} from '@/utils/bookPagination'
import {
  createPageTurnKeyBuffer,
  type PageTurnDirection,
} from '@/utils/pageTurnKeyBuffer'
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

type ChapterPageSource = {
  index: number
  content: string[]
  title: string
}

type ExternalFlipCallbacks = {
  onFinished?: (chapterIndex: number, pos: number) => void
  onCancel?: () => void
}

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
const targetExternalPage = ref<BookPage | null>(null)
const targetExternalChapterIndex = ref<number | null>(null)
const animating = ref(false)
let initialized = false
let fallbackEmitted = false

const keyboardTurnBuffer = createPageTurnKeyBuffer(direction => {
  turnPageByKeyboard(direction)
})

const currentPage = computed(() => pages.value[currentPageIndex.value])
const targetPage = computed(
  () =>
    targetExternalPage.value ??
    (targetPageIndex.value === null
      ? null
      : pages.value[targetPageIndex.value]),
)
const currentPageKey = computed(
  () => `${props.chapterIndex}:${currentPageIndex.value}:${pages.value.length}`,
)
const targetPageKey = computed(() => {
  if (targetExternalPage.value) {
    return `${targetExternalChapterIndex.value ?? 'external'}:${targetExternalPage.value.startPos}:${targetExternalPage.value.endPos}`
  }
  return `${props.chapterIndex}:${targetPageIndex.value ?? 'none'}:${pages.value.length}`
})

// 翻页方向：next/prev 决定 enter/leave 动画朝向，避免前进后退看起来一样
const flipDirection = ref<'next' | 'prev'>('next')
const pageTurnEffectComputed = computed(() => props.pageTurnEffect ?? 'book')
const pageBackground = computed(
  () => settings.themes[store.config.theme]?.content ?? '#ede7da',
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
// 页宽只跟随 props.readWidth（由父组件统一提供响应式宽度），不要再读
// window.innerWidth，保证窄屏内拖动 F12 也能响应。
const pageWidth = computed(() => {
  return Math.max(props.readWidth - 40, 240)
})
const pageHeight = ref(0)

const rootStyle = computed(() => ({
  height: pageHeight.value + 'px',
  '--bp-page-width': pageWidth.value + 'px',
  '--bp-page-height': pageHeight.value + 'px',
  '--bp-font-size': props.fontSize,
  '--bp-font-family': props.fontFamily,
  '--bp-letter': 'calc(' + (props.spacing.letter ?? 0) + ' * 1em)',
  '--bp-line': 'calc(1 + ' + (props.spacing.line ?? 0) + ')',
  '--bp-paragraph': 'calc(' + (props.spacing.paragraph ?? 0) + ' * 1em)',
  '--bp-page-bg': pageBackground.value,
}))

const measureStyle = rootStyle

const pageStyle = () => ({
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
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
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
    if (measureEl.clientHeight <= 0 || measureEl.clientWidth <= 0)
      return [block]
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

const paginateChapter = (contents: string[], title: string): BookPage[] => {
  const blocks = buildBlocks({
    contents,
    title,
    spacing: props.spacing,
    fontFamily: props.fontFamily,
    fontSize: props.fontSize,
  })
  measureApi.reset()
  return paginateBlocks(blocks, measureApi)
}

const doPaginate = () => {
  paginating.value = true
  const perf = startReaderPerf('web.book.paginate')
  try {
    pages.value = paginateChapter(props.contents, props.title)
    // 测量容器没有拿到真实尺寸时，递归 place/splitParagraph 会无限切分
    // 单字符块直至栈溢出；此时不要判定为初始化失败，等布局就绪后由
    // ResizeObserver / resize / fonts.ready 触发的重分页修正即可。
    if (pages.value.length === 0) {
      console.warn(
        '[BookPageReader] paginate produced 0 pages, layout not ready',
      )
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
    finishReaderPerf(
      perf,
      20,
      `chapter=${props.chapterIndex}, pages=${pages.value.length}`,
    )
    paginating.value = false
  }
}

const restoreIndex = () => {
  if (pages.value.length === 0) return
  currentPageIndex.value = findPageIndexByPos(
    pages.value,
    props.initialChapterPos,
  )
  emit('progressChange', props.chapterIndex, currentPage.value?.startPos ?? 0)
}

let paginateTimer: ReturnType<typeof setTimeout> | null = null
// 动画进行中触发的重分页请求挂起，避免打断跨章/章内翻页动画：
// 旧实现回调里无条件 cancelFlipAnimation()，跨章动画刚启动就会被
// 目标章图片 load / document.fonts.ready 触发的重分页定时器取消，
// 表现为跨章翻页没有动画、停在旧章。动画结束后再补一次重分页。
let pendingRepaginateAfterFlip = false

const runRepaginate = () => {
  cancelFlipAnimation()
  const prevPos = currentPage.value?.startPos ?? props.initialChapterPos
  doPaginate()
  if (pages.value.length === 0) return
  currentPageIndex.value = findPageIndexByPos(pages.value, prevPos)
}

const scheduleRepaginate = (delay = 200) => {
  // 动画进行中只挂起请求，不安排/触发会取消动画的重分页定时器。
  if (animating.value || flipLock) {
    pendingRepaginateAfterFlip = true
    return
  }
  if (paginateTimer) clearTimeout(paginateTimer)
  paginateTimer = setTimeout(() => {
    paginateTimer = null
    // 定时器到期时可能已经进入动画（启动晚于调度），再次校验。
    if (animating.value || flipLock) {
      pendingRepaginateAfterFlip = true
      return
    }
    runRepaginate()
  }, delay)
}
const onWindowResize = () => {
  // 窗口变化时先同步页面高度，再重新分页，避免 F12 占用的底部/右侧
  // 区域被继续当作有效视口使用、关闭后留下空白。
  updateHeight()
  scheduleRepaginate()
}

// ---------- 高度同步：按视口 + 顶/底工具栏预留 ----------
const updateHeight = () => {
  // 书本翻页每页内容独立、不滚动也不重复，去掉滚动模式那 2x64px 占位的
  // 富余，只留小上下边距让页面更长，单页可容纳更多文本。
  pageHeight.value = Math.max(160, window.innerHeight - 40)
}

// ---------- 翻页 ----------
let flipLock = false
let flipTimer: ReturnType<typeof setTimeout> | null = null
let externalFlipFinished: ((pos: number) => void) | null = null
let externalFlipCanceled: (() => void) | null = null

const flipDuration = () => {
  if (reducedMotion.value) return 0
  return pageTurnEffectComputed.value === 'book' ? 440 : 260
}

type FlipFrameSample = {
  perfMark: ReturnType<typeof startReaderPerf>
  kind: 'inner' | 'external'
  direction: 'next' | 'prev'
  effect: 'slide' | 'book'
  expectedDuration: number
  lastFrameAt: number
  actualFrames: number
  maxFrameGap: number
  over32Ms: number
  over50Ms: number
  frameGaps: number[]
  requestId: number
}

let flipFrameSample: FlipFrameSample | null = null

const startFlipFrameSample = (
  kind: FlipFrameSample['kind'],
  direction: FlipFrameSample['direction'],
) => {
  const perfMark = startReaderPerf('web.book.frames')
  if (!perfMark) return
  const sample: FlipFrameSample = {
    perfMark,
    kind,
    direction,
    effect: pageTurnEffectComputed.value,
    expectedDuration: flipDuration(),
    lastFrameAt: performance.now(),
    actualFrames: 0,
    maxFrameGap: 0,
    over32Ms: 0,
    over50Ms: 0,
    frameGaps: [],
    requestId: 0,
  }
  const onFrame = (timestamp: number) => {
    if (flipFrameSample !== sample) return
    const gap = timestamp - sample.lastFrameAt
    if (sample.actualFrames > 0) sample.frameGaps.push(gap)
    sample.maxFrameGap = Math.max(sample.maxFrameGap, gap)
    if (gap > 32) sample.over32Ms++
    if (gap > 50) sample.over50Ms++
    sample.actualFrames++
    sample.lastFrameAt = timestamp
    sample.requestId = requestAnimationFrame(onFrame)
  }
  flipFrameSample = sample
  sample.requestId = requestAnimationFrame(onFrame)
}

const finishFlipFrameSample = (extra = '') => {
  const sample = flipFrameSample
  if (!sample) return
  flipFrameSample = null
  if (sample.requestId) cancelAnimationFrame(sample.requestId)
  const tailGap = performance.now() - sample.lastFrameAt
  if (tailGap > 32) {
    sample.maxFrameGap = Math.max(sample.maxFrameGap, tailGap)
    sample.over32Ms++
    if (tailGap > 50) sample.over50Ms++
  }

  // 用较快的 25% 帧间隔估算浏览器当前刷新节奏。这样偶发慢帧不会降低
  // 预计帧数，同时兼容 60/90/120Hz；没有足够样本时回退到 60Hz。
  const sortedGaps = sample.frameGaps
    .filter(gap => gap > 0 && gap <= 32)
    .sort((a, b) => a - b)
  const estimatedFrameGap =
    sortedGaps.length > 0
      ? sortedGaps[Math.floor((sortedGaps.length - 1) * 0.25)]
      : 1000 / 60
  const nominalFrameGap = Math.min(
    1000 / 30,
    Math.max(1000 / 240, estimatedFrameGap),
  )
  const expectedFrames = Math.max(
    1,
    Math.round(sample.expectedDuration / nominalFrameGap),
  )
  const droppedFrames = Math.max(0, expectedFrames - sample.actualFrames)
  const suffix = [
    `type=${sample.kind}`,
    `direction=${sample.direction}`,
    `effect=${sample.effect}`,
    `actualFrames=${sample.actualFrames}`,
    `expectedFrames=${expectedFrames}`,
    `droppedFrames=${droppedFrames}`,
    `maxFrameGap=${sample.maxFrameGap.toFixed(1)}ms`,
    `over32ms=${sample.over32Ms}`,
    `over50ms=${sample.over50Ms}`,
    extra,
  ]
    .filter(Boolean)
    .join(', ')
  finishReaderPerf(sample.perfMark, 0, suffix)
}

const cancelFlipAnimation = () => {
  if (flipTimer) clearTimeout(flipTimer)
  const onCancel = externalFlipCanceled
  finishFlipFrameSample('status=canceled')
  flipTimer = null
  targetPageIndex.value = null
  targetExternalPage.value = null
  targetExternalChapterIndex.value = null
  externalFlipFinished = null
  externalFlipCanceled = null
  animating.value = false
  flipLock = false
  keyboardTurnBuffer.clear()
  // 跨章动画取消后父组件不会再重挂，丢弃挂起的重分页避免误触发。
  pendingRepaginateAfterFlip = false
  onCancel?.()
}

const finishFlip = (nextIndex: number) => {
  finishFlipFrameSample(`from=${currentPageIndex.value}, to=${nextIndex}`)
  currentPageIndex.value = nextIndex
  targetPageIndex.value = null
  animating.value = false
  flipLock = false
  emit('progressChange', props.chapterIndex, currentPage.value?.startPos ?? 0)
  // 章内翻页动画期间被挂起的重分页（字号/字体/图片加载等）此时补做一次，
  // 把当前进度恢复到对应页。跨章动画走 finishExternalFlip，结束后父组件重挂，
  // 不需要在此补分页。
  if (pendingRepaginateAfterFlip) {
    pendingRepaginateAfterFlip = false
    scheduleRepaginate(0)
  }
  keyboardTurnBuffer.flush()
}

const finishExternalFlip = () => {
  const pos = targetExternalPage.value?.startPos ?? 0
  const onFinished = externalFlipFinished
  finishFlipFrameSample(
    `chapter=${targetExternalChapterIndex.value}, pos=${pos}`,
  )
  flipTimer = null
  targetPageIndex.value = null
  targetExternalPage.value = null
  targetExternalChapterIndex.value = null
  externalFlipFinished = null
  externalFlipCanceled = null
  animating.value = false
  flipLock = false
  keyboardTurnBuffer.clear()
  // 跨章动画结束后父组件 switchBookChapter 会重挂本组件重新分页，
  // 丢弃挂起的重分页请求，避免重挂后误用旧章上下文。
  pendingRepaginateAfterFlip = false
  onFinished?.(pos)
}

const startFlip = (nextIndex: number, direction: 'next' | 'prev') => {
  if (flipLock || paginating.value || pages.value.length === 0) return
  flipDirection.value = direction
  if (reducedMotion.value) {
    finishFlip(nextIndex)
    return
  }
  flipLock = true
  startFlipFrameSample('inner', direction)
  targetPageIndex.value = nextIndex
  animating.value = true
  if (flipTimer) clearTimeout(flipTimer)
  flipTimer = setTimeout(() => {
    flipTimer = null
    finishFlip(nextIndex)
  }, flipDuration())
}

const startExternalFlip = (
  chapterIndex: number,
  page: BookPage,
  direction: 'next' | 'prev',
  callbacks: ExternalFlipCallbacks = {},
): boolean => {
  if (
    flipLock ||
    paginating.value ||
    pages.value.length === 0 ||
    !currentPage.value
  ) {
    return false
  }
  flipDirection.value = direction
  if (reducedMotion.value) {
    callbacks.onFinished?.(chapterIndex, page.startPos)
    return true
  }
  flipLock = true
  startFlipFrameSample('external', direction)
  targetPageIndex.value = null
  targetExternalPage.value = page
  targetExternalChapterIndex.value = chapterIndex
  externalFlipFinished = pos => callbacks.onFinished?.(chapterIndex, pos)
  externalFlipCanceled = callbacks.onCancel ?? null
  animating.value = true
  if (flipTimer) clearTimeout(flipTimer)
  flipTimer = setTimeout(() => {
    finishExternalFlip()
  }, flipDuration())
  return true
}

const flipToChapter = (
  chapter: ChapterPageSource,
  initialPos: number,
  direction: 'next' | 'prev',
  callbacks: ExternalFlipCallbacks = {},
): boolean => {
  if (flipLock || paginating.value || pages.value.length === 0) return false
  let targetPages: BookPage[] = []
  const perf = startReaderPerf('web.book.targetPaginate')
  try {
    targetPages = paginateChapter(chapter.content, chapter.title)
  } catch (e) {
    console.error('[BookPageReader] target chapter paginate failed', e)
    return false
  } finally {
    finishReaderPerf(
      perf,
      20,
      `chapter=${chapter.index}, pages=${targetPages?.length ?? 0}`,
    )
  }
  if (targetPages.length === 0) return false
  const nextIndex = findPageIndexByPos(targetPages, initialPos)
  const page = targetPages[nextIndex]
  if (!page) return false
  return startExternalFlip(chapter.index, page, direction, callbacks)
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
let touchStartTime = 0
let touchActive = false
// 长按选词和横向翻页共用同一组触摸事件：超过短按窗口或已有选区时，
// 把手势交还给浏览器，避免拖动选择手柄被误判成翻页。
const touchSelectionHoldMs = 350

const hasTextSelection = () => {
  const selection = window.getSelection()
  return Boolean(
    selection && selection.rangeCount > 0 && !selection.isCollapsed,
  )
}

const isSelectionGesture = () =>
  hasTextSelection() ||
  performance.now() - touchStartTime >= touchSelectionHoldMs

const onTouchStart = (e: TouchEvent) => {
  if (e.touches.length !== 1 || hasTextSelection()) {
    touchActive = false
    return
  }
  const t = e.touches[0]
  touchStartX = t.clientX
  touchStartY = t.clientY
  touchStartTime = performance.now()
  touchActive = true
}
const onTouchMove = (e: TouchEvent) => {
  if (!touchActive) return
  if (isSelectionGesture()) {
    touchActive = false
    return
  }
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
  if (isSelectionGesture()) return
  const t = e.changedTouches[0]
  const dx = t.clientX - touchStartX
  const dy = t.clientY - touchStartY
  if (Math.abs(dx) > 40 && Math.abs(dx) > Math.abs(dy)) {
    if (dx < 0) flipNext()
    else flipPrev()
  }
}
const onTouchCancel = () => {
  touchActive = false
}
const onReaderClick = (e: MouseEvent) => {
  if (hasTextSelection()) e.stopPropagation()
}

// 键盘
const onKey = (e: KeyboardEvent) => {
  if (!initialized) return
  const direction: PageTurnDirection | null =
    e.key === 'ArrowRight' ? 'next' : e.key === 'ArrowLeft' ? 'prev' : null
  if (!direction) return
  e.stopPropagation()
  e.preventDefault()
  keyboardTurnBuffer.request(direction, flipLock)
}

function turnPageByKeyboard(direction: PageTurnDirection) {
  if (direction === 'next') flipNext()
  else flipPrev()
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
  document.fonts?.ready
    .then(() => scheduleRepaginate(60))
    .catch(() => undefined)
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
  keyboardTurnBuffer.clear()
  cancelFlipAnimation()
  if (paginateTimer) clearTimeout(paginateTimer)
  measureApi.reset()
})

defineExpose({ flipNext, flipPrev, flipToChapter, currentPageIndex, pages })
</script>

<style lang="scss" scoped>
.book-page-reader {
  position: relative;
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;

  .bp-stage {
    position: relative;
    width: var(--bp-page-width);
    height: var(--bp-page-height);
    isolation: isolate;
    contain: layout paint;
  }

  .bp-page {
    position: absolute;
    inset: 0;
    box-sizing: border-box;
    padding: 0;
    overflow: hidden;
    background: var(--bp-page-bg);
    z-index: 1;

    .bp-page-inner {
      font-size: var(--bp-font-size);
      line-height: var(--bp-line);
      font-family: var(--bp-font-family);
      -webkit-user-select: text;
      user-select: text;
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

  // 固定纸质书效果：不跟随手指，底页保持稳定，只让翻动页和页边阴影运动。
  .bp-stage.bp-book-next {
    .bp-page {
      will-change: clip-path;
    }

    .bp-page-current {
      z-index: 3;
      animation: bp-book-current-next 0.44s cubic-bezier(0.32, 0.02, 0.18, 1)
        forwards;

      .bp-page-shade {
        width: 36%;
        left: 0;
        right: auto;
        background:
          linear-gradient(
            90deg,
            transparent,
            rgba(0, 0, 0, 0.28) 55%,
            rgba(255, 255, 255, 0.2) 74%,
            transparent
          ),
          linear-gradient(90deg, transparent, rgba(0, 0, 0, 0.1));
        animation: bp-book-edge-next 0.44s cubic-bezier(0.32, 0.02, 0.18, 1)
          forwards;
        will-change: transform, opacity;
      }
    }

    .bp-page-target {
      z-index: 1;
      animation: bp-book-target-next 0.44s cubic-bezier(0.32, 0.02, 0.18, 1)
        forwards;

      .bp-page-shade {
        background: linear-gradient(
          90deg,
          rgba(0, 0, 0, 0.18),
          transparent 42%
        );
        animation: bp-book-target-shade 0.44s ease-out forwards;
      }
    }
  }

  .bp-stage.bp-book-prev {
    .bp-page {
      will-change: clip-path;
    }

    .bp-page-current {
      z-index: 1;
      animation: bp-book-current-prev 0.44s cubic-bezier(0.32, 0.02, 0.18, 1)
        forwards;

      .bp-page-shade {
        background: linear-gradient(
          270deg,
          rgba(0, 0, 0, 0.16),
          transparent 46%
        );
        animation: bp-book-target-shade 0.44s ease-out forwards;
      }
    }

    .bp-page-target {
      z-index: 3;
      animation: bp-book-target-prev 0.44s cubic-bezier(0.32, 0.02, 0.18, 1)
        forwards;

      .bp-page-shade {
        width: 36%;
        left: 0;
        right: auto;
        background:
          linear-gradient(
            90deg,
            transparent,
            rgba(255, 255, 255, 0.22) 28%,
            rgba(0, 0, 0, 0.24) 55%,
            transparent
          ),
          linear-gradient(90deg, rgba(0, 0, 0, 0.1), transparent);
        animation: bp-book-edge-prev 0.44s cubic-bezier(0.32, 0.02, 0.18, 1)
          forwards;
        will-change: transform, opacity;
      }
    }
  }

  // 固定滑动效果：触发后直接左右移入/移出，不做跟手位移。
  .bp-stage.bp-slide-next {
    .bp-page {
      will-change: transform;
    }

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
    .bp-page {
      will-change: transform;
    }

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
    font:
      24px / 32px PingFangSC-Regular,
      HelveticaNeue-Light,
      'Helvetica Neue Light',
      'Microsoft YaHei',
      sans-serif;
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
  font:
    24px / 32px PingFangSC-Regular,
    HelveticaNeue-Light,
    'Helvetica Neue Light',
    'Microsoft YaHei',
    sans-serif;
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
  color: #262626;
}
.night .bp-page {
  color: #666;
}

@keyframes bp-book-current-next {
  0% {
    clip-path: inset(0 0 0 0);
  }
  100% {
    clip-path: inset(0 100% 0 0);
  }
}

@keyframes bp-book-target-next {
  0% {
    clip-path: inset(0 0 0 100%);
  }
  100% {
    clip-path: inset(0 0 0 0);
  }
}

@keyframes bp-book-current-prev {
  0% {
    clip-path: inset(0 0 0 0);
  }
  100% {
    clip-path: inset(0 0 0 100%);
  }
}

@keyframes bp-book-target-prev {
  0% {
    clip-path: inset(0 100% 0 0);
  }
  100% {
    clip-path: inset(0 0 0 0);
  }
}

@keyframes bp-book-edge-next {
  0% {
    opacity: 0.1;
    transform: translateX(186%);
  }
  46% {
    opacity: 0.82;
  }
  92% {
    opacity: 0.42;
  }
  100% {
    opacity: 0;
    transform: translateX(-42%);
  }
}

@keyframes bp-book-edge-prev {
  0% {
    opacity: 0;
    transform: translateX(-42%);
  }
  12% {
    opacity: 0.44;
  }
  56% {
    opacity: 0.82;
  }
  100% {
    opacity: 0.08;
    transform: translateX(186%);
  }
}

@keyframes bp-book-target-shade {
  0% {
    opacity: 0.36;
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
