<template>
  <div
    class="chapter-wrapper"
    :style="bodyTheme"
    :class="{ night: isNight, day: !isNight }"
    @click="showToolBar = !showToolBar"
  >
    <div class="tool-bar" :style="leftBarTheme">
      <div class="tools">
        <div class="tool-icon" @click.stop="popCataVisible = !popCataVisible">
          <div class="iconfont">&#58905;</div>
          <div class="icon-text">目录</div>
        </div>
        <div class="tool-icon" @click.stop="readSettingsVisible = !readSettingsVisible">
          <div class="iconfont">&#58971;</div>
          <div class="icon-text">设置</div>
        </div>
        <div class="tool-icon" @click="toShelf">
          <div class="iconfont">&#58892;</div>
          <div class="icon-text">书架</div>
        </div>
        <div class="tool-icon" :class="{ 'no-point': false }" @click="toTop">
          <div class="iconfont">&#58914;</div>
          <div class="icon-text">顶部</div>
        </div>
        <div class="tool-icon" :class="{ 'no-point': false }" @click="toBottom">
          <div class="iconfont">&#58915;</div>
          <div class="icon-text">底部</div>
        </div>
      </div>
    </div>
    <div class="read-bar" :style="rightBarTheme">
      <div class="tools">
        <div class="tool-icon" :class="{ 'no-point': readBarDisabled }" @click="onReadBarPrev">
          <div class="iconfont">&#58920;</div>
          <span v-if="miniInterface">{{ activeBookMode ? '上一页' : '上一章' }}</span>
        </div>
        <div class="tool-icon" :class="{ 'no-point': readBarDisabled }" @click="onReadBarNext">
          <span v-if="miniInterface">{{ activeBookMode ? '下一页' : '下一章' }}</span>
          <div class="iconfont">&#58913;</div>
        </div>
      </div>
    </div>

    <div v-if="popCataVisible" class="web-dialog-overlay" @click.self="popCataVisible = false">
      <div class="web-dialog popup" :style="{ background: popupColor, maxWidth: popupWidth + 'px' }">
        <PopCatalog @getContent="getContent" />
      </div>
    </div>

    <div v-if="readSettingsVisible" class="web-dialog-overlay" @click.self="readSettingsVisible = false">
      <div class="web-dialog popup" :style="{ background: popupColor, maxWidth: popupWidth + 'px' }">
        <read-settings />
      </div>
    </div>

    <div class="chapter" ref="content" :class="{ 'book-mode': activeBookMode }" :style="chapterTheme">
      <div class="content" :class="{ 'book-mode': activeBookMode }">
        <div class="top-bar" ref="top"></div>
        <template v-if="showContent && activeBookMode && currentChapterData">
          <book-page-reader
            ref="bookReaderRef"
            :key="'bp-' + currentChapterData.index + '-' + bookReaderSeed"
            :chapterIndex="currentChapterData.index"
            :contents="currentChapterData.content"
            :title="currentChapterData.title"
            :spacing="store.config.spacing"
            :fontSize="fontSize"
            :fontFamily="fontFamily"
            :readWidth="store.config.readWidth"
            :pageTurnEffect="store.config.pageTurnEffect"
            :initialChapterPos="bookInitialPos"
            @progressChange="onReadedLengthChange"
            @requestNextChapter="onBookRequestNextChapter"
            @requestPrevChapter="onBookRequestPrevChapter"
            @fallbackToScroll="onBookFallbackToScroll"
          />
        </template>
        <template v-else>
          <div
            v-for="data in chapterData"
            :key="data.index"
            ref="chapter"
          >
            <chapter-content
              ref="chapterRef"
              :chapterIndex="data.index"
              :contents="data.content"
              :title="data.title"
              :spacing="store.config.spacing"
              :fontSize="fontSize"
              :fontFamily="fontFamily"
              @readedLengthChange="onReadedLengthChange"
              v-if="showContent"
            />
          </div>
        </template>
        <div class="loading" ref="loading" v-if="!activeBookMode"></div>
        <div class="bottom-bar" ref="bottom"></div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import jump from '@/plugins/jump'
