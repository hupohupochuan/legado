/** https://github.com/hupohupochuan/legado/tree/master/app/src/main/java/io/legado/app/api */
/** https://github.com/hupohupochuan/legado/tree/master/app/src/main/java/io/legado/app/web */

import type { webReadConfig } from '@/web'
import ajax from './axios'
import type {
  BaseBook,
  Book,
  BookChapter,
  WebBookProgress,
  SyncBookProgressResult,
  BookGroup,
  SearchBook,
} from '@/book'
import type { BookSource, Source } from '@/source'
import type { ReplaceRule } from '@/replaceRule'

export type LegadoApiResponse<T> = {
  isSuccess: boolean
  errorMsg: string
  data: T
}

export type WebBookContentSearchResult = {
  chapterIndex: number
  chapterTitle: string
  chapterPos: number
  queryIndexInChapter: number
  queryIndexInSnippet: number
  snippet: string
}

export type BookContentSearchRequest = {
  bookUrl: string
  query: string
  maxResults?: number
}

export type BookContentSearchStartMessage = {
  type: 'start'
  totalChapters: number
  searchableChapters: number
  /** Newer servers include this so the UI can explain cache-only online search. */
  isLocalBook?: boolean
}

export type BookContentSearchResultsMessage = {
  type: 'results'
  items: WebBookContentSearchResult[]
}

export type BookContentSearchProgressMessage = {
  type: 'progress'
  scannedChapters: number
  searchableChapters: number
  matchCount: number
}

export type BookContentSearchCompleteMessage = {
  type: 'complete'
  scannedChapters: number
  matchCount: number
  skippedUncachedChapters: number
  truncated: boolean
}

export type BookContentSearchErrorMessage = {
  type: 'error'
  message: string
}

export type SaveReadTimePayload = {
  bookName: string
  durationMs: number
  timestamp: number
}

export type BookContentSearchHandlers = {
  onStart?: (message: BookContentSearchStartMessage) => void
  onResults?: (message: BookContentSearchResultsMessage) => void
  onProgress?: (message: BookContentSearchProgressMessage) => void
  onComplete?: (message: BookContentSearchCompleteMessage) => void
  onError?: (message: BookContentSearchErrorMessage) => void
  onSocketError?: (event: Event) => void
  onClose?: (event: CloseEvent, expected: boolean) => void
}

export type BookContentSearchHandle = {
  close: () => void
}

export let legado_http_entry_point = ''
export let legado_webSocket_entry_point = ''

let wsOnError: typeof WebSocket.prototype.onerror = () => {}
let wsOnMessage: typeof WebSocket.prototype.onmessage = () => {}
export const setWebsocketOnMessage = (callback: typeof wsOnMessage) =>
  (wsOnMessage = callback)
export const setWebsocketOnError = (callback: typeof wsOnError) => {
  wsOnError = callback
}

export const setApiEntryPoint = (
  http_entry_point: string,
  webSocket_entry_point: string,
) => {
  legado_http_entry_point = new URL(http_entry_point).toString()
  legado_webSocket_entry_point = new URL(webSocket_entry_point).toString()
  ajax.defaults.baseURL = legado_http_entry_point
}

// 书架API
const getReadConfig = async (http_url = legado_http_entry_point) => {
  const { data } = await ajax.get<LegadoApiResponse<string>>('getReadConfig', {
    baseURL: http_url.toString(),
  })
  if (data.isSuccess) {
    try {
      return JSON.parse(data.data) as webReadConfig
    } catch {}
  }
}
const saveReadConfig = (config: webReadConfig) =>
  ajax.post<LegadoApiResponse<unknown>>('saveReadConfig', config)

const saveBookProgress = async (
  bookProgress: WebBookProgress,
  flush = false,
) => {
  const response = await ajax.post<LegadoApiResponse<unknown>>(
    `saveBookProgress${flush ? '?flush=true' : ''}`,
    bookProgress,
  )
  if (!response.data?.isSuccess) {
    throw new Error(response.data?.errorMsg || '保存阅读进度失败')
  }
  return response
}

const saveBookProgressWithBeacon = (bookProgress: WebBookProgress) => {
  if (!bookProgress) return
  navigator.sendBeacon(
    new URL('saveBookProgress?flush=true', legado_http_entry_point),
    JSON.stringify(bookProgress),
  )
}

