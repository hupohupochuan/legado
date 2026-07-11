import { defineStore } from 'pinia'
import API from '@api'
import type {
  BaseBook,
  Book,
  BookChapter,
  BookProgress,
  WebBookProgress,
  BookGroup,
  SeachBook,
} from '@/book'
import type { webReadConfig } from '@/web'
import { toast } from '@/utils/toast'
import { toRaw } from 'vue'
import { finishReaderPerf, startReaderPerf } from '@/utils/readerPerformance'

const default_config: webReadConfig = {
  theme: 0,
  font: 0,
  fontSize: 18,
  readWidth: 800,
  infiniteLoading: false,
  customFontName: '',
  jumpDuration: 1000,
  spacing: {
    paragraph: 1,
    line: 0.8,
    letter: 0,
  },
  // 默认仍为连续滚动模式，保证旧用户行为不变。
  // 书本翻页模式出错会回退到滚动渲染，但不会覆盖用户持久化的配置。
  pageMode: 'scroll',
  pageTurnEffect: 'book',
}
let webReadConfigLoadedDate: Date | undefined

let pendingBookProgress: WebBookProgress | null = null
let bookProgressSaveWorker: Promise<void> | null = null
let bookProgressSaveTimer: ReturnType<typeof setTimeout> | null = null
let lastSubmittedBookProgressKey: string | null = null
let backendBookProgressDirty = false
let changedWhileSaving = false
let flushEpoch = 0

const bookProgressKey = (progress: BookProgress) =>
  [
    progress.name,
    progress.author,
    progress.durChapterIndex,
    progress.durChapterPos,
    progress.durChapterTitle,
  ].join('\u0000')

const clearBookProgressSaveTimer = () => {
  if (bookProgressSaveTimer === null) return
  clearTimeout(bookProgressSaveTimer)
  bookProgressSaveTimer = null
}

const scheduleBookProgressSave = () => {
  if (bookProgressSaveTimer !== null || bookProgressSaveWorker) return
  bookProgressSaveTimer = setTimeout(() => {
    bookProgressSaveTimer = null
    void submitBookProgress(false).catch(() => undefined)
  }, 5000)
}

const submitBookProgress = async (
  flush: boolean,
  fallback?: WebBookProgress,
): Promise<void> => {
  if (bookProgressSaveWorker) {
    await bookProgressSaveWorker.catch(() => undefined)
    if (flush) return submitBookProgress(true, fallback)
    return
  }
  const progress =
    pendingBookProgress ??
    (flush && backendBookProgressDirty ? fallback : undefined)
  if (!progress) return
  pendingBookProgress = null
  changedWhileSaving = false
  const progressKey = bookProgressKey(progress)
  const startedAtFlushEpoch = flushEpoch
  const perf = startReaderPerf(
    flush ? 'web.progress.flush' : 'web.progress.save',
  )
  const request = API.saveBookProgress(progress, flush)
    .then(() => {
      lastSubmittedBookProgressKey = progressKey
      if (flush) {
        backendBookProgressDirty = false
      } else if (flushEpoch === startedAtFlushEpoch) {
        backendBookProgressDirty = true
      }
    })
    .catch(error => {
      if (!pendingBookProgress) pendingBookProgress = progress
      throw error
    })
    .finally(() => {
      bookProgressSaveWorker = null
      finishReaderPerf(perf, 50)
      if (pendingBookProgress && changedWhileSaving) scheduleBookProgressSave()
    })
  bookProgressSaveWorker = request
  return request
}

const enqueueBookProgressSave = (progress: WebBookProgress) => {
  const progressSnapshot = { ...progress }
  const progressKey = bookProgressKey(progressSnapshot)
  if (
    progressKey === lastSubmittedBookProgressKey &&
    pendingBookProgress === null
  ) {
    return Promise.resolve()
  }
  pendingBookProgress = progressSnapshot
  if (bookProgressSaveWorker) changedWhileSaving = true
  scheduleBookProgressSave()
  return Promise.resolve()
}

