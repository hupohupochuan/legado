<template>
  <section
    class="book-content-search"
    aria-label="书籍全文搜索"
    @click.stop
    @keydown.stop
    @keyup.stop
  >
    <header class="search-header">
      <h2>全文搜索</h2>
      <button
        class="web-dialog__close"
        type="button"
        aria-label="关闭全文搜索"
        @click="requestClose"
      >
        &times;
      </button>
    </header>

    <form class="search-form" @submit.prevent="startSearch">
      <input
        v-model="query"
        class="web-input"
        type="search"
        maxlength="100"
        autocomplete="off"
        placeholder="输入要搜索的关键词"
        aria-label="全文搜索关键词"
      />
      <button v-if="!searching" class="web-btn web-btn--primary" type="submit">
        搜索
      </button>
      <button
        v-else
        class="web-btn web-btn--danger"
        type="button"
        @click="stopSearch"
      >
        停止
      </button>
    </form>

    <p v-if="isOnlineBook" class="search-notice">
      在线书仅搜索手机已缓存章节<span v-if="hasStarted"
        >（{{ searchableChapters }}/{{ totalChapters }} 章）</span
      >，不会联网下载未缓存内容。<span
        v-if="completed && skippedUncachedChapters > 0"
        >已跳过 {{ skippedUncachedChapters }} 个未缓存章节。</span
      >
    </p>

    <div v-if="hasSearched" class="search-summary" aria-live="polite">
      <span>
        已搜索 {{ scannedChapters }}/{{ searchableChapters }} 章<span
          v-if="results.length > 0"
          >，显示第 {{ displayedResultStart }}–{{ displayedResultEnd }} 条</span
        ><span v-else>，暂未找到结果</span>
      </span>
      <span v-if="searching" class="search-state">{{ searchingLabel }}</span>
      <span v-else-if="stopped" class="search-state">搜索已停止</span>
    </div>

    <p v-if="errorMessage" class="search-message search-message--error">
      {{ errorMessage }}
    </p>
    <p
      v-else-if="hasStarted && searchableChapters === 0"
      class="search-message"
    >
      {{ isOnlineBook ? '当前没有可搜索的缓存章节' : '当前没有可搜索的章节' }}
    </p>
    <p
      v-else-if="legacyTruncated"
      class="search-message search-message--warning"
    >
      结果超过 500 条，当前手机端不支持继续查看，请更新后重试。
    </p>
    <p v-else-if="hasMore" class="search-message search-message--warning">
      还有更多结果，可继续查看第 {{ nextResultStart }}–{{ nextResultEnd }} 条。
    </p>

    <virtual-list
      v-if="results.length > 0"
      class="search-results"
      data-key="searchResultKey"
      wrap-class="search-results__items"
      item-class="search-results__item"
      :data-sources="results"
      :data-component="BookContentSearchResult"
      :estimate-size="82"
      :keeps="16"
      :extra-props="resultExtraProps"
    />
    <div
      v-if="results.length > 0 && (currentPageIndex > 0 || hasMore)"
      class="search-pagination"
    >
      <button
        class="web-btn"
        type="button"
        :disabled="searching || currentPageIndex === 0"
        @click="showPreviousPage"
      >
        上一批
      </button>
      <span>第 {{ displayedResultStart }}–{{ displayedResultEnd }} 条</span>
      <button
        v-if="hasMore"
        class="web-btn web-btn--primary"
        type="button"
        :disabled="searching || !nextCursor"
        @click="showNextPage"
      >
        查看 {{ nextResultStart }}–{{ nextResultEnd }}
      </button>
    </div>
    <p
      v-if="
        results.length === 0 &&
        completed &&
        searchableChapters > 0 &&
        !errorMessage
      "
      class="search-empty"
    >
      没有找到匹配内容
    </p>
    <p v-if="!hasSearched" class="search-empty">
      搜索由手机端执行，本地书搜索全部章节，在线书仅搜索已有缓存。
    </p>
  </section>
</template>

<script setup lang="ts">
import VirtualList from 'vue3-virtual-scroll-list'
import API, {
  backendConnectionErrorMessage,
  type BookContentSearchHandle,
  type WebBookContentSearchResult,
} from '@api'
import BookContentSearchResult from './BookContentSearchResult.vue'

type SearchResultItem = WebBookContentSearchResult & {
  searchResultKey: string
}

type SearchPage = {
  results: SearchResultItem[]
  scannedChapters: number
  matchCount: number
  skippedUncachedChapters: number
  resultOffset: number
  hasMore: boolean
  nextCursor?: string
  legacyTruncated: boolean
}

