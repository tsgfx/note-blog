import assert from 'node:assert/strict'
import test from 'node:test'

test('rich markdown editor extension configuration has one owner', async () => {
  const { readdirSync, readFileSync, statSync } = await import('node:fs')
  const { join } = await import('node:path')
  const offenders: string[] = []
  const forbidden =
    /^\s*import\s.*(?:@tiptap\/extension-(?:bold|italic|strike|code|code-block|task-list|task-item)|tiptap-markdown)/m
  const walk = (dir: string) => {
    for (const entry of readdirSync(dir)) {
      const path = join(dir, entry)
      if (statSync(path).isDirectory()) walk(path)
      else if (/\.(ts|vue)$/.test(path) && !path.endsWith('lib/richMarkdownEditor.ts')) {
        if (forbidden.test(readFileSync(path, 'utf8'))) offenders.push(path)
      }
    }
  }
  walk(join(process.cwd(), 'src'))
  assert.deepEqual(offenders, [])
})
