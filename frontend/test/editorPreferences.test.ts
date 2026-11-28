import assert from 'node:assert/strict'
import test from 'node:test'
import {
  loadCodeWrapPreference,
  loadEditorMode,
  saveCodeWrapPreference,
  saveEditorMode,
} from '../src/lib/editorPreferences.ts'

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

test('editor mode defaults to rich and round-trips source preference', () => {
  installLocalStorage()
  assert.equal(loadEditorMode(), 'rich')
  saveEditorMode('source')
  assert.equal(loadEditorMode(), 'source')
  saveEditorMode('rich')
  assert.equal(loadEditorMode(), 'rich')
})

test('code wrap preference defaults off and round-trips', () => {
  installLocalStorage()
  assert.equal(loadCodeWrapPreference(), false)
  saveCodeWrapPreference(true)
  assert.equal(loadCodeWrapPreference(), true)
  saveCodeWrapPreference(false)
  assert.equal(loadCodeWrapPreference(), false)
})

test('editor preference keys have one owner', async () => {
  const { readdirSync, readFileSync, statSync } = await import('node:fs')
  const { join } = await import('node:path')
  const offenders: string[] = []
  const walk = (dir: string) => {
    for (const entry of readdirSync(dir)) {
      const path = join(dir, entry)
      if (statSync(path).isDirectory()) walk(path)
      else if (/\.(ts|vue)$/.test(path) && !path.endsWith('lib/editorPreferences.ts')) {
        const text = readFileSync(path, 'utf8')
        if (text.includes('note-editor-mode') || text.includes('note-editor-code-wrap')) offenders.push(path)
      }
    }
  }
  walk(join(process.cwd(), 'src'))
  assert.deepEqual(offenders, [])
})
