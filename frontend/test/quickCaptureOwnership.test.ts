import assert from 'node:assert/strict'
import test from 'node:test'

test('quick capture draft storage key has one owner', async () => {
  const { readdirSync, readFileSync, statSync } = await import('node:fs')
  const { join } = await import('node:path')
  const offenders: string[] = []
  const walk = (dir: string) => {
    for (const entry of readdirSync(dir)) {
      const path = join(dir, entry)
      if (statSync(path).isDirectory()) walk(path)
      else if (/\.(ts|vue)$/.test(path) && !path.endsWith('lib/quickCaptureState.ts')) {
        if (readFileSync(path, 'utf8').includes('note_blog_quick_capture_draft')) offenders.push(path)
      }
    }
  }
  walk(join(process.cwd(), 'src'))
  assert.deepEqual(offenders, [])
})
