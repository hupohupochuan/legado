import type { SaveReadTimePayload } from '@api'

export const READ_TIME_IDLE_THRESHOLD_MS = 10 * 60 * 1000
export const READ_TIME_MIN_RECORD_MS = 5 * 1000

type SaveReadTime = (payload: SaveReadTimePayload) => Promise<unknown>
type Clock = () => number

/**
 * 创建章节计时器。空闲超时是当前章节的粘性状态，后续交互不能把已经发生的长时间空闲抹掉。
 */
export const createReadTimeTracker = (
  saveReadTime: SaveReadTime,
  now: Clock = Date.now,
) => {
  let currentBookName = ''
  let chapterStartTime = 0
  let lastActiveTime = 0
  let idleExpired = false

  const observeIdleGap = (currentTime: number) => {
    if (
      currentBookName &&
      currentTime - lastActiveTime > READ_TIME_IDLE_THRESHOLD_MS
    ) {
      idleExpired = true
    }
  }

  const updateActiveTime = () => {
    if (!currentBookName) return
    const currentTime = now()
    observeIdleGap(currentTime)
    lastActiveTime = currentTime
  }

  const startChapter = (bookName: string) => {
    currentBookName = bookName
    chapterStartTime = now()
    lastActiveTime = chapterStartTime
    idleExpired = false
  }

  const isIdleExpired = () => {
    observeIdleGap(now())
    return idleExpired
  }

  const submitChapter = () => {
    const currentTime = now()
    observeIdleGap(currentTime)
    const durationMs = Math.floor(currentTime - chapterStartTime)
    if (!currentBookName || durationMs < READ_TIME_MIN_RECORD_MS || idleExpired)
      return false

    try {
      void saveReadTime({
        bookName: currentBookName,
        durationMs,
      }).catch(() => undefined)
    } catch {
      // API 封装通常返回 Promise；同步异常也不应阻断切章。
    }
    return true
  }

  /** 切换章节（或书籍）时先提交上一章，再为新章重置全部状态。 */
  const changeChapter = (bookName: string) => {
    const submitted = submitChapter()
    startChapter(bookName)
    return submitted
  }

  const clear = () => {
    currentBookName = ''
    chapterStartTime = 0
    lastActiveTime = 0
    idleExpired = false
  }

  return {
    startChapter,
    changeChapter,
    updateActiveTime,
    clear,
    isIdleExpired,
  }
}