import settings from '@/config/themeConfig'
import API, { backendConnectionErrorMessage, isBackendConnectionError } from '@api'
import { useLoading } from '@/hooks/loading'
import { useThrottleFn } from '@vueuse/shared'
import { isNullOrBlank } from '@/utils/utils'
import {
  finishReaderPerf,
  startReaderPerf,
} from '@/utils/readerPerformance'
import { toast } from '@/utils/toast'
import { msgbox } from '@/utils/toast'

const content = ref()
const { isLoading, loadingWrapper } = useLoading(content, '正在获取信息')
const store = useBookStore()

const getChapterRequestErrorMessage = (err: unknown, fallback: string) => {
  if (isBackendConnectionError(err)) return backendConnectionErrorMessage
  return fallback
}

const {
  catalog,
  miniInterface,
  showContent,
  bookProgress,
  theme,
  isNight,
  popCataVisible,
} = storeToRefs(store)

const readSettingsVisible = ref(false)

const chapterPos = computed({
  get: () => store.readingBook.chapterPos,
  set: value => (store.readingBook.chapterPos = value),
})
const chapterIndex = computed({
  get: () => store.readingBook.chapterIndex,
  set: value => (store.readingBook.chapterIndex = value),
})
const isSeachBook = computed({
  get: () => store.readingBook.isSeachBook,
  set: value => (store.readingBook.isSeachBook = value),
})

let persistReadingBookTimer: ReturnType<typeof setTimeout> | null = null
const persistReadingBookNow = () => {
  const book = store.readingBook
  localStorage.setItem('readingRecent', JSON.stringify(book))
  sessionStorage.setItem('chapterIndex', book.chapterIndex.toString())
  sessionStorage.setItem('chapterPos', book.chapterPos.toString())
}
const persistReadingBookSoon = () => {
  if (persistReadingBookTimer !== null) return
  persistReadingBookTimer = setTimeout(() => {
    persistReadingBookTimer = null
    persistReadingBookNow()
  }, 800)
}
const flushReadingBookPersist = () => {
  if (persistReadingBookTimer !== null) {
    clearTimeout(persistReadingBookTimer)
    persistReadingBookTimer = null
  }
  persistReadingBookNow()
}
watch(
  () => store.readingBook,
  () => persistReadingBookSoon(),
  { deep: 1 },
)