const saveReadTime = (payload: SaveReadTimePayload) =>
  ajax.post<LegadoApiResponse<unknown>>('saveReadTime', payload)

const syncBookProgress = (bookUrl: string) =>
  ajax.post<LegadoApiResponse<SyncBookProgressResult>>('syncBookProgress', {
    bookUrl,
  })

const getGroups = () => ajax.get<LegadoApiResponse<BookGroup[]>>('getGroups')

const getBookShelf = (groupId?: number | string) => {
  const url =
    groupId !== undefined ? `getBookshelf?groupId=${groupId}` : 'getBookshelf'
  return ajax.get<LegadoApiResponse<Book[]>>(url)
}

const getChapterList = (bookUrl: string) =>
  ajax.get<LegadoApiResponse<BookChapter[]>>(
    'getChapterList?url=' + encodeURIComponent(bookUrl),
  )

const getBookContent = (bookUrl: string, chapterIndex: number) =>
  ajax.get<LegadoApiResponse<string>>(
    'getBookContent?url=' +
      encodeURIComponent(bookUrl) +
      '&index=' +
      chapterIndex,
  )

const refreshToc = (bookUrl: string) =>
  ajax.get<LegadoApiResponse<BookChapter[]>>(
    'refreshToc?url=' + encodeURIComponent(bookUrl),
  )

const search = (
  searchKey: string,
  onReceive: (data: SearchBook[]) => void,
  onFinish: () => void,
) => {
  const socket = new WebSocket(
    new URL('searchBook', legado_webSocket_entry_point),
  )
  socket.onerror = wsOnError

  socket.onopen = () => {
    socket.send(JSON.stringify({ key: searchKey }))
  }
  socket.onmessage = event => {
    try {
      onReceive(JSON.parse(event.data))
      wsOnMessage?.call(socket, event)
    } catch {
      onFinish()
    }
  }

  socket.onclose = () => {
    onFinish()
  }
}

const searchBookContent = (
  request: BookContentSearchRequest,
  handlers: BookContentSearchHandlers,
): BookContentSearchHandle => {
  const socket = new WebSocket(
    new URL('searchBookContent', legado_webSocket_entry_point),
  )
  let clientClosed = false
  let terminalMessageReceived = false

  const closeSocket = () => {
    if (
      socket.readyState === WebSocket.CONNECTING ||
      socket.readyState === WebSocket.OPEN
    ) {
      try {
        socket.close()
      } catch {
        // A socket can transition between the readyState check and close().
      }
    }
  }

  const failMalformedMessage = () => {
    terminalMessageReceived = true
    try {
      handlers.onError?.({ type: 'error', message: '搜索响应格式错误' })
    } catch {
      // A consumer failure must still close the socket and cancel server work.
    }
    closeSocket()
  }

  socket.onopen = () => {
    if (clientClosed) {
      closeSocket()
      return
    }
    try {
      socket.send(JSON.stringify(request))
    } catch {
      terminalMessageReceived = true
      try {
        handlers.onError?.({ type: 'error', message: '发送搜索请求失败' })
      } finally {
        closeSocket()
      }
    }
  }

  socket.onmessage = event => {
    if (clientClosed || terminalMessageReceived) return
    try {
      const message = JSON.parse(event.data) as { type?: unknown }
      switch (message.type) {
        case 'start':
          handlers.onStart?.(message as BookContentSearchStartMessage)
          break
        case 'results':
          if (
            !Array.isArray((message as BookContentSearchResultsMessage).items)
          )
            return failMalformedMessage()
          handlers.onResults?.(message as BookContentSearchResultsMessage)
          break
        case 'progress':
          handlers.onProgress?.(message as BookContentSearchProgressMessage)
          break
        case 'complete':
          terminalMessageReceived = true
          handlers.onComplete?.(message as BookContentSearchCompleteMessage)
          closeSocket()
          break
        case 'error':
          terminalMessageReceived = true
          handlers.onError?.(message as BookContentSearchErrorMessage)
          closeSocket()
          break
        default:
          failMalformedMessage()
          return
      }
      wsOnMessage?.call(socket, event)
    } catch {
      failMalformedMessage()
    }
  }

  socket.onerror = event => {
    if (clientClosed || terminalMessageReceived) {
      closeSocket()
      return
    }
    terminalMessageReceived = true
    try {
      handlers.onSocketError?.(event)
    } finally {
      try {
        wsOnError?.call(socket, event)
      } catch {
        // The global connection handler reports the failure and may rethrow it.
      }
      closeSocket()
    }
  }

  socket.onclose = event => {
    handlers.onClose?.(event, clientClosed || terminalMessageReceived)
  }

  return {
    close: () => {
      clientClosed = true
      closeSocket()
    },
  }
}

