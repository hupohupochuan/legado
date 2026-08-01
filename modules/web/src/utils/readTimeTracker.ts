import API from '@api'

/**
 * Web 端章节阅读时长统计。
 *
 * - 只记录当前章；切换章节时提交上一章。
 * - 章节内超过 IDLE_THRESHOLD_MS 无交互，本章作废。
 * - 不足 MIN_RECORD_MS 的章节不计入。
 * - 开关统一受手机端 AppConfig.enableReadRecord 控制，后端保存时再校验。
 */

const IDLE_THRESHOLD_MS = 10 * 60 * 1000 // 10 分钟
const MIN_RECORD_MS = 5 * 1000           // 5 秒

let currentBookName = ''
let chapterStartTime = 0
let lastActiveTime = 0

const updateActiveTime = () => {
  lastActiveTime = Date.now()
}

const startChapter = (bookName: string) => {
  currentBookName = bookName
  chapterStartTime = Date.now()
  lastActiveTime = chapterStartTime
}

const isIdleExpired = () => {
  return Date.now() - lastActiveTime > IDLE_THRESHOLD_MS
}

const submitChapter = () => {
  const now = Date.now()
  const duration = now - chapterStartTime
  if (duration < MIN_RECORD_MS) return false
  if (isIdleExpired()) return false
  if (!currentBookName) return false

  API.saveReadTime({
    bookName: currentBookName,
    durationMs: duration,
    timestamp: now,
  }).catch(() => undefined)
  return true
}

/**
 * 切换章节（或切换书籍）时调用：先提交上一章，再开始记录新章。
 */
const changeChapter = (bookName: string) => {
  submitChapter()
  startChapter(bookName)
}

const clear = () => {
  currentBookName = ''
  chapterStartTime = 0
  lastActiveTime = 0
}

export const readTimeTracker = {
  startChapter,
  changeChapter,
  updateActiveTime,
  clear,
  isIdleExpired,
}