const infiniteLoading = computed(() => store.config.infiniteLoading)
// Appending a chapter must not use the global loading mask; that mask covers the
// whole reader and shows as a white overlay at the end of each chapter.
const isAppendingChapter = ref(false)
let scrollObserver: IntersectionObserver | null
let prefetchObserver: IntersectionObserver | null
const loading = ref()
type ChapterData = { index: number; content: string[]; title: string }
const prefetchedChapters = new Map<number, ChapterData>()
const prefetchingChapters = new Set<number>()
const maxPrefetchedChapterCount = 2
let prefetchGeneration = 0
const clearPrefetchedChapters = () => {
  prefetchGeneration++
  prefetchedChapters.clear()
  prefetchingChapters.clear()
}
const trimPrefetchedChapters = () => {
  while (prefetchedChapters.size > maxPrefetchedChapterCount) {
    const firstKey = prefetchedChapters.keys().next().value
    if (firstKey === undefined) break
    prefetchedChapters.delete(firstKey)
  }
}
const fetchChapterData = async (index: number) => {
  const perf = startReaderPerf('web.chapter.fetch')
  let success = false
  try {
    const bookUrl = store.readingBook.bookUrl
    const chapter = catalog.value[index]
    if (!bookUrl || !chapter) throw new Error('章节信息为空')
    const { title, index: chapterIndex } = chapter
    const res = await API.getBookContent(bookUrl, chapterIndex)
    success = res.data.isSuccess
    if (res.data.isSuccess) {
      return {
        chapter: {
          index,
          content: res.data.data.split(/\n+/),
          title,
        } as ChapterData,
        isSuccess: true,
        errorMsg: '',
      }
    }
    return {
      chapter: {
        index,
        content: [res.data.errorMsg],
        title,
      } as ChapterData,
      isSuccess: false,
      errorMsg: res.data.errorMsg,
    }
  } finally {
    finishReaderPerf(perf, 50, `index=${index}, success=${success}`)
  }
}
const prefetchChapter = (index: number) => {
  if (
    index < 0 ||
    index >= catalog.value.length ||
    prefetchedChapters.has(index) ||
    prefetchingChapters.has(index)
  ) {
    return
  }
  const generation = prefetchGeneration
  prefetchingChapters.add(index)
  fetchChapterData(index)
    .then(({ chapter, isSuccess }) => {
      if (isSuccess && generation === prefetchGeneration) {
        prefetchedChapters.set(index, chapter)
        trimPrefetchedChapters()
      }
    })
    .catch(() => undefined)
    .finally(() => {
      prefetchingChapters.delete(index)
    })
}
const prefetchNextChapter = () => {
  const lastChapter = chapterData.value.slice(-1)[0]
  if (!lastChapter) return
  const nextIndex = lastChapter.index + 1
  if (catalog.value.length - 1 >= nextIndex) prefetchChapter(nextIndex)
}
const loadMore = () => {
  const lastChapter = chapterData.value.slice(-1)[0]
  if (!lastChapter) return
  const index = lastChapter.index
  if (catalog.value.length - 1 > index) {
    const nextIndex = index + 1
    const prefetched = prefetchedChapters.get(nextIndex)
    if (prefetched) {
      prefetchedChapters.delete(nextIndex)
      isAppendingChapter.value = true
      requestAnimationFrame(() => {
        chapterData.value.push(prefetched)
        isAppendingChapter.value = false
        prefetchNextChapter()
        store.saveBookProgress()
      })
      return
    }
    getContent(nextIndex, false)
      .then(() => {
        prefetchNextChapter()
        store.saveBookProgress()
      })
      .catch(() => undefined)
  }
}
const onReachBottom = (entries: IntersectionObserverEntry[]) => {
  if (isLoading.value || isAppendingChapter.value) return
  for (const { isIntersecting } of entries) {
    if (!isIntersecting) return
    loadMore()
  }
}
const onReachPrefetch = (entries: IntersectionObserverEntry[]) => {
  if (isLoading.value || isAppendingChapter.value) return
  for (const { isIntersecting } of entries) {
    if (!isIntersecting) return
    prefetchNextChapter()
  }
}
watchEffect(() => {
  // 书本翻页模式不使用无限滚动观察器，但保留预取缓存供跨章即时切换；
  // 仅滚动模式且未开启无限滚动时才清空预取。
  if (activeBookMode.value) {
    scrollObserver?.disconnect()
    prefetchObserver?.disconnect()
  } else if (!infiniteLoading.value) {
    scrollObserver?.disconnect()
    prefetchObserver?.disconnect()
    clearPrefetchedChapters()
  } else if (loading.value) {
    scrollObserver?.observe(loading.value)
    prefetchObserver?.observe(loading.value)
  }
})

const fontFamily = computed(() => {
  if (store.config.font >= 0) {
    return settings.fonts[store.config.font]
  }
  return store.config.customFontName
})
const fontSize = computed(() => {
  return store.config.fontSize + 'px'
})

const bodyColor = computed(() => settings.themes[theme.value].body)
const chapterColor = computed(() => settings.themes[theme.value].content)
const popupColor = computed(() => settings.themes[theme.value].popup)

const readWidth = computed(() => {
  if (!miniInterface.value) {
    return store.config.readWidth - 130 + 'px'
  } else {
    return window.innerWidth + 'px'
  }
})
const popupWidth = computed(() => {
  if (!miniInterface.value) {
    return store.config.readWidth - 33
  } else {
    return window.innerWidth - 33
  }
})
const bodyTheme = computed(() => {
  return { background: bodyColor.value }
})
const chapterTheme = computed(() => {
  // 书本翻页模式每页是独立新内容、不滚动，给 .chapter 更大的宽度（吃满
  // readWidth）配合更小的横向 padding，让单页正文比滚动模式更宽。
  const w =
    activeBookMode.value && !miniInterface.value
      ? store.config.readWidth + 'px'
      : readWidth.value
  return { background: chapterColor.value, width: w }
})
const showToolBar = ref(false)
const leftBarTheme = computed(() => {
  return {
    background: popupColor.value,
    marginLeft: miniInterface.value
      ? 0
      : -(store.config.readWidth / 2 + 68) + 'px',
    display: miniInterface.value && !showToolBar.value ? 'none' : 'block',
  }
})
const rightBarTheme = computed(() => {
  return {
    background: popupColor.value,
    marginRight: miniInterface.value
      ? 0
      : -(store.config.readWidth / 2 + 52) + 'px',
    display: miniInterface.value && !showToolBar.value ? 'none' : 'block',
  }
})

