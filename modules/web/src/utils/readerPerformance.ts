type ReaderPerfMark = {
  name: string
  startMark: string
  endMark: string
  measureName: string
  start: number
}

let perfId = 0
const readerPerfStorageKey = 'legadoReaderPerf'
const remoteUrlStorageKey = 'remoteUrl'

const isReaderPerformanceExplicitlyEnabled = () => {
  if (typeof window === 'undefined') return false
  return (
    window.localStorage.getItem(readerPerfStorageKey) === '1' ||
    new URLSearchParams(window.location.search).has('readerPerf')
  )
}

export const isReaderPerformanceEnabled = () => {
  if (import.meta.env.DEV) return true
  return isReaderPerformanceExplicitlyEnabled()
}

export const startReaderPerf = (name: string): ReaderPerfMark | null => {
  if (!isReaderPerformanceEnabled()) return null
  const id = ++perfId
  const startMark = `reader:${name}:start:${id}`
  const endMark = `reader:${name}:end:${id}`
  const measureName = `reader:${name}`
  performance.mark(startMark)
  return {
    name,
    startMark,
    endMark,
    measureName,
    start: performance.now(),
  }
}

export const finishReaderPerf = (
  mark: ReaderPerfMark | null,
  thresholdMs = 0,
  extra = '',
) => {
  if (!mark || !isReaderPerformanceEnabled()) return
  performance.mark(mark.endMark)
  performance.measure(mark.measureName, mark.startMark, mark.endMark)
  const entries = performance.getEntriesByName(mark.measureName, 'measure')
  const duration =
    entries[entries.length - 1]?.duration ?? performance.now() - mark.start
  performance.clearMarks(mark.startMark)
  performance.clearMarks(mark.endMark)
  performance.clearMeasures(mark.measureName)
  if (duration < thresholdMs) return
  const suffix = extra ? ` (${extra})` : ''
  const message = `${mark.name} ${duration.toFixed(1)}ms${suffix}`
  console.debug(`[ReaderPerformance] ${message}`)
  uploadReaderPerf(message)
}

const uploadReaderPerf = (message: string) => {
  if (!isReaderPerformanceExplicitlyEnabled()) return
  const baseUrl = window.localStorage.getItem(remoteUrlStorageKey) || location.origin
  const url = new URL('saveReaderLog', baseUrl).toString()
  void fetch(url, {
    method: 'POST',
    keepalive: true,
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ message }),
  }).catch(() => undefined)
}
