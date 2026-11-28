import assert from 'node:assert/strict'
import test from 'node:test'
import { clearAuthSession, getAuthToken, hasAuthSession, storeAuthSession } from '../src/lib/authSession.ts'

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

test('auth session owns token storage lifecycle', () => {
  const data = installLocalStorage()
  assert.equal(hasAuthSession(), false)

  storeAuthSession('token-1')
  assert.equal(getAuthToken(), 'token-1')
  assert.equal(hasAuthSession(), true)
  data.set('notes_blog_user', '旧版本遗留')

  clearAuthSession()
  assert.equal(getAuthToken(), null)
  assert.equal(hasAuthSession(), false)
  assert.equal(data.has('notes_blog_user'), false)
})

test('auth storage keys have one owner', async () => {
  const { readdirSync, readFileSync, statSync } = await import('node:fs')
  const { join } = await import('node:path')
  const root = join(process.cwd(), 'src')
  const offenders: string[] = []
  const walk = (dir: string) => {
    for (const entry of readdirSync(dir)) {
      const path = join(dir, entry)
      if (statSync(path).isDirectory()) {
        walk(path)
      } else if (/\.(ts|vue)$/.test(path) && !path.endsWith('lib/authSession.ts')) {
        const text = readFileSync(path, 'utf8')
        if (text.includes('notes_blog_token') || text.includes('notes_blog_user')) offenders.push(path)
      }
    }
  }
  walk(root)
  assert.deepEqual(offenders, [])
})