const onResize = () => {
  store.setMiniInterface(window.innerWidth < 776)
  const width = store.config.readWidth
  checkPageWidth(width)
}
const checkPageWidth = (readWidth: number) => {
  if (store.miniInterface) return
  if (readWidth < 640) store.config.readWidth = 640
  if (readWidth + 2 * 68 > window.innerWidth) store.config.readWidth -= 160
}
watch(
  () => store.config.readWidth,
  width => checkPageWidth(width),
)
const top = ref()
const bottom = ref()
const toTop = () => jump(top.value)
const toBottom = () => jump(bottom.value)

const router = useRouter()
const toShelf = () => router.push('/shelf')

const chapterData = ref<ChapterData[]>([])
const noPoint = ref(true)
const bookChapterSwitching = ref(false)
const readBarDisabled = computed(() => noPoint.value || bookChapterSwitching.value)

// ---------- 书本翻页模式 (Book page turn mode) ----------
// 运行时生效的模式以 store.activePageMode 为准；初始化失败时由 BookPageReader
// emit fallbackToScroll 切回滚动，不回写用户持久化配置。
const activeBookMode = computed(() => store.activePageMode === 'book')
const bookReaderRef = ref()
// 传给 BookPageReader 的初始 chapterPos；切章时按需设为对应章/末页位置
const bookInitialPos = ref(chapterPos.value)
// 用于强制 BookPageReader 重新挂载（切回书本模式后复用同一章节时也重分页）
const bookReaderSeed = ref(0)

const currentChapterData = computed<ChapterData | null>(() => {
  const idx = store.readingBook.chapterIndex
  return chapterData.value.find(d => d.index === idx) ?? null
})

// 书本翻页模式预取相邻章节：避免每次跨章都走 loading mask 重新拉取。
const prefetchBookAdjacent = (index: number) => {
  if (!activeBookMode.value) return
  prefetchChapter(index + 1)
  prefetchChapter(index - 1)
}

const resolveBookChapterForTurn = async (index: number): Promise<ChapterData> => {
  const cached =
    prefetchedChapters.get(index) ??
    chapterData.value.find(d => d.index === index)
  if (cached) return cached
  const { chapter, isSuccess, errorMsg } = await fetchChapterData(index)
  if (!isSuccess) {
    toast.error(errorMsg)
    throw new Error(errorMsg)
  }
  return chapter
}

const rememberBookChapter = (chapter: ChapterData) => {
  const existingIndex = chapterData.value.findIndex(d => d.index === chapter.index)
  if (existingIndex >= 0) {
    chapterData.value[existingIndex] = chapter
  } else {
    chapterData.value.push(chapter)
  }
  // 书本模式只需要当前章和相邻缓存，按目标章距离保留最近 3 章。
  if (chapterData.value.length > 3) {
    chapterData.value = chapterData.value
      .slice()
      .sort((a, b) => Math.abs(a.index - chapter.index) - Math.abs(b.index - chapter.index))
      .slice(0, 3)
      .sort((a, b) => a.index - b.index)
  }
}

// 书本翻页跨章即时切换：若目标章已在预取缓存/已加载，直接复用，不显示
// "正在获取信息" loading mask，只重挂 BookPageReader 重新分页。返回 true 表示命中。
const switchBookChapter = (
  targetIndex: number,
  initialPos: number,
  safePos: number,
  chapter?: ChapterData,
): boolean => {
  const cached =
    chapter ??
    prefetchedChapters.get(targetIndex) ??
    chapterData.value.find(d => d.index === targetIndex)
  if (!cached) return false
  const perf = startReaderPerf('web.book.switchCached')
  rememberBookChapter(cached)
  prefetchedChapters.delete(targetIndex)
  bookInitialPos.value = initialPos
  // 先更新持久化进度（safePos 是安全值，不会是哨兵），再重挂组件。
  saveReadingBookProgressToBrowser(targetIndex, safePos)
  bookReaderSeed.value++
  store.saveBookProgress()
  prefetchBookAdjacent(targetIndex)
  finishReaderPerf(perf, 0, `index=${targetIndex}`)
  return true
}

