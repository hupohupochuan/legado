import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import ts from 'typescript'

const sourcePath = new URL('../src/utils/pageTurnKeyBuffer.ts', import.meta.url)
const source = await readFile(sourcePath, 'utf8')
const output = ts.transpileModule(source, {
  compilerOptions: {
    module: ts.ModuleKind.ES2022,
    target: ts.ScriptTarget.ES2022,
  },
  fileName: 'pageTurnKeyBuffer.ts',
}).outputText
const moduleUrl = `data:text/javascript;base64,${Buffer.from(output).toString('base64')}`
const { createPageTurnKeyBuffer } = await import(moduleUrl)

test('runs an unlocked keyboard turn immediately', () => {
  const turns = []
  const buffer = createPageTurnKeyBuffer(direction => turns.push(direction))

  assert.equal(buffer.request('next', false), true)
  assert.deepEqual(turns, ['next'])
  assert.equal(buffer.flush(), false)
})

test('replays one right turn after an animation lock ends', () => {
  const turns = []
  const buffer = createPageTurnKeyBuffer(direction => turns.push(direction))

  assert.equal(buffer.request('next', true), false)
  assert.deepEqual(turns, [])
  assert.equal(buffer.flush(), true)
  assert.deepEqual(turns, ['next'])
  assert.equal(buffer.flush(), false)
})

test('keeps only the latest direction while locked', () => {
  const turns = []
  const buffer = createPageTurnKeyBuffer(direction => turns.push(direction))

  buffer.request('next', true)
  buffer.request('prev', true)
  buffer.request('next', true)

  assert.equal(buffer.flush(), true)
  assert.deepEqual(turns, ['next'])
})

test('clears a buffered turn when the current reader is discarded', () => {
  const turns = []
  const buffer = createPageTurnKeyBuffer(direction => turns.push(direction))

  buffer.request('next', true)
  buffer.clear()

  assert.equal(buffer.flush(), false)
  assert.deepEqual(turns, [])
})
