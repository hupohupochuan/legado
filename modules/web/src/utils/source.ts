import type { BookSource, Source } from '../source'
import { isNullOrBlank } from './utils'

// 源名称、地址、类型三项必填均有效时返回 true
export const isValidSource: (source: Source) => boolean = source => {
  return (
    !isNullOrBlank((source as BookSource).bookSourceName) &&
    !isNullOrBlank((source as BookSource).bookSourceUrl) &&
    !isNullOrBlank((source as BookSource).bookSourceType)
  )
}

export const getSourceUniqueKey = (source: Source) =>
  (source as BookSource).bookSourceUrl
export const getSourceName = (source: Source) =>
  (source as BookSource).bookSourceName

export const isSourceMatches: (source: Source, searchKey: string) => boolean = (
  source,
  searchKey,
) => {
  const s = source as BookSource
  return (
    (s.bookSourceName.includes(searchKey) ||
      s.bookSourceUrl.includes(searchKey) ||
      s.bookSourceGroup?.includes(searchKey) ||
      s.bookSourceComment?.includes(searchKey)) ??
    false
  )
}

export const convertSourcesToMap = (sources: Source[]): Map<string, Source> => {
  const map = new Map()
  sources.forEach(source => map.set(getSourceUniqueKey(source), source))
  return map
}

export const normalizeSource = (source: Record<string, unknown>) => {
  for (const key in source) {
    const value = source[key]
    if (
      value === '' ||
      value === null ||
      (typeof value === 'string' && !value.trim())
    ) {
      delete source[key]
    } else if (value instanceof Object) {
      normalizeSource(value as Record<string, unknown>)
    }
  }
}

export const emptyBookSource = {
  ruleSearch: {},
  ruleBookInfo: {},
  ruleToc: {},
  ruleContent: {},
  ruleExplore: {},
} as BookSource