const finishBookChapterSwitching = () => {
  bookChapterSwitching.value = false
  noPoint.value = false
}

const turnBookChapter = async (direction: 'next' | 'prev') => {
  if (bookChapterSwitching.value) return
  const targetIndex =
    direction === 'next'
      ? store.readingBook.chapterIndex + 1
      : store.readingBook.chapterIndex - 1
  if (catalog.value[targetIndex] === undefined) {
    toast.error(direction === 'next' ? '本章是最后一章' : '本章是第一章')
    return
  }

  bookChapterSwitching.value = true
  noPoint.value = true
  const initialPos = direction === 'next' ? 0 : Number.MAX_SAFE_INTEGER
  const fallbackSafePos = direction === 'next' ? 0 : 0

  try {
    const targetChapter = await resolveBookChapterForTurn(targetIndex)
    toast.info(direction === 'next' ? '下一章' : '上一章')
    const started =
      bookReaderRef.value?.flipToChapter?.(
        targetChapter,
        initialPos,
        direction,
        {
          onFinished: (_chapterIndex: number, pos: number) => {
            switchBookChapter(targetIndex, pos, pos, targetChapter)
            finishBookChapterSwitching()
          },
          onCancel: finishBookChapterSwitching,
        },
      ) === true

    if (!started) {
      if (!switchBookChapter(targetIndex, initialPos, fallbackSafePos, targetChapter)) {
        getContent(targetIndex, true, fallbackSafePos, initialPos)
      }
      finishBookChapterSwitching()
    }
  } catch (err) {
    if (isBackendConnectionError(err)) {
      toast.error(backendConnectionErrorMessage)
    }
    finishBookChapterSwitching()
  }
}

// 底部 read-bar 上一章/下一章按钮：书本翻页模式下委托给 BookPageReader
// 翻页（首尾页再由其 emit requestNext/PrevChapter 触发跨章），保持改动前
// 仅底部按钮可点击翻页的范围，不放大到整页热区。
const onReadBarPrev = () => {
  if (activeBookMode.value) bookReaderRef.value?.flipPrev()
  else toPreChapter()
}
const onReadBarNext = () => {
  if (activeBookMode.value) bookReaderRef.value?.flipNext()
  else toNextChapter()
}

const onBookRequestNextChapter = () => {
  turnBookChapter('next')
}

const onBookRequestPrevChapter = () => {
  turnBookChapter('prev')
}

const onBookFallbackToScroll = () => {
  store.fallbackToScroll()
  // 复用当前章节内容切回滚动渲染；保持当前阅读进度
  bookReaderSeed.value++
}
const getContent = (
  index: number,
  reloadChapter = true,
  targetChapterPos = 0,
  // 书本翻页模式专用：传给 BookPageReader 的初始页定位参数。
  // 默认与持久化 chapterPos 一致；上一章"落到最后一页"场景传哨兵
  // (Number.MAX_SAFE_INTEGER)，仅在组件内部用于定位，不会进入 readingBook。
  initialPos?: number,
) => {
  const perf = startReaderPerf(
    reloadChapter ? 'web.chapter.switch' : 'web.chapter.append',
  )
  const previousChapterData = chapterData.value.slice()
  const previousIndex = chapterIndex.value
  const previousPos = chapterPos.value
  const previousBookInitialPos = bookInitialPos.value
  const previousShowContent = showContent.value
  if (reloadChapter) {
    clearPrefetchedChapters()
    store.setShowContent(false)
    jump(top.value, { duration: 0 })
    saveReadingBookProgressToBrowser(index, targetChapterPos)
    // 书本翻页模式：整章重载时把初始页定位参数对齐
    if (activeBookMode.value) bookInitialPos.value = initialPos ?? targetChapterPos
    chapterData.value = []
  }

  const request = fetchChapterData(index).then(
    ({ chapter, isSuccess, errorMsg }) => {
      chapterData.value.push(chapter)
      if (reloadChapter) {
        toChapterPos(targetChapterPos)
        if (infiniteLoading.value) prefetchChapter(index + 1)
        // 书本模式跨章后预取相邻章节，供下次跨章即时切换，避免再次弹 loading mask
        if (activeBookMode.value) prefetchBookAdjacent(index)
      }
      if (!isSuccess) {
        toast.error(errorMsg)
      }
      store.setContentLoading(true)
      noPoint.value = false
      store.setShowContent(true)
      if (!isSuccess) {
        throw new Error(errorMsg)
      }
    },
    err => {
      const errorMsg = getChapterRequestErrorMessage(
        err,
        reloadChapter ? '获取章节内容失败！' : '获取下一章内容失败！',
      )
      if (reloadChapter) {
        if (previousChapterData.length > 0) {
          chapterData.value = previousChapterData
          saveReadingBookProgressToBrowser(previousIndex, previousPos)
          if (activeBookMode.value) bookInitialPos.value = previousBookInitialPos
          store.setShowContent(previousShowContent)
        } else {
          chapterData.value.push({
            index,
            content: [errorMsg],
            title: catalog.value[index]?.title || '',
          })
          store.setShowContent(true)
        }
      } else {
        toast.error(errorMsg)
      }
      noPoint.value = false
      store.setContentLoading(true)
      if (reloadChapter) toast.error(errorMsg)
      throw err
    },
  )
  const measuredRequest = request.finally(() => {
    finishReaderPerf(perf, 50, `index=${index}, reload=${reloadChapter}`)
  })
  if (reloadChapter) return loadingWrapper(measuredRequest)
  // Keep infinite-scroll fetches invisible and only use this flag for de-duping.
  isAppendingChapter.value = true
  return measuredRequest.finally(() => {
    isAppendingChapter.value = false
  })
}

