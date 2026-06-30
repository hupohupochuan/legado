<template>
  <div
    class="book-page-reader"
    ref="rootRef"
    :style="rootStyle"
    :data-effect="pageTurnEffectComputed"
    :class="{ night: isNight, day: !isNight }"
    @click="onContainerClick"
    @touchstart="onTouchStart"
    @touchmove="onTouchMove"
    @touchend="onTouchEnd"
  >
    <div class="bp-stage" ref="stageRef">
      <Transition :name="transitionName">
        <div
          v-if="currentPage"
          class="bp-page"
          :key="currentPageKey"
          :style="pageStyle(currentPage)"
        >
          <div class="bp-page-inner" v-html="renderBlocks(currentPage.blocks)"></div>
        </div>
      </Transition>
    </div>

    <div class="bp-hint" v-if="paginating">分页中…</div>

    <!-- 隐藏测量容器：宽高与可见书页一致，用来做分页 DOM 测量 -->
    <div
      class="bp-measure"
      ref="measureRef"
      :style="measureStyle"
      aria-hidden="true"
    ></div>

    <!-- 翻页热区（点击区域），覆盖在 stage 上但不阻挡工具栏点击 -->
    <div class="bp-tap bp-tap--left" @click.stop="onTapLeft"></div>
    <div class="bp-tap bp-tap--center" @click="onTapCenter"></div>
    <div class="bp-tap bp-tap--right" @click.stop="onTapRight"></div>
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
let initialized = false
let fallbackEmitted = false

const currentPage = computed(() => pages.value[currentPageIndex.value])
const currentPageKey = computed(
  () => `${props.chapterIndex}:${currentPageIndex.value}:${pages.value.length}`,
)

const transitionName = computed(() => {
  if (reducedMotion.value) return 'bp-none'
  return (props.pageTurnEffect ?? 'book') === 'book' ? 'bp-book' : 'bp-slide'
})
const pageTurnEffectComputed = computed(
  () => props.pageTurnEffect ?? 'book',
)

// 视口（书页）尺寸：高度 = stage 高度，宽度 = readWidth（含 padding 内缩）
const pageWidth = computed(() => {
  if (store.miniInterface) return window.innerWidth - 40
  return props.readWidth - 130
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
    const prevPos = currentPage.value?.startPos ?? props.initialChapterPos
    doPaginate()
    if (pages.value.length === 0) return
    currentPageIndex.value = findPageIndexByPos(pages.value, prevPos)
  }, delay)
}
const onWindowResize = () => scheduleRepaginate()

// ---------- 高度同步：按视口 + 顶/底工具栏预留 ----------
const updateHeight = () => {
  // 阅读页 .content 顶部/底部分别有 64px 占位条 (top-bar/bottom-bar)，
  // 书本模式沿用同样净高，使总文档高度 = 100vh 不产生额外滚动。
  pageHeight.value = Math.max(160, window.innerHeight - 128)
}

// ---------- 翻页 ----------
let flipLock = false
let flipUnlockTimer: ReturnType<typeof setTimeout> | null = null

const afterFlip = (pos: number) => {
  emit('progressChange', props.chapterIndex, pos)
  // 翻页动画期间锁住，避免连点导致状态错乱
  const dur = reducedMotion.value
    ? 0
    : (props.pageTurnEffect ?? 'book') === 'book'
      ? 450
      : 280
  if (flipUnlockTimer) clearTimeout(flipUnlockTimer)
  flipLock = true
  flipUnlockTimer = setTimeout(() => {
    flipLock = false
    flipUnlockTimer = null
  }, dur)
}

const flipNext = () => {
  if (flipLock || paginating.value || pages.value.length === 0) return
  if (currentPageIndex.value >= pages.value.length - 1) {
    emit('requestNextChapter')
    return
  }
  currentPageIndex.value += 1
  afterFlip(currentPage.value?.startPos ?? 0)
}

const flipPrev = () => {
  if (flipLock || paginating.value || pages.value.length === 0) return
  if (currentPageIndex.value <= 0) {
    emit('requestPrevChapter')
    return
  }
  currentPageIndex.value -= 1
  afterFlip(currentPage.value?.startPos ?? 0)
}

// ---------- 交互 ----------
const onContainerClick = (_e: MouseEvent) => {
  // 由 bp-tap 覆盖层处理
}

const onTapLeft = () => flipPrev()
const onTapRight = () => flipNext()
const onTapCenter = () => {
  // 中间区域：交给父级显示工具栏（不翻页）
}

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

const init = () => {
  updateHeight()
  doPaginate()
  if (pages.value.length === 0) return
  initialized = true
  restoreIndex()
  emit('pageReady')
}

onMounted(() => {
  try {
    init()
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
  } catch (e) {
    console.error('[BookPageReader] init failed', e)
    if (!fallbackEmitted) {
      fallbackEmitted = true
      toast.warning('书本翻页初始化失败，已切换为滚动阅读')
      emit('fallbackToScroll')
    }
  }
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
  if (flipUnlockTimer) clearTimeout(flipUnlockTimer)
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
    will-change: transform, opacity;

    .bp-page-inner {
      font-size: var(--bp-font-size);
      line-height: var(--bp-line);
      font-family: var(--bp-font-family);
    }
  }

  // 书本翻页：绕左边缘旋转 + 淡出
  .bp-book-enter-active,
  .bp-book-leave-active {
    transition: transform 0.45s ease-in-out, opacity 0.45s ease-in-out;
    transform-origin: left center;
  }
  .bp-book-enter-from {
    transform: rotateY(180deg);
    opacity: 0;
  }
  .bp-book-leave-to {
    transform: rotateY(-180deg);
    opacity: 0;
  }

  // 滑动翻页：横向平移
  .bp-slide-enter-active,
  .bp-slide-leave-active {
    transition: transform 0.28s ease-in-out, opacity 0.28s ease-in-out;
  }
  .bp-slide-enter-from {
    transform: translateX(100%);
    opacity: 0;
  }
  .bp-slide-leave-to {
    transform: translateX(-100%);
    opacity: 0;
  }

  // 无动画
  .bp-none-enter-active,
  .bp-none-leave-active {
    transition: none;
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

  .bp-tap {
    position: absolute;
    top: 0;
    height: 100%;
    z-index: 5;
    cursor: pointer;
  }
  .bp-tap--left {
    left: 0;
    width: 28%;
  }
  .bp-tap--center {
    left: 28%;
    width: 44%;
  }
  .bp-tap--right {
    right: 0;
    width: 28%;
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
</style>