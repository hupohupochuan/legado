import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import ts from 'typescript'

const sourcePath = new URL(
  '../src/utils/readTimeTrackerCore.ts',
  import.meta.url,
)
const source = await readFile(sourcePath, 'utf8')
const output = ts.transpileModule(source, {
  compilerOptions: {
    module: ts.ModuleKind.ES2022,
    target: ts.ScriptTarget.ES2022,
  },
  fileName: 'readTimeTrackerCore.ts',
}).outputText
const moduleUrl = `data:text/javascript;base64,${Buffer.from(output).toString('base64')}`
const {
  createReadTimeTracker,
  READ_TIME_IDLE_THRESHOLD_MS,
  READ_TIME_MIN_RECORD_MS,
} = await import(moduleUrl)

const createHarness = () => {
  let currentTime = 0
  const payloads = []
  const tracker = createReadTimeTracker(
    payload => {
      payloads.push(payload)
      return Promise.resolve()
    },
    () => currentTime,
  )
  return {
    tracker,
    payloads,
    setTime: value => {
      currentTime = value
    },
  }
}

test('submits a completed chapter at the five-second boundary', () => {
  const harness = createHarness()
  harness.tracker.startChapter('测试书')
  harness.setTime(READ_TIME_MIN_RECORD_MS)

  assert.equal(harness.tracker.changeChapter('测试书'), true)
  assert.deepEqual(harness.payloads, [
    { bookName: '测试书', durationMs: READ_TIME_MIN_RECORD_MS },
  ])
})

test('does not submit a chapter shorter than five seconds', () => {
  const harness = createHarness()
  harness.tracker.startChapter('测试书')
  harness.setTime(READ_TIME_MIN_RECORD_MS - 1)

  assert.equal(harness.tracker.changeChapter('测试书'), false)
  assert.deepEqual(harness.payloads, [])
})

test('keeps the chapter valid while every activity gap stays below ten minutes', () => {
  const harness = createHarness()
  harness.tracker.startChapter('测试书')
  harness.setTime(READ_TIME_IDLE_THRESHOLD_MS - 1)
  harness.tracker.updateActiveTime()
  harness.setTime(READ_TIME_IDLE_THRESHOLD_MS * 2 - 2)

  assert.equal(harness.tracker.changeChapter('测试书'), true)
  assert.equal(harness.payloads.length, 1)
})

test('keeps an observed idle timeout sticky after activity resumes', () => {
  const harness = createHarness()
  harness.tracker.startChapter('测试书')
  harness.setTime(READ_TIME_IDLE_THRESHOLD_MS + 1)
  harness.tracker.updateActiveTime()
  harness.setTime(READ_TIME_IDLE_THRESHOLD_MS + READ_TIME_MIN_RECORD_MS + 1)

  assert.equal(harness.tracker.changeChapter('测试书'), false)
  assert.deepEqual(harness.payloads, [])
})

test('resets the idle timeout for the next chapter', () => {
  const harness = createHarness()
  harness.tracker.startChapter('测试书')
  harness.setTime(READ_TIME_IDLE_THRESHOLD_MS + 1)
  assert.equal(harness.tracker.changeChapter('测试书'), false)

  harness.setTime(READ_TIME_IDLE_THRESHOLD_MS + READ_TIME_MIN_RECORD_MS + 1)
  assert.equal(harness.tracker.changeChapter('测试书'), true)
  assert.deepEqual(harness.payloads, [
    { bookName: '测试书', durationMs: READ_TIME_MIN_RECORD_MS },
  ])
})

test('clear discards the unfinished chapter without submitting it', () => {
  const harness = createHarness()
  harness.tracker.startChapter('测试书')
  harness.setTime(READ_TIME_MIN_RECORD_MS)
  harness.tracker.clear()

  assert.equal(harness.tracker.changeChapter('测试书'), false)
  assert.deepEqual(harness.payloads, [])
})