const chapter = ref()
const chapterRef = ref()
const toChapterPos = (pos: number) => {
  // 书本翻页模式由 BookPageReader 自行按 chapterPos 定位页，
  // 且此时模板不渲染 chapter-content，chapterRef 为 undefined。
  if (activeBookMode.value) return
  nextTick(() => {
    if (chapterRef.value && chapterRef.value.length === 1)
      chapterRef.value[0].scrollToReadedLength(pos)
  })
}

const saveBookProgressThrottle = useThrottleFn(
  () => store.saveBookProgress(),
  60000,
)
let lastReadedProgressKey = ''
// 跟踪上次保存进度的章节 index；章节切换时立即保存（绕过 60s 节流），
// 确保书本翻页上一章/下一章落到真实末页/首页位置后能立刻持久化。
let lastProgressIndex = -1

const onReadedLengthChange = (index: number, pos: number) => {
  const progressKey = `${index}:${pos}`
  if (lastReadedProgressKey === progressKey) return
  lastReadedProgressKey = progressKey
  saveReadingBookProgressToBrowser(index, pos)
  if (index !== lastProgressIndex) {
    lastProgressIndex = index
    store.saveBookProgress()
  } else {
    saveBookProgressThrottle()
  }
}

watchEffect(() => {
  document.title = catalog.value[chapterIndex.value]?.title || document.title
})

const saveReadingBookProgressToBrowser = (index: number, pos: number) => {
  chapterIndex.value = index
  chapterPos.value = pos
}

const onVisibilityChange = () => {
  const _bookProgress = bookProgress.value
  if (document.visibilityState == 'hidden' && _bookProgress) {
    flushReadingBookPersist()
    store.saveBookProgress(true)
  }
}

const toNextChapter = () => {
  const index = chapterIndex.value + 1
  if (typeof catalog.value[index] !== 'undefined') {
    store.setContentLoading(true)
    getContent(index)
      ?.then(() => {
        toast.info('下一章')
        store.saveBookProgress()
      })
      .catch(() => undefined)
  } else {
    toast.error('本章是最后一章')
  }
}
const toPreChapter = () => {
  const index = chapterIndex.value - 1
  if (typeof catalog.value[index] !== 'undefined') {
    store.setContentLoading(true)
    getContent(index)
      ?.then(() => {
        toast.info('上一章')
        store.saveBookProgress()
      })
      .catch(() => undefined)
  } else {
    toast.error('本章是第一章')
  }
}

