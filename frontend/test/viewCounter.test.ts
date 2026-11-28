import assert from 'node:assert/strict'
import test from 'node:test'
import { claimPostView } from '../src/lib/viewCounter.ts'

function installSessionStorage() {
  const data = new Map<string, string>()
  Object.defineProperty(globalThis, 'sessionStorage', {
    configurable: true,
    value: {
      getItem: (key: string) => data.get(key) ?? null,
      setItem: (key: string, value: string) => void data.set(key, value),
      removeItem: (key: string) => void data.delete(key),
    },
  })
  return data
}

test('post view is claimed once per slug and session', () => {
  installSessionStorage()
  assert.equal(claimPostView('a'), true)
  assert.equal(claimPostView('a'), false)
  assert.equal(claimPostView('b'), true)
  assert.equal(claimPostView(''), false)
})

test('post view storage key has one owner', async () => {
  const { readdirSync, readFileSync, statSync } = await import('node:fs')
  const { join } = await import('node:path')
  const offenders: string[] = []
  const walk = (dir: string) => {
    for (const entry of readdirSync(dir)) {
      const path = join(dir, entry)
      if (statSync(path).isDirectory()) walk(path)
      else if (/\.(ts|vue)$/.test(path) && !path.endsWith('lib/viewCounter.ts')) {
        if (readFileSync(path, 'utf8').includes('blog_viewed_')) offenders.push(path)
      }
    }
  }
  walk(join(process.cwd(), 'src'))
  assert.deepEqual(offenders, [])
})
