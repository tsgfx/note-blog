import assert from 'node:assert/strict'
import test from 'node:test'
import { clearEditorDraft, editorDraftId, loadEditorDraft, writeEditorDraft } from '../src/lib/editorDraft.ts'

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

test('editor draft round-trips through the canonical storage owner', () => {
  installLocalStorage()
  const at = writeEditorDraft({ id: editorDraftId(''), title: 'T', summary: '', tagsText: 'a, b', contentMd: '# T' })
  assert.ok(at)

  const draft = loadEditorDraft()
  assert.equal(draft?.id, 'new')
  assert.equal(draft?.title, 'T')
  assert.equal(draft?.tagsText, 'a, b')
  assert.equal(draft?.at, at)

  clearEditorDraft()
  assert.equal(loadEditorDraft(), null)
})

test('damaged draft data is treated as absent', () => {
  const data = installLocalStorage()
  data.set('note-editor-draft-v1', '{broken')
  assert.equal(loadEditorDraft(), null)

  data.set('note-editor-draft-v1', JSON.stringify({ id: 'new', title: 'T' }))
  assert.equal(loadEditorDraft(), null)
})

test('editor draft key has one owner', async () => {
  const { readdirSync, readFileSync, statSync } = await import('node:fs')
  const { join } = await import('node:path')
  const offenders: string[] = []
  const walk = (dir: string) => {
    for (const entry of readdirSync(dir)) {
      const path = join(dir, entry)
      if (statSync(path).isDirectory()) walk(path)
      else if (/\.(ts|vue)$/.test(path) && !path.endsWith('lib/editorDraft.ts')) {
        if (readFileSync(path, 'utf8').includes('note-editor-draft-v1')) offenders.push(path)
      }
    }
  }
  walk(join(process.cwd(), 'src'))
  assert.deepEqual(offenders, [])
})
