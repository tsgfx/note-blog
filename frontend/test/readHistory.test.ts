import assert from 'node:assert/strict'
import test from 'node:test'
import { getReadingHistory, recordReadingHistory } from '../src/lib/readHistory.ts'

function installLocalStorage() {
  const data = new Map<string, string>()
  Object.defineProperty(globalThis, 'localStorage', {
    configurable: true,
    value: {
      getItem: (key: string) => data.get(key) ?? null,
      setItem: (key: string, value: string) => void data.set(key, value),
      removeItem: (key: string) => void data.delete(key),
    },
  })
  return data
}

test('reading history is newest-first, deduplicated, and capped', () => {
  installLocalStorage()
  for (const slug of ['a', 'b', 'c', 'b']) recordReadingHistory(slug)
  assert.deepEqual(getReadingHistory(), ['b', 'c', 'a'])

  for (let index = 0; index < 20; index += 1) recordReadingHistory(`post-${index}`)
  assert.equal(getReadingHistory().length, 10)
  assert.equal(getReadingHistory()[0], 'post-19')
})

test('damaged or foreign history data falls back to empty recommendations input', () => {
  const data = installLocalStorage()
  data.set('notesblog_read_history', '{broken json')
  assert.deepEqual(getReadingHistory(), [])

  data.set('notesblog_read_history', JSON.stringify(['valid', 42, '', null]))
  assert.deepEqual(getReadingHistory(), ['valid'])
})

test('reading-history storage key has one owner', async () => {
  const { readdirSync, readFileSync, statSync } = await import('node:fs')
  const { join } = await import('node:path')
  const offenders: string[] = []
  const walk = (dir: string) => {
    for (const entry of readdirSync(dir)) {
      const path = join(dir, entry)
      if (statSync(path).isDirectory()) walk(path)
      else if (/\.(ts|vue)$/.test(path) && !path.endsWith('lib/readHistory.ts')) {
        if (readFileSync(path, 'utf8').includes('notesblog_read_history')) offenders.push(path)
      }
    }
  }
  walk(join(process.cwd(), 'src'))
  assert.deepEqual(offenders, [])
})