const props = defineProps<{
  bookUrl: string
  isOnlineBook?: boolean
}>()

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'select', result: WebBookContentSearchResult): void
}>()

const pageSize = 500
const maxCachedPages = 3
const query = ref('')
const submittedQuery = ref('')
const results = ref<SearchResultItem[]>([])
const totalChapters = ref(0)
const searchableChapters = ref(0)
const scannedChapters = ref(0)
const matchCount = ref(0)
const skippedUncachedChapters = ref(0)
const searching = ref(false)
const hasSearched = ref(false)
const hasStarted = ref(false)
const completed = ref(false)
const stopped = ref(false)
const hasMore = ref(false)
const nextCursor = ref<string>()
const resultOffset = ref(0)
const currentPageIndex = ref(0)
const loadingPageIndex = ref<number>()
const legacyTruncated = ref(false)
const errorMessage = ref('')
const serverIsLocalBook = ref<boolean>()

let activeSearch: BookContentSearchHandle | null = null
let activeGeneration = 0
const pageCursors: Array<string | undefined> = [undefined]
const pageCache = new Map<number, SearchPage>()

const isOnlineBook = computed(() => {
  if (serverIsLocalBook.value !== undefined) return !serverIsLocalBook.value
  return props.isOnlineBook === true
})

const displayedResultStart = computed(() =>
  results.value.length > 0 ? resultOffset.value + 1 : 0,
)
const displayedResultEnd = computed(
  () => resultOffset.value + results.value.length,
)
const nextResultStart = computed(() => displayedResultEnd.value + 1)
const nextResultEnd = computed(() => displayedResultEnd.value + pageSize)
const searchingLabel = computed(() => {
  const pageIndex = loadingPageIndex.value
  if (pageIndex === undefined || pageIndex === currentPageIndex.value)
    return '搜索中…'
  const start = pageIndex * pageSize + 1
  return `正在加载第 ${start}–${start + pageSize - 1} 条…`
})

const selectResult = (result: WebBookContentSearchResult) => {
  emit('select', result)
}

const resultExtraProps = computed(() => ({
  keyword: submittedQuery.value,
  onSelect: selectResult,
}))

const clearPageState = () => {
  pageCursors.splice(0, pageCursors.length, undefined)
  pageCache.clear()
  currentPageIndex.value = 0
  loadingPageIndex.value = undefined
  resultOffset.value = 0
  hasMore.value = false
  nextCursor.value = undefined
  legacyTruncated.value = false
}

const resetSearchState = () => {
  results.value = []
  totalChapters.value = 0
  searchableChapters.value = 0
  scannedChapters.value = 0
  matchCount.value = 0
  skippedUncachedChapters.value = 0
  hasStarted.value = false
  completed.value = false
  stopped.value = false
  errorMessage.value = ''
  serverIsLocalBook.value = undefined
  clearPageState()
}

const cancelActiveSearch = (markStopped = true) => {
  if (!activeSearch && !searching.value) return
  activeGeneration++
  const handle = activeSearch
  activeSearch = null
  searching.value = false
  loadingPageIndex.value = undefined
  if (markStopped) stopped.value = true
  handle?.close()
}

const stopSearch = () => cancelActiveSearch(true)

const cachePage = (pageIndex: number, page: SearchPage) => {
  pageCache.delete(pageIndex)
  pageCache.set(pageIndex, page)
  while (pageCache.size > maxCachedPages) {
    const oldestPageIndex = pageCache.keys().next().value
    if (oldestPageIndex === undefined) break
    pageCache.delete(oldestPageIndex)
  }
}

const applyPage = (pageIndex: number, page: SearchPage) => {
  currentPageIndex.value = pageIndex
  results.value = page.results.slice()
  scannedChapters.value = page.scannedChapters
  matchCount.value = page.matchCount
  skippedUncachedChapters.value = page.skippedUncachedChapters
  resultOffset.value = page.resultOffset
  hasMore.value = page.hasMore
  nextCursor.value = page.nextCursor
  legacyTruncated.value = page.legacyTruncated
  completed.value = true
  stopped.value = false
  errorMessage.value = ''
}

