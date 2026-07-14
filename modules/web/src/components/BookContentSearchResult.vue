<template>
  <button class="search-result" type="button" @click="onSelect(source)">
    <span class="search-result__title">{{ source.chapterTitle }}</span>
    <span class="search-result__snippet">
      <span>{{ snippetBefore }}</span>
      <span v-if="snippetKeyword" class="search-keyword">{{
        snippetKeyword
      }}</span>
      <span>{{ snippetAfter }}</span>
    </span>
  </button>
</template>

<script setup lang="ts">
import type { WebBookContentSearchResult } from '@api'

const props = defineProps<{
  source: WebBookContentSearchResult & { searchResultKey: string }
  keyword: string
  onSelect: (result: WebBookContentSearchResult) => void
}>()

const keywordRange = computed(() => {
  const start = Math.min(
    props.source.snippet.length,
    Math.max(0, props.source.queryIndexInSnippet),
  )
  return {
    start,
    end: Math.min(props.source.snippet.length, start + props.keyword.length),
  }
})

const snippetBefore = computed(() =>
  props.source.snippet.slice(0, keywordRange.value.start),
)
const snippetKeyword = computed(() =>
  props.source.snippet.slice(keywordRange.value.start, keywordRange.value.end),
)
const snippetAfter = computed(() =>
  props.source.snippet.slice(keywordRange.value.end),
)
</script>

<style scoped>
.search-result {
  display: block;
  width: 100%;
  min-height: 76px;
  padding: 12px 14px;
  border: 0;
  border-bottom: 1px solid var(--web-border-light);
  background: transparent;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
}

.search-result:hover,
.search-result:focus-visible {
  background: rgba(64, 158, 255, 0.08);
  outline: none;
}

.search-result__title,
.search-result__snippet {
  display: block;
}

.search-result__title {
  margin-bottom: 6px;
  overflow: hidden;
  color: inherit;
  font-size: 15px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.search-result__snippet {
  overflow-wrap: anywhere;
  color: var(--web-text-secondary);
  font-size: 13px;
  line-height: 1.55;
}

.search-keyword {
  border-radius: 2px;
  background: #ffe58f;
  color: #7a4d00;
  font-weight: 600;
}

:global(.night) .search-result__snippet {
  color: #aaa;
}

:global(.night) .search-keyword {
  background: #7a5a16;
  color: #fff1b8;
}
</style>