let canJump = true
const handleKeyPress = (event: KeyboardEvent) => {
  if (!canJump) return
  switch (event.key) {
    case 'ArrowLeft':
      event.stopPropagation()
      event.preventDefault()
      // 书本模式下左右键由 BookPageReader 处理页面翻转，这里不再切章
      if (!activeBookMode.value) toPreChapter()
      break
    case 'ArrowRight':
      event.stopPropagation()
      event.preventDefault()
      if (!activeBookMode.value) toNextChapter()
      break
    case 'ArrowUp':
      event.stopPropagation()
      event.preventDefault()
      if (document.documentElement.scrollTop === 0) {
        toast.warning('已到达页面顶部')
      } else {
        canJump = false
        jump(0 - document.documentElement.clientHeight + 100, {
          duration: store.config.jumpDuration,
          callback: () => (canJump = true),
        })
      }
      break
    case 'ArrowDown':
      event.stopPropagation()
      event.preventDefault()
      if (
        document.documentElement.clientHeight +
          document.documentElement.scrollTop ===
        document.documentElement.scrollHeight
      ) {
        toast.warning('已到达页面底部')
      } else {
        canJump = false
        jump(document.documentElement.clientHeight - 100, {
          duration: store.config.jumpDuration,
          callback: () => (canJump = true),
        })
      }
      break
  }
}

const ignoreKeyPress = (event: KeyboardEvent) => {
  if (event.key === 'ArrowUp' || event.key === 'ArrowDown') {
    event.preventDefault()
    event.stopPropagation()
  }
}

onMounted(async () => {
  await store.loadWebConfig()
  // 运行时阅读模式与用户配置保持同步；之后由 fallback 逻辑保护
  store.syncActivePageMode()
  const bookUrl = sessionStorage.getItem('bookUrl')
  const name = sessionStorage.getItem('bookName')
  const author = sessionStorage.getItem('bookAuthor')
  const chapterIndex = Number(sessionStorage.getItem('chapterIndex') || 0)
  const chapterPos = Number(sessionStorage.getItem('chapterPos') || 0)
  const isSeachBook = sessionStorage.getItem('isSeachBook') === 'true'
  if (isNullOrBlank(bookUrl) || isNullOrBlank(name) || author === null) {
    toast.warning('书籍信息为空，即将自动返回书架页面...')
    return setTimeout(toShelf, 500)
  }
  const book: typeof store.readingBook = {
    bookUrl: bookUrl!,
    name: name!,
    author: author!,
    chapterIndex,
    chapterPos,
    isSeachBook,
  }
  onResize()
  window.addEventListener('resize', onResize)
  loadingWrapper(
    store.loadWebCatalog(book).then(chapters => {
      store.setReadingBook(book)
      getContent(chapterIndex, true, chapterPos)
      window.addEventListener('keyup', handleKeyPress)
      window.addEventListener('keydown', ignoreKeyPress)
      document.addEventListener('visibilitychange', onVisibilityChange)
      scrollObserver = new IntersectionObserver(onReachBottom, {
        rootMargin: '-100% 0% 20% 0%',
      })
      prefetchObserver = new IntersectionObserver(onReachPrefetch, {
        rootMargin: '0% 0% 150% 0%',
      })
      if (infiniteLoading.value === true) scrollObserver.observe(loading.value)
      if (infiniteLoading.value === true) prefetchObserver.observe(loading.value)
      document.title = '...'
      document.title = (name as string) + ' | ' + chapters[chapterIndex].title
    }),
  )
})

onUnmounted(() => {
  window.removeEventListener('keyup', handleKeyPress)
  window.removeEventListener('keydown', ignoreKeyPress)
  window.removeEventListener('resize', onResize)
  document.removeEventListener('visibilitychange', onVisibilityChange)
  readSettingsVisible.value = false
  popCataVisible.value = false
  scrollObserver?.disconnect()
  scrollObserver = null
  prefetchObserver?.disconnect()
  prefetchObserver = null
  clearPrefetchedChapters()
  flushReadingBookPersist()
})

const addToBookShelfConfirm = async () => {
  const book = store.readingBook
  if (book.isSeachBook === true) {
    try {
      await msgbox.confirm(
        `是否将《${book.name}》放入书架？`,
        '放入书架',
        { closeOnHashChange: false },
      )
      isSeachBook.value = false
    } catch {
      await API.deleteBook(book)
      sessionStorage.removeItem('isSeachBook')
    }
  }
}
onBeforeRouteLeave(async (to, from, next) => {
  console.log('onBeforeRouteLeave')
  window.removeEventListener('keyup', handleKeyPress)
  flushReadingBookPersist()
  await addToBookShelfConfirm()
  next()
})
</script>