const loadPage = (pageIndex: number, cursor?: string) => {
  const cached = pageCache.get(pageIndex)
  if (cached) {
    pageCache.delete(pageIndex)
    pageCache.set(pageIndex, cached)
    applyPage(pageIndex, cached)
    return
  }

  cancelActiveSearch(false)
  searching.value = true
  stopped.value = false
  errorMessage.value = ''
  loadingPageIndex.value = pageIndex
  const generation = ++activeGeneration
  const isCurrentSearch = () => generation === activeGeneration
  const incomingResults: SearchResultItem[] = []
  let incomingScannedChapters = pageIndex === 0 ? 0 : scannedChapters.value
  let incomingMatchCount = 0
  let incomingResultOffset = pageIndex * pageSize
  let targetIsVisible = pageIndex === 0 || results.value.length === 0

  const showIncomingPage = () => {
    targetIsVisible = true
    currentPageIndex.value = pageIndex
    results.value = incomingResults.slice()
    scannedChapters.value = incomingScannedChapters
    matchCount.value = incomingMatchCount
    resultOffset.value = incomingResultOffset
    hasMore.value = false
    nextCursor.value = undefined
    legacyTruncated.value = false
    completed.value = false
  }

  if (targetIsVisible) showIncomingPage()
  activeSearch = API.searchBookContent(
    {
      bookUrl: props.bookUrl,
      query: submittedQuery.value,
      maxResults: pageSize,
      ...(cursor ? { cursor } : {}),
    },
    {
      onStart: message => {
        if (!isCurrentSearch()) return
        hasStarted.value = true
        totalChapters.value = Math.max(0, message.totalChapters)
        searchableChapters.value = Math.max(0, message.searchableChapters)
        incomingResultOffset = Math.max(
          0,
          message.resultOffset ?? pageIndex * pageSize,
        )
        if (typeof message.isLocalBook === 'boolean')
          serverIsLocalBook.value = message.isLocalBook
        if (targetIsVisible) resultOffset.value = incomingResultOffset
      },
      onResults: message => {
        if (!isCurrentSearch()) return
        const room = pageSize - incomingResults.length
        if (room <= 0) return
        const startIndex = incomingResults.length
        const nextItems = message.items.slice(0, room).map((item, index) => ({
          ...item,
          searchResultKey: `${item.chapterIndex}:${item.queryIndexInChapter}:${incomingResultOffset + startIndex + index}`,
        }))
        incomingResults.push(...nextItems)
        incomingMatchCount = Math.max(
          incomingMatchCount,
          incomingResults.length,
        )
        if (!targetIsVisible) showIncomingPage()
        else {
          results.value = incomingResults.slice()
          matchCount.value = incomingMatchCount
        }
      },
      onProgress: message => {
        if (!isCurrentSearch()) return
        hasStarted.value = true
        incomingScannedChapters = Math.max(0, message.scannedChapters)
        incomingMatchCount = Math.max(0, message.matchCount)
        searchableChapters.value = Math.max(0, message.searchableChapters)
        if (targetIsVisible) {
          scannedChapters.value = incomingScannedChapters
          matchCount.value = incomingMatchCount
        }
      },
      onComplete: message => {
        if (!isCurrentSearch()) return
        if (!targetIsVisible) showIncomingPage()
        activeSearch = null
        searching.value = false
        loadingPageIndex.value = undefined
        completed.value = true
        stopped.value = false
        scannedChapters.value = Math.max(0, message.scannedChapters)
        matchCount.value = Math.max(0, message.matchCount)
        skippedUncachedChapters.value = Math.max(
          0,
          message.skippedUncachedChapters,
        )
        const continuationCursor =
          typeof message.nextCursor === 'string' && message.nextCursor
            ? message.nextCursor
            : undefined
        hasMore.value =
          (message.hasMore ?? message.truncated) && !!continuationCursor
        nextCursor.value = continuationCursor
        legacyTruncated.value = message.truncated && !continuationCursor
        resultOffset.value = incomingResultOffset
        const previousContinuationCursor = pageCursors[pageIndex + 1]
        const continuationChanged =
          previousContinuationCursor !== undefined &&
          previousContinuationCursor !== continuationCursor
        if (continuationCursor) {
          pageCursors[pageIndex + 1] = continuationCursor
          if (continuationChanged) pageCursors.splice(pageIndex + 2)
        } else {
          pageCursors.splice(pageIndex + 1)
        }
        if (!continuationCursor || continuationChanged) {
          for (const cachedPageIndex of pageCache.keys()) {
            if (cachedPageIndex > pageIndex) pageCache.delete(cachedPageIndex)
          }
        }
        cachePage(pageIndex, {
          results: incomingResults.slice(),
          scannedChapters: scannedChapters.value,
          matchCount: matchCount.value,
          skippedUncachedChapters: skippedUncachedChapters.value,
          resultOffset: resultOffset.value,
          hasMore: hasMore.value,
          nextCursor: nextCursor.value,
          legacyTruncated: legacyTruncated.value,
        })
      },
      onError: message => {
        if (!isCurrentSearch()) return
        activeSearch = null
        searching.value = false
        loadingPageIndex.value = undefined
        errorMessage.value = message.message || '搜索失败'
      },
      onSocketError: () => {
        if (!isCurrentSearch()) return
        activeSearch = null
        searching.value = false
        loadingPageIndex.value = undefined
        errorMessage.value = backendConnectionErrorMessage
      },
      onClose: (_event, expected) => {
        if (!isCurrentSearch() || expected || !searching.value) return
        activeSearch = null
        searching.value = false
        loadingPageIndex.value = undefined
        errorMessage.value = backendConnectionErrorMessage
      },
    },
  )
}