export const useBookStore = defineStore('book', {
  state: () => {
    return {
      searchBooks: [] as SeachBook[],
      shelf: [] as Book[],
      groups: [] as BookGroup[],
      currentGroupId: undefined as number | string | undefined,
      catalog: [] as BookChapter[],
      readingBook: { chapterPos: 0, chapterIndex: 0 } as BaseBook & {
        chapterPos: number
        chapterIndex: number
        isSeachBook?: boolean
      },
      popCataVisible: false,
      contentLoading: true,
      showContent: false,
      config: default_config,
      miniInterface: false,
      readSettingsVisible: false,
      // 运行时生效的阅读模式。默认与 config.pageMode 一致；书本翻页组件
      // 初始化失败时会切回 'scroll'，但不会回写 config，避免临时错误污染用户配置。
      activePageMode: 'scroll' as 'scroll' | 'book',
    }
  },
  getters: {
    bookProgress: (state): BookProgress | undefined => {
      if (state.catalog.length == 0) return
      const { chapterIndex, chapterPos, name, author } = state.readingBook
      const title = state.catalog[chapterIndex]?.title
      if (!title) return
      return {
        name,
        author,
        durChapterIndex: chapterIndex,
        durChapterPos: chapterPos,
        durChapterTime: new Date().getTime(),
        durChapterTitle: title,
      }
    },
    theme: state => {
      return state.config.theme
    },
    isNight: state => state.config.theme == 6,
  },
  actions: {
    /** 加载分组列表 */
    async loadGroups() {
      try {
        const resp = await API.getGroups()
        const { isSuccess, data, errorMsg } = resp.data
        if (isSuccess) {
          this.groups = data
        } else {
          console.error('获取分组失败:', errorMsg)
        }
      } catch (e) {
        console.error('获取分组出错:', e)
      }
    },
    /**
     * 加载书架书籍列表。
     * 如果已有缓存且分组不变则直接返回缓存，避免重复请求。
     */
    async loadBookShelf(groupId?: number | string): Promise<Book[]> {
      const fetchBookshellf_promise = API.getBookShelf(groupId).then(resp => {
        console.log('API.getBookShelf数据返回')
        const { isSuccess, data, errorMsg } = resp.data
        if (isSuccess === true) {
          if (
            this.shelf.length !== data.length &&
            this.shelf.length > 0 &&
            data.length > 0 &&
            groupId === this.currentGroupId
          ) {
            toast.info('书架数据已更新')
          }
          this.shelf = data.sort((a: any, b: any) => {
            const x = a['durChapterTime'] || 0
            const y = b['durChapterTime'] || 0
            return y - x
          })
        } else {
          if (errorMsg.includes('还没有添加小说') && this.shelf.length > 0) {
            toast.info('当前书架上的书籍已经被删除')
            return (this.shelf = [])
          }
          toast.error(errorMsg ?? '后端返回格式错误！')
        }
        console.log('书架数据已更新')
        return this.shelf
      })

      if (this.shelf.length > 0 && groupId === this.currentGroupId) {
        console.log('返回缓存书架数据')
        return this.shelf
      } else {
        this.currentGroupId = groupId
        console.log('从阅读后端获取书架数据...')
        return await fetchBookshellf_promise
      }
    },
    async loadWebCatalog(
      book: typeof this.readingBook,
    ): Promise<BookChapter[]> {
      const { bookUrl, name, chapterIndex } = book
      const fetchChapterList_promise = API.getChapterList(
        bookUrl as string,
      ).then(res => {
        const { isSuccess, data, errorMsg } = res.data
        if (isSuccess === false) {
          toast.error(errorMsg)
          throw new Error()
        }
        if (
          bookUrl === this.readingBook.bookUrl &&
          data.length !== this.catalog.length &&
          data.length > 0 &&
          this.catalog.length > 0
        ) {
          toast.info(`书籍${name}: 章节目录已更新`)
        }
        this.catalog = data
        console.log(`书籍${name}: 章节目录已更新`)
        return this.catalog
      })
      if (
        bookUrl === this.readingBook.bookUrl &&
        this.catalog.length > 0 &&
        this.catalog.length - 1 >= chapterIndex
      ) {
        console.log(`返回书籍《${name}》 缓存的章节目录`)
        return this.catalog
      } else {
        console.log(`从阅读后端获取书籍《${name}》 章节目录数据...`)
        return await fetchChapterList_promise
      }
    },
    async refreshCatalog() {
      const bookUrl = this.readingBook.bookUrl
      if (!bookUrl) {
        toast.error('当前书籍地址为空')
        return
      }
      try {
        const res = await API.refreshToc(bookUrl)
        const { isSuccess, data, errorMsg } = res.data
        if (isSuccess === false) {
          toast.error(errorMsg)
          return
        }
        this.catalog = data
        toast.success('目录已刷新')
        return this.catalog
      } catch {
        toast.error('刷新目录失败')
      }
    },
    setPopCataVisible(visible: boolean) {
      this.popCataVisible = visible
    },
    setContentLoading(loading: boolean) {
      this.contentLoading = loading
    },
    setReadingBook(readingBook: typeof this.readingBook) {
      this.readingBook = readingBook
      const progress = this.bookProgress
      if (progress) {
        clearBookProgressSaveTimer()
        pendingBookProgress = null
        backendBookProgressDirty = false
        lastSubmittedBookProgressKey = bookProgressKey(progress)
      }
    },
    async loadWebConfig() {
      if (webReadConfigLoadedDate === undefined) {
        const _config = await API.getReadConfig()
        webReadConfigLoadedDate = new Date()
        console.log(
          `${this.$id}.loadWebConfig: ${webReadConfigLoadedDate.toLocaleString()}成功加载阅读配置`,
        )
        return this.setConfig(_config)
      }
      console.log(
        `${this.$id}.loadWebConfig: 已于${webReadConfigLoadedDate.toLocaleString()}成功加载`,
      )
    },
    setConfig(config?: webReadConfig) {
      this.config = Object.assign({}, this.config, config)
    },
    setReadSettingsVisible(visible: boolean) {
      this.readSettingsVisible = visible
    },
    setShowContent(visible: boolean) {
      this.showContent = visible
    },
    setMiniInterface(mini: boolean) {
      this.miniInterface = mini
    },
    /** 同步 config.pageMode 到运行时 activePageMode（用户主动切换或配置加载后调用） */
    syncActivePageMode() {
      this.activePageMode = this.config.pageMode || 'scroll'
    },
    /** 书本翻页组件初始化失败时切回滚动，不回写 config */
    fallbackToScroll() {
      this.activePageMode = 'scroll'
    },
    setActivePageMode(mode: 'scroll' | 'book') {
      this.activePageMode = mode
    },
    async setSearchBooks(books: SeachBook[]) {
      books.forEach(book => {
        const isSeachBook = this.shelf.every(
          item => item.bookUrl !== book.bookUrl,
        )
        if (isSeachBook === true) {
          this.searchBooks.push(book)
        }
      })
    },
    clearSearchBooks() {
      this.searchBooks = []
    },
    /**
     * 保存阅读进度到后端。
     * @param useBeacon 是否使用 navigator.sendBeacon（页面关闭前调用，
     *                   仅做尽力交付，浏览器不会等待响应）。
     */
    async saveBookProgress(flush = false, useBeacon = false) {
      const progress = this.bookProgress
      if (!progress) return Promise.resolve()
      const { bookUrl } = this.readingBook
      const webProgress: WebBookProgress = { ...progress, bookUrl }
      const shelfRaw = toRaw(this.shelf)
      const findIndex = shelfRaw.findIndex(book => book.bookUrl === bookUrl)
      if (findIndex > -1) {
        this.shelf[findIndex] = Object.assign({}, shelfRaw[findIndex], progress)
      }
      if (useBeacon) {
        if (
          pendingBookProgress === null &&
          !backendBookProgressDirty &&
          bookProgressKey(webProgress) === lastSubmittedBookProgressKey
        )
          return
        const perf = startReaderPerf('web.progress.saveBeacon')
        clearBookProgressSaveTimer()
        pendingBookProgress = null
        backendBookProgressDirty = false
        flushEpoch++
        API.saveBookProgressWithBeacon(webProgress)
        finishReaderPerf(perf, 0)
        return
      }
      if (flush) {
        clearBookProgressSaveTimer()
        if (bookProgressKey(webProgress) !== lastSubmittedBookProgressKey) {
          pendingBookProgress = webProgress
        }
        return submitBookProgress(true, webProgress)
      }
      return enqueueBookProgressSave(webProgress)
    },
  },
})