<style lang="scss" scoped>
.chapter-wrapper {
  padding: 0 4%;
  overflow-x: hidden;

  .no-point {
    pointer-events: none;
  }

  .tool-bar {
    position: fixed;
    top: 0;
    left: 50%;
    z-index: 100;

    .tools {
      display: flex;
      flex-direction: column;

      .tool-icon {
        font-size: 18px;
        width: 58px;
        height: 48px;
        text-align: center;
        padding-top: 12px;
        cursor: pointer;
        outline: none;

        .iconfont {
          font-family: iconfont;
          width: 16px;
          height: 16px;
          font-size: 16px;
          margin: 0 auto 6px;
        }

        .icon-text {
          font-size: 12px;
        }
      }
    }
  }

  .read-bar {
    position: fixed;
    bottom: 0;
    right: 50%;
    z-index: 100;

    .tools {
      display: flex;
      flex-direction: column;

      .tool-icon {
        font-size: 18px;
        width: 42px;
        height: 31px;
        padding-top: 12px;
        text-align: center;
        align-items: center;
        cursor: pointer;
        outline: none;
        margin-top: -1px;

        .iconfont {
          font-family: iconfont;
          width: 16px;
          height: 16px;
          font-size: 16px;
          margin: 0 auto 6px;
        }
      }
    }
  }

  .chapter {
    font-family: 'Microsoft YaHei', PingFangSC-Regular, HelveticaNeue-Light,
      'Helvetica Neue Light', sans-serif;
    text-align: left;
    padding: 0 65px;
    min-height: 100vh;
    width: 670px;
    margin: 0 auto;

    .content {
      font-size: 18px;
      line-height: 1.8;
      font-family: 'Microsoft YaHei', PingFangSC-Regular, HelveticaNeue-Light,
        'Helvetica Neue Light', sans-serif;

      .bottom-bar,
      .top-bar {
        height: 64px;
      }

      // 书本翻页模式：每页独立不滚动，缩小上下占位让单页更高
      &.book-mode {
        .bottom-bar,
        .top-bar {
          height: 20px;
        }
      }
    }

    // 书本翻页模式：缩小横向 padding 让单页正文更宽，配合 chapterTheme 的
    // 全 readWidth 宽度，整页吃满阅读宽度
    &.book-mode {
      padding: 0 20px;
    }
  }
}

.day {
  .popup {
    box-shadow: 0 2px 4px rgba(0, 0, 0, 0.12), 0 0 6px rgba(0, 0, 0, 0.04);
  }

  .tool-icon {
    border: 1px solid rgba(0, 0, 0, 0.1);
    margin-top: -1px;
    color: #000;

    .icon-text {
      color: rgba(0, 0, 0, 0.4);
    }
  }

  .chapter {
    border: 1px solid #d8d8d8;
    color: #262626;
  }
}

.night {
  .popup {
    box-shadow: 0 2px 4px rgba(0, 0, 0, 0.48), 0 0 6px rgba(0, 0, 0, 0.16);
  }

  .tool-icon {
    border: 1px solid #444;
    margin-top: -1px;
    color: #666;

    .icon-text {
      color: #666;
    }
  }

  .chapter {
    border: 1px solid #444;
    color: #666;
  }
}

@media screen and (max-width: 776px) {
  .chapter-wrapper {
    padding: 0;

    .tool-bar {
      left: 0;
      width: 100vw;
      margin-left: 0 !important;

      .tools {
        flex-direction: row;
        justify-content: space-between;

        .tool-icon {
          border: none;
        }
      }
    }

    .read-bar {
      right: 0;
      width: 100vw;
      margin-right: 0 !important;

      .tools {
        flex-direction: row;
        justify-content: space-between;
        padding: 0 15px;

        .tool-icon {
          border: none;
          width: auto;

          .iconfont {
            display: inline-block;
          }
        }
      }
    }

    .chapter {
      width: 100vw !important;
      padding: 0 20px;
      box-sizing: border-box;
    }
  }
}
</style>
