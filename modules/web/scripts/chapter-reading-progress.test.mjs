import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import ts from 'typescript'

const sourcePath = new URL(
  '../src/utils/chapterReadingProgress.ts',
  import.meta.url,
)
const source = await readFile(sourcePath, 'utf8')
const output = ts.transpileModule(source, {
  compilerOptions: {
    module: ts.ModuleKind.ES2022,
    target: ts.ScriptTarget.ES2022,
  },
  fileName: 'chapterReadingProgress.ts',
}).outputText
const moduleUrl = `data:text/javascript;base64,${Buffer.from(output).toString('base64')}`
const { getChapterReadingProgress } = await import(moduleUrl)

test('uses the persisted zero-based chapter index for display', () => {
  assert.deepEqual(getChapterReadingProgress(22, 180), {
    currentChapter: 23,
    totalChapters: 180,
    percentage: (23 / 180) * 100,
    percentageText: '12.8%',
    label: '总进度 12.8%　23/180',
  })
})

test('shows the first chapter as one of the total chapters', () => {
  const progress = getChapterReadingProgress(0, 180)

  assert.equal(progress.currentChapter, 1)
  assert.equal(progress.percentageText, '0.6%')
})

test('shows one hundred percent on the last chapter', () => {
  const progress = getChapterReadingProgress(179, 180)

  assert.equal(progress.currentChapter, 180)
  assert.equal(progress.percentageText, '100.0%')
})

test('clamps stale chapter indexes to the available catalog', () => {
  assert.equal(getChapterReadingProgress(-4, 10).currentChapter, 1)
  assert.equal(getChapterReadingProgress(20, 10).currentChapter, 10)
})

test('returns a safe empty state for an unavailable catalog', () => {
  assert.deepEqual(getChapterReadingProgress(5, 0), {
    currentChapter: 0,
    totalChapters: 0,
    percentage: 0,
    percentageText: '0.0%',
    label: '总进度 0.0%　0/0',
  })
  assert.equal(
    getChapterReadingProgress(Number.NaN, Number.NaN).label,
    '总进度 0.0%　0/0',
  )
})