const startSearch = () => {
  const normalizedQuery = query.value.trim()
  if (!normalizedQuery) {
    errorMessage.value = '请输入搜索关键词'
    return
  }
  if (!props.bookUrl) {
    errorMessage.value = '当前书籍信息为空'
    return
  }

  cancelActiveSearch(false)
  resetSearchState()
  submittedQuery.value = normalizedQuery
  hasSearched.value = true
  loadPage(0)
}

const showPreviousPage = () => {
  if (searching.value || currentPageIndex.value <= 0) return
  const pageIndex = currentPageIndex.value - 1
  loadPage(pageIndex, pageCursors[pageIndex])
}

const showNextPage = () => {
  if (searching.value || !hasMore.value || !nextCursor.value) return
  const pageIndex = currentPageIndex.value + 1
  pageCursors[pageIndex] = nextCursor.value
  loadPage(pageIndex, nextCursor.value)
}

const requestClose = () => {
  cancelActiveSearch(true)
  emit('close')
}

watch(
  () => props.bookUrl,
  (bookUrl, previousBookUrl) => {
    if (!previousBookUrl || bookUrl === previousBookUrl) return
    cancelActiveSearch(false)
    resetSearchState()
    query.value = ''
    submittedQuery.value = ''
    hasSearched.value = false
  },
)

onUnmounted(() => cancelActiveSearch(false))

defineExpose({ cancelActiveSearch })
</script>

<style scoped>
.book-content-search {
  display: flex;
  width: 100%;
  min-width: 0;
  max-height: 72vh;
  flex-direction: column;
  color: var(--web-text);
}

.search-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
}

.search-header h2 {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
}

.search-form {
  display: flex;
  gap: 8px;
}

.search-form .web-input {
  min-width: 0;
  flex: 1;
}

.search-notice,
.search-summary,
.search-message,
.search-empty {
  margin: 10px 0 0;
  font-size: 13px;
  line-height: 1.5;
}

.search-notice {
  color: var(--web-text-secondary);
}

.search-summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  color: var(--web-text-secondary);
}

.search-state {
  flex-shrink: 0;
  color: var(--web-primary);
}

.search-message {
  border-radius: var(--web-radius);
  padding: 8px 10px;
  background: var(--web-info-light);
  color: var(--web-text-secondary);
}

.search-message--warning {
  background: var(--web-warning-light);
  color: #a86c00;
}

.search-message--error {
  background: var(--web-danger-light);
  color: var(--web-danger);
}

.search-results {
  height: min(50vh, 480px);
  min-height: 120px;
  margin-top: 10px;
  overflow-y: auto;
  border: 1px solid var(--web-border-light);
  border-radius: var(--web-radius);
  overscroll-behavior: contain;
}

.search-pagination {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: 10px;
  color: var(--web-text-secondary);
  font-size: 13px;
}

.search-pagination .web-btn {
  min-width: 72px;
}

.search-empty {
  padding: 28px 12px;
  color: var(--web-text-secondary);
  text-align: center;
}

:global(.night) .book-content-search {
  --web-text: #bbb;
  --web-text-secondary: #999;
  --web-bg-white: #292929;
  --web-border: #555;
  --web-border-light: #444;
  --web-info-light: rgba(255, 255, 255, 0.06);
  --web-warning-light: rgba(230, 162, 60, 0.14);
  --web-danger-light: rgba(245, 108, 108, 0.12);
}

:global(.night) .search-message--warning {
  color: #e6a23c;
}

@media screen and (max-width: 776px) {
  .book-content-search {
    max-height: calc(100vh - 64px);
  }

  .search-form .web-btn {
    padding-right: 12px;
    padding-left: 12px;
  }

  .search-results {
    height: calc(100vh - 250px);
    max-height: 520px;
  }
}
</style>