const saveBook = (book: BaseBook) =>
  ajax.post<LegadoApiResponse<unknown>>('saveBook', book)
const deleteBook = (book: BaseBook) =>
  ajax.post<LegadoApiResponse<unknown>>('deleteBook', book)

const addLocalBook = async (file: File) => {
  const formData = new FormData()
  formData.append('fileName', file.name)
  formData.append('fileData', file)
  const response = await fetch(
    new URL('addLocalBook', legado_http_entry_point).toString(),
    { method: 'POST', body: formData },
  )
  if (!response.ok) {
    throw new Error(`HTTP ${response.status}`)
  }
  return { data: (await response.json()) as LegadoApiResponse<unknown> }
}

const getSources = () => ajax.get<LegadoApiResponse<BookSource[]>>('getBookSources')

const saveSource = (data: Source) =>
  ajax.post<LegadoApiResponse<unknown>>('saveBookSource', data)

const saveSources = (data: Source[]) =>
  ajax.post<LegadoApiResponse<BookSource[]>>('saveBookSources', data)

const deleteSource = (data: Source[]) =>
  ajax.post<LegadoApiResponse<unknown>>('deleteBookSources', data)

const getReplaceRules = () =>
  ajax.get<LegadoApiResponse<ReplaceRule[]>>('getReplaceRules')

const saveReplaceRule = (rule: ReplaceRule) =>
  ajax.post<LegadoApiResponse<unknown>>('saveReplaceRule', rule)

const deleteReplaceRule = (rule: ReplaceRule) =>
  ajax.post<LegadoApiResponse<unknown>>('deleteReplaceRule', rule)

const testReplaceRule = (rule: ReplaceRule, text: string) =>
  ajax.post<LegadoApiResponse<string>>('testReplaceRule', { rule, text })

const debug = (
  sourceUrl: string,
  searchKey: string,
  onReceive: (data: string) => void,
  onFinish: () => void,
) => {
  const url = new URL('bookSourceDebug', legado_webSocket_entry_point)

  const socket = new WebSocket(url)
  socket.onerror = wsOnError
  socket.onopen = () => {
    socket.send(JSON.stringify({ tag: sourceUrl, key: searchKey }))
  }
  socket.onmessage = event => {
    onReceive(event.data)
    wsOnMessage?.call(socket, event)
  }

  socket.onclose = () => {
    onFinish()
  }
}

const getProxyCoverUrl = (coverUrl: string) => {
  if (coverUrl.startsWith(legado_http_entry_point)) return coverUrl
  return new URL(
    'cover?path=' + encodeURIComponent(coverUrl),
    legado_http_entry_point,
  ).toString()
}

const getProxyImageUrl = (
  bookUrl: string,
  src: string,
  width: number | `${number}`,
) => {
  if (src.startsWith(legado_http_entry_point)) return src
  return new URL(
    'image?path=' +
      encodeURIComponent(src) +
      '&url=' +
      encodeURIComponent(bookUrl) +
      '&width=' +
      width,
    legado_http_entry_point,
  ).toString()
}

export default {
  getReadConfig,
  saveReadConfig,
  saveBookProgress,
  saveBookProgressWithBeacon,
  saveReadTime,
  syncBookProgress,
  getGroups,
  getBookShelf,
  getChapterList,
  getBookContent,
  refreshToc,
  search,
  searchBookContent,
  saveBook,
  deleteBook,
  addLocalBook,

  getSources,
  saveSources,
  saveSource,
  deleteSource,
  debug,

  getReplaceRules,
  saveReplaceRule,
  deleteReplaceRule,
  testReplaceRule,

  getProxyCoverUrl,
  getProxyImageUrl,
}
