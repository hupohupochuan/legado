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
        已搜索 {{ scannedChapters }}/{{ searchableChapters }} 章，找到
        {{ displayedMatchCount }} 处
      </span>
      <span v-if="searching" class="search-state">搜索中…</span>
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
    <p v-else-if="truncated" class="search-message search-message--warning">
      结果过多，仅显示前 500 条
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
    <p
      v-else-if="completed && searchableChapters > 0 && !errorMessage"
      class="search-empty"
    >
      没有找到匹配内容
    </p>
    <p v-else-if="!hasSearched" class="search-empty">
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

const props = defineProps<{
  bookUrl: string
  isOnlineBook?: boolean
}>()

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'select', result: WebBookContentSearchResult): void
}>()

const maxResults = 500
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
const truncated = ref(false)
const errorMessage = ref('')
const serverIsLocalBook = ref<boolean>()

let activeSearch: BookContentSearchHandle | null = null
let activeGeneration = 0

const isOnlineBook = computed(() => {
  if (serverIsLocalBook.value !== undefined) return !serverIsLocalBook.value
  return props.isOnlineBook === true
})

const displayedMatchCount = computed(() =>
  Math.max(matchCount.value, results.value.length),
)

const selectResult = (result: WebBookContentSearchResult) => {
  emit('select', result)
}

const resultExtraProps = computed(() => ({
  keyword: submittedQuery.value,
  onSelect: selectResult,
}))

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
  truncated.value = false
  errorMessage.value = ''
  serverIsLocalBook.value = undefined
}

const cancelActiveSearch = (markStopped = true) => {
  if (!activeSearch && !searching.value) return
  activeGeneration++
  const handle = activeSearch
  activeSearch = null
  searching.value = false
  if (markStopped) stopped.value = true
  handle?.close()
}

const stopSearch = () => cancelActiveSearch(true)

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
  searching.value = true
  const generation = ++activeGeneration

  const isCurrentSearch = () => generation === activeGeneration
  activeSearch = API.searchBookContent(
    {
      bookUrl: props.bookUrl,
      query: normalizedQuery,
      maxResults,
    },
    {
      onStart: message => {
        if (!isCurrentSearch()) return
        hasStarted.value = true
        totalChapters.value = Math.max(0, message.totalChapters)
        searchableChapters.value = Math.max(0, message.searchableChapters)
        if (typeof message.isLocalBook === 'boolean')
          serverIsLocalBook.value = message.isLocalBook
      },
      onResults: message => {
        if (!isCurrentSearch()) return
        const room = maxResults - results.value.length
        if (room <= 0) return
        const startIndex = results.value.length
        const nextItems = message.items.slice(0, room).map((item, index) => ({
          ...item,
          searchResultKey: `${item.chapterIndex}:${item.queryIndexInChapter}:${startIndex + index}`,
        }))
        results.value.push(...nextItems)
        matchCount.value = Math.max(matchCount.value, results.value.length)
      },
      onProgress: message => {
        if (!isCurrentSearch()) return
        hasStarted.value = true
        scannedChapters.value = Math.max(0, message.scannedChapters)
        searchableChapters.value = Math.max(0, message.searchableChapters)
        matchCount.value = Math.max(0, message.matchCount)
      },
      onComplete: message => {
        if (!isCurrentSearch()) return
        activeSearch = null
        searching.value = false
        completed.value = true
        stopped.value = false
        scannedChapters.value = Math.max(0, message.scannedChapters)
        matchCount.value = Math.max(0, message.matchCount)
        skippedUncachedChapters.value = Math.max(
          0,
          message.skippedUncachedChapters,
        )
        truncated.value = message.truncated
      },
      onError: message => {
        if (!isCurrentSearch()) return
        activeSearch = null
        searching.value = false
        errorMessage.value = message.message || '搜索失败'
      },
      onSocketError: () => {
        if (!isCurrentSearch()) return
        activeSearch = null
        searching.value = false
        errorMessage.value = backendConnectionErrorMessage
      },
      onClose: (_event, expected) => {
        if (!isCurrentSearch() || expected || !searching.value) return
        activeSearch = null
        searching.value = false
        errorMessage.value = backendConnectionErrorMessage
      },
    },
  )
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
